package com.ayashii.kuro.data.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.ayashii.kuro.data.settings.LyricsLanguage
import com.ayashii.kuro.data.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class LyricsRepository(private val context: Context) {

    private val httpClient = com.ayashii.kuro.data.remote.HttpClientProvider.client

    private val memCache = LruCache<String, LyricsData>(100)
    private val negativeCache = LruCache<String, Long>(200)
    private val NEGATIVE_CACHE_TTL = 7 * 24 * 60 * 60 * 1000L
    private val appContext = context.applicationContext
    private val diskCacheDir = File(appContext.cacheDir, "lyrics_cache_v8").apply {
        mkdirs()
    }

    init {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                for (v in 4..7) {
                    val oldCache = File(appContext.cacheDir, "lyrics_cache_v$v")
                    if (oldCache.exists()) oldCache.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    private sealed class LyricsFetchResult {
        data class Success(val data: LyricsData) : LyricsFetchResult()
        object Empty : LyricsFetchResult()
        object Error : LyricsFetchResult()
    }

    private fun resolveTargetLang(): LyricsLanguage {
        return try {
            SettingsManager.getInstance().lyricsLanguage.value
        } catch (_: Throwable) {
            LyricsLanguage.INDONESIAN
        }
    }

    suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationSeconds: Long = 0L,
        autoTranslate: Boolean = true
    ): LyricsData? = withContext(Dispatchers.IO) {
        val targetLang = resolveTargetLang()
        val cacheKey = "${trackId}_${targetLang.deeplCode}"

        synchronized(negativeCache) {
            val emptyAt = negativeCache.get(trackId)
            if (emptyAt != null && System.currentTimeMillis() - emptyAt < NEGATIVE_CACHE_TTL) {
                return@withContext null
            }
        }

        synchronized(memCache) {
            if (autoTranslate) {
                memCache.get(cacheKey)?.let { return@withContext it }
            } else {
                memCache.get(trackId)?.let { return@withContext it }
            }
        }

        val langDiskFile = File(diskCacheDir, "${trackId}_${targetLang.deeplCode}.json")
        val baseDiskFile = File(diskCacheDir, "$trackId.json")

        if (autoTranslate && langDiskFile.exists()) {
            try {
                val cached = parseLyricsJson(langDiskFile.readText())
                if (cached != null && cached.lines.any { !it.translation.isNullOrBlank() }) {
                    synchronized(memCache) {
                        memCache.put(cacheKey, cached)
                    }
                    return@withContext cached
                } else {
                    langDiskFile.delete()
                }
            } catch (e: Exception) {
                Log.d("LyricsRepository", "Disk cache read error: ${e.message}")
            }
        }

        if (baseDiskFile.exists()) {
            try {
                val cached = parseLyricsJson(baseDiskFile.readText())
                if (cached != null) {
                    if (!autoTranslate) {
                        synchronized(memCache) { memCache.put(trackId, cached) }
                        return@withContext cached
                    }
                    val translatedLines = translateLines(cached.lines, targetLang)
                    val localized = cached.copy(lines = translatedLines)
                    val hasTranslation = translatedLines.any { !it.translation.isNullOrBlank() }
                    synchronized(memCache) {
                        memCache.put(trackId, cached)
                        if (hasTranslation) {
                            memCache.put(cacheKey, localized)
                        }
                    }
                    if (hasTranslation) {
                        try {
                            langDiskFile.writeText(serializeLyricsJson(localized))
                        } catch (_: Exception) {}
                    }
                    return@withContext localized
                } else {
                    baseDiskFile.delete()
                }
            } catch (e: Exception) {
                Log.d("LyricsRepository", "Base disk cache read error: ${e.message}")
            }
        }

        val emptyMarker = File(diskCacheDir, "$trackId.empty")
        if (emptyMarker.exists() && System.currentTimeMillis() - emptyMarker.lastModified() < NEGATIVE_CACHE_TTL) {
            synchronized(negativeCache) { negativeCache.put(trackId, emptyMarker.lastModified()) }
            return@withContext null
        }

        val ytmResult = fetchFromYouTubeMusic(trackId, title, artist)
        val lrclibResult = if (ytmResult is LyricsFetchResult.Success) {
            LyricsFetchResult.Empty
        } else {
            fetchFromLrclib(trackId, title, artist, durationSeconds)
        }
        val fetchResult = when {
            ytmResult is LyricsFetchResult.Success -> ytmResult
            lrclibResult is LyricsFetchResult.Success -> lrclibResult
            ytmResult is LyricsFetchResult.Error || lrclibResult is LyricsFetchResult.Error -> LyricsFetchResult.Error
            else -> LyricsFetchResult.Empty
        }
        var lyrics: LyricsData? = null

        if (fetchResult is LyricsFetchResult.Success) {
            val sanitizedLines = LrcParser.sanitizeLines(fetchResult.data.lines)
            if (isValidLyricsLines(sanitizedLines)) {
                lyrics = fetchResult.data.copy(lines = sanitizedLines)
            }
        }

        if (lyrics != null) {
            try {
                pruneDiskCacheIfNeeded()
                baseDiskFile.writeText(serializeLyricsJson(lyrics))
                if (emptyMarker.exists()) emptyMarker.delete()
            } catch (_: Exception) {}

            val rawLyrics = lyrics
            var translatedReady = false
            if (autoTranslate && lyrics.lines.isNotEmpty()) {
                val translatedLines = translateLines(lyrics.lines, targetLang)
                lyrics = lyrics.copy(lines = translatedLines)
                translatedReady = translatedLines.any { !it.translation.isNullOrBlank() }
                if (translatedReady) {
                    try {
                        langDiskFile.writeText(serializeLyricsJson(lyrics))
                    } catch (_: Exception) {}
                }
            }

            synchronized(memCache) {
                memCache.put(trackId, rawLyrics)
                if (autoTranslate && translatedReady) {
                    memCache.put(cacheKey, lyrics)
                }
            }
        } else {
            val verifiedEmpty = fetchResult is LyricsFetchResult.Empty || fetchResult is LyricsFetchResult.Success
            val hadNetworkError = fetchResult is LyricsFetchResult.Error

            if (!hadNetworkError && liriqoVerifiedEmpty) {
                synchronized(negativeCache) { negativeCache.put(trackId, System.currentTimeMillis()) }
                try {
                    emptyMarker.createNewFile()
                } catch (_: Exception) {}
            }
        }

        lyrics
    }

    private fun pruneDiskCacheIfNeeded() {
        try {
            val files = diskCacheDir.listFiles() ?: return
            if (files.size > 500) {
                files.sortedBy { it.lastModified() }.take(files.size - 400).forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    private fun fetchFromYouTubeMusic(
        trackId: String,
        title: String,
        artist: String
    ): LyricsFetchResult {
        if (!trackId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return LyricsFetchResult.Empty
        }

        return try {
            val (_, glCode) = SettingsManager.getInstance().getEffectiveHlAndGl()
            val nextPayload = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20260401.01.00")
                    put("hl", "en")
                    put("gl", glCode)
                }))
                put("videoId", trackId)
            }
            val next = postYouTubeMusicRequest(
                endpoint = "next",
                payload = nextPayload,
                clientName = "67",
                clientVersion = "1.20260401.01.00"
            ) ?: return LyricsFetchResult.Error

            val browseId = findLyricsBrowseId(next)
                ?: return LyricsFetchResult.Empty

            val lyricsPayload = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject().apply {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", "7.21.50")
                    put("hl", "en")
                    put("gl", glCode)
                }))
                put("browseId", browseId)
            }
            val lyricsJson = postYouTubeMusicRequest(
                endpoint = "browse",
                payload = lyricsPayload,
                clientName = "21",
                clientVersion = "7.21.50"
            ) ?: return LyricsFetchResult.Error

            val timedModel = findValueByKey(lyricsJson, "timedLyricsModel")
            val timedLines = findValueByKey(timedModel, "timedLyricsData") as? org.json.JSONArray
            if (timedLines != null && timedLines.length() > 0) {
                val parsed = buildList {
                    for (index in 0 until timedLines.length()) {
                        val line = timedLines.optJSONObject(index) ?: continue
                        val text = line.optString("lyricLine").trim()
                        val cue = line.optJSONObject("cueRange") ?: JSONObject()
                        val startMs = cue.optString("startTimeMilliseconds").toLongOrNull() ?: continue
                        val endMs = cue.optString("endTimeMilliseconds").toLongOrNull()
                        if (text.isNotBlank()) {
                            add(LyricLine(startMs.coerceAtLeast(0L), text, endTimeMs = endMs))
                        }
                    }
                }.sortedBy { it.timeMs }
                val clean = LrcParser.sanitizeLines(parsed)
                if (isValidLyricsLines(clean)) {
                    return LyricsFetchResult.Success(
                        LyricsData(trackId, title, artist, clean, isSynced = true)
                    )
                }
            }

            val description = findValueByKey(lyricsJson, "description")
            val runs = (description as? JSONObject)?.optJSONArray("runs")
            val plain = if (runs != null) {
                buildString {
                    for (index in 0 until runs.length()) {
                        append(runs.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }.replace("\r", "").trim()
            } else {
                ""
            }
            if (plain.isNotBlank()) {
                val clean = LrcParser.sanitizeLines(LrcParser.parsePlain(plain))
                if (isValidLyricsLines(clean)) {
                    return LyricsFetchResult.Success(
                        LyricsData(trackId, title, artist, clean, isSynced = false)
                    )
                }
            }

            LyricsFetchResult.Empty
        } catch (_: Exception) {
            LyricsFetchResult.Error
        }
    }

    private fun postYouTubeMusicRequest(
        endpoint: String,
        payload: JSONObject,
        clientName: String,
        clientVersion: String
    ): JSONObject? {
        val origin = "https://music.youtube.com"
        val request = Request.Builder()
            .url("$origin/youtubei/v1/$endpoint")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .header("X-Origin", origin)
            .header("X-YouTube-Client-Name", clientName)
            .header("X-YouTube-Client-Version", clientVersion)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val builder = request.newBuilder()
        com.ayashii.kuro.data.auth.AuthManager.getInstance()
            .applyAuthToRequest(builder, payload, origin)
        return httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.string()?.let(::JSONObject)
        }
    }

    private fun findLyricsBrowseId(root: JSONObject): String? {
        fun search(value: Any?): String? {
            when (value) {
                is JSONObject -> {
                    val browseId = value.optString("browseId").takeIf { it.isNotBlank() }
                    val config = value.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                    val pageType = config?.optString("pageType").orEmpty()
                    if (browseId != null && (
                        pageType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" ||
                            browseId.startsWith("MPLY")
                    )) {
                        return browseId
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val found = search(value.opt(keys.next()))
                        if (found != null) return found
                    }
                }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        val found = search(value.opt(index))
                        if (found != null) return found
                    }
                }
            }
            return null
        }

        search(root)?.let { return it }
        val tabs = findValueByKey(root, "tabs") as? org.json.JSONArray ?: return null
        for (index in 0 until tabs.length()) {
            val renderer = tabs.optJSONObject(index)?.optJSONObject("tabRenderer") ?: continue
            val id = renderer.optString("browseId")
            if (id.startsWith("MPLY")) return id
        }
        return null
    }

    private fun findValueByKey(root: Any?, target: String): Any? {
        when (root) {
            is JSONObject -> {
                if (root.has(target)) return root.opt(target)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val found = findValueByKey(root.opt(keys.next()), target)
                    if (found != null) return found
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until root.length()) {
                    val found = findValueByKey(root.opt(index), target)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun fetchFromLrclib(
        trackId: String,
        title: String,
        artist: String,
        durationSeconds: Long
    ): LyricsFetchResult {
        if (title.isBlank() || artist.isBlank()) return LyricsFetchResult.Empty

        return try {
            val cleanTitle = title
                .replace(Regex("""\s*\([^)]*\)|\s*\[[^]]*\]"""), "")
                .trim()
            val cleanArtist = artist.split(Regex(""",|feat\.|ft\."""), limit = 2)
                .firstOrNull()
                .orEmpty()
                .trim()
            if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
                return LyricsFetchResult.Empty
            }

            val url = buildString {
                append("https://lrclib.net/api/get?track_name=")
                append(URLEncoder.encode(cleanTitle, "UTF-8"))
                append("&artist_name=")
                append(URLEncoder.encode(cleanArtist, "UTF-8"))
                if (durationSeconds > 0L) {
                    append("&duration=")
                    append(durationSeconds)
                }
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "HeastMusic/1.0 (https://github.com/XyrexxArchive/heast-music)")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.code == 404) return LyricsFetchResult.Empty
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    return LyricsFetchResult.Error
                }

                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics").trim()
                if (synced.isNotBlank()) {
                    val lines = LrcParser.sanitizeLines(LrcParser.parse(synced))
                    if (isValidLyricsLines(lines)) {
                        return LyricsFetchResult.Success(
                            LyricsData(trackId, title, artist, lines, isSynced = true)
                        )
                    }
                }

                val plain = json.optString("plainLyrics").trim()
                if (plain.isNotBlank()) {
                    val lines = LrcParser.sanitizeLines(LrcParser.parsePlain(plain))
                    if (isValidLyricsLines(lines)) {
                        return LyricsFetchResult.Success(
                            LyricsData(trackId, title, artist, lines, isSynced = false)
                        )
                    }
                }

                LyricsFetchResult.Empty
            }
        } catch (_: Exception) {
            LyricsFetchResult.Error
        }
    }

    fun isValidLyricsLines(lines: List<LyricLine>): Boolean {
        val sanitized = LrcParser.sanitizeLines(lines)
        if (sanitized.isEmpty()) return false
        val nonBlank = sanitized.filter { it.text.isNotBlank() && !isIntroToken(it.text) }
        if (nonBlank.isEmpty()) return false
        if (nonBlank.size <= 2) {
            val combined = nonBlank.joinToString(" ") { it.text.lowercase().trim() }
            if (combined.contains("null") || combined.contains("batal") || combined.contains("cancel")) {
                return false
            }
        }
        return true
    }

    suspend fun translateLyrics(
        lyrics: LyricsData,
        targetLang: LyricsLanguage = resolveTargetLang()
    ): LyricsData = withContext(Dispatchers.IO) {
        if (lyrics.lines.isEmpty()) return@withContext lyrics
        val cacheKey = "${lyrics.trackId}_${targetLang.deeplCode}"
        val langDiskFile = File(diskCacheDir, "${lyrics.trackId}_${targetLang.deeplCode}.json")
        if (langDiskFile.exists()) {
            try {
                val cached = parseLyricsJson(langDiskFile.readText())
                if (cached != null && cached.lines.any { !it.translation.isNullOrBlank() }) {
                    synchronized(memCache) {
                        memCache.put(cacheKey, cached)
                        memCache.put(lyrics.trackId, cached)
                    }
                    return@withContext cached
                }
            } catch (_: Exception) {}
        }

        val translatedLines = translateLines(lyrics.lines, targetLang)
        val result = lyrics.copy(lines = translatedLines)
        if (translatedLines.any { !it.translation.isNullOrBlank() }) {
            synchronized(memCache) {
                memCache.put(cacheKey, result)
            }
            try {
                langDiskFile.writeText(serializeLyricsJson(result))
            } catch (_: Exception) {}
        }
        result
    }

    private val DEEPL_ENDPOINTS = listOf(
        "https://oneshot.fal.free.deepl.com/v1/translate",
        "https://oneshot.kef.free.deepl.com/v1/translate"
    )
    private val DEEPL_USER_AGENT = "DeepL/26.41.0 (com.deepl.mobiletranslator; build:264100; Android 14)"

    private fun translateLinesToIndonesian(lines: List<LyricLine>): List<LyricLine> {
        return translateLines(lines, resolveTargetLang())
    }

    private fun translateLines(lines: List<LyricLine>, targetLang: LyricsLanguage): List<LyricLine> {
        val cleanInput = LrcParser.sanitizeLines(lines)
        if (cleanInput.isEmpty()) return emptyList()

        val rawTexts = cleanInput.map { it.text }
        val payload = com.ayashii.kuro.data.native.KuroNativeCore.buildDeepLRequestPayload(rawTexts, targetLang.deeplCode)

        for (endpoint in DEEPL_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", DEEPL_USER_AGENT)
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val respBody = resp.body?.string().orEmpty()
                        val translatedTexts = com.ayashii.kuro.data.native.KuroNativeCore.parseDeepLResponseTranslations(respBody)
                        if (translatedTexts.isNotEmpty()) {
                            return cleanInput.mapIndexed { idx, line ->
                                val trans = translatedTexts.getOrNull(idx)?.trim()
                                val validTrans = if (!trans.isNullOrBlank() && !trans.equals(line.text.trim(), ignoreCase = true) && !LrcParser.isJunkOrCreditLine(trans)) trans else null
                                line.copy(translation = validTrans)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("LyricsRepository", "DeepL endpoint $endpoint error: ${e.message}")
            }
        }

        try {
            val textToTranslate = cleanInput.mapIndexed { idx, line ->
                "[[[$idx]]] ${line.text}"
            }.joinToString("\n")

            val formBody = ("client=gtx&sl=auto&tl=${targetLang.googleCode}&dt=t&q=" + encode(textToTranslate))
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val req = Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .post(formBody)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val jsonArr = JSONArray(resp.body?.string().orEmpty())
                    val segments = jsonArr.optJSONArray(0)
                    if (segments != null) {
                        val sb = StringBuilder()
                        for (i in 0 until segments.length()) {
                            val seg = segments.optJSONArray(i)
                            if (seg != null && !seg.isNull(0)) {
                                sb.append(seg.optString(0, ""))
                            }
                        }
                        val fullTranslated = sb.toString()

                        val tagRegex = Regex("""\[\s*\[\s*\[\s*(\d+)\s*\]\s*\]\s*\]\s*([\s\S]*?)(?=(?:\[\s*\[\s*\[\s*\d+\s*\]\s*\]\s*\]|\Z))""")
                        val tagMatches = tagRegex.findAll(fullTranslated).toList()
                        val translatedMap = mutableMapOf<Int, String>()
                        for (m in tagMatches) {
                            val idx = m.groupValues[1].toIntOrNull()
                            val text = m.groupValues[2].trim()
                            if (idx != null) {
                                translatedMap[idx] = text
                            }
                        }

                        if (translatedMap.isNotEmpty()) {
                            return cleanInput.mapIndexed { idx, line ->
                                val trans = translatedMap[idx]
                                val validTrans = if (!trans.isNullOrBlank() && !trans.equals(line.text.trim(), ignoreCase = true) && !LrcParser.isJunkOrCreditLine(trans)) trans else null
                                line.copy(translation = validTrans)
                            }
                        }

                        val delimiterRegex = Regex("""(?:\r?\n)?\s*(?:~[\s~]+~|\[\s*\[\s*\[\s*\d+\s*\]\s*\]\s*\])\s*(?:\r?\n)?""")
                        val translatedBlocks = if (delimiterRegex.containsMatchIn(fullTranslated)) {
                            fullTranslated.split(delimiterRegex).map { it.trim() }.filter { it.isNotEmpty() }
                        } else {
                            fullTranslated.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        }

                        return cleanInput.mapIndexed { idx, line ->
                            val trans = translatedBlocks.getOrNull(idx)?.trim()
                            val validTrans = if (!trans.isNullOrBlank() && !trans.equals(line.text.trim(), ignoreCase = true) && !trans.matches(Regex("""^\[\s*\[\s*\[.*\]\s*\]\s*\]$""")) && !LrcParser.isJunkOrCreditLine(trans)) trans else null
                            line.copy(translation = validTrans)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("LyricsRepository", "Fallback translation error: ${e.message}")
        }
        return cleanInput
    }

    private fun encode(str: String): String = URLEncoder.encode(str, "UTF-8")

    private fun serializeLyricsJson(data: LyricsData): String {
        val root = JSONObject().apply {
            put("trackId", data.trackId)
            put("title", data.title)
            put("artist", data.artist)
            put("isSynced", data.isSynced)
            val linesArr = JSONArray()
            data.lines.forEach { line ->
                val lineObj = JSONObject().apply {
                    put("timeMs", line.timeMs)
                    put("text", line.text)
                    if (line.translation != null) put("translation", line.translation)
                    if (line.endTimeMs != null) put("endTimeMs", line.endTimeMs)
                }
                linesArr.put(lineObj)
            }
            put("lines", linesArr)
        }
        return root.toString()
    }

    private fun parseLyricsJson(jsonStr: String): LyricsData? {
        return try {
            val root = JSONObject(jsonStr)
            val trackId = root.getString("trackId")
            val title = root.getString("title")
            val artist = root.getString("artist")
            val isSynced = root.optBoolean("isSynced", true)
            val linesArr = root.getJSONArray("lines")
            val rawLines = mutableListOf<LyricLine>()
            for (i in 0 until linesArr.length()) {
                val obj = linesArr.getJSONObject(i)
                rawLines.add(
                    LyricLine(
                        timeMs = obj.getLong("timeMs"),
                        text = obj.getString("text"),
                        translation = if (obj.has("translation") && !obj.isNull("translation")) obj.optString("translation").takeIf { it.isNotBlank() } else null,
                        endTimeMs = if (obj.has("endTimeMs") && !obj.isNull("endTimeMs")) obj.getLong("endTimeMs") else null
                    )
                )
            }
            val sanitizedLines = LrcParser.sanitizeLines(rawLines)
            if (!isValidLyricsLines(sanitizedLines)) return null
            LyricsData(trackId, title, artist, sanitizedLines, isSynced)
        } catch (e: Exception) {
            null
        }
    }
}
