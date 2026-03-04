package com.bismaya.mediadl

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// ── Snapshot of a single torrent search result (for history persistence) ────────
data class TorrentSearchResultSnapshot(
    val name: String,
    val infoHash: String,
    val sizeBytes: Long,
    val seeders: Int,
    val category: String
)

// ── A saved torrent search session ────────────────────────────────────────────────
data class TorrentSearchRecord(
    val query: String,
    val timestamp: Long,
    val resultCount: Int,
    val topResults: List<TorrentSearchResultSnapshot>
)

data class TorrentPrefs(
    val downloadSpeedLimit: Int = 0,        // bytes/sec, 0 = unlimited
    val uploadSpeedLimit: Int = 0,          // bytes/sec, 0 = unlimited
    val wifiOnly: Boolean = false,
    val sequentialByDefault: Boolean = false,
    val maxActiveDownloads: Int = 5,
    val maxActiveSeeds: Int = 3,
    val showNotifications: Boolean = true,
    val autoQueueOnLowRam: Boolean = true,
    val savePath: String = "",              // empty = default (Downloads/MediaDL/Torrents)
    val stopSeedingOnComplete: Boolean = false, // auto-pause when download finishes
    val maxSeedRatio: Float = 0f,           // 0 = seed forever; e.g. 1.0 = 1:1 ratio
    val enableIpFiltering: Boolean = false  // IP blocklist filter
)

object TorrentSettingsManager {
    private const val PREFS_KEY = "torrent_settings"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)

    fun load(context: Context): TorrentPrefs {
        val p = prefs(context)
        return TorrentPrefs(
            downloadSpeedLimit = p.getInt("dl_limit", 0),
            uploadSpeedLimit = p.getInt("ul_limit", 0),
            wifiOnly = p.getBoolean("wifi_only", false),
            sequentialByDefault = p.getBoolean("sequential", false),
            maxActiveDownloads = p.getInt("max_active_dl", 5),
            maxActiveSeeds = p.getInt("max_active_seeds", 3),
            showNotifications = p.getBoolean("notifications", true),
            autoQueueOnLowRam = p.getBoolean("auto_queue_ram", true),
            savePath = p.getString("save_path", "") ?: "",
            stopSeedingOnComplete = p.getBoolean("stop_seeding_complete", false),
            maxSeedRatio = p.getFloat("max_seed_ratio", 0f),
            enableIpFiltering = p.getBoolean("enable_ip_filter", false)
        )
    }

    fun save(context: Context, settings: TorrentPrefs) {
        prefs(context).edit().apply {
            putInt("dl_limit", settings.downloadSpeedLimit)
            putInt("ul_limit", settings.uploadSpeedLimit)
            putBoolean("wifi_only", settings.wifiOnly)
            putBoolean("sequential", settings.sequentialByDefault)
            putInt("max_active_dl", settings.maxActiveDownloads)
            putInt("max_active_seeds", settings.maxActiveSeeds)
            putBoolean("notifications", settings.showNotifications)
            putBoolean("auto_queue_ram", settings.autoQueueOnLowRam)
            putString("save_path", settings.savePath)
            putBoolean("stop_seeding_complete", settings.stopSeedingOnComplete)
            putFloat("max_seed_ratio", settings.maxSeedRatio)
            putBoolean("enable_ip_filter", settings.enableIpFiltering)
        }.apply()
    }

    /** Torrent search records — persisted as JSON, max 25 entries. */
    fun loadSearchRecords(context: Context): List<TorrentSearchRecord> {
        val json = prefs(context).getString("torrent_search_records_v2", null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val topArr = o.getJSONArray("top")
                TorrentSearchRecord(
                    query       = o.getString("q"),
                    timestamp   = o.getLong("ts"),
                    resultCount = o.getInt("count"),
                    topResults  = (0 until topArr.length()).map { j ->
                        val r = topArr.getJSONObject(j)
                        TorrentSearchResultSnapshot(
                            name     = r.getString("n"),
                            infoHash = r.getString("h"),
                            sizeBytes = r.getLong("s"),
                            seeders  = r.getInt("sd"),
                            category = r.optString("cat", "")
                        )
                    }
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveSearchRecords(context: Context, records: List<TorrentSearchRecord>) {
        val arr = JSONArray()
        records.take(25).forEach { rec ->
            arr.put(JSONObject().apply {
                put("q",     rec.query)
                put("ts",    rec.timestamp)
                put("count", rec.resultCount)
                val top = JSONArray()
                rec.topResults.take(5).forEach { r ->
                    top.put(JSONObject().apply {
                        put("n",   r.name)
                        put("h",   r.infoHash)
                        put("s",   r.sizeBytes)
                        put("sd",  r.seeders)
                        put("cat", r.category)
                    })
                }
                put("top", top)
            })
        }
        prefs(context).edit()
            .putString("torrent_search_records_v2", arr.toString())
            .apply()
    }
}