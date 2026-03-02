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
     * Handles here are fetched from AddTorrentAlert and stored for later use.
     * NEVER call handle.status() on these outside the alert thread.
     */
    private val handles = ConcurrentHashMap<String, TorrentHandle>()

    /**
     * Thread-safe cache of the latest TorrentItem snapshot for each torrent.
     * Built exclusively on libtorrent's alert thread (via StateUpdateAlert),
     * which means zero concurrent JNI access. Read from any thread safely.
     */
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
                                // We are on the libtorrent alert thread — safe to call handle methods
                                val h = alert.handle()
                                val hash = try { h.infoHash().toHex() } catch (e: Throwable) {
                                    Log.e(TAG, "infoHash() failed on add: ${e.message}"); return
                                }
                                handles[hash] = h
                                if (prefs.sequentialByDefault) {
                                    try { h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) } catch (_: Throwable) { }
                                }
                                Log.d(TAG, "Torrent added [$hash]")
                                // Trigger immediate StateUpdateAlert so cache is populated quickly
                                try { sm.postTorrentUpdates() } catch (_: Throwable) { }
                                onTorrentAdded?.invoke(hash)
                            }
                        }
                        is StateUpdateAlert -> {
                            // StateUpdateAlert fires after postTorrentUpdates().
                            // We use it as a safe trigger: iterate our own handles map here
                            // on the single alert thread, where h.status() is safe to call.
                            handles.forEach { (hash, h) ->
                                try {
                                    val item = buildTorrentItemOnAlertThread(hash, h)
                                    torrentItemCache[hash] = item
                                } catch (e: Throwable) {
                                    Log.e(TAG, "StateUpdateAlert: failed to build item [$hash]: ${e.message}")
                                }
                            }
                        }
                        is TorrentRemovedAlert -> {
                            // Clean up dead handles so polling never touches them again
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
                            // Trigger status refresh so file list appears promptly
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
        handles.values.forEach { h ->
            try {
                val isPaused = h.status(TorrentHandle.QUERY_ACCURATE_DOWNLOAD_COUNTERS)
                    .flags().and_(TorrentFlags.PAUSED).non_zero()
                if (!isPaused) h.pause()
            } catch (_: Throwable) { }
        }
    }

    fun resumeAll() {
        handles.values.forEach { h ->
            try { h.resume() } catch (_: Exception) { }
        }
    }

    // ── Internal helpers (called ONLY from the alert thread) ────────────────────

    /**
     * Builds a TorrentItem by calling JNI methods on [handle].
     * MUST be called only from the libtorrent alert thread (StateUpdateAlert handler),
     * where calling handle.status() is safe (single alert thread, no concurrent access).
     */
    private fun buildTorrentItemOnAlertThread(hash: String, handle: TorrentHandle): TorrentItem {
        val status = handle.status()

        val torrentName: String = try {
            handle.getName().let { if (it.isNullOrBlank()) "Fetching metadata\u2026" else it }
        } catch (_: Throwable) { "Unknown" }

        val total: Long
        val files = mutableListOf<TorrentFileInfo>()
        if (status.hasMetadata()) {
            val ti = try { handle.torrentFile() } catch (_: Throwable) { null }
            total = ti?.totalSize() ?: 0L
            if (ti != null) {
                try {
                    val fs = ti.files()
                    val progresses: LongArray = try {
                        handle.fileProgress(TorrentHandle.PIECE_GRANULARITY)
                    } catch (_: Throwable) { LongArray(0) }
                    for (i in 0 until fs.numFiles()) {
                        val fileSize = fs.fileSize(i)
                        val downloaded = if (i < progresses.size) progresses[i] else 0L
                        val prio = try { handle.filePriority(i).swig().toInt() } catch (_: Throwable) { 4 }
                        files.add(
                            TorrentFileInfo(
                                index = i,
                                name = fs.fileName(i),
                                size = fileSize,
                                progress = if (fileSize > 0) (downloaded.toFloat() / fileSize) else 0f,
                                priority = prio
                            )
                        )
                    }
                } catch (_: Throwable) { }
            }
        } else {
            total = 0L
        }

        val done: Long  = try { status.totalDone() }                   catch (_: Throwable) { 0L }
        val prog: Float = try { status.progress() }                    catch (_: Throwable) { 0f }
        val dlSpeed     = try { status.downloadPayloadRate().toLong() } catch (_: Throwable) { 0L }
        val ulSpeed     = try { status.uploadPayloadRate().toLong() }   catch (_: Throwable) { 0L }
        val seeds: Int  = try { status.numSeeds() }                    catch (_: Throwable) { 0 }
        val numPeers    = try { status.numPeers() }                    catch (_: Throwable) { 0 }
        val torrentState = try { mapState(status) }                   catch (_: Throwable) { TorrentState.ERROR }
        val path: String = try { handle.savePath() }                  catch (_: Throwable) { "" }
        val sequential  = try {
            status.flags().and_(TorrentFlags.SEQUENTIAL_DOWNLOAD).non_zero()
        } catch (_: Throwable) { false }
        val errMsg: String? = try {
            val ec: ErrorCode = status.errorCode()
            if (ec.isError) ec.message else null
        } catch (_: Throwable) { null }
        val magnetLink  = "magnet:?xt=urn:btih:$hash" +
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
            state           = torrentState,
            files           = files.toList(),
            savePath        = path,
            error           = errMsg,
            isSequential    = sequential,
            magnetUri       = magnetLink
        )
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
