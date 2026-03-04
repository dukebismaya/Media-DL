package com.bismaya.mediadl

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bismaya.mediadl.core.RepositoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

    // ── Search filters (applied client-side after results arrive) ──
    var searchCategoryFilter by mutableStateOf("All")
    var searchSizeFilter by mutableStateOf("Any")

    // ── Settings state ──
    var settings by mutableStateOf(TorrentPrefs())
        private set
    var showSettings by mutableStateOf(false)

    // ── Battery optimization prompt ──
    var needsBatteryOptPrompt by mutableStateOf(false)

    // ── Seeding auto-pause tracking ──
    val autoPausedForSeeding: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val prevTorrentStates: MutableMap<String, TorrentState> = ConcurrentHashMap()

    // ── File browser state ──
    private val defaultSaveDir: File
        get() {
            val base = if (settings.savePath.isNotBlank()) {
                File(settings.savePath)
            } else {
                // App-private external storage: not indexed by MediaStore, not accessible
                // to other apps without root.  Falls back to internal storage if SD is
                // unavailable.  Path: Android/data/com.bismaya.mediadl/files/Torrents
                appContext.getExternalFilesDir("Torrents")
                    ?: File(appContext.filesDir, "Torrents")
            }
            if (!base.exists()) base.mkdirs()
            return base
        }

    var currentBrowsePath by mutableStateOf("")
        private set
    var browseItems by mutableStateOf<List<FileItem>>(emptyList())
        private set
    val canGoUp: Boolean
        get() {
            val root = defaultSaveDir.absolutePath
            return currentBrowsePath.isNotBlank() && currentBrowsePath != root
        }

    private val notifiedComplete: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var observerJob: Job? = null
    private val torrentRepo by lazy { RepositoryHelper.getTorrentRepository(appContext) }

    // ── Completed-torrent persistence ──
    private val completedPrefs
        get() = appContext.getSharedPreferences("torrent_completed_set", Context.MODE_PRIVATE)

    private fun persistCompletedSet() {
        completedPrefs.edit().putStringSet("hashes", autoPausedForSeeding.toSet()).apply()
    }

    /** Persists [hash] as completed-and-paused in both SharedPrefs (legacy) and Room. */
    private fun markCompletedAndPaused(hash: String) {
        persistCompletedSet()
        viewModelScope.launch(Dispatchers.IO) {
            try { torrentRepo.setCompletedAndPaused(hash, true) } catch (_: Exception) {}
        }
    }

    // ── Init ──
    init {
        try {
            loadSettings()
            // Restore hashes from SharedPrefs immediately (synchronous, backward-compat)
            val saved = completedPrefs.getStringSet("hashes", emptySet()) ?: emptySet()
            autoPausedForSeeding.addAll(saved)
            // Also load from Room (the durable source of truth) and migrate any
            // SharedPrefs-only hashes into Room for a clean transition.
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val roomIds = torrentRepo.getCompletedAndPausedIds().toSet()
                    autoPausedForSeeding.addAll(roomIds)
                    for (hash in saved) {
                        if (hash !in roomIds) {
                            try { torrentRepo.setCompletedAndPaused(hash, true) } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TorrentVM", "Room completedAndPaused load failed", e)
                }
            }
            startEngine()
        } catch (e: Exception) {
            Log.e("TorrentVM", "Init failed", e)
            CrashLogger.logException(appContext, "TorrentViewModel.init", e)
            errorMessage = "Torrent init failed: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ENGINE LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    private fun startEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                TorrentBridge.init(appContext)
                TorrentBridge.start()

                withContext(Dispatchers.Main) {
                    isEngineRunning = true
                    currentBrowsePath = defaultSaveDir.absolutePath
                    loadBrowseDir()
                    checkBatteryOptimization()
                }
                startObservingTorrents()
            } catch (e: Throwable) {
                Log.e("TorrentVM", "Failed to start engine", e)
                CrashLogger.logException(appContext, "TorrentEngine.start", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to start torrent engine: ${e.message}"
                }
            }
        }
    }

    private fun startObservingTorrents() {
        observerJob?.cancel()
        observerJob = viewModelScope.launch {
            TorrentBridge.observeInfoList().collectLatest { listState ->
                // TorrentListState is a sealed interface: Initial | Loaded
                val infoList = (listState as? com.bismaya.mediadl.core.model.data.TorrentListState.Loaded)?.list()
                    ?: return@collectLatest
                val items = infoList.map { info ->
                    with(TorrentBridge) { info.toItem() }
                }
                // ── Seeding auto-stop ──
                val prefs = settings
                for (item in items) {
                    val prevState = prevTorrentStates.put(item.infoHash, item.state)
                    val isNewlySeeding = item.state == TorrentState.SEEDING && prevState != TorrentState.SEEDING
                    val isNewlyFinished = item.state == TorrentState.FINISHED && prevState != TorrentState.FINISHED

                    if (isNewlySeeding && prefs.stopSeedingOnComplete && item.infoHash !in autoPausedForSeeding) {
                        autoPausedForSeeding.add(item.infoHash)
                        markCompletedAndPaused(item.infoHash)
                        TorrentBridge.pauseTorrent(item.infoHash)
                    }
                    if (item.state == TorrentState.SEEDING && prefs.maxSeedRatio > 0f &&
                        item.shareRatio >= prefs.maxSeedRatio && item.infoHash !in autoPausedForSeeding) {
                        autoPausedForSeeding.add(item.infoHash)
                        markCompletedAndPaused(item.infoHash)
                        TorrentBridge.pauseTorrent(item.infoHash)
                    }
                }
                withContext(Dispatchers.Main) {
                    val newHashes = items.map { it.infoHash }.toSet()
                    for (item in items) {
                        val idx = torrents.indexOfFirst { it.infoHash == item.infoHash }
                        if (idx >= 0) torrents[idx] = item else torrents.add(item)
                    }
                    torrents.removeAll { it.infoHash !in newHashes }
                }
            }
        }

        viewModelScope.launch {
            TorrentBridge.observeDeletedTorrentIds().collectLatest { deletedId ->
                withContext(Dispatchers.Main) {
                    torrents.removeAll { it.infoHash == deletedId }
                    notifiedComplete.remove(deletedId)
                    autoPausedForSeeding.remove(deletedId)
                    persistCompletedSet()
                    prevTorrentStates.remove(deletedId)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAGNET / FILE INPUT
    // ══════════════════════════════════════════════════════════════════════════

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
        if (!isEngineRunning) { errorMessage = "Torrent engine is not running"; return }
        if (settings.wifiOnly && !isWifiConnected()) {
            errorMessage = "Wi-Fi only mode is enabled — connect to Wi-Fi"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (input.startsWith("magnet:?")) {
                    val magnet = TorrentBridge.parseMagnet(input)
                    if (magnet != null) {
                        TorrentBridge.addTorrent(
                            source = input, fromMagnet = true,
                            sha1hash = magnet.sha1hash,
                            name = magnet.name ?: magnet.sha1hash,
                            downloadPath = Uri.fromFile(defaultSaveDir)
                        )
                        withContext(Dispatchers.Main) { magnetInput = ""; errorMessage = null }
                    } else {
                        withContext(Dispatchers.Main) { errorMessage = "Invalid magnet link" }
                    }
                } else {
                    withContext(Dispatchers.Main) { statusMessage = "Fetching torrent file…" }
                    val url = java.net.URL(input)
                    val conn = url.openConnection()
                    conn.connectTimeout = 15_000; conn.readTimeout = 15_000
                    val tmp = File(appContext.cacheDir, "tmp_${System.currentTimeMillis()}.torrent")
                    conn.getInputStream().use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                    TorrentBridge.addTorrentUri(Uri.fromFile(tmp), Uri.fromFile(defaultSaveDir))
                    tmp.delete()
                    withContext(Dispatchers.Main) { magnetInput = ""; errorMessage = null }
                }
            } catch (e: Exception) {
                Log.e("TorrentVM", "addMagnet failed", e)
                withContext(Dispatchers.Main) { errorMessage = "Failed to add torrent: ${e.message}" }
            }
        }
    }

    fun addMagnetDirect(uri: String) {
        if (uri.isBlank() || !isEngineRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val magnet = TorrentBridge.parseMagnet(uri)
                if (magnet != null) {
                    TorrentBridge.addTorrent(
                        source = uri, fromMagnet = true,
                        sha1hash = magnet.sha1hash,
                        name = magnet.name ?: magnet.sha1hash,
                        downloadPath = Uri.fromFile(defaultSaveDir)
                    )
                }
            } catch (e: Exception) { Log.e("TorrentVM", "addMagnetDirect failed", e) }
        }
    }

    fun addTorrentFromUri(uri: Uri) {
        if (!isEngineRunning) { errorMessage = "Torrent engine is not running"; return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                TorrentBridge.addTorrentUri(uri, Uri.fromFile(defaultSaveDir))
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Error adding torrent: ${e.message}" }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TORRENT CONTROL
    // ══════════════════════════════════════════════════════════════════════════

    fun pauseTorrent(hash: String) {
        viewModelScope.launch(Dispatchers.IO) { TorrentBridge.pauseTorrent(hash) }
    }

    fun resumeTorrent(hash: String) {
        if (settings.wifiOnly && !isWifiConnected()) {
            errorMessage = "Wi-Fi only mode — connect to Wi-Fi to resume"
            return
        }
        viewModelScope.launch(Dispatchers.IO) { TorrentBridge.resumeTorrent(hash) }
    }

    fun requestDelete(hash: String) {
        pendingDeleteHash = hash
        pendingDeleteFiles = false
    }

    fun confirmDelete(deleteFiles: Boolean) {
        val hash = pendingDeleteHash ?: return
        // Optimistic UI removal — engine will also remove from Room asynchronously.
        torrents.removeAll { it.infoHash == hash }
        notifiedComplete.remove(hash)
        autoPausedForSeeding.remove(hash)
        prevTorrentStates.remove(hash)
        persistCompletedSet()
        pendingDeleteHash = null
        pendingDeleteFiles = false
        viewModelScope.launch(Dispatchers.IO) {
            TorrentBridge.deleteTorrents(listOf(hash), deleteFiles)
        }
    }

    fun cancelDelete() { pendingDeleteHash = null; pendingDeleteFiles = false }

    fun toggleExpanded(hash: String) {
        expandedHash = if (expandedHash == hash) null else hash
    }

    fun dismissError() { errorMessage = null }

    fun toggleSequential(hash: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = TorrentBridge.isSequential(hash)
            TorrentBridge.setSequential(hash, !current)
        }
    }

    fun forceRecheck(hash: String) {
        viewModelScope.launch(Dispatchers.IO) { TorrentBridge.forceRecheck(listOf(hash)) }
    }

    fun requestTrackerAnnounce(hash: String) {
        viewModelScope.launch(Dispatchers.IO) { TorrentBridge.forceAnnounce(listOf(hash)) }
    }

    fun observeTrackers(hash: String) = TorrentBridge.observeTrackers(hash)
    fun observePeers(hash: String)    = TorrentBridge.observePeers(hash)
    fun observePieces(hash: String)   = TorrentBridge.observePieces(hash)

    /** Synchronous snapshot of trackers — safe to call from LaunchedEffect. */
    fun getTrackerList(hash: String): List<TrackerInfo> = TorrentBridge.getTrackerList(hash)

    /** Synchronous snapshot of peers — safe to call from LaunchedEffect. */
    fun getPeerList(hash: String): List<PeerInfo> = TorrentBridge.getPeerList(hash)

    fun getAdvancedInfo(hash: String): com.bismaya.mediadl.core.model.data.AdvancedTorrentInfo? =
        TorrentBridge.getAdvancedInfoSync(hash)

    fun toggleFileDownload(hash: String, fileIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = torrents.firstOrNull { it.infoHash == hash } ?: return@launch
            val file = item.files.getOrNull(fileIndex) ?: return@launch
            val newPriority = if (file.priority == 0) 4 else 0
            TorrentBridge.prioritizeFile(hash, fileIndex, newPriority)
        }
    }

    fun shareMagnet(context: Context, hash: String) {
        val magnetUri = TorrentBridge.makeMagnet(hash) ?: return
        val name = torrents.find { it.infoHash == hash }?.name ?: "Torrent"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, magnetUri)
            putExtra(Intent.EXTRA_SUBJECT, name)
        }
        context.startActivity(Intent.createChooser(intent, "Share magnet link"))
    }

    fun openFile(context: Context, torrentItem: TorrentItem) {
        val firstFile = torrentItem.files.firstOrNull() ?: return
        val file = File(torrentItem.savePath, firstFile.name)
        if (!file.exists()) return
        FileOperations.openFile(context, file)
    }

    /**
     * Returns the file list for a torrent, fetching it live from the engine if
     * the item's embedded [TorrentItem.files] list is empty (e.g. for completed torrents).
     * Must only be called from a background coroutine.
     */
    fun getFilesForTorrent(hash: String): List<TorrentFileInfo> =
        TorrentBridge.getFilesForTorrent(hash)

    /**
     * Deletes specific files belonging to a torrent from disk and triggers a
     * MediaStore re-scan of the surrounding folder.
     */
    fun deleteSelectedFiles(hash: String, fileIndices: Set<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileMeta = TorrentBridge.getFilesForTorrent(hash)
                val saveRoot = defaultSaveDir
                val torrentName = torrents.firstOrNull { it.infoHash == hash }?.name ?: ""
                // Try both <saveRoot>/<name>/<file> and <saveRoot>/<file> layouts
                for (fi in fileMeta.filter { it.index in fileIndices }) {
                    val candidates = listOf(
                        File(saveRoot, "$torrentName/${fi.name}"),
                        File(saveRoot, fi.name),
                        File(saveRoot, "${hash.take(8)}/${fi.name}")
                    )
                    candidates.firstOrNull { it.exists() }?.delete()
                }
            } catch (e: Exception) {
                Log.e("TorrentVM", "deleteSelectedFiles failed", e)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    fun onSearchQueryChange(value: String) { searchQuery = value; searchError = null }

    fun searchTorrents() {
        val q = searchQuery.trim()
        if (q.isBlank()) return
        isSearching = true; searchError = null; searchResults = emptyList()
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
                            val snapshot = results.take(5).map { r ->
                                TorrentSearchResultSnapshot(
                                    name = r.name, infoHash = r.infoHash,
                                    sizeBytes = r.size, seeders = r.seeders, category = r.category
                                )
                            }
                            val record = TorrentSearchRecord(
                                query = q, timestamp = System.currentTimeMillis(),
                                resultCount = results.size, topResults = snapshot
                            )
                            torrentSearchRecords.removeAll { it.query.equals(q, ignoreCase = true) }
                            torrentSearchRecords.add(0, record)
                            if (torrentSearchRecords.size > 25)
                                torrentSearchRecords.removeAt(torrentSearchRecords.size - 1)
                            TorrentSettingsManager.saveSearchRecords(appContext, torrentSearchRecords.toList())
                        }
                    },
                    onFailure = { e -> searchError = "Search failed: ${e.message}" }
                )
            }
        }
    }

    fun initiateSearch(query: String) {
        searchQuery = query; activeTab = TorrentTab.SEARCH; searchTorrents()
    }

    fun addFromSearchResult(result: TorrentSearchResult) {
        if (!isEngineRunning) { errorMessage = "Torrent engine is not running"; return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val magnet = TorrentBridge.parseMagnet(result.magnetUri)
                if (magnet != null) {
                    TorrentBridge.addTorrent(
                        source = result.magnetUri, fromMagnet = true,
                        sha1hash = magnet.sha1hash, name = result.name,
                        downloadPath = Uri.fromFile(defaultSaveDir)
                    )
                } else {
                    withContext(Dispatchers.Main) { errorMessage = "Failed to add torrent" }
                }
            } catch (e: Throwable) {
                Log.e("TorrentVM", "addFromSearchResult failed", e)
                CrashLogger.logException(appContext, "addFromSearchResult", e)
                withContext(Dispatchers.Main) { errorMessage = "Failed to add torrent: ${e.message}" }
            }
        }
        activeTab = TorrentTab.ACTIVE
        statusMessage = "Adding: ${result.name}"
        viewModelScope.launch { delay(3000); statusMessage = null }
    }

    fun clearTorrentSearchHistory() {
        torrentSearchRecords.clear()
        TorrentSettingsManager.saveSearchRecords(appContext, emptyList())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SETTINGS
    // ══════════════════════════════════════════════════════════════════════════

    private fun loadSettings() {
        settings = TorrentSettingsManager.load(appContext)
        val saved = TorrentSettingsManager.loadSearchRecords(appContext)
        torrentSearchRecords.clear(); torrentSearchRecords.addAll(saved)
    }

    fun updateSettings(newSettings: TorrentPrefs) {
        settings = newSettings
        TorrentSettingsManager.save(appContext, newSettings)
        // Sync speed limits → engine SessionSettings via SettingsRepository (triggers handleSettingsChanged)
        TorrentBridge.setSpeedLimits(newSettings.downloadSpeedLimit, newSettings.uploadSpeedLimit)
        // Sync active slot limits → engine SessionSettings
        TorrentBridge.setActiveLimits(newSettings.maxActiveDownloads, newSettings.maxActiveSeeds)
        // Sync IP filter toggle → engine session
        TorrentBridge.setIpFiltering(newSettings.enableIpFiltering)
        // Sync save path to libretorrent's SettingsRepository
        if (newSettings.savePath.isNotBlank()) {
            RepositoryHelper.getSettingsRepository(appContext)
                .saveTorrentsIn("file://" + newSettings.savePath)
        }
        // Reset auto-pause cache when seeding settings change
        autoPausedForSeeding.clear()
        persistCompletedSet()
    }

    /**
     * Open a specific torrent file in an external media player via the streaming server.
     * [fileName] is used to derive the MIME type so Android routes to a media player
     * instead of a browser.  Returns false if streaming is unavailable.
     */
    fun streamFile(context: Context, torrentId: String, fileIndex: Int, fileName: String): Boolean {
        val url = TorrentBridge.getStreamUrl(torrentId, fileIndex) ?: return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "mp4"              -> "video/mp4"
            "mkv"              -> "video/x-matroska"
            "avi"              -> "video/x-msvideo"
            "webm"             -> "video/webm"
            "mov"              -> "video/quicktime"
            "mp3"              -> "audio/mpeg"
            "flac"             -> "audio/flac"
            "ogg"              -> "audio/ogg"
            "m4a"              -> "audio/mp4"
            "wav"              -> "audio/wav"
            "aac"              -> "audio/aac"
            else               -> "video/*"        // fallback — still picks a player not Chrome
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Show chooser so the user can pick VLC, MX Player, built-in player, etc.
            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            Log.e("TorrentVM", "streamFile failed for $url", e)
            false
        }
    }

    private fun checkBatteryOptimization() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(appContext.packageName)) return
        val dismissed = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("battery_opt_dismissed", false)
        if (!dismissed) needsBatteryOptPrompt = true
    }

    fun dismissBatteryOptPrompt(permanent: Boolean = false) {
        needsBatteryOptPrompt = false
        if (permanent) {
            appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("battery_opt_dismissed", true).apply()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILE BROWSER
    // ══════════════════════════════════════════════════════════════════════════

    fun loadBrowseDir() {
        if (currentBrowsePath.isBlank()) currentBrowsePath = defaultSaveDir.absolutePath
        val dir = File(currentBrowsePath)
        if (!dir.exists()) dir.mkdirs()
        browseItems = (dir.listFiles()?.toList() ?: emptyList())
            .map { FileItem(it) }
            .sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun navigateTo(item: FileItem) {
        if (item.isDirectory) { currentBrowsePath = item.file.absolutePath; loadBrowseDir() }
    }

    fun navigateUp() {
        val current = File(currentBrowsePath)
        val root = defaultSaveDir.absolutePath
        val parent = current.parentFile
        if (parent != null && current.absolutePath != root) {
            currentBrowsePath = parent.absolutePath; loadBrowseDir()
        }
    }

    fun openBrowseFile(context: Context, item: FileItem) = FileOperations.openFile(context, item.file)
    fun deleteBrowseFile(item: FileItem) { FileOperations.deleteFile(item.file); loadBrowseDir() }
    fun shareBrowseFile(context: Context, item: FileItem) = FileOperations.shareFile(context, item.file)
    fun renameBrowseFile(item: FileItem, newName: String) {
        FileOperations.renameFile(item.file, newName); loadBrowseDir()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun onCleared() {
        observerJob?.cancel()
        TorrentBridge.requestStop()
        super.onCleared()
    }
}
