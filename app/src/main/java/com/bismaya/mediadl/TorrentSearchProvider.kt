package com.bismaya.mediadl

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
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
        // 24 trackers — more = more peer discovery = faster downloads
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
            "udp://tracker.bitsearch.to:1337/announce",
            "udp://tracker.leech.ie:1337/announce",
            "udp://tracker.ololosh.space:6969/announce",
            "https://tracker1.520.jp:443/announce",
            "https://tracker.tamersunion.org:443/announce",
            "https://tracker.gbitt.info:443/announce"
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Search providers
// ══════════════════════════════════════════════════════════════════════════════
//
//  Provider  │ Focus                │ API style
//  ──────────┼──────────────────────┼──────────────
//  YTS       │ Movies (HD)          │ JSON REST
//  apibay    │ General (TPB)        │ JSON REST
//  Knaben    │ General meta-search  │ JSON REST
//  TorrCSV   │ General crowdsourced │ JSON REST
//  Solidtorr │ General meta-search  │ JSON REST
//  Nyaa      │ Anime / J-content    │ RSS XML
//
//  All six run IN PARALLEL — total time = slowest single provider.
// ══════════════════════════════════════════════════════════════════════════════

object TorrentSearchProvider {
    private const val TAG = "TorrentSearch"
    private val HASH_RE = Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?")

    // ── Public entry point ─────────────────────────────────────────────────────

    suspend fun search(query: String): Result<List<TorrentSearchResult>> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val jobs = listOf(
                    async { searchYts(query) },
                    async { searchApibay(query) },
                    async { searchKnaben(query) },
                    async { searchTorrentCsv(query) },
                    async { searchSolidtorrents(query) },
                    async { searchNyaa(query) }
                )
                val merged = jobs.awaitAll()
                    .flatMap { it.getOrNull().orEmpty() }
                    .distinctBy { it.infoHash }
                    .sortedByDescending { it.seeders }
                if (merged.isNotEmpty()) Result.success(merged)
                else Result.failure(Exception("No results found"))
            }
        }

    // ══════════════════════════════════════════════════════════════════
    // Provider 1 — YTS  (movies, very reliable, HD quality variants)
    // ══════════════════════════════════════════════════════════════════

    private fun searchYts(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGet(
            "https://yts.mx/api/v2/list_movies.json?query_term=${enc(query)}&limit=50&sort_by=seeds"
        ) ?: return Result.failure(Exception("YTS: no response"))
        val root = JSONObject(body)
        if (root.optString("status") != "ok") return Result.success(emptyList())
        val movies = root.optJSONObject("data")?.optJSONArray("movies")
            ?: return Result.success(emptyList())
        buildList {
            for (i in 0 until movies.length()) {
                val movie = movies.getJSONObject(i)
                val title = movie.optString("title_long", movie.optString("title", ""))
                val torrents = movie.optJSONArray("torrents") ?: continue
                for (j in 0 until torrents.length()) {
                    val t = torrents.getJSONObject(j)
                    val hash = t.optString("hash", "")
                    if (hash.isBlank()) continue
                    val quality = t.optString("quality", "")
                    val codec = t.optString("type", "")
                    add(TorrentSearchResult(
                        name = buildString {
                            append(title)
                            if (quality.isNotBlank()) append(" [$quality${if (codec.isNotBlank()) " $codec" else ""}]")
                        },
                        infoHash = hash.lowercase(),
                        size = t.optLong("size_bytes", 0),
                        seeders = t.optInt("seeds", 0),
                        leechers = t.optInt("peers", 0),
                        category = "Video",
                        addedTimestamp = t.optLong("date_uploaded_unix", 0)
                    ))
                }
            }
        }
    }.also { if (it.isFailure) Log.e(TAG, "YTS failed", it.exceptionOrNull()) }

    // ══════════════════════════════════════════════════════════════════
    // Provider 2 — apibay.org  (The Pirate Bay index)
    // ══════════════════════════════════════════════════════════════════

    private fun searchApibay(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGet("https://apibay.org/q.php?q=${enc(query)}&cat=0")
            ?: return Result.failure(Exception("apibay: no response"))
        if (!body.trimStart().startsWith("[")) {
            Log.w(TAG, "apibay non-JSON: ${body.take(60)}")
            return Result.failure(Exception("apibay: ${body.take(60)}"))
        }
        val arr = JSONArray(body)
        buildList { for (i in 0 until arr.length()) parseApibayObject(arr.getJSONObject(i))?.let { add(it) } }
    }.also { if (it.isFailure) Log.e(TAG, "apibay failed", it.exceptionOrNull()) }

    private fun parseApibayObject(obj: JSONObject): TorrentSearchResult? {
        val name = obj.optString("name", "")
        val hash = obj.optString("info_hash", "")
        if (name.isBlank() || name == "No results returned") return null
        if (hash.isBlank() || hash == "0" || !hash.matches(HASH_RE)) return null
        return TorrentSearchResult(
            name = name, infoHash = hash.lowercase(),
            size = obj.optLong("size", 0),
            seeders = obj.optInt("seeders", 0), leechers = obj.optInt("leechers", 0),
            category = apibayCategory(obj.optString("category", "0")),
            addedTimestamp = obj.optLong("added", 0)
        )
    }

    private fun apibayCategory(code: String) = when {
        code.startsWith("1") -> "Audio"; code.startsWith("2") -> "Video"
        code.startsWith("3") -> "Apps";  code.startsWith("4") -> "Games"
        else -> "Other"
    }

    // ══════════════════════════════════════════════════════════════════
    // Provider 3 — Knaben  (European meta-search, JSON)
    // ══════════════════════════════════════════════════════════════════

    private fun searchKnaben(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGet(
            "https://knaben.eu/api/v1/?search=${enc(query)}&orderBy=seeders&orderDirection=desc&size=50&hideFake=true"
        ) ?: return Result.failure(Exception("Knaben: no response"))
        if (!body.trimStart().startsWith("{")) return Result.failure(Exception("Knaben: non-JSON"))
        val hits = JSONObject(body).optJSONArray("hits") ?: return Result.success(emptyList())
        buildList { for (i in 0 until hits.length()) parseKnabenObject(hits.getJSONObject(i))?.let { add(it) } }
    }.also { if (it.isFailure) Log.e(TAG, "Knaben failed", it.exceptionOrNull()) }

    private fun parseKnabenObject(obj: JSONObject): TorrentSearchResult? {
        val name = obj.optString("title", obj.optString("name", ""))
        val hash = obj.optString("infoHash", obj.optString("info_hash", ""))
        if (name.isBlank() || hash.isBlank() || !hash.matches(HASH_RE)) return null
        val cat = obj.optString("category", "")
        return TorrentSearchResult(
            name = name, infoHash = hash.lowercase(),
            size = obj.optLong("bytes", obj.optLong("size", 0)),
            seeders = obj.optInt("seeders", 0), leechers = obj.optInt("leechers", 0),
            category = inferCategory(cat),
            addedTimestamp = obj.optLong("uploadDate", 0)
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Provider 4 — Torrent-CSV  (open crowdsourced DB, millions of entries)
    // https://github.com/torrent-paradise/torrents-csv-site
    // ══════════════════════════════════════════════════════════════════

    private fun searchTorrentCsv(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGet(
            "https://torrents-csv.com/service/search?q=${enc(query)}&size=100&page=1"
        ) ?: return Result.failure(Exception("TorrentCSV: no response"))
        if (!body.trimStart().startsWith("{")) return Result.failure(Exception("TorrentCSV: non-JSON"))
        val torrents = JSONObject(body).optJSONArray("torrents") ?: return Result.success(emptyList())
        buildList {
            for (i in 0 until torrents.length()) {
                val obj = torrents.getJSONObject(i)
                val hash = obj.optString("infohash", "")
                val name = obj.optString("name", "")
                if (hash.isBlank() || name.isBlank() || !hash.matches(HASH_RE)) continue
                add(TorrentSearchResult(
                    name = name, infoHash = hash.lowercase(),
                    size = obj.optLong("size_bytes", 0),
                    seeders = obj.optInt("seeders", 0), leechers = obj.optInt("leechers", 0),
                    category = inferCategory(name),
                    addedTimestamp = 0L
                ))
            }
        }
    }.also { if (it.isFailure) Log.e(TAG, "TorrentCSV failed", it.exceptionOrNull()) }

    // ══════════════════════════════════════════════════════════════════
    // Provider 5 — Solidtorrents  (meta-search aggregator)
    // ══════════════════════════════════════════════════════════════════

    private fun searchSolidtorrents(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGet(
            "https://solidtorrents.to/api/v1/search?q=${enc(query)}&sort=seeders&limit=50"
        ) ?: return Result.failure(Exception("Solidtorrents: no response"))
        if (!body.trimStart().startsWith("{")) return Result.failure(Exception("Solidtorrents: non-JSON"))
        val results = JSONObject(body).optJSONArray("results") ?: return Result.success(emptyList())
        buildList {
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val hash = obj.optString("infohash", obj.optString("info_hash", ""))
                val name = obj.optString("title", obj.optString("name", ""))
                if (hash.isBlank() || name.isBlank() || !hash.matches(HASH_RE)) continue
                val swarm = obj.optJSONObject("swarm")
                add(TorrentSearchResult(
                    name = name, infoHash = hash.lowercase(),
                    size = obj.optLong("size", 0),
                    seeders = swarm?.optInt("seeders", 0) ?: obj.optInt("seeders", 0),
                    leechers = swarm?.optInt("leechers", 0) ?: obj.optInt("leechers", 0),
                    category = inferCategory(obj.optString("category", name)),
                    addedTimestamp = 0L
                ))
            }
        }
    }.also { if (it.isFailure) Log.e(TAG, "Solidtorrents failed", it.exceptionOrNull()) }

    // ══════════════════════════════════════════════════════════════════
    // Provider 6 — Nyaa.si  (anime, manga, J-music, J-drama — RSS XML)
    // ══════════════════════════════════════════════════════════════════

    private fun searchNyaa(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGet(
            "https://nyaa.si/?page=rss&q=${enc(query)}&c=0_0&f=0"
        ) ?: return Result.failure(Exception("Nyaa: no response"))

        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val xpp = factory.newPullParser().apply { setInput(StringReader(body)) }

        val results = mutableListOf<TorrentSearchResult>()
        var inItem = false
        var title = ""; var hash = ""; var size = 0L
        var seeders = 0; var leechers = 0; var category = "Anime"
        var currentTag = ""

        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = xpp.name ?: ""
                    if (currentTag == "item") {
                        inItem = true
                        title = ""; hash = ""; size = 0L; seeders = 0; leechers = 0; category = "Anime"
                    }
                }
                XmlPullParser.TEXT -> if (inItem) when (currentTag) {
                    "title"         -> title    = xpp.text.trim()
                    "nyaa:infoHash" -> hash     = xpp.text.trim().lowercase()
                    "nyaa:size"     -> size     = parseNyaaSize(xpp.text.trim())
                    "nyaa:seeders"  -> seeders  = xpp.text.trim().toIntOrNull() ?: 0
                    "nyaa:leechers" -> leechers = xpp.text.trim().toIntOrNull() ?: 0
                    "nyaa:category" -> category = nyaaCategory(xpp.text.trim())
                }
                XmlPullParser.END_TAG -> {
                    if (xpp.name == "item" && inItem) {
                        if (hash.isNotBlank() && hash.matches(HASH_RE) && title.isNotBlank()) {
                            results += TorrentSearchResult(
                                name = title, infoHash = hash,
                                size = size, seeders = seeders, leechers = leechers,
                                category = category, addedTimestamp = 0L
                            )
                        }
                        inItem = false
                    }
                    currentTag = ""
                }
            }
            event = xpp.next()
        }
        results
    }.also { if (it.isFailure) Log.e(TAG, "Nyaa failed", it.exceptionOrNull()) }

    private fun parseNyaaSize(s: String): Long {
        val parts = s.trim().split(" ")
        val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0L
        return when (parts.getOrNull(1)?.uppercase()) {
            "KIB" -> (num * 1024).toLong()
            "MIB" -> (num * 1024 * 1024).toLong()
            "GIB" -> (num * 1024 * 1024 * 1024).toLong()
            "TIB" -> (num * 1024 * 1024 * 1024 * 1024).toLong()
            else  -> num.toLong()
        }
    }

    private fun nyaaCategory(cat: String) = when {
        cat.contains("music", ignoreCase = true) || cat.contains("audio", ignoreCase = true) -> "Audio"
        cat.contains("manga", ignoreCase = true) || cat.contains("book",  ignoreCase = true) -> "Other"
        cat.contains("software", ignoreCase = true) -> "Apps"
        else -> "Anime"
    }

    // ══════════════════════════════════════════════════════════════════
    // Shared helpers
    // ══════════════════════════════════════════════════════════════════

    private fun inferCategory(text: String) = when {
        text.contains("audio",    ignoreCase = true) ||
        text.contains("music",    ignoreCase = true) -> "Audio"
        text.contains("video",    ignoreCase = true) ||
        text.contains("movie",    ignoreCase = true) ||
        text.contains("film",     ignoreCase = true) ||
        text.contains("series",   ignoreCase = true) ||
        text.contains("season",   ignoreCase = true) -> "Video"
        text.contains("app",      ignoreCase = true) ||
        text.contains("software", ignoreCase = true) -> "Apps"
        text.contains("game",     ignoreCase = true) -> "Games"
        else -> "Other"
    }

    private fun enc(q: String) = URLEncoder.encode(q.trim(), "UTF-8")

    private fun httpGet(urlStr: String): String? = try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.setRequestProperty("User-Agent", "MediaDL/1.0")
        conn.setRequestProperty("Accept",     "*/*")
        if (conn.responseCode != 200) {
            Log.w(TAG, "HTTP ${conn.responseCode} → $urlStr")
            conn.disconnect(); null
        } else {
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }
    } catch (e: Exception) {
        Log.e(TAG, "httpGet: $urlStr — ${e.message}")
        null
    }

    // ── Formatting ──────────────────────────────────────────────────────────────

    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024             -> "$bytes B"
        bytes < 1024 * 1024      -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}

