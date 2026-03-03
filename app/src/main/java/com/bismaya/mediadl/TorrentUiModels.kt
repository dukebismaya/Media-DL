package com.bismaya.mediadl

// ── Torrent state codes — used by the Compose UI layer ───────────────────────────
enum class TorrentState {
    QUEUED,
    CHECKING,
    METADATA,          // DOWNLOADING_METADATA
    DOWNLOADING,
    FINISHED,
    SEEDING,
    PAUSED,
    MOVING,            // storage being relocated
    STOPPED,
    ERROR,
    UNKNOWN
}

// ── Tracker status constants ───────────────────────────────────────────────────────
object TrackerStatus {
    const val UNKNOWN       = -1
    const val WORKING       = 0
    const val UPDATING      = 1
    const val NOT_CONTACTED = 2
    const val NOT_WORKING   = 3
}

// ── Tracker display model ─────────────────────────────────────────────────────────
data class TrackerInfo(
    val url:     String,
    val message: String,
    val tier:    Int,
    val status:  Int
)

// ── Peer display model ─────────────────────────────────────────────────────────────
data class PeerInfo(
    val ip:            String,
    val client:        String,
    val downloadSpeed: Long,   // bytes/sec
    val uploadSpeed:   Long,   // bytes/sec
    val progress:      Float,  // 0.0 – 1.0
    val flags:         Long = 0L
)

// ── Per-file display model ─────────────────────────────────────────────────────────
data class TorrentFileInfo(
    val index:    Int,
    val name:     String,
    val size:     Long,
    val progress: Float,
    val priority: Int = 4   // 0=skip, 1=low, 4=normal, 7=top
)

// ── Core torrent item (exposed to the Compose UI) ─────────────────────────────────
data class TorrentItem(
    val infoHash:               String,
    val name:                   String,
    val totalSize:              Long,
    val downloadedBytes:        Long,
    val totalSentBytes:         Long = 0L,
    val progress:               Float,
    val progressPct:            Int = 0,
    val downloadSpeed:          Long,
    val uploadSpeed:            Long,
    val seeders:                Int,
    val connectedSeeds:         Int = 0,
    val peers:                  Int,
    val connectedPeers:         Int = 0,
    val totalSeeds:             Int = 0,
    val totalPeers:             Int = 0,
    val connectedLeechers:      Int = 0,
    val totalLeechers:          Int = 0,
    val state:                  TorrentState,
    val files:                  List<TorrentFileInfo> = emptyList(),
    val savePath:               String = "",
    val error:                  String? = null,
    val isSequential:           Boolean = false,
    val isAutoManaged:          Boolean = false,
    val isManuallyPaused:       Boolean = false,
    val firstLastPiecePriority: Boolean = false,
    val hasMissingFiles:        Boolean = false,
    val magnetUri:              String = "",
    val etaSec:                 Long = Long.MAX_VALUE,
    val shareRatio:             Double = 0.0,
    val activeTimeSec:          Long = 0L,
    val seedingTimeSec:         Long = 0L,
    val numDownloadedPieces:    Int = 0,
    val pieces:                 BooleanArray = BooleanArray(0),
    val totalWanted:            Long = 0L,
    val downloadSpeedLimit:     Int = 0,
    val uploadSpeedLimit:       Int = 0,
    val maxConnections:         Int = -1,
    val maxUploads:             Int = -1
)

// ── Engine-level event listener (Kotlin functional interface) ─────────────────────
interface TorrentEngineCallback {
    fun onTorrentAdded(infoHash: String)                                              = Unit
    fun onTorrentFinished(infoHash: String)                                           = Unit
    fun onTorrentRemoved(infoHash: String)                                            = Unit
    fun onTorrentError(infoHash: String, error: Exception)                            = Unit
    fun onSessionStarted()                                                            = Unit
    fun onSessionStopped()                                                            = Unit
    fun onSessionError(message: String)                                               = Unit
}
