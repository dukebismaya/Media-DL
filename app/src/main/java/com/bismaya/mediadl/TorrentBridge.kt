package com.bismaya.mediadl

import android.content.Context
import android.net.Uri
import com.bismaya.mediadl.core.RepositoryHelper
import com.bismaya.mediadl.core.model.AddTorrentParams
import com.bismaya.mediadl.core.model.TorrentEngine
import com.bismaya.mediadl.core.model.TorrentInfoProvider
import com.bismaya.mediadl.core.model.data.MagnetInfo
import com.bismaya.mediadl.core.model.data.Priority
import com.bismaya.mediadl.core.model.data.TorrentInfo
import com.bismaya.mediadl.core.model.data.TorrentListState
import com.bismaya.mediadl.core.model.data.TorrentStateCode
import com.bismaya.mediadl.core.model.data.entity.TagInfo
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow

/**
 * Thin Kotlin adapter over the libretorrent TorrentEngine + TorrentInfoProvider.
 *
 * Converts RxJava3 Flowables to Kotlin Flows so TorrentViewModel can use
 * idiomatic coroutine / Flow APIs without touching any RxJava3 directly.
 *
 * Also provides suspend-friendly wrappers for one-shot operations.
 */
object TorrentBridge {

    // ── Singletons (lazy-initialised on first call) ────────────────────────────

    private lateinit var engine: TorrentEngine
    private lateinit var infoProvider: TorrentInfoProvider
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        engine = TorrentEngine.getInstance(appContext)
        infoProvider = TorrentInfoProvider.getInstance(appContext)
    }

    fun requireInit(context: Context) {
        if (!::engine.isInitialized) init(context)
    }

    // ── Engine lifecycle ───────────────────────────────────────────────────────

    fun start() = engine.start()

    /** True if the libtorrent session is currently running. */
    fun isRunning(): Boolean = engine.isRunning

    /** Observe engine running state as a Kotlin Flow. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeEngineRunning(context: Context): Flow<Boolean> {
        requireInit(context)
        return engine.observeEngineRunning().asFlow()
    }

    /** Request graceful stop (respects keepAlive preference). */
    fun requestStop() = engine.requestStop()

    /** Hard stop regardless of keepAlive. */
    fun forceStop() = engine.forceStop()

    // ── Torrent list ───────────────────────────────────────────────────────────

    /**
     * Observe live list of all torrent infos as a Kotlin Flow.
     * Emits a [TorrentListState] every time any torrent changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeInfoList(): Flow<TorrentListState> =
        infoProvider.observeInfoList().asFlow()

    /** One-shot current list of all TorrentInfo objects. */
    fun getInfoListNow(): List<TorrentInfo> =
        infoProvider.getInfoListSingle().blockingGet()

    // ── Single torrent ─────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeInfo(torrentId: String): Flow<TorrentInfo> =
        infoProvider.observeInfo(torrentId).asFlow()

    fun makeInfoSync(torrentId: String): TorrentInfo? =
        engine.makeInfoSync(torrentId)

    /**
     * Returns the full file list for a torrent by reading its metadata.
     * Falls back to an empty list if metadata is not yet available.
     * Must be called from a background thread.
     */
    fun getFilesForTorrent(id: String): List<TorrentFileInfo> {
        val meta = engine.getTorrentMetaInfo(id) ?: return emptyList()
        val priorities = engine.makeInfoSync(id)?.filePriorities ?: emptyArray()
        val received  = engine.makeAdvancedInfoSync(id)?.filesReceivedBytes ?: LongArray(0)
        return meta.fileList.map { f ->
            val idx      = f.index
            val priority = priorities.getOrNull(idx)?.value() ?: 4
            val rx       = received.getOrElse(idx) { 0L }
            val progress = if (f.size > 0) (rx.toFloat() / f.size).coerceIn(0f, 1f) else 1f
            TorrentFileInfo(
                index    = idx,
                name     = f.path.substringAfterLast('/').ifBlank { f.path },
                size     = f.size,
                progress = progress,
                priority = priority
            )
        }
    }

    // ── Add torrent ────────────────────────────────────────────────────────────

    /**
     * Add a magnet URI. Returns a [MagnetInfo] describing the parsed magnet,
     * or throws on failure.
     */
    fun parseMagnet(uri: String): MagnetInfo? = engine.parseMagnet(uri)

    /**
     * Add a torrent by magnet URI or .torrent content URI. Paused by default
     * when [addPaused] = true.
     */
    fun addTorrent(
        source: String,
        fromMagnet: Boolean,
        sha1hash: String,
        name: String,
        downloadPath: Uri,
        sequentialDownload: Boolean = false,
        addPaused: Boolean = false,
        filePriorities: Array<Priority>? = null,
        firstLastPiecePriority: Boolean = false
    ) {
        val params = AddTorrentParams(
            source,
            fromMagnet,
            sha1hash,
            name,
            filePriorities,
            downloadPath,
            sequentialDownload,
            addPaused,
            emptyList<TagInfo>(),
            firstLastPiecePriority
        )
        engine.addTorrents(listOf(params), false)
    }

    /** Add a .torrent from a content [Uri]. */
    fun addTorrentUri(uri: Uri) = engine.addTorrent(uri)

    /** Add a .torrent from a content [Uri] to a specific save path. */
    fun addTorrentUri(uri: Uri, savePath: Uri) = engine.addTorrent(uri, savePath)

    // ── Torrent control ────────────────────────────────────────────────────────

    fun pauseResume(torrentId: String) = engine.pauseResumeTorrent(torrentId)

    /** Pause a single torrent (manual pause — persists across session restarts). */
    fun pauseTorrent(torrentId: String) = engine.pauseResumeTorrent(torrentId)

    /** Resume a single torrent (manual resume). */
    fun resumeTorrent(torrentId: String) = engine.pauseResumeTorrent(torrentId)

    fun deleteTorrents(ids: List<String>, withFiles: Boolean) =
        engine.deleteTorrents(ids, withFiles)

    fun pauseAll() = engine.pauseAll()

    fun resumeAll() = engine.resumeAll()

    fun forceRecheck(ids: List<String>) = engine.forceRecheckTorrents(ids)

    fun forceAnnounce(ids: List<String>) = engine.forceAnnounceTorrents(ids)

    // ── Torrent settings ───────────────────────────────────────────────────────

    fun setSequential(id: String, sequential: Boolean) =
        engine.setSequentialDownload(id, sequential)

    fun isSequential(id: String): Boolean = engine.isSequentialDownload(id)

    fun setFirstLastPiece(id: String, enabled: Boolean) =
        engine.setFirstLastPiecePriority(id, enabled)

    fun setTorrentName(id: String, name: String) = engine.setTorrentName(id, name)

    fun setDownloadPath(id: String, path: Uri) = engine.setDownloadPath(id, path)

    fun prioritizeFiles(id: String, priorities: Array<Priority>) =
        engine.prioritizeFiles(id, priorities)

    /**
     * Set priority for a single file by index.
     * priority 0 = skip, 4 = normal, 7 = top.
     */
    fun prioritizeFile(id: String, fileIndex: Int, priority: Int) {
        val meta = engine.getTorrentMetaInfo(id) ?: return
        val count = meta.fileCount
        if (fileIndex < 0 || fileIndex >= count) return
        val priorities = Array<Priority>(count) { Priority.DEFAULT }
        priorities[fileIndex] = Priority.fromValue(priority)
        engine.prioritizeFiles(id, priorities)
    }

    fun makeMagnet(id: String, includePriorities: Boolean = false): String? =
        engine.makeMagnet(id, includePriorities)

    // ── Session-wide speed limits ──────────────────────────────────────────────

    /**
     * Apply global download / upload speed limits (bytes/sec). 0 = unlimited.
     * Saves to SettingsRepository so the engine picks them up via settings sync.
     */
    fun setSpeedLimits(downloadLimit: Int, uploadLimit: Int) {
        val repo = com.bismaya.mediadl.core.RepositoryHelper.getSettingsRepository(appContext)
        repo.maxDownloadSpeedLimit(downloadLimit)
        repo.maxUploadSpeedLimit(uploadLimit)
    }

    // ── Trackers ───────────────────────────────────────────────────────────────

    fun replaceTrackers(id: String, urls: List<String>) =
        engine.replaceTrackers(id, urls)

    fun addTrackers(id: String, urls: List<String>) =
        engine.addTrackers(id, urls)

    fun deleteTrackers(id: String, urls: List<String>) =
        engine.deleteTrackers(id, urls)

    /** Observe live tracker list for a torrent as a Kotlin Flow. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTrackers(id: String): Flow<List<com.bismaya.mediadl.core.model.data.TrackerInfo>> =
        infoProvider.observeTrackersInfo(id).asFlow()

    /** Observe live peer list for a torrent as a Kotlin Flow. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePeers(id: String): Flow<List<com.bismaya.mediadl.core.model.data.PeerInfo>> =
        infoProvider.observePeersInfo(id).asFlow()

    /** Observe live piece map for a torrent as a Kotlin Flow. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePieces(id: String): Flow<BooleanArray> =
        infoProvider.observePiecesInfo(id).asFlow()

    /** Observe torrent-deleted events (emits the sha1hash of the deleted torrent). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDeletedTorrentIds(): Flow<String> =
        infoProvider.observeTorrentsDeleted().asFlow()

    // ── Metadata ───────────────────────────────────────────────────────────────

    fun getTorrentMetaInfo(id: String) = engine.getTorrentMetaInfo(id)

    fun getPieces(id: String): BooleanArray? = engine.getPieces(id)

    // ── Synchronous snapshot getters ───────────────────────────────────────────

    /** Synchronous snapshot of tracker list mapped to UI model. */
    fun getTrackerList(id: String): List<TrackerInfo> =
        engine.makeTrackerInfoList(id).map { jt ->
            TrackerInfo(
                url     = jt.url ?: "",
                message = jt.message ?: "",
                tier    = jt.tier,
                status  = jt.status
            )
        }

    /** Synchronous snapshot of peer list mapped to UI model. */
    fun getPeerList(id: String): List<PeerInfo> =
        engine.makePeerInfoList(id).map { jp ->
            PeerInfo(
                ip            = jp.ip ?: "",
                client        = jp.client ?: "",
                downloadSpeed = jp.downSpeed.toLong(),
                uploadSpeed   = jp.upSpeed.toLong(),
                progress      = jp.progress / 100f
            )
        }

    /** Synchronous snapshot of advanced info (piece counts, ratios, times, etc). */
    fun getAdvancedInfoSync(id: String): com.bismaya.mediadl.core.model.data.AdvancedTorrentInfo? =
        engine.makeAdvancedInfoSync(id)

    // ── Session stats ──────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSessionStats(): Flow<com.bismaya.mediadl.core.model.data.SessionStats> =
        infoProvider.observeSessionStats().asFlow()

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Map a libretorrent [TorrentStateCode] to the legacy [TorrentState] enum
     * still used by the Compose UI layer.
     */
    fun TorrentStateCode.toUiState(): TorrentState = when (this) {
        TorrentStateCode.CHECKING             -> TorrentState.CHECKING
        TorrentStateCode.DOWNLOADING_METADATA -> TorrentState.METADATA
        TorrentStateCode.DOWNLOADING          -> TorrentState.DOWNLOADING
        TorrentStateCode.FINISHED             -> TorrentState.FINISHED
        TorrentStateCode.SEEDING              -> TorrentState.SEEDING
        TorrentStateCode.PAUSED               -> TorrentState.PAUSED
        TorrentStateCode.STOPPED              -> TorrentState.STOPPED
        TorrentStateCode.ERROR                -> TorrentState.ERROR
        else                                  -> TorrentState.UNKNOWN
    }

    /**
     * Convert a libretorrent [TorrentInfo] to the UI-facing [TorrentItem].
     */
    fun TorrentInfo.toItem(): TorrentItem = TorrentItem(
        infoHash       = torrentId,
        name           = name ?: torrentId,
        totalSize      = totalBytes,
        downloadedBytes = receivedBytes,
        totalSentBytes  = uploadedBytes,
        progress        = progress / 100f,
        progressPct     = progress,
        downloadSpeed   = downloadSpeed,
        uploadSpeed     = uploadSpeed,
        seeders         = 0,
        peers           = totalPeers,
        connectedPeers  = peers,
        state           = stateCode?.toUiState() ?: TorrentState.UNKNOWN,
        etaSec          = if (ETA >= TorrentInfo.MAX_ETA) Long.MAX_VALUE else ETA,
        error           = error
    )
}
