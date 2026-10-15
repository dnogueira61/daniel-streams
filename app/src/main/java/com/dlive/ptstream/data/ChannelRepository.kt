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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

enum class TabFilter {
    PORTUGAL,
    LIVE_GAMES,
    GAMING,
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
    private val PREF_AUTO_RESUME_LAST_CHANNEL = "pref_auto_resume_last_channel"
    private val PREF_LAST_WATCHED_CHANNEL_ID = "pref_last_watched_channel_id"
    private val PREF_RECENT_CHANNEL_IDS = "pref_recent_channel_ids"
    private val PREF_THEME_MODE = "pref_theme_mode"
    private val PREF_ACCENT_COLOR = "pref_accent_color"
    private val PREF_DEFAULT_TAB = "pref_default_tab"
    private val PREF_CHANNEL_VIEW_MODE = "pref_channel_view_mode"
    private val PREF_SHOW_CLOCK = "pref_show_clock"
    private val CACHE_FILE_NAME = "channels_cache.json"

    private var cachedChannels: List<Channel> = emptyList()
    private var liveEvents: List<LiveEvent> = emptyList()

    companion object {
        const val DEFAULT_TIMST_BASE_URL = "https://timst.top"
        const val FALLBACK_TIMST_BASE_URL = "https://tim-streams.com"

        @Volatile
        private var sharedLiveEvents: List<LiveEvent> = emptyList()
    }

    @Volatile
    private var precomputedPtChannels: List<Channel> = emptyList()
    @Volatile
    private var precomputedAllChannels: List<Channel> = emptyList()
    @Volatile
    private var precomputedFavChannels: List<Channel> = emptyList()
    @Volatile
    private var precomputedGamingChannels: List<Channel> = emptyList()
    @Volatile
    private var precomputedPtCategories: List<String> = listOf("Todos")
    @Volatile
    private var precomputedAllCategories: List<String> = listOf("Todos")

    val channelsVersion = androidx.compose.runtime.mutableIntStateOf(0)

    init {
        if (!prefs.getBoolean("pip_default_reset_v206", false)) {
            prefs.edit()
                .putBoolean(PREF_AUTO_PIP_ON_BACK, false)
                .putBoolean("pip_default_reset_v206", true)
                .apply()
        }
        loadChannels()
    }

    fun rebuildPrecomputedLists() {
        val hiddenIds = getHiddenChannelIds()
        val pt = ArrayList<Channel>()
        val all = ArrayList<Channel>()
        val fav = ArrayList<Channel>()
        val gaming = ArrayList<Channel>()

        for (ch in cachedChannels) {
            if (hiddenIds.contains(ch.id)) continue
            // Exclui canais portugueses do separador TODOS (Mundo), pois já têm separador próprio
            if (!ch.isPortuguese) all.add(ch)
            if (ch.isPortuguese) pt.add(ch)
            if (ch.isFavorite) fav.add(ch)
            if (ch.category.equals("Gaming", ignoreCase = true) || ch.category.contains("Gaming", ignoreCase = true)) {
                gaming.add(ch)
            }
        }

        // Ordenar canais PT: primeiro TODOS os Sport TV (1..6..+), a seguir TODOS os DAZN (1..6), depois os restantes
        val sportTv = pt.filter { it.name.contains("Sport TV", ignoreCase = true) }
            .sortedBy { getSportTvOrder(it.name) }
        val dazn = pt.filter { it.name.contains("DAZN", ignoreCase = true) || it.name.contains("Eleven", ignoreCase = true) }
            .sortedBy { getDaznOrder(it.name) }
        val otherPt = pt.filter { !it.name.contains("Sport TV", ignoreCase = true) && !it.name.contains("DAZN", ignoreCase = true) && !it.name.contains("Eleven", ignoreCase = true) }

        val defaultPt = sportTv + dazn + otherPt

        val customOrder = getCustomChannelOrder()
        val customOrderMap = customOrder.mapIndexed { index, id -> id to index }.toMap()

        precomputedPtChannels = if (customOrderMap.isNotEmpty()) {
            defaultPt.sortedWith(compareBy { ch ->
                customOrderMap[ch.id] ?: (10000 + defaultPt.indexOf(ch))
            })
        } else {
            defaultPt
        }

        precomputedAllChannels = all
        precomputedFavChannels = fav
        precomputedGamingChannels = gaming

        val preferredPtCats = listOf("Generalistas", "Desporto")
        val availablePtCats = pt.map { it.category }.distinct().filter { !it.contains("Filme", ignoreCase = true) }
        val orderedPtCats = preferredPtCats.filter { availablePtCats.contains(it) } + availablePtCats.filter { !preferredPtCats.contains(it) }.sorted()
        precomputedPtCategories = listOf("Todos") + orderedPtCats

        val allCats = all.map { it.category }.distinct().filter { !it.contains("Filme", ignoreCase = true) && !it.equals("Generalistas", ignoreCase = true) && !it.equals("Gaming", ignoreCase = true) }.sorted()
        precomputedAllCategories = listOf("Todos", "Desporto") + (allCats.filter { it != "Desporto" })

        channelsVersion.intValue++
    }

    private val PREF_CUSTOM_CHANNEL_ORDER = "custom_channel_order_ids"

    fun getCustomChannelOrder(): List<String> {
        val raw = prefs.getString(PREF_CUSTOM_CHANNEL_ORDER, null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun setCustomChannelOrder(orderedIds: List<String>) {
        prefs.edit().putString(PREF_CUSTOM_CHANNEL_ORDER, orderedIds.joinToString(",")).apply()
        rebuildPrecomputedLists()
    }

    fun resetCustomChannelOrder() {
        prefs.edit().remove(PREF_CUSTOM_CHANNEL_ORDER).apply()
        rebuildPrecomputedLists()
    }

    fun moveChannel(fromChannelId: String, toChannelId: String) {
        val currentPt = precomputedPtChannels.toMutableList()
        val fromIdx = currentPt.indexOfFirst { it.id == fromChannelId }
        val toIdx = currentPt.indexOfFirst { it.id == toChannelId }
        if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
            val item = currentPt.removeAt(fromIdx)
            currentPt.add(toIdx, item)
            setCustomChannelOrder(currentPt.map { it.id })
        }
    }

    fun moveChannelByOffset(channelId: String, offset: Int) {
        val currentPt = precomputedPtChannels.toMutableList()
        val fromIdx = currentPt.indexOfFirst { it.id == channelId }
        if (fromIdx != -1) {
            val toIdx = (fromIdx + offset).coerceIn(0, currentPt.size - 1)
            if (fromIdx != toIdx) {
                val item = currentPt.removeAt(fromIdx)
                currentPt.add(toIdx, item)
                setCustomChannelOrder(currentPt.map { it.id })
            }
        }
    }

    fun moveChannelToPosition(channelId: String, targetPosition: Int) {
        val currentPt = precomputedPtChannels.toMutableList()
        val fromIdx = currentPt.indexOfFirst { it.id == channelId }
        if (fromIdx != -1) {
            val toIdx = targetPosition.coerceIn(0, currentPt.size - 1)
            if (fromIdx != toIdx) {
                val item = currentPt.removeAt(fromIdx)
                currentPt.add(toIdx, item)
                setCustomChannelOrder(currentPt.map { it.id })
            }
        }
    }

    fun getSportTvOrder(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("sport tv 1") || lower.contains("sport tv1") -> 1
            lower.contains("sport tv 2") || lower.contains("sport tv2") -> 2
            lower.contains("sport tv 3") || lower.contains("sport tv3") -> 3
            lower.contains("sport tv 4") || lower.contains("sport tv4") -> 4
            lower.contains("sport tv 5") || lower.contains("sport tv5") -> 5
            lower.contains("sport tv 6") || lower.contains("sport tv6") -> 6
            lower.contains("sport tv +") || lower.contains("sport tv mais") || lower.contains("sport tv+") -> 7
            else -> 8
        }
    }

    fun getDaznOrder(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("dazn 1") || lower.contains("dazn1") || lower.contains("eleven 1") || lower.contains("eleven1") -> 1
            lower.contains("dazn 2") || lower.contains("dazn2") || lower.contains("eleven 2") || lower.contains("eleven2") -> 2
            lower.contains("dazn 3") || lower.contains("dazn3") || lower.contains("eleven 3") || lower.contains("eleven3") -> 3
            lower.contains("dazn 4") || lower.contains("dazn4") || lower.contains("eleven 4") || lower.contains("eleven4") -> 4
            lower.contains("dazn 5") || lower.contains("dazn5") || lower.contains("eleven 5") || lower.contains("eleven5") -> 5
            lower.contains("dazn 6") || lower.contains("dazn6") || lower.contains("eleven 6") || lower.contains("eleven6") -> 6
            else -> 7
        }
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

    fun isTv(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return context.packageManager.hasSystemFeature("android.software.leanback") ||
                context.packageManager.hasSystemFeature("android.hardware.type.television") ||
                uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isAutoPipOnBack(): Boolean {
        if (isTv()) return false
        return prefs.getBoolean(PREF_AUTO_PIP_ON_BACK, false)
    }
    fun setAutoPipOnBack(enabled: Boolean) = prefs.edit().putBoolean(PREF_AUTO_PIP_ON_BACK, enabled).apply()

    fun getChannelViewMode(): String = prefs.getString(PREF_CHANNEL_VIEW_MODE, "AUTO") ?: "AUTO"
    fun setChannelViewMode(mode: String) = prefs.edit().putString(PREF_CHANNEL_VIEW_MODE, mode).apply()

    fun isShowClockEnabled(): Boolean {
        val defaultVal = isTv()
        return prefs.getBoolean(PREF_SHOW_CLOCK, defaultVal)
    }
    fun setShowClockEnabled(enabled: Boolean) = prefs.edit().putBoolean(PREF_SHOW_CLOCK, enabled).apply()

    fun isAutoUnmuteEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_UNMUTE, true)
    fun setAutoUnmuteEnabled(enabled: Boolean) = prefs.edit().putBoolean(PREF_AUTO_UNMUTE, enabled).apply()

    fun getDefaultServer(): String = prefs.getString(PREF_DEFAULT_SERVER, "stream") ?: "stream"
    fun setDefaultServer(server: String) = prefs.edit().putString(PREF_DEFAULT_SERVER, server).apply()

    fun isAutoResumeEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_RESUME_LAST_CHANNEL, false)
    fun setAutoResumeEnabled(enabled: Boolean) = prefs.edit().putBoolean(PREF_AUTO_RESUME_LAST_CHANNEL, enabled).apply()

    fun getRecentChannelIds(): List<String> {
        val raw = prefs.getString(PREF_RECENT_CHANNEL_IDS, null) ?: return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun recordChannelWatched(channelId: String) {
        if (channelId.isBlank() || channelId.startsWith("event_")) return
        prefs.edit().putString(PREF_LAST_WATCHED_CHANNEL_ID, channelId).apply()
        val current = getRecentChannelIds().toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        val trimmed = current.take(30)
        prefs.edit().putString(PREF_RECENT_CHANNEL_IDS, trimmed.joinToString(",")).apply()
        rebuildPrecomputedLists()
    }

    fun getLastWatchedChannelId(): String? = prefs.getString(PREF_LAST_WATCHED_CHANNEL_ID, null)
    fun setLastWatchedChannelId(channelId: String) {
        recordChannelWatched(channelId)
    }

    fun getBaseUrl(): String = prefs.getString(PREF_BASE_URL, "https://dlive.sx") ?: "https://dlive.sx"
    fun setBaseUrl(newUrl: String) {
        val clean = if (newUrl.endsWith("/")) newUrl.dropLast(1) else newUrl
        prefs.edit().putString(PREF_BASE_URL, clean).apply()
    }

    fun getTimstBaseUrl(): String {
        val saved = prefs.getString(PREF_TIMST_BASE_URL, null)?.trim()
        if (saved.isNullOrBlank() || saved.contains("grandemx.org") || saved.contains("exmxbxe.cfd") || saved.contains("timst.cfd") || saved.contains("tim-streams.com")) {
            setTimstBaseUrl(DEFAULT_TIMST_BASE_URL)
            return DEFAULT_TIMST_BASE_URL
        }
        return saved
    }
    fun getTimstFallbackBaseUrl(): String = FALLBACK_TIMST_BASE_URL

    fun setTimstBaseUrl(newUrl: String) {
        val clean = if (newUrl.endsWith("/")) newUrl.dropLast(1) else newUrl
        prefs.edit().putString(PREF_TIMST_BASE_URL, clean).apply()
    }

    fun getThemeMode(): String = prefs.getString(PREF_THEME_MODE, "DARK") ?: "DARK"
    fun setThemeMode(mode: String) = prefs.edit().putString(PREF_THEME_MODE, mode).apply()

    fun getAccentColor(): String = prefs.getString(PREF_ACCENT_COLOR, "RED") ?: "RED"
    fun setAccentColor(color: String) = prefs.edit().putString(PREF_ACCENT_COLOR, color).apply()

    fun getDefaultTab(): String = prefs.getString(PREF_DEFAULT_TAB, "PORTUGAL") ?: "PORTUGAL"
    fun setDefaultTab(tab: String) = prefs.edit().putString(PREF_DEFAULT_TAB, tab).apply()

    val epgRepository = EpgRepository(context)

    private fun loadChannels() {
        val favIds = getFavoriteIds()
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)

        try {
            val appVersionCode = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
                }
            } catch (_: Exception) { 0L }

            val lastLoadedVersion = prefs.getLong("last_loaded_channels_version", 0L)
            if (lastLoadedVersion < appVersionCode) {
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                prefs.edit().putLong("last_loaded_channels_version", appVersionCode).apply()
            }

            val reader = if (cacheFile.exists() && cacheFile.length() > 100) {
                InputStreamReader(cacheFile.inputStream(), "UTF-8")
            } else {
                InputStreamReader(context.assets.open("channels.json"), "UTF-8")
            }
            reader.use { r ->
                val itemType = object : TypeToken<List<Channel>>() {}.type
                val list: List<Channel> = Gson().fromJson(r, itemType) ?: emptyList()

                cachedChannels = list.map { ch ->
                    val isPt = ch.isPortuguese
                    val logo = ch.logoUrl ?: ChannelLogoHelper.getDefaultOnlineLogo(ch.name)
                    ch.copy(
                        isPt = isPt,
                        country = if (isPt) "PT" else ch.country,
                        category = ch.category,
                        isFavorite = favIds.contains(ch.id),
                        logoUrl = logo,
                        status = ch.safeStatus,
                        backupStreamUrl = normalizeStreamUrl(ch.backupStreamUrl),
                        backupStreamUrl2 = normalizeStreamUrl(ch.backupStreamUrl2)
                    )
                }
                rebuildPrecomputedLists()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                context.assets.open("channels.json").use { inputStream ->
                    val r = InputStreamReader(inputStream, "UTF-8")
                    val itemType = object : TypeToken<List<Channel>>() {}.type
                    val list: List<Channel> = Gson().fromJson(r, itemType) ?: emptyList()
                    cachedChannels = list.map { ch ->
                        val isPt = ch.isPortuguese
                        ch.copy(
                            isPt = isPt,
                            country = if (isPt) "PT" else ch.country,
                            category = ch.category,
                            isFavorite = favIds.contains(ch.id),
                            logoUrl = ch.logoUrl ?: ChannelLogoHelper.getDefaultOnlineLogo(ch.name),
                            status = ch.safeStatus,
                            backupStreamUrl = normalizeStreamUrl(ch.backupStreamUrl),
                            backupStreamUrl2 = normalizeStreamUrl(ch.backupStreamUrl2)
                        )
                    }
                    rebuildPrecomputedLists()
                }
            } catch (_: Exception) {
                cachedChannels = emptyList()
                rebuildPrecomputedLists()
            }
        }
    }

    private fun normalizeStreamUrl(url: String?): String? {
        if (url == null) return null
        var u = url.trim()
        if (u.contains("grandemx.org")) {
            u = u.replace("https://grandemx.org", "https://ehgembxe.cfd").replace("http://grandemx.org", "https://ehgembxe.cfd")
        }
        if (u.contains("exmxbxe.cfd")) {
            u = u.replace("https://exmxbxe.cfd", "https://ehgembxe.cfd").replace("http://exmxbxe.cfd", "https://ehgembxe.cfd")
        }
        return u
    }

    /**
     * Sincroniza canais online a partir do repositório GitHub e deteta domínios
     */
    fun syncChannelsFromWeb(scope: CoroutineScope, force: Boolean = false, onFinished: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val lastSync = prefs.getLong("last_channels_sync_time", 0L)
            if (!force && System.currentTimeMillis() - lastSync < 3 * 3600 * 1000L) {
                withContext(Dispatchers.Main) { onFinished?.invoke(true) }
                return@launch
            }
            var success = false
            try {
                autoDetectWorkingDomains()
                val remoteUrl = "https://raw.githubusercontent.com/dnogueira61/daniel-streams/main/app/src/main/assets/channels.json"
                val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val itemType = object : TypeToken<List<Channel>>() {}.type
                    val list: List<Channel> = Gson().fromJson(jsonStr, itemType) ?: emptyList()
                    if (list.isNotEmpty()) {
                        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                        cacheFile.writeText(jsonStr)
                    }
                }
                loadChannels()
                success = true
                prefs.edit().putLong("last_channels_sync_time", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                onFinished?.invoke(success)
            }
        }
    }

    fun autoDetectWorkingDomains() {
        val mirrors = listOf(
            getBaseUrl(),
            "https://dlhd.pk",
            "https://dlhd.st",
            "https://dlstreams.st",
            "https://dlhd.dad",
            "https://dlive.sx"
        )
        for (m in mirrors) {
            try {
                val u = URL(m)
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "HEAD"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank() && (location.startsWith("http://") || location.startsWith("https://"))) {
                    setBaseUrl(location)
                    break
                }
                if (code in 200..399) {
                    setBaseUrl(m)
                    break
                }
            } catch (_: Exception) {}
        }

        val timstMirrors = listOf(
            getTimstBaseUrl(),
            DEFAULT_TIMST_BASE_URL,
            "https://timst.cfd"
        )
        for (tm in timstMirrors) {
            try {
                val u = URL(tm)
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "HEAD"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank() && (location.startsWith("http://") || location.startsWith("https://")) && !location.contains("tim-streams.com")) {
                    setTimstBaseUrl(location)
                    break
                }
                if (code in 200..399 && !tm.contains("tim-streams.com")) {
                    setTimstBaseUrl(tm)
                    break
                }
            } catch (_: Exception) {}
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
            val apiMirrors = listOf(
                "${getTimstBaseUrl()}/api/live-upcoming",
                "$DEFAULT_TIMST_BASE_URL/api/live-upcoming",
                "$FALLBACK_TIMST_BASE_URL/api/live-upcoming"
            )
            for (apiUrl in apiMirrors) {
                try {
                    val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
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
                            val timeStr = ev.get("time")?.asString ?: ""
                            val rawTs = ev.get("timestamp")?.asLong ?: 0L
                            val startMs = if (rawTs > 0L) (if (rawTs in 1..99999999999L) rawTs * 1000L else rawTs) else parseTimeToTimestamp(timeStr)
                            val timeDisplay = if (startMs > 0L) formatMatchTime(startMs) else formatEventTime(timeStr)
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
                                    time = timeDisplay,
                                    viewers = viewers,
                                    streams = streamsList,
                                    isSoccer = isSoccer,
                                    startTimestamp = startMs
                                )
                            )
                        }
                    }
                    if (eventsList.isNotEmpty()) {
                        break
                    }
                }
            } catch (e: Exception) {
                // Try next mirror
            }
        }


            // Also fetch from Streamed (streamed.pk / strmd.link active mirror)
            try {
                val streamedConn = (URL("https://streamed.pk/api/matches/all-today").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    setRequestProperty("Referer", "https://streamed.pk/")
                }
                if (streamedConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val strmJson = streamedConn.inputStream.bufferedReader().use { it.readText() }
                    val strmArray = JsonParser.parseString(strmJson).asJsonArray
                    for (item in strmArray) {
                        val obj = item.asJsonObject
                        val id = obj.get("id")?.asString ?: continue
                        val title = obj.get("title")?.asString ?: continue
                        val category = obj.get("category")?.asString ?: "sports"
                        val poster = obj.get("poster")?.asString?.let {
                            if (it.startsWith("/")) "https://streamed.pk$it" else it
                        }
                        val sourcesArr = obj.getAsJsonArray("sources")

                        val streamedStreams = mutableListOf<EventStream>()
                        if (sourcesArr != null) {
                            for (s in sourcesArr) {
                                val sObj = s.asJsonObject
                                val srcName = sObj.get("source")?.asString ?: "server"
                                val srcId = sObj.get("id")?.asString ?: continue
                                val embedUrl = "https://embed.st/embed/$srcName/$srcId/1"
                                streamedStreams.add(EventStream("Streamed (${srcName.uppercase()})", embedUrl))
                            }
                        }
                        if (streamedStreams.isEmpty()) continue

                        val genreName = when (category.lowercase()) {
                            "football", "soccer" -> "⚽ Futebol"
                            "basketball" -> "🏀 Basquetebol"
                            "hockey" -> "🏒 Hóquei"
                            "baseball" -> "⚾ Basebol"
                            "tennis" -> "🎾 Ténis"
                            "motor-sports", "motorsports" -> "🏎️ Motores"
                            "fighting", "mma", "boxing" -> "🥊 Desportos Combate"
                            "american-football" -> "🏈 Futebol Americano"
                            else -> "🏆 Desporto"
                        }
                        val isSoccer = category.lowercase() in listOf("football", "soccer")

                        // Match against existing events (e.g. from TimStreams) to merge alternative streams
                        val existingIndex = eventsList.indexOfFirst {
                            val n1 = it.name.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs").replace(".", "")
                            val n2 = title.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs").replace(".", "")
                            n1.contains(n2) || n2.contains(n1)
                        }

                        val dateRaw = obj.get("date")?.asLong ?: 0L
                        val startMs = if (dateRaw in 1..99999999999L) dateRaw * 1000L else dateRaw
                        val timeDisplay = formatMatchTime(startMs)

                        if (existingIndex >= 0) {
                            val existing = eventsList[existingIndex]
                            val combined = existing.streams + streamedStreams
                            val bestTs = if (existing.startTimestamp > 0L) existing.startTimestamp else startMs
                            val bestTime = if (existing.time.isNotBlank() && existing.time != "Hoje" && existing.time != "24/7") existing.time else timeDisplay
                            eventsList[existingIndex] = existing.copy(
                                streams = combined,
                                startTimestamp = bestTs,
                                time = bestTime
                            )
                        } else {
                            eventsList.add(
                                LiveEvent(
                                    id = "strmd-$id",
                                    name = title,
                                    logo = poster,
                                    genre = if (isSoccer) 1 else 99,
                                    genreName = genreName,
                                    time = timeDisplay,
                                    viewers = 250,
                                    streams = streamedStreams,
                                    isSoccer = isSoccer,
                                    startTimestamp = startMs
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Also fetch from StreamFree (strmfree.st)
            try {
                val sfConn = (URL("https://strmfree.st/api/v1/streams").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (sfConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val sfJson = sfConn.inputStream.bufferedReader().use { it.readText() }
                    val sfRoot = JsonParser.parseString(sfJson).asJsonObject
                    val sfStreams = sfRoot.getAsJsonArray("streams")
                    if (sfStreams != null) {
                        for (item in sfStreams) {
                            val obj = item.asJsonObject
                            val id = obj.get("id")?.asString ?: continue
                            val name = obj.get("name")?.asString ?: continue
                            val category = obj.get("category")?.asString ?: "soccer"
                            val league = obj.get("league")?.asString ?: ""
                            val thumb = obj.get("thumbnail_url")?.asString
                            val sourcesArr = obj.getAsJsonArray("sources")
                            val streamList = mutableListOf<EventStream>()
                            if (sourcesArr != null) {
                                for (i in 0 until sourcesArr.size()) {
                                    val sUrl = sourcesArr[i].asString
                                    val label = if (i == 0) "StreamFree (1080p)" else "StreamFree (Backup ${i + 1})"
                                    streamList.add(EventStream(label, sUrl))
                                }
                            }
                            if (streamList.isEmpty()) continue

                            val isSoccer = category.equals("soccer", ignoreCase = true) || category.equals("football", ignoreCase = true)
                            val genreName = when (category.lowercase()) {
                                "soccer" -> "⚽ Futebol"
                                "basketball" -> "🏀 Basquetebol"
                                "hockey" -> "🏒 Hóquei"
                                "combat" -> "🥊 Desportos Combate"
                                "racing" -> "🏎️ Motores"
                                "tennis" -> "🎾 Ténis"
                                else -> if (league.isNotBlank()) "🏆 $league" else "🏆 Desporto"
                            }

                            val dateRaw = obj.get("match_timestamp")?.asLong ?: 0L
                            val startMs = if (dateRaw in 1..99999999999L) dateRaw * 1000L else dateRaw
                            val timeDisplay = formatMatchTime(startMs)

                            val existingIndex = eventsList.indexOfFirst {
                                val n1 = it.name.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs").replace(".", "")
                                val n2 = name.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs").replace(".", "")
                                n1.contains(n2) || n2.contains(n1)
                            }

                            if (existingIndex >= 0) {
                                val existing = eventsList[existingIndex]
                                val bestTs = if (existing.startTimestamp > 0L) existing.startTimestamp else startMs
                                val bestTime = if (existing.time.isNotBlank() && existing.time != "Hoje" && existing.time != "24/7") existing.time else timeDisplay
                                eventsList[existingIndex] = existing.copy(
                                    streams = existing.streams + streamList,
                                    startTimestamp = bestTs,
                                    time = bestTime
                                )
                            } else {
                                eventsList.add(
                                    LiveEvent(
                                        id = "sf-$id",
                                        name = name,
                                        logo = thumb,
                                        genre = if (isSoccer) 1 else 99,
                                        genreName = genreName,
                                        time = timeDisplay,
                                        viewers = 350,
                                        streams = streamList,
                                        isSoccer = isSoccer,
                                        startTimestamp = startMs
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Also fetch from PPV.ST (api.ppv.st / embedindia.st)
            try {
                val ppvConn = (URL("https://api.ppv.st/api/streams").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Referer", "https://ppv.st/")
                }
                if (ppvConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val ppvJson = ppvConn.inputStream.bufferedReader().use { it.readText() }
                    val ppvRoot = JsonParser.parseString(ppvJson).asJsonObject
                    val catArray = ppvRoot.getAsJsonArray("streams")
                    if (catArray != null) {
                        for (catItem in catArray) {
                            val catObj = catItem.asJsonObject
                            val catName = catObj.get("category")?.asString ?: "Desporto"
                            val streamsArr = catObj.getAsJsonArray("streams") ?: continue

                            for (sItem in streamsArr) {
                                val sObj = sItem.asJsonObject
                                val sName = sObj.get("name")?.asString ?: continue
                                val iframe = sObj.get("iframe")?.asString ?: continue
                                val tag = sObj.get("tag")?.asString ?: ""
                                val sourceTag = sObj.get("source_tag")?.asString ?: "Stream"
                                val poster = sObj.get("poster")?.asString
                                val id = sObj.get("id")?.asString ?: sName.hashCode().toString()
                                val dateRaw = sObj.get("starts_at")?.asLong ?: 0L
                                val startMs = if (dateRaw in 1..99999999999L) dateRaw * 1000L else if (dateRaw < 0L) 0L else dateRaw
                                val timeDisplay = formatMatchTime(startMs)

                                val isSoccer = catName.contains("football", ignoreCase = true) ||
                                        catName.contains("soccer", ignoreCase = true) ||
                                        tag.contains("league", ignoreCase = true) ||
                                        tag.contains("liga", ignoreCase = true) ||
                                        tag.contains("cup", ignoreCase = true)

                                val genreName = when {
                                    isSoccer -> "⚽ Futebol"
                                    catName.contains("basket", ignoreCase = true) -> "🏀 Basquetebol"
                                    catName.contains("combat", ignoreCase = true) || catName.contains("wrestling", ignoreCase = true) -> "🥊 Combate"
                                    catName.contains("motor", ignoreCase = true) -> "🏎️ Motores"
                                    catName.contains("hockey", ignoreCase = true) -> "🏒 Hóquei"
                                    catName.contains("baseball", ignoreCase = true) -> "⚾ Basebol"
                                    else -> "🏆 $catName"
                                }

                                val ppvStream = EventStream("PPV ($sourceTag)", iframe)

                                val existingIndex = eventsList.indexOfFirst {
                                    val n1 = it.name.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs").replace(".", "")
                                    val n2 = sName.lowercase().replace(" ", "").replace("@", "vs").replace("-", "vs").replace(".", "")
                                    n1.contains(n2) || n2.contains(n1)
                                }

                                if (existingIndex >= 0) {
                                    val existing = eventsList[existingIndex]
                                    val bestTs = if (existing.startTimestamp > 0L) existing.startTimestamp else startMs
                                    val bestTime = if (existing.time.isNotBlank() && existing.time != "Hoje" && existing.time != "24/7") existing.time else timeDisplay
                                    if (existing.streams.none { it.url == iframe }) {
                                        eventsList[existingIndex] = existing.copy(
                                            streams = existing.streams + listOf(ppvStream),
                                            startTimestamp = bestTs,
                                            time = bestTime
                                        )
                                    }
                                } else {
                                    eventsList.add(
                                        LiveEvent(
                                            id = "ppv-$id",
                                            name = sName,
                                            logo = poster,
                                            genre = if (isSoccer) 1 else 99,
                                            genreName = genreName,
                                            time = timeDisplay,
                                            viewers = 200,
                                            streams = listOf(ppvStream),
                                            isSoccer = isSoccer,
                                            startTimestamp = startMs
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fetch OndeBola highlights (Portuguese televised matches priority)
            val ondeBolaHighlights = try {
                SportsMatchHelper.fetchOndeBolaHighlights()
            } catch (e: Throwable) {
                emptyList()
            }

            val nowMs = System.currentTimeMillis()
            // Filter out matches that ended more than 10 hours ago
            val validEvents = eventsList.filter {
                if (it.startTimestamp > 0L) {
                    (nowMs - it.startTimestamp) < (10 * 3600 * 1000L)
                } else {
                    true
                }
            }.map { ev ->
                val hl = SportsMatchHelper.matchWithOndeBola(ev.name, ondeBolaHighlights)
                if (hl != null) {
                    ev.copy(isHighlight = true, highlightPriority = hl.priority)
                } else {
                    ev
                }
            }

            // Ordenação:
            // 1. Destaques OndeBola Hoje (Futebol principal transmitido na TV portuguesa)
            // 2. Futebol (isSoccerEvent) prioritário em relação a outros desportos
            // 3. Jogos com hora agendada ordenados cronologicamente
            // 4. Streams 24/7 e outras modalidades no fim
            val sortedEvents = validEvents.sortedWith { a, b ->
                val aIsSoccer = SportsMatchHelper.isSoccerEvent(a)
                val bIsSoccer = SportsMatchHelper.isSoccerEvent(b)

                when {
                    // Prioridade 1: Destaques OndeBola primeiro (ordem do OndeBola)
                    a.isHighlight && b.isHighlight -> a.highlightPriority.compareTo(b.highlightPriority)
                    a.isHighlight -> -1
                    b.isHighlight -> 1

                    // Prioridade 2: Futebol primeiro, outras modalidades depois
                    aIsSoccer != bIsSoccer -> if (aIsSoccer) -1 else 1

                    // Prioridade 3: Hora do jogo
                    a.startTimestamp == 0L && b.startTimestamp == 0L -> (-a.viewers).compareTo(-b.viewers)
                    a.startTimestamp == 0L -> 1
                    b.startTimestamp == 0L -> -1
                    a.startTimestamp != b.startTimestamp -> a.startTimestamp.compareTo(b.startTimestamp)
                    else -> (-a.viewers).compareTo(-b.viewers)
                }
            }

            val translatedEvents = sortedEvents.map {
                it.copy(name = SportsMatchHelper.translateToPt(it.name))
            }

            liveEvents = translatedEvents
            sharedLiveEvents = translatedEvents

            withContext(Dispatchers.Main) {
                onResult(translatedEvents)
            }
        }
    }

    fun formatMatchTime(startMs: Long, isLive: Boolean = false): String {
        if (startMs <= 0L) return "24/7"
        return try {
            val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
            val now = System.currentTimeMillis()
            val eventDate = Date(startMs)

            val lisbonDateFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = lisbonTz }
            val eventDay = lisbonDateFmt.format(eventDate)
            val today = lisbonDateFmt.format(Date(now))
            val tomorrow = lisbonDateFmt.format(Date(now + 86400000L))

            val lisbonTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = lisbonTz }
            val timeFormatted = lisbonTimeFmt.format(eventDate)

            val isRunning = isLive || (now in startMs..(startMs + (2.5 * 3600 * 1000).toLong()))

            when (eventDay) {
                today -> if (isRunning) "🔴 $timeFormatted" else timeFormatted
                tomorrow -> "Amanhã $timeFormatted"
                else -> {
                    val displayDateFmt = SimpleDateFormat("dd/MM", Locale.getDefault()).apply { timeZone = lisbonTz }
                    "${displayDateFmt.format(eventDate)} $timeFormatted"
                }
            }
        } catch (_: Exception) {
            if (isLive) "🔴 Em Direto" else "Hoje"
        }
    }

    private fun parseTimeToTimestamp(rawTime: String): Long {
        if (rawTime.isBlank()) return 0L
        return try {
            val utcTz = TimeZone.getTimeZone("UTC")
            val cleanStr = rawTime.trim().replace("Z", "")
            val parsedDate: Date? = if (cleanStr.contains("T")) {
                val pattern = if (cleanStr.length >= 19) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd'T'HH:mm"
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = utcTz }.parse(cleanStr)
            } else if (cleanStr.contains(" ")) {
                val pattern = if (cleanStr.length >= 19) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = utcTz }.parse(cleanStr)
            } else if (cleanStr.contains(":")) {
                val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
                val cal = Calendar.getInstance(lisbonTz)
                val parts = cleanStr.split(":")
                cal.set(Calendar.HOUR_OF_DAY, parts[0].trim().toInt())
                cal.set(Calendar.MINUTE, parts[1].trim().toInt())
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.time
            } else {
                null
            }
            parsedDate?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatEventTime(rawTime: String): String {
        if (rawTime.isBlank()) return ""
        return try {
            val lisbonTz = TimeZone.getTimeZone("Europe/Lisbon")
            val utcTz = TimeZone.getTimeZone("UTC")

            val cleanStr = rawTime.trim().replace("Z", "")
            val parsedDate: Date? = if (cleanStr.contains("T")) {
                val pattern = if (cleanStr.length >= 19) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd'T'HH:mm"
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = utcTz }.parse(cleanStr)
            } else if (cleanStr.contains(" ")) {
                val pattern = if (cleanStr.length >= 19) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = utcTz }.parse(cleanStr)
            } else {
                null
            }

            if (parsedDate != null) {
                val lisbonDateFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = lisbonTz }
                val eventDay = lisbonDateFmt.format(parsedDate)
                val today = lisbonDateFmt.format(Date())
                val tomorrow = lisbonDateFmt.format(Date(System.currentTimeMillis() + 86400000L))

                val lisbonTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = lisbonTz }
                val timeFormatted = lisbonTimeFmt.format(parsedDate)

                when (eventDay) {
                    today -> "Hoje $timeFormatted"
                    tomorrow -> "Amanhã $timeFormatted"
                    else -> {
                        val displayDateFmt = SimpleDateFormat("dd/MM", Locale.getDefault()).apply { timeZone = lisbonTz }
                        "${displayDateFmt.format(parsedDate)} $timeFormatted"
                    }
                }
            } else {
                rawTime
            }
        } catch (_: Exception) {
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
                TabFilter.GAMING -> precomputedGamingChannels
                TabFilter.LIVE_GAMES -> getTopFootballChannels()
                TabFilter.TIMSTREAMS -> {
                    val hidden = getHiddenChannelIds()
                    cachedChannels.filter { !hidden.contains(it.id) && (!it.backupStreamUrl.isNullOrBlank() || it.category == "TimStreams" || it.id.startsWith("timst-")) }
                }
            }
        }

        val baseList = when (tab) {
            TabFilter.PORTUGAL -> if (includeHidden) {
                val list = cachedChannels.filter { it.isPortuguese }
                val sportTv = list.filter { it.name.contains("Sport TV", ignoreCase = true) }.sortedBy { getSportTvOrder(it.name) }
                val dazn = list.filter { it.name.contains("DAZN", ignoreCase = true) || it.name.contains("Eleven", ignoreCase = true) }.sortedBy { getDaznOrder(it.name) }
                val others = list.filter { !it.name.contains("Sport TV", ignoreCase = true) && !it.name.contains("DAZN", ignoreCase = true) && !it.name.contains("Eleven", ignoreCase = true) }
                sportTv + dazn + others
            } else precomputedPtChannels
            TabFilter.LIVE_GAMES -> getTopFootballChannels(query)
            TabFilter.GAMING -> if (includeHidden) cachedChannels.filter { it.category.contains("Gaming", ignoreCase = true) } else precomputedGamingChannels
            TabFilter.TIMSTREAMS -> {
                val list = cachedChannels.filter { !it.backupStreamUrl.isNullOrBlank() || it.category == "TimStreams" || it.id.startsWith("timst-") }
                if (includeHidden) list else {
                    val hidden = getHiddenChannelIds()
                    list.filter { !hidden.contains(it.id) }
                }
            }
            TabFilter.FAVORITES -> if (includeHidden) cachedChannels.filter { it.isFavorite } else precomputedFavChannels
            TabFilter.ALL -> if (includeHidden) cachedChannels.filter { !it.isPortuguese } else precomputedAllChannels
        }

        return baseList.filter { ch ->
            val matchesCategory = if (categoryFilter == "Todos") true
            else if (categoryFilter == "⚡ TimStreams") !ch.backupStreamUrl.isNullOrBlank() || ch.category == "TimStreams" || ch.id.startsWith("timst-")
            else if (categoryFilter.equals("Desporto", ignoreCase = true) && tab == TabFilter.ALL) {
                ch.category.equals("Desporto", ignoreCase = true) && !ch.isPortuguese
            }
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
        val list = if (liveEvents.isNotEmpty()) liveEvents else sharedLiveEvents
        if (query.isBlank()) return list
        return list.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.genreName.contains(query, ignoreCase = true)
        }
    }

    fun getAvailableCategories(tab: TabFilter): List<String> {
        val base = when (tab) {
            TabFilter.PORTUGAL -> precomputedPtCategories.filter { !it.contains("Filme", ignoreCase = true) }
            TabFilter.ALL -> precomputedAllCategories.filter { !it.contains("Filme", ignoreCase = true) }
            TabFilter.GAMING -> listOf("Todos")
            TabFilter.TIMSTREAMS -> listOf("Todos", "Desporto", "Infantil")
            TabFilter.LIVE_GAMES -> listOf("Todos", "Futebol", "Motores", "Outros")
            TabFilter.FAVORITES -> {
                val cats = precomputedFavChannels.map { it.category }.distinct().filter { !it.contains("Filme", ignoreCase = true) }.sorted()
                listOf("Todos") + cats
            }
        }
        return if (tab == TabFilter.PORTUGAL || tab == TabFilter.ALL) {
            if (base.contains("⚡ TimStreams")) base else base + listOf("⚡ TimStreams")
        } else base
    }

    fun getChannelById(channelId: String): Channel? {
        if (channelId.isBlank()) return null
        return cachedChannels.firstOrNull { it.id == channelId }
    }

    fun getAllCachedChannels(): List<Channel> = cachedChannels
}

