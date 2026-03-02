package com.bismaya.mediadl

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── UI Screen State ─────────────────────────────────────────────────────────────
sealed interface ScreenState {
    data object Idle : ScreenState
    data object Fetching : ScreenState
    data class Success(val info: VideoInfo) : ScreenState
    data class Error(val message: String) : ScreenState
}

// ── ViewModel ───────────────────────────────────────────────────────────────────
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context get() = getApplication<Application>().applicationContext
    private val historyRepo = DownloadHistoryRepo(application.applicationContext)

    // ── observable state ──
    var screenState by mutableStateOf<ScreenState>(ScreenState.Idle)
        private set

    var url by mutableStateOf("")
    var selectedFormatId by mutableStateOf("")
    var selectedTab by mutableStateOf("video")

    var isDownloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableFloatStateOf(0f)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set

    /** Info about the currently active download (cleared when done) */
    var activeDownloadTitle by mutableStateOf("")
        private set
    var activeDownloadThumbnail by mutableStateOf("")
        private set

    var isUpdating by mutableStateOf(false)
        private set
    var ytdlpVersion by mutableStateOf("Checking...")
        private set

    /** Signals consumed by the UI for one-shot haptic events. */
    var hapticEvent by mutableStateOf<HapticType?>(null)
        private set

    /** Current navigation tab */
    var currentNavTab by mutableStateOf(NavTab.HOME)

    /** Download history */
    var downloadHistory by mutableStateOf<List<DownloadRecord>>(emptyList())
        private set
    var historyExpanded by mutableStateOf(false)

    /** Tracks which download card is currently expanded (by record id). Null = all collapsed */
    var expandedDownloadId by mutableStateOf<String?>(null)

    fun toggleExpandedDownload(id: String) {
        expandedDownloadId = if (expandedDownloadId == id) null else id
    }

    /** Delete confirmation: holds the record ID pending deletion */
    var pendingDeleteId by mutableStateOf<String?>(null)

    fun requestDelete(id: String) {
        pendingDeleteId = id
    }

    fun confirmDelete() {
        val id = pendingDeleteId ?: return
        deleteDownload(id)
        pendingDeleteId = null
    }

    fun cancelDelete() {
        pendingDeleteId = null
    }

    /**
     * IDs hidden from History screen (cleared via "Clear All" or individual remove).
     * Downloads screen is NOT affected by these.
     */
    private val hiddenHistoryPrefs: android.content.SharedPreferences =
        appContext.getSharedPreferences("mediadl_hidden_history", android.content.Context.MODE_PRIVATE)
    var hiddenHistoryIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun loadHiddenHistoryIds() {
        hiddenHistoryIds = hiddenHistoryPrefs.getStringSet("hidden_ids", emptySet()) ?: emptySet()
    }

    private fun saveHiddenHistoryIds() {
        hiddenHistoryPrefs.edit().putStringSet("hidden_ids", hiddenHistoryIds).apply()
    }

    /**
     * IDs hidden from Downloads screen (deleted by the user via delete button).
     * History screen is NOT affected by these — records stay in history.
     */
    private val hiddenDownloadPrefs: android.content.SharedPreferences =
        appContext.getSharedPreferences("mediadl_hidden_downloads", android.content.Context.MODE_PRIVATE)
    var hiddenDownloadIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun loadHiddenDownloadIds() {
        hiddenDownloadIds = hiddenDownloadPrefs.getStringSet("hidden_ids", emptySet()) ?: emptySet()
    }

    private fun saveHiddenDownloadIds() {
        hiddenDownloadPrefs.edit().putStringSet("hidden_ids", hiddenDownloadIds).apply()
    }

    /** Filters for Downloads screen */
    var downloadsMediaFilter by mutableStateOf(MediaFilter.ALL)
    var downloadsDateFilter by mutableStateOf(DateFilter.ALL)

    /** Filters for History screen */
    var historyMediaFilter by mutableStateOf(MediaFilter.ALL)
    var historyDateFilter by mutableStateOf(DateFilter.ALL)

    fun filteredDownloads(): List<DownloadRecord> {
        return downloadHistory
            .filter { it.id !in hiddenDownloadIds }
            .applyFilters(downloadsMediaFilter, downloadsDateFilter)
    }

    fun filteredHistory(): List<DownloadRecord> {
        return downloadHistory
            .filter { it.id !in hiddenHistoryIds }
            .applyFilters(historyMediaFilter, historyDateFilter)
    }

    /** Count of visible downloads (excluding deleted) */
    fun visibleDownloadsCount(): Int = downloadHistory.count { it.id !in hiddenDownloadIds }

    /** Count of visible history items (excluding hidden) */
    fun visibleHistoryCount(): Int = downloadHistory.count { it.id !in hiddenHistoryIds }

    private fun List<DownloadRecord>.applyFilters(media: MediaFilter, date: DateFilter): List<DownloadRecord> {
        val now = System.currentTimeMillis()
        return this
            .filter { record ->
                when (media) {
                    MediaFilter.ALL -> true
                    MediaFilter.VIDEO -> record.type == "video"
                    MediaFilter.AUDIO -> record.type == "audio"
                }
            }
            .filter { record ->
                val diff = now - record.timestamp
                when (date) {
                    DateFilter.ALL -> true
                    DateFilter.TODAY -> diff < 86_400_000
                    DateFilter.WEEK -> diff < 604_800_000
                    DateFilter.MONTH -> diff < 2_592_000_000
                }
            }
    }

    private var fetchJob: Job? = null

    // ── init ──
    init {
        autoUpdateYtDlp()
        loadHiddenHistoryIds()
        loadHiddenDownloadIds()
        recoverAndLoadHistory()
    }

    private fun recoverAndLoadHistory() {
        // First load whatever is in SharedPreferences
        downloadHistory = historyRepo.getAll()

        // If empty, try restoring from external backup (survives uninstall)
        if (downloadHistory.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val restored = historyRepo.restoreFromBackup()
                if (restored > 0) {
                    withContext(Dispatchers.Main) {
                        downloadHistory = historyRepo.getAll()
                    }
                }
            }
        }

        // Then scan MediaStore in background to recover files from a reinstall
        viewModelScope.launch(Dispatchers.IO) {
            val recovered = historyRepo.recoverFromMediaStore()
            if (recovered > 0) {
                withContext(Dispatchers.Main) {
                    downloadHistory = historyRepo.getAll()
                }
            }
        }
    }

    private fun loadHistory() {
        downloadHistory = historyRepo.getAll()
    }

    /** Remove from history view only (hides from History tab, keeps in Downloads & on device) */
    fun removeHistoryItem(id: String) {
        hiddenHistoryIds = hiddenHistoryIds + id
        saveHiddenHistoryIds()
    }

    /** Delete file from device (MediaStore) and hide from Downloads tab. History record is kept. */
    fun deleteDownload(id: String) {
        val record = downloadHistory.find { it.id == id }
        if (record != null) {
            deleteFromMediaStore(appContext, record.contentUri)
        }
        // Hide from Downloads view; do NOT remove from history repo
        hiddenDownloadIds = hiddenDownloadIds + id
        saveHiddenDownloadIds()
    }

    /** Clear all items from History view only (Downloads are NOT affected) */
    fun clearHistory() {
        hiddenHistoryIds = hiddenHistoryIds + downloadHistory.map { it.id }.toSet()
        saveHiddenHistoryIds()
    }

    // ── Haptic event types ──
    enum class HapticType { LIGHT, SUCCESS, ERROR }

    fun consumeHaptic() { hapticEvent = null }

    // ── URL helpers ──
    private val urlRegex = Regex("^https?://[\\w\\-]+(\\.[\\w\\-]+)+.*$", RegexOption.IGNORE_CASE)
    fun isUrlValid(): Boolean = urlRegex.matches(url.trim())

    fun onUrlChange(value: String) {
        url = value
        // If we were in Error, go back to Idle so user can retry
        if (screenState is ScreenState.Error) {
            screenState = ScreenState.Idle
        }
    }

    // ── Fetch ──
    fun fetch() {
        if (!isUrlValid()) return
        screenState = ScreenState.Fetching
        hapticEvent = HapticType.LIGHT

        fetchJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { fetchVideoInfo(url.trim()) }
                val first = info.formats.firstOrNull { it.type == "video" }
                selectedFormatId = first?.formatId ?: info.formats.firstOrNull()?.formatId ?: ""
                selectedTab = "video"
                screenState = ScreenState.Success(info)
                hapticEvent = HapticType.SUCCESS
            } catch (ce: CancellationException) {
                // User cancelled – return to Idle silently
                screenState = ScreenState.Idle
            } catch (e: Exception) {
                CrashLogger.logException(appContext, "MainViewModel.fetch", e)
                val rawMsg = e.localizedMessage ?: e.message ?: ""
                val cleanMsg = cleanErrorMessage(rawMsg)
                if ("403" in rawMsg || "older than" in rawMsg || "Forbidden" in rawMsg) {
                    retryAfterUpdate()
                } else if (needsImpersonation(rawMsg)) {
                    screenState = ScreenState.Error(
                        "This site requires browser impersonation which is not yet supported on Android. " +
                                "Try YouTube, Instagram, TikTok, Twitter/X, Facebook, or other major platforms."
                    )
                    hapticEvent = HapticType.ERROR
                } else {
                    screenState = ScreenState.Error(cleanMsg.ifBlank { "Failed to fetch video info" })
                    hapticEvent = HapticType.ERROR
                }
            }
        }
    }

    fun cancelFetch() {
        fetchJob?.cancel()
        fetchJob = null
        screenState = ScreenState.Idle
        hapticEvent = HapticType.LIGHT
    }

    fun clearResult() {
        url = ""
        selectedFormatId = ""
        selectedTab = "video"
        statusMessage = null
        screenState = ScreenState.Idle
    }

    /** Pull-to-refresh: reset everything back to idle */
    var isRefreshing by mutableStateOf(false)
        private set

    fun refresh() {
        fetchJob?.cancel()
        fetchJob = null
        isRefreshing = true
        url = ""
        selectedFormatId = ""
        selectedTab = "video"
        statusMessage = null
        isDownloading = false
        downloadProgress = 0f
        screenState = ScreenState.Idle
        hapticEvent = HapticType.LIGHT
        viewModelScope.launch {
            delay(400) // brief visual feedback
            isRefreshing = false
        }
    }

    // ── retry after yt-dlp update ──
    private fun retryAfterUpdate() {
        viewModelScope.launch {
            try {
                statusMessage = "Updating yt-dlp and retrying..."
                withContext(Dispatchers.IO) { YoutubeDL.getInstance().updateYoutubeDL(appContext) }
                val ver = withContext(Dispatchers.IO) { YoutubeDL.getInstance().version(appContext) }
                ytdlpVersion = "yt-dlp $ver"
                val info = withContext(Dispatchers.IO) { fetchVideoInfo(url.trim()) }
                val first = info.formats.firstOrNull { it.type == "video" }
                selectedFormatId = first?.formatId ?: info.formats.firstOrNull()?.formatId ?: ""
                selectedTab = "video"
                screenState = ScreenState.Success(info)
                statusMessage = null
                hapticEvent = HapticType.SUCCESS
            } catch (retryEx: Exception) {
                screenState = ScreenState.Error(
                    cleanErrorMessage(retryEx.localizedMessage ?: retryEx.message ?: "Failed after update")
                )
                statusMessage = null
                hapticEvent = HapticType.ERROR
            }
        }
    }

    // ── Download ──
    fun download() {
        val state = screenState
        if (state !is ScreenState.Success) return
        val info = state.info
        if (selectedFormatId.isBlank()) {
            screenState = ScreenState.Error("Please select a quality option first.")
            return
        }

        isDownloading = true
        downloadProgress = 0f
        statusMessage = "Starting download..."
        hapticEvent = HapticType.LIGHT
        activeDownloadTitle = info.title
        activeDownloadThumbnail = info.thumbnail
        currentNavTab = NavTab.DOWNLOADS

        viewModelScope.launch {
            val tempDir = File(appContext.cacheDir, "downloads")
            try {
                withContext(Dispatchers.IO) {
                    if (!tempDir.exists()) tempDir.mkdirs()
                    // Clean stale files from previous downloads
                    tempDir.listFiles()?.forEach { it.delete() }
                }

                val executeError: Exception? = withContext(Dispatchers.IO) {
                    val request = YoutubeDLRequest(info.url)
                    applyCommonOptionsWithUrl(request, info.url)
                    request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")

                    // Use concurrent fragment downloading for speed
                    request.addOption("--concurrent-fragments", "4")

                    val selectedFormat = info.formats.find { it.formatId == selectedFormatId }
                    val isAudio = selectedFormat?.type == "audio"

                    if (isAudio) {
                        when (selectedFormatId) {
                            "__extract_mp3" -> {
                                request.addOption("-x")
                                request.addOption("--audio-format", "mp3")
                            }
                            "__extract_m4a" -> {
                                request.addOption("-x")
                                request.addOption("--audio-format", "m4a")
                            }
                            else -> request.addOption("-f", selectedFormatId)
                        }
                    } else {
                        request.addOption("-f", selectedFormatId)
                        request.addOption("--merge-output-format", "mp4")
                    }

                    try {
                        YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
                            val p = progress.coerceIn(0f, 100f)
                            downloadProgress = p / 100f
                            statusMessage = "Downloading... ${"%.1f".format(p)}% (ETA: ${eta}s)"
                            showProgressNotification(appContext, info.title, p.toInt())
                        }
                        null // no error
                    } catch (e: Exception) {
                        e // capture but don't throw — file might still exist
                    }
                }

                // Check for downloaded file regardless of whether execute() threw
                val resultFile = withContext(Dispatchers.IO) {
                    tempDir.listFiles()
                        ?.filter { it.length() > 0 }
                        ?.sortedByDescending { it.lastModified() }
                        ?.firstOrNull()
                }

                if (resultFile != null && resultFile.exists()) {
                    val isAudio = info.formats.find { it.formatId == selectedFormatId }?.type == "audio"
                    val mime = if (isAudio) "audio/*" else "video/*"
                    val fileSize = resultFile.length()
                    val savedFileName = resultFile.name

                    // Save to system Movies / Music folder via MediaStore
                    val contentUri = withContext(Dispatchers.IO) {
                        saveToMediaStore(appContext, resultFile, isAudio)
                    }
                    withContext(Dispatchers.IO) { resultFile.delete() }

                    // Record in download history
                    val selectedFormat = info.formats.find { it.formatId == selectedFormatId }
                    val record = DownloadRecord(
                        id = System.currentTimeMillis().toString(),
                        title = info.title,
                        platform = detectPlatform(info.url) ?: "Web",
                        type = if (isAudio) "audio" else "video",
                        format = selectedFormat?.label ?: selectedFormatId,
                        fileSize = fileSize,
                        fileName = savedFileName,
                        timestamp = System.currentTimeMillis(),
                        url = info.url,
                        contentUri = contentUri?.toString() ?: "",
                        thumbnailUrl = info.thumbnail
                    )
                    historyRepo.add(record)
                    loadHistory()

                    showCompleteNotification(appContext, info.title, contentUri, mime)
                    statusMessage = "Download complete!"
                    downloadProgress = 1f
                    hapticEvent = HapticType.SUCCESS
                    activeDownloadTitle = ""
                    activeDownloadThumbnail = ""
                } else if (executeError != null) {
                    // No file and we had an error — report it
                    cancelProgressNotification(appContext)
                    val rawMsg = executeError.localizedMessage ?: executeError.message ?: "Unknown error"
                    val errMsg = when {
                        "403" in rawMsg || "older than" in rawMsg || "Forbidden" in rawMsg ->
                            "Download blocked — tap the version pill to update yt-dlp, then retry."
                        "timed out" in rawMsg || "TransportError" in rawMsg ->
                            "Connection timed out. Check your internet and try again."
                        "Unsupported URL" in rawMsg ->
                            "This site or URL is not supported."
                        else -> "Download failed: ${cleanErrorMessage(rawMsg)}"
                    }
                    statusMessage = errMsg
                    hapticEvent = HapticType.ERROR
                } else {
                    cancelProgressNotification(appContext)
                    statusMessage = null
                    screenState = ScreenState.Error("Download completed but file not found.")
                    hapticEvent = HapticType.ERROR
                }
            } catch (e: Exception) {
                CrashLogger.logException(appContext, "MainViewModel.download", e)
                cancelProgressNotification(appContext)
                val rawMsg = e.localizedMessage ?: e.message ?: "Unknown error"
                statusMessage = "Download failed: ${cleanErrorMessage(rawMsg)}"
                hapticEvent = HapticType.ERROR
            } finally {
                isDownloading = false
                if (activeDownloadTitle.isNotBlank()) {
                    // Clear after short delay so the user can see 100% state
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(2500)
                        activeDownloadTitle = ""
                        activeDownloadThumbnail = ""
                    }
                }
            }
        }
    }

    // ── yt-dlp update ──
    fun updateYtDlp() {
        if (isUpdating) return
        isUpdating = true
        statusMessage = "Updating yt-dlp..."
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(appContext)
                    val newVersion = YoutubeDL.getInstance().version(appContext)
                    ytdlpVersion = "yt-dlp $newVersion"
                }
                statusMessage = "yt-dlp updated!"
            } catch (e: Exception) {
                statusMessage = "Update failed: ${e.localizedMessage}"
            } finally { isUpdating = false }
        }
    }

    private fun autoUpdateYtDlp() {
        viewModelScope.launch {
            try {
                isUpdating = true
                statusMessage = "Updating yt-dlp..."
                withContext(Dispatchers.IO) {
                    try { YoutubeDL.getInstance().updateYoutubeDL(appContext) }
                    catch (_: Exception) { }
                }
                val ver = withContext(Dispatchers.IO) { YoutubeDL.getInstance().version(appContext) }
                ytdlpVersion = "yt-dlp $ver"
                isUpdating = false
                statusMessage = "yt-dlp is up to date"
                delay(2500)
                statusMessage = null
            } catch (_: Exception) {
                ytdlpVersion = "yt-dlp unknown"
                isUpdating = false
                statusMessage = null
            }
        }
    }
}
