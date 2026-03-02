package com.bismaya.mediadl

import android.content.Context
import android.content.SharedPreferences

data class TorrentPrefs(
    val downloadSpeedLimit: Int = 0,        // bytes/sec, 0 = unlimited
    val uploadSpeedLimit: Int = 0,          // bytes/sec, 0 = unlimited
    val wifiOnly: Boolean = false,
    val sequentialByDefault: Boolean = false,
    val maxActiveDownloads: Int = 5,
    val maxActiveSeeds: Int = 3,
    val showNotifications: Boolean = true,
    val autoQueueOnLowRam: Boolean = true,
    val savePath: String = ""               // empty = default (Downloads/MediaDL/Torrents)
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
            savePath = p.getString("save_path", "") ?: ""
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
        }.apply()
    }
}
