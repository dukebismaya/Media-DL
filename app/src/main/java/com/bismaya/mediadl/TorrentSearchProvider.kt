package com.bismaya.mediadl

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TorrentSearchResult(
    val name: String,
    val infoHash: String,
    val size: Long,
    val seeders: Int,
    val leechers: Int,
    val category: String,
    val addedTimestamp: Long
) {
    val magnetUri: String
        get() {
            val trackers = COMMON_TRACKERS.joinToString("") {
                "&tr=${URLEncoder.encode(it, "UTF-8")}"
            }
            val dn = URLEncoder.encode(name, "UTF-8")
            return "magnet:?xt=urn:btih:$infoHash&dn=$dn$trackers"
        }

    companion object {
        val COMMON_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:80/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce"
        )
    }
}

object TorrentSearchProvider {
    private const val TAG = "TorrentSearch"

    suspend fun search(query: String): Result<List<TorrentSearchResult>> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = URL("https://apibay.org/q.php?q=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "MediaDL/1.0")

            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("Search failed (HTTP ${conn.responseCode})"))
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val arr = JSONArray(body)
            val results = mutableListOf<TorrentSearchResult>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "")
                val hash = obj.optString("info_hash", "")
                if (name == "No results returned" || hash.isBlank() || hash == "0") continue
                // Only accept valid SHA-1 (40 hex) or SHA-256 (64 hex) info hashes
                if (!hash.matches(Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?")))
                    continue

                results.add(
                    TorrentSearchResult(
                        name = name,
                        infoHash = hash,
                        size = obj.optLong("size", 0),
                        seeders = obj.optInt("seeders", 0),
                        leechers = obj.optInt("leechers", 0),
                        category = mapCategory(obj.optString("category", "0")),
                        addedTimestamp = obj.optLong("added", 0)
                    )
                )
            }

            Result.success(results.sortedByDescending { it.seeders })
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            Result.failure(e)
        }
    }

    private fun mapCategory(code: String): String = when {
        code.startsWith("1") -> "Audio"
        code.startsWith("2") -> "Video"
        code.startsWith("3") -> "Apps"
        code.startsWith("4") -> "Games"
        code.startsWith("6") -> "Other"
        else -> "Other"
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
