package com.bismaya.mediadl

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
        // Comprehensive tracker list — more trackers → more peers → faster downloads
        val COMMON_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:80/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://tracker.altrosky.cc:6969/announce",
            "udp://tracker-udp.gbitt.info:80/announce",
            "udp://retracker01-msk-virt.corbina.net:80/announce",
            "udp://p4p.arenabg.com:1337/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
            "udp://new-line.net:6969/announce",
            "udp://movie-blog.org:6969/announce",
            "udp://bt2.archive.org:6969/announce",
            "udp://bt1.archive.org:6969/announce",
            "https://tracker1.520.jp:443/announce",
            "https://tracker.tamersunion.org:443/announce",
            "https://tracker.gbitt.info:443/announce"
        )
    }
}

object TorrentSearchProvider {
    private const val TAG = "TorrentSearch"

    // ── Public entry point: try providers in order ─────────────────────────────

    suspend fun search(query: String): Result<List<TorrentSearchResult>> = withContext(Dispatchers.IO) {
        // 1. Primary — The Pirate Bay API
        val primary = searchApibay(query)
        if (primary.isSuccess) {
            val list = primary.getOrNull().orEmpty()
            if (list.isNotEmpty()) return@withContext Result.success(list)
        }

        // 2. Fallback — Knaben (meta-search, same result format we already know)
        val fallback = searchKnaben(query)
        if (fallback.isSuccess) {
            val list = fallback.getOrNull().orEmpty()
            if (list.isNotEmpty()) return@withContext Result.success(list)
        }

        // 3. Both empty → no results
        if ((primary.getOrNull()?.isEmpty() == true) || (fallback.getOrNull()?.isEmpty() == true)) {
            return@withContext Result.success(emptyList())
        }

        // Both failed → return whichever error is most descriptive
        Result.failure(primary.exceptionOrNull() ?: fallback.exceptionOrNull()
            ?: Exception("All search providers failed"))
    }

    // ── Provider A: apibay.org (The Pirate Bay) ───────────────────────────────

    private fun searchApibay(query: String): Result<List<TorrentSearchResult>> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val body = httpGet("https://apibay.org/q.php?q=$encoded&cat=0")
                ?: return Result.failure(Exception("No response from search server"))

            // apibay sometimes returns plain-text errors ("Database", "Error", etc.)
            if (!body.trimStart().startsWith("[")) {
                Log.w(TAG, "apibay returned non-JSON: ${body.take(80)}")
                return Result.failure(Exception("Search service temporarily unavailable"))
            }

            val arr = JSONArray(body)
            val results = mutableListOf<TorrentSearchResult>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                parseApibayObject(obj)?.let { results += it }
            }
            Result.success(results.sortedByDescending { it.seeders })
        } catch (e: Exception) {
            Log.e(TAG, "apibay search failed", e)
            Result.failure(e)
        }
    }

    private fun parseApibayObject(obj: JSONObject): TorrentSearchResult? {
        val name = obj.optString("name", "")
        val hash = obj.optString("info_hash", "")
        if (name.isBlank() || name == "No results returned") return null
        if (hash.isBlank() || hash == "0") return null
        if (!hash.matches(Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?"))) return null
        return TorrentSearchResult(
            name = name,
            infoHash = hash.lowercase(),
            size = obj.optLong("size", 0),
            seeders = obj.optInt("seeders", 0),
            leechers = obj.optInt("leechers", 0),
            category = mapApibayCategory(obj.optString("category", "0")),
            addedTimestamp = obj.optLong("added", 0)
        )
    }

    private fun mapApibayCategory(code: String): String = when {
        code.startsWith("1") -> "Audio"
        code.startsWith("2") -> "Video"
        code.startsWith("3") -> "Apps"
        code.startsWith("4") -> "Games"
        code.startsWith("6") -> "Other"
        else -> "Other"
    }

    // ── Provider B: Knaben (public meta-search, JSON API) ─────────────────────

    private fun searchKnaben(query: String): Result<List<TorrentSearchResult>> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            // Knaben public API — no key required
            val body = httpGet(
                "https://knaben.eu/api/v1/?search=$encoded&orderBy=seeders&orderDirection=desc&size=50&hideFake=true"
            ) ?: return Result.failure(Exception("No response from fallback search"))

            if (!body.trimStart().startsWith("{")) {
                Log.w(TAG, "knaben returned non-JSON: ${body.take(80)}")
                return Result.failure(Exception("Fallback search unavailable"))
            }

            val root = JSONObject(body)
            val hits = root.optJSONArray("hits") ?: return Result.success(emptyList())

            val results = mutableListOf<TorrentSearchResult>()
            for (i in 0 until hits.length()) {
                val obj = hits.getJSONObject(i)
                parseKnabenObject(obj)?.let { results += it }
            }
            Result.success(results) // already sorted by seeders from API
        } catch (e: Exception) {
            Log.e(TAG, "knaben search failed", e)
            Result.failure(e)
        }
    }

    private fun parseKnabenObject(obj: JSONObject): TorrentSearchResult? {
        val name = obj.optString("title", obj.optString("name", ""))
        val hash = obj.optString("infoHash", obj.optString("info_hash", ""))
        if (name.isBlank() || hash.isBlank()) return null
        if (!hash.matches(Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?"))) return null
        val category = obj.optString("category", "")
        return TorrentSearchResult(
            name = name,
            infoHash = hash.lowercase(),
            size = obj.optLong("bytes", obj.optLong("size", 0)),
            seeders = obj.optInt("seeders", 0),
            leechers = obj.optInt("leechers", 0),
            category = when {
                category.contains("audio", ignoreCase = true) ||
                category.contains("music", ignoreCase = true) -> "Audio"
                category.contains("video", ignoreCase = true) ||
                category.contains("movie", ignoreCase = true) -> "Video"
                category.contains("app", ignoreCase = true)   -> "Apps"
                category.contains("game", ignoreCase = true)  -> "Games"
                else -> "Other"
            },
            addedTimestamp = obj.optLong("uploadDate", 0)
        )
    }

    // ── HTTP helper ────────────────────────────────────────────────────────────

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", "MediaDL/1.0")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            body
        } catch (e: Exception) {
            Log.e(TAG, "httpGet failed: $urlStr", e)
            null
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
