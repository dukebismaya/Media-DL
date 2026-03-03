package com.bismaya.mediadl

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.util.Log
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// ── Torrent download item state ─────────────────────────────────────────────────
data class TorrentItem(
    val infoHash: String,
    val name: String,
    val totalSize: Long,
    val downloadedBytes: Long,
    val progress: Float,           // 0.0 – 1.0
    val downloadSpeed: Long,       // bytes/sec
    val uploadSpeed: Long,         // bytes/sec
    val seeders: Int,
    val peers: Int,
    val state: TorrentState,
    val files: List<TorrentFileInfo> = emptyList(),
    val savePath: String = "",
    val error: String? = null,
    val isSequential: Boolean = false,
    val magnetUri: String = ""
)

enum class TorrentState {
    QUEUED, CHECKING, METADATA, DOWNLOADING, FINISHED, SEEDING, PAUSED, ERROR
}

data class TorrentFileInfo(
    val index: Int,
    val name: String,
    val size: Long,
    val progress: Float,
    val priority: Int = 4          // 0=skip, 1=low, 4=normal, 7=top
)

// ── Torrent Engine (singleton) ──────────────────────────────────────────────────
object TorrentEngine {

    private const val TAG = "TorrentEngine"
    private var session: SessionManager? = null

    /**
     * Maps info-hash → TorrentHandle for control operations (pause/resume/etc).
     * Handles are stored on AddTorrentAlert and removed on TorrentRemovedAlert.
     *
     * Thread-safety rules:
     *  • NEVER call handle.status(), handle.fileProgress(), or handle.filePriority()
     *    from an alert callback. Alert callbacks run on libtorrent's network thread
     *    which already holds the session mutex; re-entering those calls causes a
     *    recursive lock on a non-recursive C++ mutex → undefined behaviour → SIGSEGV.
     *  • It IS safe to call those methods from the Kotlin IO dispatcher (Dispatchers.IO)
     *    because that is a different OS thread and the mutex is acquired normally.
     *  • Lightweight handle ops (pause, resume, setFlags, infoHash) are safe from any
     *    thread — they post a message to the session thread rather than locking directly.
     */
    private val handles = ConcurrentHashMap<String, TorrentHandle>()

    /**
     * Single-threaded executor for ALL JNI calls that require the session mutex
     * (handle.status, fileProgress, filePriority, savePath, torrentFile).
     *
     * Why: libtorrent's alert callbacks run on the network thread which already
     * holds the session mutex. Calling any of the above from an alert callback
     * re-enters a non-recursive C++ mutex → instant SIGSEGV.
     *
     * By routing ALL heavy JNI to this single executor we guarantee:
     *  (a) Never called from the alert thread  → no deadlock
     *  (b) All reads are sequential             → no concurrent stale-handle race
     */
    private val jniExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "torrent-jni").also { it.isDaemon = true }
    }

    /**
     * Info-hash of the card currently expanded in the UI, or null.
     * Written from the IO polling coroutine, read on the alert-dispatch thread.
     * @Volatile is sufficient because we only need visibility, not atomicity.
     */
    @Volatile private var expandedCardHash: String? = null

    private val torrentItemCache = ConcurrentHashMap<String, TorrentItem>()

    /** Custom save directory (empty = default) */
    var customSaveDir: String = ""

    /** Callback: torrent was added (passes info hash string, no JNI) */
    var onTorrentAdded: ((String) -> Unit)? = null

    /** Callback: torrent finished (passes info hash string, no JNI) */
    var onTorrentFinished: ((String) -> Unit)? = null

    /** Callback for errors */
    var onError: ((String, String) -> Unit)? = null

    val isRunning: Boolean get() = session?.isRunning == true

    private val trackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.openbittorrent.com:80/announce",
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce"
    )

    @Synchronized
    fun start(context: Context, prefs: TorrentPrefs = TorrentPrefs()) {
        if (session?.isRunning == true) return

        val sm = SessionManager()
        session = sm

        sm.addListener(object : AlertListener {
            override fun types(): IntArray? = null

            override fun alert(alert: Alert<*>) {
                try {
                    when (alert) {
                        is AddTorrentAlert -> {
                            val ec = alert.error()
                            if (ec.isError) {
                                Log.e(TAG, "Add torrent error: ${ec.message}")
                                onError?.invoke("", ec.message)
                            } else {
                                val h = alert.handle()
                                val hash = try { h.infoHash().toHex() } catch (e: Throwable) {
                                    Log.e(TAG, "infoHash() failed on add: ${e.message}"); return
                                }
                                handles[hash] = h
                                if (prefs.sequentialByDefault) {
                                    try { h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) } catch (_: Throwable) { }
                                }
                                // Seed a minimal placeholder so the UI card appears immediately.
                                // IMPORTANT: do NOT call handle.status(), handle.fileProgress() or
                                // handle.filePriority() from any alert callback — the alert dispatcher
                                // runs on libtorrent's network thread which already holds the session
                                // mutex. Re-entering those calls causes a recursive mutex lock →
                                // undefined behaviour → SIGSEGV. All heavy JNI work goes to the
                                // dedicated fetchAndMergeFileInfo() called from the IO thread.
                                torrentItemCache[hash] = TorrentItem(
                                    infoHash = hash, name = "Fetching metadata\u2026",
                                    totalSize = 0L, downloadedBytes = 0L, progress = 0f,
                                    downloadSpeed = 0L, uploadSpeed = 0L,
                                    seeders = 0, peers = 0, state = TorrentState.METADATA
                                )
                                Log.d(TAG, "Torrent added [$hash]")
                                try { sm.postTorrentUpdates() } catch (_: Throwable) { }
                                onTorrentAdded?.invoke(hash)
                            }
                        }
                        is StateUpdateAlert -> {
                            // Process pre-computed status snapshots ON the alert-dispatch thread.
                            // libtorrent only includes alive torrents in state_update_alert, so
                            // every status here is safe to access.  Alert dispatch is sequential —
                            // TorrentRemovedAlert cannot interleave while we're here, which means
                            // every entry in `handles` that appears in this status list is valid.
                            //
                            // We derive the info-hash via TorrentHandle(st.swig().getHandle())
                            // and call infoHash() — confirmed safe on the alert-dispatch thread.
                            // We do NOT call h.status() ourselves: the snapshot is already in `st`.
                            val statuses = alert.status()
                            val aliveHashes = HashSet<String>(statuses.size * 2)
                            for (st in statuses) {
                                try {
                                    val h = TorrentHandle(st.swig().getHandle())
                                    val hash = h.infoHash().toHex()
                                    aliveHashes.add(hash)
                                    torrentItemCache[hash] = buildTorrentItemFromStatus(hash, st)
                                } catch (_: Throwable) { }
                            }
                            // File info for the expanded card — only if we just confirmed it alive
                            val expanded = expandedCardHash
                            if (expanded != null && expanded in aliveHashes) {
                                val h = handles[expanded]
                                if (h != null) {
                                    // Submit to jniExecutor so the potentially-slow file-info reads
                                    // do not block alert dispatch any longer than necessary.
                                    // The handle is alive now; the tiny race between submit and
                                    // execute is acceptable here (user-triggered, infrequent).
                                    jniExecutor.execute { doFetchFileInfo(expanded, h) }
                                }
                            }
                        }
                        is TorrentRemovedAlert -> {
                            // Clean up dead handles and stale cache entries
                            try {
                                val hash = alert.handle().infoHash().toHex()
                                handles.remove(hash)
                                torrentItemCache.remove(hash)
                                Log.d(TAG, "Torrent removed [$hash]")
                            } catch (_: Throwable) { }
                        }
                        is StateChangedAlert -> { /* StateUpdateAlert handles updates */ }
                        is TorrentFinishedAlert -> {
                            val hash = try { alert.handle().infoHash().toHex() } catch (_: Throwable) { return }
                            Log.d(TAG, "Torrent finished: $hash")
                            onTorrentFinished?.invoke(hash)
                        }
                        is PieceFinishedAlert -> { /* StateUpdateAlert handles progress */ }
                        is TorrentErrorAlert -> {
                            val hash = try { alert.handle().infoHash().toHex() } catch (_: Throwable) { "" }
                            val ec = alert.error()
                            Log.e(TAG, "Torrent error [$hash]: ${ec.message}")
                            onError?.invoke(hash, ec.message)
                        }
                        is MetadataReceivedAlert -> {
                            val hash = try { alert.handle().infoHash().toHex() } catch (_: Throwable) { return }
                            Log.d(TAG, "Metadata received: $hash")
                            // Trigger a StateUpdateAlert (processed on jniExecutor) so the
                            // cache gets a fresh snapshot and file info is fetched immediately.
                            try { sm.postTorrentUpdates() } catch (_: Throwable) { }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "AlertListener crashed: ${e.message}", e)
                }
            }
        })

        val saveDir = getActiveSaveDirectory(context)
        if (!saveDir.exists()) saveDir.mkdirs()

        val params = SessionParams(buildSettingsPack(prefs))
        sm.start(params)
        Log.d(TAG, "TorrentEngine started, save dir: ${saveDir.absolutePath}")
    }

    @Synchronized
    fun stop() {
        session?.stop()
        handles.clear()
        torrentItemCache.clear()
        jniExecutor.shutdownNow()
        session = null
        Log.d(TAG, "TorrentEngine stopped")
    }

    /**
     * Ask libtorrent to post a StateUpdateAlert for all active torrents.
     * Safe to call from any thread. The alert will be processed on the alert
     * thread and the cache will be refreshed there.
     */
    fun requestStatusUpdates() {
        try { session?.postTorrentUpdates() } catch (_: Throwable) { }
    }

    // ── Add torrents ────────────────────────────────────────────────────────────

    fun addMagnet(context: Context, magnetUri: String): Boolean {
        val sm = session ?: return false
        if (!sm.isRunning) {
            Log.w(TAG, "addMagnet called but session not running")
            return false
        }
        return try {
            val saveDir = getActiveSaveDirectory(context)
            if (!saveDir.exists()) saveDir.mkdirs()
            sm.download(magnetUri, saveDir, torrent_flags_t())
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add magnet: ${e.message}", e)
            onError?.invoke("", e.message ?: "Failed to add magnet link")
            false
        }
    }

    fun addTorrentFile(context: Context, torrentFile: File): Boolean {
        val sm = session ?: return false
        return try {
            val saveDir = getActiveSaveDirectory(context)
            if (!saveDir.exists()) saveDir.mkdirs()
            val ti = TorrentInfo(torrentFile)
            sm.download(ti, saveDir)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add torrent: ${e.message}", e)
            onError?.invoke("", e.message ?: "Failed to add torrent file")
            false
        }
    }

    fun addTorrentBytes(context: Context, bytes: ByteArray): Boolean {
        val sm = session ?: return false
        return try {
            val saveDir = getActiveSaveDirectory(context)
            if (!saveDir.exists()) saveDir.mkdirs()
            val ti = TorrentInfo(bytes)
            sm.download(ti, saveDir)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add torrent bytes: ${e.message}", e)
            onError?.invoke("", e.message ?: "Failed to add torrent")
            false
        }
    }

    // ── Torrent control ─────────────────────────────────────────────────────────

    fun pause(infoHash: String) {
        handles[infoHash]?.pause()
    }

    fun resume(infoHash: String) {
        handles[infoHash]?.resume()
    }

    fun remove(infoHash: String, deleteFiles: Boolean = false) {
        val h = handles.remove(infoHash) ?: return
        val sm = session ?: return
        if (deleteFiles) {
            sm.remove(h, SessionHandle.DELETE_FILES)
        } else {
            sm.remove(h)
        }
    }

    // ── Sequential download ─────────────────────────────────────────────────────

    fun setSequentialDownload(infoHash: String, enabled: Boolean) {
        val h = handles[infoHash] ?: return
        try {
            if (enabled) {
                h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            } else {
                h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set sequential: ${e.message}")
        }
    }

    fun isSequential(infoHash: String): Boolean =
        torrentItemCache[infoHash]?.isSequential ?: false

    // ── File priorities ─────────────────────────────────────────────────────────

    fun setFilePriority(infoHash: String, fileIndex: Int, priority: Priority) {
        val h = handles[infoHash] ?: return
        try {
            h.filePriority(fileIndex, priority)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set file priority: ${e.message}")
        }
    }

    fun getFilePriority(infoHash: String, fileIndex: Int): Int {
        val h = handles[infoHash] ?: return 4
        return try {
            h.filePriority(fileIndex).swig().toInt()
        } catch (_: Exception) { 4 }
    }

    // ── Speed limits ────────────────────────────────────────────────────────────

    fun setSpeedLimits(downloadLimit: Int, uploadLimit: Int) {
        val sm = session ?: return
        try {
            val sp = sm.settings()
            sp.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), downloadLimit)
            sp.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), uploadLimit)
            sm.applySettings(sp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speed limits: ${e.message}")
        }
    }

    // ── Magnet URI generation ───────────────────────────────────────────────────

    fun getMagnetUri(infoHash: String): String {
        // Use cached name — safe, no JNI
        val name = torrentItemCache[infoHash]?.name ?: ""
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val trackerParams = trackers.joinToString("") {
            "&tr=${URLEncoder.encode(it, "UTF-8")}"
        }
        return "magnet:?xt=urn:btih:$infoHash&dn=$encodedName$trackerParams"
    }

    // ── Snapshots (read from cache — no JNI, safe from any thread) ──────────────

    fun getTorrentItem(infoHash: String): TorrentItem? = torrentItemCache[infoHash]

    fun getAllTorrents(): List<TorrentItem> = torrentItemCache.values.toList()

    /**
     * Blocking status + file-info refresh, called from the ViewModel's IO polling loop.
     *
     * Submits ALL JNI reads as a single batch to [jniExecutor] (the only thread
     * allowed to call h.status / h.fileProgress / h.filePriority / h.savePath) and
     * blocks until the batch completes (timeout 5 s).  Because the batch is a single
     * queued task on a single-threaded executor there is zero concurrency with any
     * other JNI work, and zero chance of re-entering the session mutex from the
     * libtorrent alert / network thread.
     *
     * @param expandedHash  info-hash of the card currently expanded in the UI, or null.
     *   Full per-file data (file progress, priority, save path) is only fetched for
     *   this torrent and for any torrent whose file list has not yet been populated.
     */
    fun refreshNow(expandedHash: String? = null): List<TorrentItem> {
        // Record which card is expanded so StateUpdateAlert can fetch file info for it.
        expandedCardHash = expandedHash
        // Ask libtorrent to post a StateUpdateAlert. The alert-dispatch thread will
        // receive it, update torrentItemCache from the pre-computed snapshots, and
        // (if a card is expanded) enqueue a doFetchFileInfo task on jniExecutor.
        // No JNI reads happen on the polling coroutine thread — just a cache read.
        try { session?.postTorrentUpdates() } catch (_: Throwable) { }
        return torrentItemCache.values.toList()
    }

    // ── Network & RAM helpers ───────────────────────────────────────────────────

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isLowRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.lowMemory || (mi.availMem < 150 * 1024 * 1024) // < 150 MB
    }

    fun pauseAllDownloading() {
        // h.pause() posts to the session thread internally — safe from any thread.
        // We intentionally do NOT call h.status() here; that would be unsafe outside
        // the alert thread and is the known source of JNI SIGSEGV crashes.
        // Calling pause() on an already-paused torrent is a benign no-op in libtorrent.
        handles.values.forEach { h ->
            try { h.pause() } catch (_: Throwable) { }
        }
    }

    fun resumeAll() {
        handles.values.forEach { h ->
            try { h.resume() } catch (_: Exception) { }
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────────────

    /**
     * Builds a TorrentItem from a [TorrentStatus] snapshot.
     * [status] is the return value of [TorrentHandle.status] called on [jniExecutor],
     * so it contains live data already copied out of the native layer.
     * This function itself is pure Kotlin — no further JNI calls.
     */
    private fun buildTorrentItemFromStatus(hash: String, status: TorrentStatus): TorrentItem {
        val torrentName = try {
            status.name().let { if (it.isNullOrBlank()) "Fetching metadata\u2026" else it }
        } catch (_: Throwable) { torrentItemCache[hash]?.name ?: "Fetching metadata\u2026" }

        // total_wanted is populated in the status snapshot when hasMetadata is true
        val total = if (status.hasMetadata()) {
            try { status.totalWanted() } catch (_: Throwable) {
                torrentItemCache[hash]?.totalSize ?: 0L
            }
        } else 0L

        // Preserve the file list from the most recent fetchAndMergeFileInfo() call on IO thread
        val existingFiles = torrentItemCache[hash]?.files ?: emptyList()

        val done       = try { status.totalDone() }                   catch (_: Throwable) { 0L }
        val prog       = try { status.progress() }                    catch (_: Throwable) { 0f }
        val dlSpeed    = try { status.downloadPayloadRate().toLong() } catch (_: Throwable) { 0L }
        val ulSpeed    = try { status.uploadPayloadRate().toLong() }   catch (_: Throwable) { 0L }
        val seeds      = try { status.numSeeds() }                    catch (_: Throwable) { 0 }
        val numPeers   = try { status.numPeers() }                    catch (_: Throwable) { 0 }
        val state      = try { mapState(status) }                    catch (_: Throwable) { TorrentState.ERROR }
        // savePath is on TorrentHandle, not on the status snapshot — use the cached value.
        // fetchAndMergeFileInfo() (called from IO thread) keeps this up to date.
        val path       = torrentItemCache[hash]?.savePath ?: ""
        val sequential = try {
            status.flags().and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero()
        } catch (_: Throwable) { false }
        val errMsg: String? = try {
            val ec: ErrorCode = status.errorCode()
            if (ec.isError) ec.message else null
        } catch (_: Throwable) { null }
        val magnetLink = "magnet:?xt=urn:btih:$hash" +
            "&dn=${URLEncoder.encode(torrentName, "UTF-8")}" +
            trackers.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }

        return TorrentItem(
            infoHash        = hash,
            name            = torrentName,
            totalSize       = total,
            downloadedBytes = done,
            progress        = prog,
            downloadSpeed   = dlSpeed,
            uploadSpeed     = ulSpeed,
            seeders         = seeds,
            peers           = numPeers,
            state           = state,
            files           = existingFiles,
            savePath        = path,
            error           = errMsg,
            isSequential    = sequential,
            magnetUri       = magnetLink
        )
    }

    /**
     * Fetches per-file info and merges it into [torrentItemCache].
     * MUST be called only from [jniExecutor] — never from the alert thread
     * or un-serialized IO threads.
     */
    private fun doFetchFileInfo(hash: String, h: TorrentHandle) {
        val current = torrentItemCache[hash] ?: return
        try {
            val ti = h.torrentFile() ?: return
            val fs = ti.files()
            val savePath  = try { h.savePath() } catch (_: Throwable) { current.savePath }
            val totalSize = try { ti.totalSize() } catch (_: Throwable) { current.totalSize }
            val progresses: LongArray = try {
                h.fileProgress(TorrentHandle.PIECE_GRANULARITY)
            } catch (_: Throwable) { LongArray(0) }
            val files = (0 until fs.numFiles()).mapNotNull { i ->
                try {
                    val fileSize   = fs.fileSize(i)
                    val downloaded = if (i < progresses.size) progresses[i] else 0L
                    val prio       = try { h.filePriority(i).swig().toInt() } catch (_: Throwable) { 4 }
                    TorrentFileInfo(
                        index    = i,
                        name     = fs.fileName(i),
                        size     = fileSize,
                        progress = if (fileSize > 0) downloaded.toFloat() / fileSize else 0f,
                        priority = prio
                    )
                } catch (_: Throwable) { null }
            }
            torrentItemCache[hash] = current.copy(
                totalSize = if (totalSize > 0) totalSize else current.totalSize,
                savePath  = savePath,
                files     = files
            )
        } catch (_: Throwable) { }
    }

    /**
     * Public entry-point for the ViewModel to request a file-info refresh for
     * the expanded card. Submits work to [jniExecutor] and returns immediately.
     */
    fun fetchAndMergeFileInfo(infoHash: String) {
        val h = handles[infoHash] ?: return
        jniExecutor.execute { doFetchFileInfo(infoHash, h) }
    }

    private fun mapState(status: TorrentStatus): TorrentState {
        val ec: ErrorCode = status.errorCode()
        if (ec.isError) return TorrentState.ERROR

        val isPaused: Boolean = status.flags().and_(TorrentFlags.PAUSED).non_zero()
        if (isPaused) return TorrentState.PAUSED

        return when (status.state()) {
            TorrentStatus.State.CHECKING_FILES -> TorrentState.CHECKING
            TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.METADATA
            TorrentStatus.State.DOWNLOADING -> TorrentState.DOWNLOADING
            TorrentStatus.State.FINISHED -> TorrentState.FINISHED
            TorrentStatus.State.SEEDING -> TorrentState.SEEDING
            TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.CHECKING
            else -> TorrentState.QUEUED
        }
    }

    fun getActiveSaveDirectory(context: Context): File {
        if (customSaveDir.isNotBlank()) {
            val custom = File(customSaveDir)
            if (custom.exists() || custom.mkdirs()) return custom
        }
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "MediaDL/Torrents")
    }

    private fun buildSettingsPack(prefs: TorrentPrefs): SettingsPack {
        return SettingsPack().apply {
            // Peer discovery
            setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)

            // Active limits
            setInteger(settings_pack.int_types.active_downloads.swigValue(), prefs.maxActiveDownloads)
            setInteger(settings_pack.int_types.active_seeds.swigValue(), prefs.maxActiveSeeds)
            setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)

            // Speed limits
            setInteger(settings_pack.int_types.download_rate_limit.swigValue(), prefs.downloadSpeedLimit)
            setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), prefs.uploadSpeedLimit)

            // User agent
            setString(
                settings_pack.string_types.user_agent.swigValue(),
                "MediaDL/1.0 libtorrent/${LibTorrent.version()}"
            )
        }
    }
}
