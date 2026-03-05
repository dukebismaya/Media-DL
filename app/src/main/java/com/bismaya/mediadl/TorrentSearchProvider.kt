package com.bismaya.mediadl

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TorrentSearchResult(
    val name: String,
    val infoHash: String,
    val size: Long,
    val seeders: Int,
    val leechers: Int,
    val category: String,
    val addedTimestamp: Long,
    val source: String = ""
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

    /** Total number of search providers (used to track streaming progress). */
    const val PROVIDER_COUNT = 10

    /** Browser-like User-Agent — required for scraping sites like 1337x. */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    /** Score used for result ranking: seeders dominate, leechers add minor weight. */
    private fun score(r: TorrentSearchResult): Double = r.seeders + 0.15 * r.leechers

    /** Per-provider outcome reported via [searchStreaming]. */
    data class ProviderStatus(
        val name: String,
        val count: Int,      // results contributed (unique, after dedup)
        val failed: Boolean  // true = threw exception or got no HTTP response
    )

    // ── Public entry point (streaming) ─────────────────────────────────────────
    //
    // [onPartialResult] is called on Main each time a provider finishes with ≥1 new result.
    // [onProgress]      is called on Main with (doneCount, PROVIDER_COUNT).
    // [onProviderDone]  is called on Main for EVERY provider with its status.

    suspend fun searchStreaming(
        query: String,
        onPartialResult: (List<TorrentSearchResult>) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onProviderDone: (ProviderStatus) -> Unit = {}
    ) {
        val mutex = Mutex()
        val seen  = mutableSetOf<String>()
        val accum = mutableListOf<TorrentSearchResult>()
        var done  = 0

        withContext(Dispatchers.IO) {
            coroutineScope {
                val providers: List<Pair<String, suspend () -> Result<List<TorrentSearchResult>>>> = listOf(
                    "YTS"     to { searchYts(query) },
                    "TPB"     to { searchApibay(query) },
                    "Knaben"  to { searchKnaben(query) },
                    "TorrCSV" to { searchTorrentCsv(query) },
                    "Solid"   to { searchSolidtorrents(query) },
                    "Nyaa"    to { searchNyaa(query) },
                    "EZTV"   to { searchEztv(query) },
                    "TorDL"   to { searchTorrentDownloads(query) },
                    "LimeTrr" to { searchLimetorrents(query) },
                    "1337x"   to { searchLeet337x(query) }
                )
                providers.forEach { (name, provider) ->
                    launch {
                        val result  = provider()
                        val raw     = result.getOrNull().orEmpty()
                        val failed  = result.isFailure
                        mutex.withLock {
                            done++
                            val newOnes = raw.filter { seen.add(it.infoHash) }
                            accum.addAll(newOnes)
                            val snapshot = accum.sortedByDescending { score(it) }
                            val d = done
                            val status = ProviderStatus(name, newOnes.size, failed)
                            withContext(Dispatchers.Main) {
                                onProgress(d, PROVIDER_COUNT)
                                onProviderDone(status)
                                if (snapshot.isNotEmpty()) onPartialResult(snapshot)
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Provider 1 — YTS  (movies, very reliable, HD quality variants)
    // ══════════════════════════════════════════════════════════════════

    private fun searchYts(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGet(
            "https://movies-api.accel.li/api/v2/list_movies.json?query_term=${enc(query)}&limit=50&sort_by=seeds&order_by=desc&minimum_rating=0"
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
                        addedTimestamp = t.optLong("date_uploaded_unix", 0),
                        source = "YTS"
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
            addedTimestamp = obj.optLong("added", 0),
            source = "TPB"
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
        val body = httpGetWithFallback(listOf(
            "https://knaben.eu/api/v1/?search=${enc(query)}&orderBy=seeders&orderDirection=desc&size=50&hideFake=true",
            "https://knaben.org/api/v1/?search=${enc(query)}&orderBy=seeders&orderDirection=desc&size=50&hideFake=true"
        )) ?: return Result.failure(Exception("Knaben: no response"))
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
            addedTimestamp = obj.optLong("uploadDate", 0),
            source = "Knaben"
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
                    addedTimestamp = 0L,
                    source = "TorrCSV"
                ))
            }
        }
    }.also { if (it.isFailure) Log.e(TAG, "TorrentCSV failed", it.exceptionOrNull()) }

    // ══════════════════════════════════════════════════════════════════
    // Provider 5 — Solidtorrents  (meta-search aggregator)
    // ══════════════════════════════════════════════════════════════════

    private fun searchSolidtorrents(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGetWithFallback(listOf(
            "https://solidtorrents.to/api/v1/search?q=${enc(query)}&sort=seeders&limit=50",
            "https://solidtorrents.net/api/v1/search?q=${enc(query)}&sort=seeders&limit=50"
        )) ?: return Result.failure(Exception("Solidtorrents: no response"))
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
                    addedTimestamp = 0L,
                    source = "Solid"
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
                                category = category, addedTimestamp = 0L,
                                source = "Nyaa"
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
    // Provider 7 — EZTV  (TV shows, JSON REST API)
    // ══════════════════════════════════════════════════════════════════

    private fun searchEztv(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGetWithFallback(listOf(
            "https://eztvx.to/api/get-torrents?limit=50&page=1&search=${enc(query)}",
            "https://eztv.re/api/get-torrents?limit=50&page=1&search=${enc(query)}",
            "https://eztv.wf/api/get-torrents?limit=50&page=1&search=${enc(query)}"
        )) ?: return Result.failure(Exception("EZTV: no response"))
        if (!body.trimStart().startsWith("{")) return Result.failure(Exception("EZTV: non-JSON"))
        val torrents = JSONObject(body).optJSONArray("torrents") ?: return Result.success(emptyList())
        // EZTV returns the latest torrents (ignoring the query) when there are no matches,
        // so filter client-side to keep only results that actually match the search terms.
        val queryWords = query.lowercase().split("\\s+".toRegex()).filter { it.length >= 2 }
        buildList {
            for (i in 0 until torrents.length()) {
                val obj = torrents.getJSONObject(i)
                val hash = obj.optString("hash", "")
                val name = obj.optString("title", obj.optString("filename", ""))
                if (hash.isBlank() || name.isBlank() || !hash.matches(HASH_RE)) continue
                val nameLc = name.lowercase()
                if (queryWords.none { nameLc.contains(it) }) continue
                add(TorrentSearchResult(
                    name = name, infoHash = hash.lowercase(),
                    size = obj.optLong("size_bytes", 0),
                    seeders = obj.optInt("seeds", 0),
                    leechers = obj.optInt("peers", 0),
                    category = "Video",
                    addedTimestamp = obj.optLong("date_released_unix", 0),
                    source = "EZTV"
                ))
            }
        }
    }.also { if (it.isFailure) Log.e(TAG, "EZTV failed", it.exceptionOrNull()) }

    // ══════════════════════════════════════════════════════════════════
    // Provider 8 — TorrentDownloads  (general, RSS XML)
    // ══════════════════════════════════════════════════════════════════

    private fun searchTorrentDownloads(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGetWithFallback(listOf(
            "https://www.torrentdownloads.pro/rss.xml?type=search&search=${enc(query)}",
            "https://www.torrentdownloads.me/rss.xml?type=search&search=${enc(query)}"
        )) ?: return Result.failure(Exception("TorrentDL: no response"))

        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val xpp = factory.newPullParser().apply { setInput(StringReader(body)) }

        val results = mutableListOf<TorrentSearchResult>()
        var inItem = false
        var title = ""; var hash = ""; var size = 0L; var seeds = 0; var leechers = 0
        var currentTag = ""

        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = xpp.name ?: ""
                    if (currentTag == "item") {
                        inItem = true; title = ""; hash = ""; size = 0L; seeds = 0; leechers = 0
                    }
                }
                XmlPullParser.TEXT -> if (inItem) when (currentTag) {
                    "title"     -> title    = xpp.text.trim()
                    "info_hash" -> hash     = xpp.text.trim().lowercase()
                    "size"      -> size     = xpp.text.trim().toLongOrNull() ?: 0L
                    "seeders"   -> seeds    = xpp.text.trim().toIntOrNull() ?: 0
                    "leechers"  -> leechers = xpp.text.trim().toIntOrNull() ?: 0
                }
                XmlPullParser.END_TAG -> {
                    if (xpp.name == "item" && inItem && hash.isNotBlank() && hash.matches(HASH_RE) && title.isNotBlank()) {
                        results += TorrentSearchResult(
                            name = title, infoHash = hash,
                            size = size, seeders = seeds, leechers = leechers,
                            category = inferCategory(title), addedTimestamp = 0L, source = "TorDL"
                        )
                    }
                    if (xpp.name == "item") inItem = false
                    currentTag = ""
                }
            }
            event = xpp.next()
        }
        results
    }.also { if (it.isFailure) Log.e(TAG, "TorrentDownloads failed", it.exceptionOrNull()) }

    // ══════════════════════════════════════════════════════════════════
    // Provider 9 — Lime Torrents  (RSS, hash extracted from guid URL)
    // ══════════════════════════════════════════════════════════════════

    private fun searchLimetorrents(query: String): Result<List<TorrentSearchResult>> = runCatching {
        val body = httpGetWithFallback(listOf(
            "https://www.limetorrents.fun/searchrss/all/${enc(query)}/",
            "https://www.limetorrents.lol/searchrss/all/${enc(query)}/"
        )) ?: return Result.failure(Exception("LimeTorrents: no response"))

        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val xpp = factory.newPullParser().apply { setInput(StringReader(body)) }

        val results = mutableListOf<TorrentSearchResult>()
        var inItem = false
        var title = ""; var hash = ""; var size = 0L; var seeds = 0
        var currentTag = ""

        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = xpp.name ?: ""
                    currentTag = tag
                    if (tag == "item") { inItem = true; title = ""; hash = ""; size = 0L; seeds = 0 }
                    // <enclosure url="..." length="..."> carries size in the attribute
                    if (tag == "enclosure" && inItem) {
                        size = xpp.getAttributeValue(null, "length")?.toLongOrNull() ?: size
                        // extract hash from enclosure URL e.g. /torrent/HASH/name.torrent
                        val url = xpp.getAttributeValue(null, "url") ?: ""
                        if (hash.isBlank()) HASH_RE.find(url)?.let { hash = it.value.lowercase() }
                    }
                }
                XmlPullParser.TEXT -> if (inItem) when (currentTag) {
                    "title" -> title = xpp.text.trim()
                    // guid: https://www.limetorrents.lol/torrent/{hash}/{slug}/
                    "guid"  -> if (hash.isBlank()) HASH_RE.find(xpp.text.trim())?.let { hash = it.value.lowercase() }
                    "seeds" -> seeds = xpp.text.trim().toIntOrNull() ?: seeds
                }
                XmlPullParser.END_TAG -> {
                    if (xpp.name == "item" && inItem && hash.isNotBlank() && title.isNotBlank()) {
                        results += TorrentSearchResult(
                            name = title, infoHash = hash,
                            size = size, seeders = seeds, leechers = 0,
                            category = inferCategory(title), addedTimestamp = 0L, source = "LimeTrr"
                        )
                    }
                    if (xpp.name == "item") inItem = false
                    currentTag = ""
                }
            }
            event = xpp.next()
        }
        results
    }.also { if (it.isFailure) Log.e(TAG, "LimeTorrents failed", it.exceptionOrNull()) }

    // ══════════════════════════════════════════════════════════════════
    // Provider 10 — 1337x  (HTML scrape: search page + parallel detail pages for magnets)
    // ══════════════════════════════════════════════════════════════════

    private suspend fun searchLeet337x(query: String): Result<List<TorrentSearchResult>> = runCatching {
        // Step 1: fetch search results page (try mirror domains if primary is blocked)
        val mirrors = listOf("1337x.to", "x1337x.ws", "x1337x.eu", "1337x.st")
        var searchBody: String? = null
        var activeDomain = mirrors.first()
        for (domain in mirrors) {
            searchBody = httpGet("https://$domain/search/${enc(query)}/1/", BROWSER_UA)
            if (searchBody != null) { activeDomain = domain; break }
        }
        if (searchBody == null) return Result.failure(Exception("1337x: no response from any mirror"))

        val tbody = searchBody.substringAfter("<tbody>", "").substringBefore("</tbody>", "")
        if (tbody.isBlank()) return Result.success(emptyList())

        // Regex patterns against each <tr>…</tr> fragment
        val hrefRe  = Regex("""href="(/torrent/\d+/[^"]+/)""")
        val nameRe  = Regex("""<a href="/torrent/\d+/[^"]+/">([^<]+)</a>""")
        val seedsRe = Regex("""<td class="seeds">(\d+)</td>""")
        val leechRe = Regex("""<td class="leeches">(\d+)</td>""")
        val sizeRe  = Regex("""<td class="size"[^>]*>([\d.,]+\s*[KMGTkmgt]?i?B)""")
        val catRe   = Regex("""icon-type.*?<a[^>]*>([^<]+)</a>""", RegexOption.DOT_MATCHES_ALL)

        data class Row(val path: String, val name: String, val seeds: Int, val leeches: Int,
                       val sizeStr: String, val catRaw: String)

        val rows = tbody.split("</tr>").mapNotNull { row ->
            if (!row.contains("/torrent/")) return@mapNotNull null
            val path  = hrefRe.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val name  = nameRe.find(row)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val seeds   = seedsRe.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val leeches = leechRe.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val sizeStr = sizeRe.find(row)?.groupValues?.get(1) ?: ""
            val catRaw  = catRe.find(row)?.groupValues?.get(1)?.trim() ?: ""
            Row(path, name, seeds, leeches, sizeStr, catRaw)
        }.take(15)  // cap to avoid too many follow-up requests

        if (rows.isEmpty()) return Result.success(emptyList())

        // Step 2: parallel fetch each torrent detail page using the same working mirror
        val magnetRe = Regex("""href="(magnet:\?xt=urn:btih:[^"]+)""")
        coroutineScope {
            rows.map { row ->
                async {
                    runCatching {
                        val detail = httpGet("https://$activeDomain${row.path}", BROWSER_UA)
                            ?: return@runCatching null
                        val magnetUri = magnetRe.find(detail)?.groupValues?.get(1)
                            ?: return@runCatching null
                        val hash = HASH_RE.find(magnetUri)?.value?.lowercase()
                            ?: return@runCatching null
                        TorrentSearchResult(
                            name = row.name,
                            infoHash = hash,
                            size = parseHumanSize(row.sizeStr),
                            seeders = row.seeds,
                            leechers = row.leeches,
                            category = leet337xCategory(row.catRaw),
                            addedTimestamp = 0L,
                            source = "1337x"
                        )
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }.also { if (it.isFailure) Log.e(TAG, "1337x failed", it.exceptionOrNull()) }

    private fun leet337xCategory(cat: String) = when {
        cat.contains("Movie", ignoreCase = true) || cat.contains("TV", ignoreCase = true)
            || cat.contains("Video", ignoreCase = true)         -> "Video"
        cat.contains("Music", ignoreCase = true)
            || cat.contains("Audio", ignoreCase = true)         -> "Audio"
        cat.contains("Anime", ignoreCase = true)                -> "Anime"
        cat.contains("Game", ignoreCase = true)                 -> "Games"
        cat.contains("App", ignoreCase = true)
            || cat.contains("Software", ignoreCase = true)      -> "Apps"
        else                                                    -> inferCategory(cat)
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

    /** Parse a human-readable size string like "1.5 GB" anywhere in freeform text. */
    private fun parseHumanSize(s: String): Long {
        val m = Regex("([\\d.]+)\\s*(B|KB|MB|GB|TB)", RegexOption.IGNORE_CASE).find(s) ?: return 0L
        val num = m.groupValues[1].toDoubleOrNull() ?: return 0L
        return when (m.groupValues[2].uppercase()) {
            "KB" -> (num * 1_024).toLong()
            "MB" -> (num * 1_024 * 1_024).toLong()
            "GB" -> (num * 1_024 * 1_024 * 1_024).toLong()
            "TB" -> (num * 1_024 * 1_024 * 1_024 * 1_024).toLong()
            else -> num.toLong()
        }
    }

    private fun enc(q: String) = URLEncoder.encode(q.trim(), "UTF-8")

    // ── HTTP client ────────────────────────────────────────────────────────────
    //
    // Uses OkHttp with a Cloudflare DNS-over-HTTPS resolver.
    //
    // WHY: Many ISPs (especially in South/Southeast Asia) block torrent-related
    // domains via DNS poisoning — the DNS query for "yts.mx", "1337x.to", etc.
    // returns a wrong IP or NXDOMAIN. By resolving hostnames through Cloudflare's
    // HTTPS endpoint (1.1.1.1) instead of the system resolver, we bypass ISP DNS
    // blocking entirely. The DoH request itself goes to the IP 1.1.1.1 directly
    // (no DNS lookup needed for it), so there’s no circular dependency.

    /** OkHttp client used for the DoH bootstrap (connects to 1.1.1.1 by IP, no DNS). */
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Custom DNS resolver that queries Cloudflare 1.1.1.1 over HTTPS.
     * Falls back to the system resolver if DoH fails for any reason.
     */
    private val cloudflareDoH = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                // Connect to Cloudflare's 1.1.1.1 by IP — no DNS resolution required here.
                val url = "https://1.1.1.1/dns-query?name=${URLEncoder.encode(hostname, "UTF-8")}&type=A"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "MediaDL/1.0")
                    .build()
                val body = bootstrapClient.newCall(request).execute().use { resp ->
                    resp.body?.string()
                } ?: return Dns.SYSTEM.lookup(hostname)
                val answers = JSONObject(body).optJSONArray("Answer")
                    ?: return Dns.SYSTEM.lookup(hostname)
                val ips = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val a = answers.getJSONObject(i)
                    if (a.optInt("type") == 1) {  // type 1 = A record (IPv4)
                        try { ips.add(InetAddress.getByName(a.optString("data"))) }
                        catch (_: Exception) {}
                    }
                }
                if (ips.isEmpty()) Dns.SYSTEM.lookup(hostname)
                else { Log.d(TAG, "DoH → $hostname = ${ips.first()}"); ips }
            } catch (e: Exception) {
                Log.w(TAG, "DoH failed for $hostname (${e.message}), falling back to system DNS")
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    /** Main OkHttpClient — uses DoH for DNS, browser-like headers, 10 s timeouts. */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(cloudflareDoH)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Try multiple URLs in order, returning the first successful response body.
     * Useful for providers with mirror domains.
     */
    private fun httpGetWithFallback(
        urls: List<String>,
        userAgent: String = BROWSER_UA
    ): String? {
        for (url in urls) {
            val result = httpGet(url, userAgent)
            if (result != null) return result
        }
        return null
    }

    private fun httpGet(urlStr: String, userAgent: String = BROWSER_UA): String? = try {
        val request = Request.Builder()
            .url(urlStr)
            .header("User-Agent",      userAgent)
            .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Connection",      "keep-alive")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} → $urlStr")
                null
            } else {
                response.body?.string()
            }
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

