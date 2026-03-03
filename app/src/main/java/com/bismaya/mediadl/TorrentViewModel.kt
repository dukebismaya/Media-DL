package com.bismaya.mediadl

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ── Inner tab for the torrent screen ────────────────────────────────────────────
enum class TorrentTab(val label: String) {
    ACTIVE("Active"),
    SEARCH("Search"),
    FILES("Files")
}

// ── TorrentViewModel ────────────────────────────────────────────────────────────
class TorrentViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context get() = getApplication<Application>().applicationContext

    // ── Core state ──
    val torrents = mutableStateListOf<TorrentItem>()
    var magnetInput by mutableStateOf("")
    var isEngineRunning by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var expandedHash by mutableStateOf<String?>(null)
    var pendingDeleteHash by mutableStateOf<String?>(null)
    var pendingDeleteFiles by mutableStateOf(false)

    // ── Active tab ──
    var activeTab by mutableStateOf(TorrentTab.ACTIVE)

    // ── Search state ──
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<TorrentSearchResult>>(emptyList())
    var isSearching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    val torrentSearchRecords = mutableStateListOf<TorrentSearchRecord>()

    // ── Settings state ──
    var settings by mutableStateOf(TorrentPrefs())
        private set
    var showSettings by mutableStateOf(false)

    // ── File browser state ──
    private val defaultDir: File get() = TorrentEngine.getActiveSaveDirectory(appContext)
    var currentBrowsePath by mutableStateOf("")
        private set
    var browseItems by mutableStateOf<List<FileItem>>(emptyList())
        private set
    val canGoUp: Boolean
        get() {
            val root = defaultDir.absolutePath
            return currentBrowsePath.isNotBlank() && currentBrowsePath != root
        }

    // ── Notification tracking — must be thread-safe (accessed from IO + Main) ──
    private val notifiedComplete: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var pollingJob: Job? = null

    // ── Init ──
    init {
        try {
            loadSettings()
            TorrentNotificationHelper.createChannel(appContext)
            startEngine()
        } catch (e: Exception) {
            Log.e("TorrentVM", "Init failed", e)
            CrashLogger.logException(appContext, "TorrentViewModel.init", e)
            errorMessage = "Torrent init failed: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // ENGINE LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════════

    private fun startEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                TorrentEngine.customSaveDir = settings.savePath
                TorrentEngine.start(appContext, settings)

                // Polling loop is the sole source of UI state.
                // Calling JNI from alert-triggered coroutines caused concurrent
                // handle access and native SIGSEGV (handles are not thread-safe).

                TorrentEngine.onTorrentAdded = { hash ->
                    viewModelScope.launch(Dispatchers.Main) {
                        statusMessage = "Torrent added — starting…"
                        // Add a placeholder card immediately so the user sees progress
                        // from the first moment. The polling loop will replace it with
                        // real data within ~700 ms.
                        if (torrents.none { it.infoHash == hash }) {
                            torrents.add(
                                TorrentItem(
                                    infoHash = hash,
                                    name = "Fetching metadata…",
                                    totalSize = 0L, downloadedBytes = 0L, progress = 0f,
                                    downloadSpeed = 0L, uploadSpeed = 0L,
                                    seeders = 0, peers = 0,
                                    state = TorrentState.METADATA
                                )
                            )
                        }
                        clearStatusAfterDelay()
                    }
                }

                TorrentEngine.onTorrentFinished = { hash ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (settings.showNotifications && hash !in notifiedComplete) {
                            // Look up name from the already-polled list
                            val item = torrents.firstOrNull { it.infoHash == hash }
                            if (item != null) TorrentNotificationHelper.showComplete(appContext, item)
                            notifiedComplete.add(hash)
                        }
                    }
                }

                TorrentEngine.onError = { _, message ->
                    // No refreshTorrent here — polling handles state updates
                    viewModelScope.launch(Dispatchers.Main) { errorMessage = message }
                }

                withContext(Dispatchers.Main) {
                    isEngineRunning = true
                    currentBrowsePath = defaultDir.absolutePath
                    loadBrowseDir()
                }
                // refreshAll fetches from libtorrent on IO, then pushes to Main
                refreshAll()

                startPolling()
            } catch (e: Throwable) {
                Log.e("TorrentVM", "Failed to start engine", e)
                CrashLogger.logException(appContext, "TorrentEngine.start", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to start torrent engine: ${e.message}"
                }
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(700)
                // Read expanded hash on Main (Compose state must not be read from IO)
                val expandedHash = withContext(Dispatchers.Main) { expandedHash }
                // refreshNow() stores the expanded hash, triggers postTorrentUpdates()
                // (which causes StateUpdateAlert to fire and update the cache on the
                // alert-dispatch thread), then returns the current cache immediately.
                // All JNI reads happen on the alert-dispatch thread — zero JNI here.
                val items = TorrentEngine.refreshNow(expandedHash)
                withContext(Dispatchers.Main) {
                    for (item in items) {
                        val idx = torrents.indexOfFirst { it.infoHash == item.infoHash }
                        if (idx >= 0) torrents[idx] = item
                        else torrents.add(item)
                    }
                    val currentHashes = items.map { it.infoHash }.toSet()
                    torrents.removeAll { it.infoHash !in currentHashes }
                }

                // Snapshot settings on Main (Compose state must not be read from IO)
                val currentSettings = withContext(Dispatchers.Main) { settings }

                // Notifications for active downloads
                if (currentSettings.showNotifications) {
                    items.forEach { item ->
                        when (item.state) {
                            TorrentState.DOWNLOADING, TorrentState.METADATA ->
                                TorrentNotificationHelper.showProgress(appContext, item)
                            TorrentState.FINISHED, TorrentState.SEEDING -> {
                                if (item.infoHash !in notifiedComplete) {
                                    TorrentNotificationHelper.showComplete(appContext, item)
                                    notifiedComplete.add(item.infoHash)
                                }
                                TorrentNotificationHelper.cancel(appContext, item.infoHash)
                            }
                            else -> { }
                        }
                    }
                }

                // Auto-queue on low RAM
                if (currentSettings.autoQueueOnLowRam && TorrentEngine.isLowRam(appContext)) {
                    TorrentEngine.pauseAllDownloading()
                    withContext(Dispatchers.Main) {
                        statusMessage = "Downloads paused — low memory"
                        clearStatusAfterDelay()
                    }
                }

                // Adaptive idle throttle: slow down polling when nothing is active
                val hasActiveDownload = items.any {
                    it.state == TorrentState.DOWNLOADING ||
                    it.state == TorrentState.METADATA ||
                    it.state == TorrentState.CHECKING
                }
                if (!hasActiveDownload) delay(800) // ~1.5 s total cycle when idle

                // WiFi-only check
                if (currentSettings.wifiOnly && !TorrentEngine.isWifiConnected(appContext)) {
                    val downloading = items.any {
                        it.state == TorrentState.DOWNLOADING || it.state == TorrentState.METADATA
                    }
                    if (downloading) {
                        TorrentEngine.pauseAllDownloading()
                        withContext(Dispatchers.Main) {
                            statusMessage = "Downloads paused — Wi-Fi not connected"
                            clearStatusAfterDelay()
                        }
                    }
                }
            }
        }
    }

    /**
     * Fetch a single torrent item on IO (JNI-safe) then update state on Main.
     * Safe to call from any thread.
     */
    private fun refreshTorrent(hash: String) {
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                TorrentEngine.getTorrentItem(hash)
            }
            if (item != null) {
                val idx = torrents.indexOfFirst { it.infoHash == hash }
                if (idx >= 0) torrents[idx] = item else torrents.add(0, item)
            }
        }
    }

    /**
     * Fetch all torrents on IO (JNI-safe) then update state on Main.
     * Must be called from within a coroutine.
     */
    private suspend fun refreshAll() {
        val items = withContext(Dispatchers.IO) {
            TorrentEngine.getAllTorrents()
        }
        // Now back on the caller's dispatcher (Main)
        torrents.clear()
        torrents.addAll(items)
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // MAGNET / FILE INPUT
    // ══════════════════════════════════════════════════════════════════════════════

    fun onMagnetInputChange(value: String) {
        magnetInput = value
        errorMessage = null
    }

    fun isMagnetValid(): Boolean {
        val trimmed = magnetInput.trim()
        return trimmed.startsWith("magnet:?") ||
                trimmed.endsWith(".torrent") ||
                (trimmed.startsWith("http") && "torrent" in trimmed.lowercase())
    }

    fun addMagnet() {
        val input = magnetInput.trim()
        if (input.isBlank()) return
        if (!isEngineRunning) {
            errorMessage = "Torrent engine is not running"
            return
        }

        // WiFi-only gate
        if (settings.wifiOnly && !TorrentEngine.isWifiConnected(appContext)) {
            errorMessage = "Wi-Fi only mode is enabled — connect to Wi-Fi"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val success = if (input.startsWith("magnet:?")) {
                TorrentEngine.addMagnet(appContext, input)
            } else {
                withContext(Dispatchers.Main) { statusMessage = "Fetching torrent file…" }
                try {
                    val url = java.net.URL(input)
                    val conn = url.openConnection()
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val bytes = conn.getInputStream().use { it.readBytes() }
                    TorrentEngine.addTorrentBytes(appContext, bytes)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { errorMessage = "Failed to fetch torrent: ${e.message}" }
                    false
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    magnetInput = ""
                    errorMessage = null
                } else if (errorMessage == null) {
                    errorMessage = "Failed to add torrent"
                }
            }
        }
    }

    /** Add magnet URI directly (from SmartBar or intent) */
    fun addMagnetDirect(uri: String) {
        if (uri.isBlank() || !isEngineRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            TorrentEngine.addMagnet(appContext, uri)
        }
    }

    /** Add from content URI (file picker for .torrent files) */
    fun addTorrentFromUri(uri: Uri) {
        if (!isEngineRunning) {
            errorMessage = "Torrent engine is not running"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val success = TorrentEngine.addTorrentBytes(appContext, bytes)
                    if (!success) {
                        withContext(Dispatchers.Main) { errorMessage = "Failed to add torrent file" }
                    }
                } else {
                    withContext(Dispatchers.Main) { errorMessage = "Could not read torrent file" }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Error reading file: ${e.message}" }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // TORRENT CONTROL
    // ══════════════════════════════════════════════════════════════════════════════

    fun pauseTorrent(hash: String) {
        viewModelScope.launch(Dispatchers.IO) {
            TorrentEngine.pause(hash)
            // Polling will reflect the paused state within 1500 ms
        }
    }

    fun resumeTorrent(hash: String) {
        // WiFi-only gate
        if (settings.wifiOnly && !TorrentEngine.isWifiConnected(appContext)) {
            errorMessage = "Wi-Fi only mode — connect to Wi-Fi to resume"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            TorrentEngine.resume(hash)
            // Polling will reflect the resumed state within 1500 ms
        }
    }

    fun requestDelete(hash: String) {
        pendingDeleteHash = hash
        pendingDeleteFiles = false
    }

    fun confirmDelete(deleteFiles: Boolean) {
        val hash = pendingDeleteHash ?: return
        TorrentEngine.remove(hash, deleteFiles)
        TorrentNotificationHelper.cancel(appContext, hash)
        torrents.removeAll { it.infoHash == hash }
        pendingDeleteHash = null
        pendingDeleteFiles = false
    }

    fun cancelDelete() {
        pendingDeleteHash = null
        pendingDeleteFiles = false
    }

    fun toggleExpanded(hash: String) {
        expandedHash = if (expandedHash == hash) null else hash
    }

    fun dismissError() {
        errorMessage = null
    }

    // ── Sequential download ──
    fun toggleSequential(hash: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = TorrentEngine.isSequential(hash)
            TorrentEngine.setSequentialDownload(hash, !current)
            // Polling will reflect the change within 1500 ms
        }
    }

    // ── File selection / priority ──
    fun toggleFileDownload(hash: String, fileIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentPriority = TorrentEngine.getFilePriority(hash, fileIndex)
            val newPriority = if (currentPriority == 0) {
                org.libtorrent4j.Priority.DEFAULT
            } else {
                org.libtorrent4j.Priority.IGNORE
            }
            TorrentEngine.setFilePriority(hash, fileIndex, newPriority)
            // Polling will reflect the change within 1500 ms
        }
    }

    // ── Share magnet link ──
    fun shareMagnet(context: Context, hash: String) {
        val magnetUri = TorrentEngine.getMagnetUri(hash)
        if (magnetUri.isBlank()) return
        val name = torrents.find { it.infoHash == hash }?.name ?: "Torrent"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, magnetUri)
            putExtra(Intent.EXTRA_SUBJECT, name)
        }
        context.startActivity(Intent.createChooser(intent, "Share magnet link"))
    }

    // ── Open downloaded file ──
    fun openFile(context: Context, torrentItem: TorrentItem) {
        if (torrentItem.files.isEmpty()) return
        val firstFile = torrentItem.files.first()
        val file = File(torrentItem.savePath, firstFile.name)
        if (!file.exists()) return
        FileOperations.openFile(context, file)
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════════════════════════════════════════════

    fun onSearchQueryChange(value: String) {
        searchQuery = value
        searchError = null
    }

    fun searchTorrents() {
        val q = searchQuery.trim()
        if (q.isBlank()) return
        isSearching = true
        searchError = null
        searchResults = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            val result = TorrentSearchProvider.search(q)
            withContext(Dispatchers.Main) {
                isSearching = false
                result.fold(
                    onSuccess = { results ->
                        searchResults = results
                        if (results.isEmpty()) {
                            searchError = "No results found for \"$q\""
                        } else {
                            // Save search record with top-5 results
                            val snapshot = results.take(5).map { r ->
                                TorrentSearchResultSnapshot(
                                    name      = r.name,
                                    infoHash  = r.infoHash,
                                    sizeBytes = r.size,
                                    seeders   = r.seeders,
                                    category  = r.category
                                )
                            }
                            val record = TorrentSearchRecord(
                                query       = q,
                                timestamp   = System.currentTimeMillis(),
                                resultCount = results.size,
                                topResults  = snapshot
                            )
                            // Remove old entry with same query (dedup), prepend new one
                            torrentSearchRecords.removeAll { it.query.equals(q, ignoreCase = true) }
                            torrentSearchRecords.add(0, record)
                            if (torrentSearchRecords.size > 25)
                                torrentSearchRecords.removeAt(torrentSearchRecords.size - 1)
                            TorrentSettingsManager.saveSearchRecords(appContext, torrentSearchRecords.toList())
                        }
                    },
                    onFailure = { e ->
                        searchError = "Search failed: ${e.message}"
                    }
                )
            }
        }
    }

    /** Pre-fill search query (from SmartBar) and switch to search tab */
    fun initiateSearch(query: String) {
        searchQuery = query
        activeTab = TorrentTab.SEARCH
        searchTorrents()
    }

    fun addFromSearchResult(result: TorrentSearchResult) {
        if (!isEngineRunning) {
            errorMessage = "Torrent engine is not running"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val magnetUri = result.magnetUri
                val success = TorrentEngine.addMagnet(appContext, magnetUri)
                if (!success) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to add torrent"
                    }
                }
            } catch (e: Throwable) {
                Log.e("TorrentVM", "addFromSearchResult failed", e)
                CrashLogger.logException(appContext, "addFromSearchResult", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to add torrent: ${e.message}"
                }
            }
        }
        activeTab = TorrentTab.ACTIVE
        statusMessage = "Adding: ${result.name}"
        clearStatusAfterDelay()
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // SETTINGS
    // ══════════════════════════════════════════════════════════════════════════════

    private fun loadSettings() {
        settings = TorrentSettingsManager.load(appContext)
        val saved = TorrentSettingsManager.loadSearchRecords(appContext)
        torrentSearchRecords.clear()
        torrentSearchRecords.addAll(saved)
    }

    fun clearTorrentSearchHistory() {
        torrentSearchRecords.clear()
        TorrentSettingsManager.saveSearchRecords(appContext, emptyList())
    }

    fun updateSettings(newSettings: TorrentPrefs) {
        settings = newSettings
        TorrentSettingsManager.save(appContext, newSettings)
        // Apply runtime changes
        TorrentEngine.setSpeedLimits(newSettings.downloadSpeedLimit, newSettings.uploadSpeedLimit)
        TorrentEngine.customSaveDir = newSettings.savePath
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // FILE BROWSER
    // ══════════════════════════════════════════════════════════════════════════════

    fun loadBrowseDir() {
        if (currentBrowsePath.isBlank()) {
            currentBrowsePath = defaultDir.absolutePath
        }
        val dir = File(currentBrowsePath)
        if (!dir.exists()) dir.mkdirs()
        val children = dir.listFiles()?.toList() ?: emptyList()
        browseItems = children
            .map { FileItem(it) }
            .sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun navigateTo(item: FileItem) {
        if (item.isDirectory) {
            currentBrowsePath = item.file.absolutePath
            loadBrowseDir()
        }
    }

    fun navigateUp() {
        val current = File(currentBrowsePath)
        val root = defaultDir.absolutePath
        val parent = current.parentFile
        if (parent != null && current.absolutePath != root) {
            currentBrowsePath = parent.absolutePath
            loadBrowseDir()
        }
    }

    fun openBrowseFile(context: Context, item: FileItem) {
        FileOperations.openFile(context, item.file)
    }

    fun deleteBrowseFile(item: FileItem) {
        FileOperations.deleteFile(item.file)
        loadBrowseDir()
    }

    fun shareBrowseFile(context: Context, item: FileItem) {
        FileOperations.shareFile(context, item.file)
    }

    fun renameBrowseFile(item: FileItem, newName: String) {
        FileOperations.renameFile(item.file, newName)
        loadBrowseDir()
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════════

    private fun clearStatusAfterDelay() {
        viewModelScope.launch {
            delay(3000)
            statusMessage = null
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
