package com.dlive.ptstream.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

enum class TabFilter {
    PORTUGAL,
    LIVE_GAMES,
    FAVORITES,
    TIMSTREAMS,
    ALL
}

class ChannelRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dlive_prefs", Context.MODE_PRIVATE)
    private val PREF_FAVORITES = "fav_channels"
    private val PREF_HIDDEN_CHANNELS = "hidden_channels"
    private val PREF_BASE_URL = "base_domain_url"
    private val PREF_TIMST_BASE_URL = "timst_base_url"
    private val PREF_AUTO_PIP_ON_BACK = "pref_auto_pip_on_back"
    private val PREF_AUTO_UNMUTE = "pref_auto_unmute"
    private val PREF_DEFAULT_SERVER = "pref_default_server"
    private val CACHE_FILE_NAME = "channels_cache.json"

    private var cachedChannels: List<Channel> = emptyList()
    private var liveEvents: List<LiveEvent> = emptyList()

    @Volatile
    private var precomputedPtChannels: List<Channel> = emptyList()
    @Volatile
    private var precomputedAllChannels: List<Channel> = emptyList()
    @Volatile
    private var precomputedFavChannels: List<Channel> = emptyList()
    @Volatile
    private var precomputedPtCategories: List<String> = listOf("Todos")
    @Volatile
    private var precomputedAllCategories: List<String> = listOf("Todos")

    val channelsVersion = androidx.compose.runtime.mutableIntStateOf(0)

    init {
        loadChannels()
    }

    fun rebuildPrecomputedLists() {
        val hiddenIds = getHiddenChannelIds()
        val pt = ArrayList<Channel>()
        val all = ArrayList<Channel>()
        val fav = ArrayList<Channel>()

        for (ch in cachedChannels) {
            if (hiddenIds.contains(ch.id)) continue
            all.add(ch)
            if (ch.isPortuguese) pt.add(ch)
            if (ch.isFavorite) fav.add(ch)
        }

        precomputedPtChannels = pt
        precomputedAllChannels = all
        precomputedFavChannels = fav

        val preferredPtCats = listOf("Generalistas", "Desporto", "Filmes & Séries")
        val availablePtCats = pt.map { it.category }.distinct()
        val orderedPtCats = preferredPtCats.filter { availablePtCats.contains(it) } + availablePtCats.filter { !preferredPtCats.contains(it) }.sorted()

        val allCats = all.map { it.category }.distinct().filter { !preferredPtCats.contains(it) }.sorted()
        precomputedAllCategories = listOf("Todos") + orderedPtCats + allCats

        channelsVersion.intValue++
    }

    fun getHiddenChannelIds(): Set<String> {
        return prefs.getStringSet(PREF_HIDDEN_CHANNELS, emptySet()) ?: emptySet()
    }

    fun hideChannel(channelId: String) {
        val hidden = getHiddenChannelIds().toMutableSet()
        hidden.add(channelId)
        prefs.edit().putStringSet(PREF_HIDDEN_CHANNELS, hidden).apply()
        rebuildPrecomputedLists()
    }

    fun unhideChannel(channelId: String) {
        val hidden = getHiddenChannelIds().toMutableSet()
        hidden.remove(channelId)
        prefs.edit().putStringSet(PREF_HIDDEN_CHANNELS, hidden).apply()
        rebuildPrecomputedLists()
    }

    fun unhideAllChannels() {
        prefs.edit().remove(PREF_HIDDEN_CHANNELS).apply()
        rebuildPrecomputedLists()
    }

    fun isChannelHidden(channelId: String): Boolean {
        return getHiddenChannelIds().contains(channelId)
    }

    fun getHiddenChannels(): List<Channel> {
        val hiddenIds = getHiddenChannelIds()
        return cachedChannels.filter { hiddenIds.contains(it.id) }
    }

    fun isAutoPipOnBack(): Boolean = prefs.getBoolean(PREF_AUTO_PIP_ON_BACK, true)
    fun setAutoPipOnBack(enabled: Boolean) = prefs.edit().putBoolean(PREF_AUTO_PIP_ON_BACK, enabled).apply()

    fun isAutoUnmuteEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_UNMUTE, true)
    fun setAutoUnmuteEnabled(enabled: Boolean) = prefs.edit().putBoolean(PREF_AUTO_UNMUTE, enabled).apply()

    fun getDefaultServer(): String = prefs.getString(PREF_DEFAULT_SERVER, "stream") ?: "stream"
    fun setDefaultServer(server: String) = prefs.edit().putString(PREF_DEFAULT_SERVER, server).apply()

    fun getBaseUrl(): String = prefs.getString(PREF_BASE_URL, "https://dlive.sx") ?: "https://dlive.sx"
    fun setBaseUrl(newUrl: String) {
        val clean = if (newUrl.endsWith("/")) newUrl.dropLast(1) else newUrl
        prefs.edit().putString(PREF_BASE_URL, clean).apply()
    }

    fun getTimstBaseUrl(): String = prefs.getString(PREF_TIMST_BASE_URL, "https://timst.top") ?: "https://timst.top"
    fun setTimstBaseUrl(newUrl: String) {
        val clean = if (newUrl.endsWith("/")) newUrl.dropLast(1) else newUrl
        prefs.edit().putString(PREF_TIMST_BASE_URL, clean).apply()
    }

    val epgRepository = EpgRepository(context)

    private fun loadChannels() {
        val favIds = getFavoriteIds()

        // Clean stale cache file if present
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (cacheFile.exists()) {
            try {
                cacheFile.delete()
            } catch (e: Exception) {}
        }

        // Bundled curated channels
        try {
            context.assets.open("channels.json").use { inputStream ->
                val reader = InputStreamReader(inputStream, "UTF-8")
                val itemType = object : TypeToken<List<Channel>>() {}.type
                val list: List<Channel> = Gson().fromJson(reader, itemType) ?: emptyList()

                cachedChannels = list.map { ch ->
                    val isPt = ch.isPortuguese
                    val logo = ch.logoUrl ?: ChannelLogoHelper.getDefaultOnlineLogo(ch.name)
                    ch.copy(
                        isPt = isPt,
                        country = if (isPt) "PT" else ch.country,
                        category = ch.category,
                        isFavorite = favIds.contains(ch.id),
                        logoUrl = logo,
                        status = ch.safeStatus
                    )
                }
                rebuildPrecomputedLists()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            cachedChannels = emptyList()
            rebuildPrecomputedLists()
        }
    }

    /**
     * Sincroniza canais online e atualiza grelha
     */
    fun syncChannelsFromWeb(scope: CoroutineScope, onFinished: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            var success = false
            try {
                loadChannels()
                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                onFinished?.invoke(success)
            }
        }
    }

    private fun normalizeChannelName(name: String): String {
        return name.lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace(".", "")
            .replace("portugal", "")
            .replace("pt", "")
    }

    /**
     * Obter Jogos em Direto do TimStreams (Futebol prioritário)
     */
    fun fetchLiveMatches(scope: CoroutineScope, onResult: (List<LiveEvent>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val eventsList = mutableListOf<LiveEvent>()
            try {
                val url = "${getTimstBaseUrl()}/api/live-upcoming"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JsonParser.parseString(jsonStr).asJsonObject
                    val eventsArr = root.getAsJsonArray("events")

                    // Build genre map
                    val genreMap = mutableMapOf<Int, String>()
                    val genresArr = root.getAsJsonArray("genres")
                    if (genresArr != null) {
                        for (g in genresArr) {
                            val gObj = g.asJsonObject
                            val gid = gObj.get("id")?.asInt ?: continue
                            val gname = gObj.get("name")?.asString ?: "Desporto"
                            genreMap[gid] = when (gid) {
                                1 -> "⚽ Futebol"
                                2 -> "🏎️ Motores"
                                3 -> "🥊 Desportos Combate"
                                6 -> "🤼 Wrestling"
                                7 -> "🏀 Basquetebol"
                                8 -> "🏈 Futebol Americano"
                                9 -> "⚾ Basebol"
                                10 -> "🎾 Ténis"
                                11 -> "🏒 Hóquei"
                                else -> gname
                            }
                        }
                    }

                    if (eventsArr != null) {
                        for (el in eventsArr) {
                            val ev = el.asJsonObject
                            val id = ev.get("url")?.asString ?: System.currentTimeMillis().toString()
                            val name = ev.get("name")?.asString ?: "Evento Desportivo"
                            val logo = ev.get("logo")?.asString
                            val genreId = ev.get("genre")?.asInt ?: 1
                            val genreName = genreMap[genreId] ?: "⚽ Futebol"
                            val time = ev.get("time")?.asString ?: ""
                            val viewers = ev.get("viewers")?.asInt ?: 0

                            val streamsList = mutableListOf<EventStream>()
                            val streamsArr = ev.getAsJsonArray("streams")
                            if (streamsArr != null) {
                                for (s in streamsArr) {
                                    val sObj = s.asJsonObject
                                    val sName = sObj.get("name")?.asString ?: "Servidor"
                                    val sUrl = sObj.get("url")?.asString ?: continue
                                    streamsList.add(EventStream(sName, sUrl))
                                }
                            }

                            val isAmericanFootball = genreName.contains("americano", ignoreCase = true) ||
                                    genreName.contains("american", ignoreCase = true) ||
                                    genreId == 2
                            val isSoccer = !isAmericanFootball && (
                                genreId == 1 ||
                                genreName.contains("futebol", ignoreCase = true) ||
                                genreName.contains("soccer", ignoreCase = true) ||
                                name.contains("fc", ignoreCase = true) ||
                                name.contains("sporting", ignoreCase = true) ||
                                name.contains("benfica", ignoreCase = true) ||
                                name.contains("porto", ignoreCase = true)
                            )

                            eventsList.add(
                                LiveEvent(
                                    id = id,
                                    name = name,
                                    logo = logo,
                                    genre = genreId,
                                    genreName = genreName,
                                    time = formatEventTime(time),
                                    viewers = viewers,
                                    streams = streamsList,
                                    isSoccer = isSoccer
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Also fetch from NTV (Kobra, Falcon, Raptor)
            try {
                val ntvConn = (URL("https://ntv.st/api/get-matches?server=kobra&type=both").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", "https://ntv.st/")
                }
                if (ntvConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val ntvJson = ntvConn.inputStream.bufferedReader().use { it.readText() }
                    val ntvRoot = JsonParser.parseString(ntvJson).asJsonObject
                    val ntvAll = ntvRoot.getAsJsonArray("all")
                    if (ntvAll != null) {
                        for (item in ntvAll) {
                            val obj = item.asJsonObject
                            val id = obj.get("id")?.asString ?: continue
                            val title = obj.get("title")?.asString ?: continue
                            val category = obj.get("category")?.asString ?: "sports"
                            val isLive = obj.get("live")?.asBoolean ?: false

                            val genreName = when (category.lowercase()) {
                                "football", "soccer" -> "⚽ Futebol"
                                "motor-sports" -> "🏎️ Motores"
                                "basketball" -> "🏀 Basquetebol"
                                "tennis" -> "🎾 Ténis"
                                "golf" -> "⛳ Golfe"
                                "american-football" -> "🏈 Futebol Americano"
                                else -> "🏆 Desporto"
                            }

                            val ntvStreams = listOf(
                                EventStream("NTV Kobra", "https://ntv.st/watch/kobra/$id"),
                                EventStream("NTV Falcon", "https://ntv.st/watch/falcon/$id"),
                                EventStream("NTV Raptor", "https://ntv.st/watch/raptor/$id")
                            )

                            // Check if this match already exists from TimStreams by title similarity
                            val existingIndex = eventsList.indexOfFirst {
                                val n1 = it.name.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs")
                                val n2 = title.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs")
                                n1.contains(n2) || n2.contains(n1)
                            }

                            if (existingIndex >= 0) {
                                // Add NTV streams as additional options
                                val existing = eventsList[existingIndex]
                                val combinedStreams = existing.streams + ntvStreams
                                eventsList[existingIndex] = existing.copy(streams = combinedStreams)
                            } else {
                                val isSoccer = category.lowercase() in listOf("football", "soccer") ||
                                        title.contains(" vs ", ignoreCase = true) && !title.contains("at", ignoreCase = true)

                                eventsList.add(
                                    LiveEvent(
                                        id = "ntv-$id",
                                        name = title,
                                        logo = null,
                                        genre = if (isSoccer) 1 else 99,
                                        genreName = genreName,
                                        time = if (isLive) "🔴 EM DIRETO" else "Hoje",
                                        viewers = if (isLive) 500 else 100,
                                        streams = ntvStreams,
                                        isSoccer = isSoccer
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Prioritize soccer, then sort by viewers descending
            eventsList.sortWith(compareBy({ !it.isSoccer }, { -it.viewers }))
            liveEvents = eventsList

            withContext(Dispatchers.Main) {
                onResult(eventsList)
            }
        }
    }

    private fun formatEventTime(rawTime: String): String {
        return try {
            if (rawTime.contains("T")) {
                val parts = rawTime.split("T")
                val time = parts[1].take(5)
                val date = parts[0]
                "Hoje $time"
            } else {
                rawTime
            }
        } catch (e: Exception) {
            rawTime
        }
    }

    /**
     * Testar canais portugueses: verifica DaddyLive e TimStreams para cada canal
     */
    fun testPortugueseChannels(
        scope: CoroutineScope,
        onProgress: (current: Int, total: Int, channelName: String, isOnline: Boolean) -> Unit,
        onFinished: (onlineCount: Int, totalCount: Int) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val ptList = cachedChannels.filter { it.isPortuguese }
            var onlineCount = 0

            for ((index, ch) in ptList.withIndex()) {
                var isOnline = false

                // 1. Test DaddyLive primary stream
                try {
                    val streamUrl = "${getBaseUrl()}/stream/stream-${ch.id}.php"
                    val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                        setRequestProperty("Referer", "${getBaseUrl()}/")
                    }
                    val code = conn.responseCode
                    if (code == 200 || code == 302) {
                        isOnline = true
                    }
                } catch (e: Exception) {
                    // Failover to backup
                }

                // 2. If primary failed, test TimStreams backup
                if (!isOnline && !ch.backupStreamUrl.isNullOrBlank()) {
                    try {
                        val backupConn = (URL(ch.backupStreamUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "HEAD"
                            connectTimeout = 3000
                            readTimeout = 3000
                            setRequestProperty("User-Agent", "Mozilla/5.0")
                            setRequestProperty("Referer", "${getTimstBaseUrl()}/")
                        }
                        val code = backupConn.responseCode
                        if (code == 200 || code == 302) {
                            isOnline = true
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                ch.status = if (isOnline) ChannelStatus.ONLINE else ChannelStatus.OFFLINE
                if (isOnline) onlineCount++

                withContext(Dispatchers.Main) {
                    onProgress(index + 1, ptList.size, ch.name, isOnline)
                }
            }

            withContext(Dispatchers.Main) {
                onFinished(onlineCount, ptList.size)
            }
        }
    }

    fun getFavoriteIds(): Set<String> {
        return prefs.getStringSet(PREF_FAVORITES, emptySet()) ?: emptySet()
    }

    fun toggleFavorite(channelId: String): Boolean {
        val favs = getFavoriteIds().toMutableSet()
        val isNowFav = if (favs.contains(channelId)) {
            favs.remove(channelId)
            false
        } else {
            favs.add(channelId)
            true
        }
        prefs.edit().putStringSet(PREF_FAVORITES, favs).apply()

        cachedChannels = cachedChannels.map {
            if (it.id == channelId) it.copy(isFavorite = isNowFav) else it
        }
        rebuildPrecomputedLists()

        return isNowFav
    }

    fun getTopFootballChannels(query: String = ""): List<Channel> {
        val footballPattern = Regex(
            "sport tv|benfica|sporting tv|porto canal|canal 11|dazn|sky sports (premier league|football|main event)|tnt sports|espn|bein sports|super sport (premier league|football)|premier sports",
            RegexOption.IGNORE_CASE
        )

        val base = precomputedAllChannels
        return base.filter { ch ->
            val isFootballChannel = ch.isPortuguese && (ch.category.contains("Desporto", ignoreCase = true) || footballPattern.containsMatchIn(ch.name)) ||
                    footballPattern.containsMatchIn(ch.name) ||
                    (ch.category.contains("Desporto", ignoreCase = true) && (ch.name.contains("league", ignoreCase = true) || ch.name.contains("football", ignoreCase = true) || ch.name.contains("soccer", ignoreCase = true)))

            if (!isFootballChannel) return@filter false

            if (query.isBlank()) true
            else ch.name.contains(query, ignoreCase = true) || ch.id.contains(query)
        }.sortedWith(compareBy({ !it.isPortuguese }, { it.name }))
    }

    fun getChannels(tab: TabFilter, query: String = "", categoryFilter: String = "Todos", includeHidden: Boolean = false): List<Channel> {
        if (!includeHidden && query.isBlank() && categoryFilter == "Todos") {
            return when (tab) {
                TabFilter.PORTUGAL -> precomputedPtChannels
                TabFilter.ALL -> precomputedAllChannels
                TabFilter.FAVORITES -> precomputedFavChannels
                TabFilter.LIVE_GAMES -> getTopFootballChannels()
                TabFilter.TIMSTREAMS -> {
                    val hidden = getHiddenChannelIds()
                    cachedChannels.filter { !hidden.contains(it.id) && (!it.backupStreamUrl.isNullOrBlank() || it.category == "TimStreams" || it.id.startsWith("timst-")) }
                }
            }
        }

        val baseList = when (tab) {
            TabFilter.PORTUGAL -> if (includeHidden) cachedChannels.filter { it.isPortuguese } else precomputedPtChannels
            TabFilter.LIVE_GAMES -> getTopFootballChannels(query)
            TabFilter.TIMSTREAMS -> {
                val list = cachedChannels.filter { !it.backupStreamUrl.isNullOrBlank() || it.category == "TimStreams" || it.id.startsWith("timst-") }
                if (includeHidden) list else {
                    val hidden = getHiddenChannelIds()
                    list.filter { !hidden.contains(it.id) }
                }
            }
            TabFilter.FAVORITES -> if (includeHidden) cachedChannels.filter { it.isFavorite } else precomputedFavChannels
            TabFilter.ALL -> if (includeHidden) cachedChannels else precomputedAllChannels
        }

        return baseList.filter { ch ->
            val matchesCategory = if (categoryFilter == "Todos") true
            else if (categoryFilter == "⚡ TimStreams") !ch.backupStreamUrl.isNullOrBlank() || ch.category == "TimStreams" || ch.id.startsWith("timst-")
            else ch.category.equals(categoryFilter, ignoreCase = true)

            val matchesQuery = if (query.isBlank()) true else {
                ch.name.contains(query, ignoreCase = true) ||
                        ch.id.contains(query) ||
                        ch.category.contains(query, ignoreCase = true)
            }
            matchesCategory && matchesQuery
        }
    }

    fun getLiveEvents(query: String = ""): List<LiveEvent> {
        if (query.isBlank()) return liveEvents
        return liveEvents.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.genreName.contains(query, ignoreCase = true)
        }
    }

    fun getAvailableCategories(tab: TabFilter): List<String> {
        val base = when (tab) {
            TabFilter.PORTUGAL -> precomputedPtCategories
            TabFilter.ALL -> precomputedAllCategories
            TabFilter.TIMSTREAMS -> listOf("Todos", "Desporto", "Filmes", "Infantil")
            TabFilter.LIVE_GAMES -> listOf("Todos", "Futebol", "Motores", "Outros")
            TabFilter.FAVORITES -> {
                val cats = precomputedFavChannels.map { it.category }.distinct().sorted()
                listOf("Todos") + cats
            }
        }
        return if (tab == TabFilter.PORTUGAL || tab == TabFilter.ALL) {
            if (base.contains("⚡ TimStreams")) base else base + listOf("⚡ TimStreams")
        } else base
    }
}
