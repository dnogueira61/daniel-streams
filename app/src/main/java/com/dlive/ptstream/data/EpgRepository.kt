package com.dlive.ptstream.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class EpgProgram(
    val channelId: String = "",
    val title: String = "",
    val startEpoch: Long = 0L,
    val stopEpoch: Long = 0L,
    val episode: String? = null
) {
    val timeRange: String
        get() {
            // Formata sempre no fuso horário oficial de Portugal Continental (Europe/Lisbon: WET/WEST),
            // garantindo 100% de precisão mesmo se a Google TV ou Tablet estiverem configurados com UTC ou fuso incorreto.
            val sdf = SimpleDateFormat("HH'h'mm", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("Europe/Lisbon")
            }
            return "${sdf.format(Date(startEpoch))} - ${sdf.format(Date(stopEpoch))}"
        }

    fun getProgress(now: Long = System.currentTimeMillis()): Float {
        if (now <= startEpoch) return 0f
        if (now >= stopEpoch) return 1f
        val total = (stopEpoch - startEpoch).toFloat()
        if (total <= 0f) return 0f
        return ((now - startEpoch) / total).coerceIn(0f, 1f)
    }
}

class EpgRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("dlive_epg_prefs", Context.MODE_PRIVATE)
    private val EPG_CACHE_FILE = "epg_cache.json"

    // Primary Feeds
    private val EPG_M3UPT_URL = "https://raw.githubusercontent.com/LITUATUI/M3UPT/main/EPG/epg-m3upt.xml.gz"
    private val EPG_SPORTS_UK_URL = "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"
    private val EPG_SPORTS_ES_URL = "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz"

    // Fallbacks
    private val EPG_PT_FALLBACK_URL = "https://epgshare01.online/epgshare01/epg_ripper_PT1.xml.gz"
    private val EPG_STRONG8K_URL = "https://JohnPulse.github.io/iptv-epg/epg-strong8k.xml.gz"

    // Key: Normalized channel alias / EPG channel ID -> List of programmes
    private val programMap = mutableMapOf<String, MutableList<EpgProgram>>()

    val epgVersion = mutableIntStateOf(0)
    var isUpdating = false
        private set

    // Channel name keywords mapped to EPG Channel IDs (Portuguese, European Sports, International)
    private val channelToEpgIds = mapOf(
        // Portugal Generalistas & Notícias
        "rtp 1" to listOf("RTP1.pt", "RTP.1.HD.pt", "rtp1.pt"),
        "rtp 2" to listOf("RTP2.pt", "RTP.2.HD.pt", "rtp2.pt"),
        "sic" to listOf("SIC.pt", "SIC.HD.pt", "sic.pt"),
        "tvi" to listOf("TVI.pt", "TVI.HD.pt", "tvi.pt"),
        "sic notícias" to listOf("SICNoticias.pt", "SIC.Notícias.HD.pt", "sicnoticias.pt"),
        "rtp 3" to listOf("RTPNoticias.pt", "RTP.3.HD.pt", "rtp3.pt"),
        "cnn portugal" to listOf("CNNPortugal.pt", "CNN.pt", "cnnportugal.pt"),
        "porto canal" to listOf("PortoCanal.pt", "Porto.Canal.HD.pt", "porto.pt"),
        "cmtv" to listOf("CMTV.pt", "CMTV.HD.pt", "cmtv.pt"),
        "canal 11" to listOf("Canal11.pt", "canal11"),
        "v+ tvi" to listOf("VPlusTVI.pt", "V+.TVI.pt", "TVI.pt"),
        "rtp memória" to listOf("RTPMemoria.pt", "RTP.Memória.pt", "rtpmemoria.pt"),
        "rtp açores" to listOf("RTPAcores.pt", "RTP.Açores.pt"),
        "rtp madeira" to listOf("RTPMadeira.pt", "RTP.Madeira.pt", "rtpmadeira.pt"),
        "artv" to listOf("ARTV.pt"),

        // Desporto Portugal
        "sport tv 1" to listOf("SportTV1.pt", "SPORT.TV1.HD.pt"),
        "sport tv 2" to listOf("SportTV2.pt", "SPORT.TV2.HD.pt"),
        "sport tv 3" to listOf("SportTV3.pt", "SPORT.TV3.HD.pt"),
        "sport tv 4" to listOf("SportTV4.pt", "SPORT.TV4.HD.pt"),
        "sport tv 5" to listOf("SportTV5.pt", "SPORT.TV5.HD.pt"),
        "sport tv 6" to listOf("SportTV6.pt", "SPORT.TV6.HD.pt"),
        "sport tv 7" to listOf("SportTV7.pt", "SPORT.TV7.HD.pt"),
        "sport tv +" to listOf("SportTVPlus.pt"),
        "sport tv plus" to listOf("SportTVPlus.pt"),
        "dazn 1 portugal" to listOf("DAZN1.pt", "DAZN.1.pt", "ElevenSports1.pt"),
        "dazn 2 portugal" to listOf("DAZN2.pt", "DAZN.2.pt", "ElevenSports2.pt"),
        "dazn 3 portugal" to listOf("DAZN3.pt", "DAZN.3.pt", "ElevenSports3.pt"),
        "dazn 4 portugal" to listOf("DAZN4.pt", "DAZN.4.pt", "ElevenSports4.pt"),
        "dazn 5 portugal" to listOf("DAZN5.pt", "DAZN.5.pt", "ElevenSports5.pt"),
        "dazn 6" to listOf("DAZN6.pt", "DAZN.6.pt"),
        "benfica tv" to listOf("BenficaTV.pt", "BenficaTV1.pt", "BTV1.HD.pt"),
        "sporting tv" to listOf("SportingTV.pt", "Sporting.TV.HD.pt"),
        "a bola tv" to listOf("ABolaTV.pt", "A.Bola.TV.HD.pt", "abolatv.pt"),
        "eurosport 1 pt" to listOf("Eurosport1.pt", "Eurosport.1.HD.pt"),
        "eurosport 2 pt" to listOf("Eurosport2.pt", "Eurosport.2.HD.pt"),

        // Desporto Internacional: UK & Irlanda (Premier League, Champions, etc.)
        "tnt sports 1" to listOf("TNTSports1.uk", "TNT.Sports.1.HD.uk"),
        "tnt sports 2" to listOf("TNTSports2.uk", "TNT.Sports.2.HD.uk"),
        "tnt sports 3" to listOf("TNTSports3.uk", "TNT.Sports.3.HD.uk"),
        "tnt sports 4" to listOf("TNTSports4.uk", "TNT.Sports.4.HD.uk"),
        "premier sports 1" to listOf("PremierSports1.ie", "Premier.Sports.1.HD.uk"),
        "premier sports 2" to listOf("PremierSports2.ie", "Premier.Sports.2.HD.uk"),
        "sky sports premier league" to listOf("SkySp.PL.HD.uk", "SkySportsPremierLeague.uk"),
        "sky sports main event" to listOf("SkySpMainEvHD.uk", "SkySportsMainEvent.uk"),
        "sky sports football" to listOf("Sky.Sports.Football.HD.uk", "SkySp.Fball.HD.uk", "SkySp.Fball.uk"),
        "sky sports f1" to listOf("SkySp.F1.HD.uk", "SkySp.F1.uk", "SkySportF1.de"),
        "sky sports action" to listOf("SkySp.ActionHD.uk", "SkySp.Action.uk"),
        "sky sports arena" to listOf("SkySp+.HD.uk", "SkySp+HD.uk", "SkySp+.uk"),
        "sky sports cricket" to listOf("SkySpCricket.HD.uk", "SkySp.Cricket.uk"),
        "sky sports golf" to listOf("SkySp.Golf.HD.uk", "SkySp.Golf.uk"),
        "sky sports tennis" to listOf("SkySp.Tennis.HD.uk"),
        "sky sport bundesliga" to listOf("SkySportBundesliga1.de", "SkySportBundesliga.de"),
        "sky sport f1 germany" to listOf("SkySportF1.de"),
        "sky sport calcio" to listOf("SkySportCalcio.it"),
        "sky sport uno" to listOf("SkySportUno.it"),
        "eurosport 1 uk" to listOf("Eurosport1.uk"),
        "eurosport 2 uk" to listOf("Eurosport2.uk"),

        // Desporto Espanha (LaLiga, etc.)
        "movistar deportes 2" to listOf("Movistar.Deportes.2.es"),
        "movistar deportes 3" to listOf("Movistar.Deportes.3.es"),
        "movistar deportes" to listOf("Movistar.Deportes.1.es"),
        "movistar laliga" to listOf("DAZN.LALIGA.es", "DAZN.LaLiga.es", "M+.LaLiga.es"),
        "movistar liga de campeones" to listOf("M+.Liga.de.Campeones.es"),
        "dazn laliga 2" to listOf("DAZN.LALIGA.2.es", "DAZN.LaLiga.2.es"),
        "dazn laliga" to listOf("DAZN.LALIGA.es", "DAZN.LaLiga.es"),
        "dazn 1 spain" to listOf("DAZN.1.es"),
        "dazn 2 spain" to listOf("DAZN.2.es"),
        "dazn 1 bar" to listOf("DAZN.1.Bar.es"),
        "dazn 2 bar" to listOf("DAZN.2.Bar.es"),
        "eurosport 1 spain" to listOf("Eurosport.1.es"),

        // Desporto França & Polónia
        "canal+ foot" to listOf("canalplusfoot.fr", "CanalPlusFoot.fr"),
        "canal+ sport" to listOf("canalplussport.pl", "CanalPlusSport.fr"),
        "canal+ formula 1" to listOf("canalplusforuma1.fr"),
        "canal+ motogp" to listOf("canalplusmotogp.fr"),
        "polsat sport 1" to listOf("PolsatSport1.pl", "PolsatSport.pl"),
        "polsat sport 2" to listOf("PolsatSport2.pl", "PolsatSportExtra.pl"),
        "polsat sport 3" to listOf("PolsatSport3.pl", "PolsatSportNews.pl"),
        "polsat sport fight" to listOf("PolsatSportFight.pl"),
        "motogp channel" to listOf("canalplusmotogp.fr"),
        "ufc fight pass" to listOf("FastFunBox.pt"),

        // Filmes & Séries
        "tvcine top" to listOf("TVCineTop.pt", "TVCine.TOP.HD.pt", "tvcinetop.pt"),
        "tvcine edition" to listOf("TVCineEdition.pt", "TVCine.EDITION.HD.pt", "tvcineedition.pt"),
        "tvcine emotion" to listOf("TVCineEmotion.pt", "TVCine.EMOTION.HD.pt", "tvcineemotion.pt"),
        "tvcine action" to listOf("TVCineAction.pt", "TVCine.ACTION.HD.pt", "tvcineaction.pt"),
        "canal hollywood" to listOf("CanalHollywood.pt", "Canal.Hollywood.HD.pt", "canalhollywood.pt"),
        "cinemundo" to listOf("CineMundo.pt", "Cinemundo.HD.pt", "cinemundo.pt"),
        "nos studios" to listOf("NosStudios.pt", "canalnoshd.pt"),
        "star channel" to listOf("StarChannel.pt", "Star.Channel.HD.pt", "Fox.pt"),
        "star movies" to listOf("StarMovies.pt", "Star.Movies.HD.pt", "FoxMovies.pt"),
        "star comedy" to listOf("StarComedy.pt", "Star.Comedy.HD.pt", "FoxComedy.pt"),
        "star crime" to listOf("StarCrime.pt", "Star.Crime.HD.pt", "FoxCrime.pt"),
        "star life" to listOf("StarLife.pt", "Star.Life.HD.pt", "FoxLife.pt"),
        "axn" to listOf("AXN.pt", "AXN.HD.pt", "axn.pt"),
        "axn movies" to listOf("AXNMovies.pt", "AXN.Movies.HD.pt", "axnmovies.pt"),
        "axn white" to listOf("AXNWhite.pt", "AXN.White.HD.pt", "axnwhite.pt"),
        "amc" to listOf("AMC.pt", "AMC.HD.pt", "amc.pt"),
        "amc break" to listOf("AMC.Break.HD.pt", "amcbreak.pt"),
        "amc crime" to listOf("AMC.Crime.HD.pt", "Crime.Investigation.pt"),
        "syfy" to listOf("Syfy.pt", "Syfy.HD.pt", "syfy.pt"),

        // Entretenimento e Cultura
        "sic mulher" to listOf("SIC.Mulher.HD.pt", "sicmulher.pt"),
        "sic radical" to listOf("SIC.Radical.HD.pt", "sicradical.pt"),
        "sic caras" to listOf("SIC.Caras.HD.pt", "siccaras.pt"),
        "sic novelas" to listOf("SIC.Novelas.pt", "sic.pt"),
        "24 kitchen" to listOf("24Kitchen.HD.pt", "24kitchen.pt"),
        "casa e cozinha" to listOf("Casa.Cozinha.pt"),
        "tlc" to listOf("TLC.pt", "tlc.pt"),
        "e! entertainment" to listOf("E!.Entertainment.HD.pt", "eentertainment.pt"),
        "canal q" to listOf("Canal.Q.pt", "canalq.pt"),
        "national geographic" to listOf("National.Geographic.HD.pt", "nationalgeographic.pt", "NatGeo.pt"),
        "nat geo wild" to listOf("National.Geographic.WILD.HD.pt", "nationalgeographicwild.pt"),
        "odisseia" to listOf("ODISSEIA.HD.pt", "odisseia.pt"),
        "canal panda" to listOf("Canal.Panda.HD.pt", "canalpanda.pt"),
        "cartoon network" to listOf("Cartoon.Network.HD.pt", "cartoonnetwork.pt"),
        "disney channel" to listOf("Disney.Channel.pt", "disneychannel.pt"),
        "disney junior" to listOf("Disney.Junior.pt", "disneyjunior.pt"),
        "nickelodeon" to listOf("Nickelodeon.pt", "nickelodeon.pt"),
        "nick jr" to listOf("Nick.Jr..pt", "nickjr.pt"),
        "baby tv" to listOf("Baby.TV.pt", "babytv.pt"),
        "mtv portugal" to listOf("MTV.Portugal.HD.pt", "mtv.pt"),
        "trace urban" to listOf("Trace.Urban.HD.pt", "traceurban.pt"),
        "mcm pop" to listOf("MCM.Pop.pt", "mcmpop.pt"),
        "mcm top" to listOf("MCM.Top.pt", "mcmtop.pt"),
        "mezzo" to listOf("Mezzo.pt", "mezzo.pt"),
        "stingray iconcerts" to listOf("Stingray.iConcerts.HD.pt")
    )

    // Chaves ordenadas por tamanho decrescente para garantir correspondência exata
    // (ex: "sport tv 1" antes de "sport tv", "movistar deportes 2" antes de "movistar deportes")
    private val sortedChannelKeys by lazy {
        channelToEpgIds.keys.sortedByDescending { it.length }
    }

    private val targetEpgIds: Set<String> by lazy {
        channelToEpgIds.values.flatten().toSet()
    }

    init {
        loadCache()
    }

    private fun loadCache() {
        try {
            val cacheFile = File(context.filesDir, EPG_CACHE_FILE)
            if (cacheFile.exists()) {
                val type = object : TypeToken<Map<String, List<EpgProgram>>>() {}.type
                val map: Map<String, List<EpgProgram>>? = Gson().fromJson(cacheFile.readText(), type)
                if (!map.isNullOrEmpty()) {
                    synchronized(programMap) {
                        programMap.clear()
                        map.forEach { (k, v) -> programMap[k] = v.toMutableList() }
                    }
                    epgVersion.intValue++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun findEpgIdsForChannel(channelName: String): List<String> {
        val lower = channelName.lowercase().trim()
        for (key in sortedChannelKeys) {
            if (lower.contains(key) || key.contains(lower)) {
                return channelToEpgIds[key] ?: listOf(channelName)
            }
        }
        return listOf(channelName)
    }

    fun getCurrentProgram(channelName: String): EpgProgram? {
        val epgIds = findEpgIdsForChannel(channelName)
        val now = System.currentTimeMillis()
        synchronized(programMap) {
            for (epgId in epgIds) {
                val list = programMap[epgId] ?: continue
                val current = list.firstOrNull { it.startEpoch <= now && now < it.stopEpoch }
                if (current != null) return current
            }
        }
        return null
    }

    fun getChannelSchedule(channelName: String): List<EpgProgram> {
        val epgIds = findEpgIdsForChannel(channelName)
        val now = System.currentTimeMillis() - 2 * 3600 * 1000L // desde 2 horas atrás até ao futuro
        synchronized(programMap) {
            for (epgId in epgIds) {
                val list = programMap[epgId] ?: continue
                val upcoming = list.filter { it.stopEpoch >= now }.sortedBy { it.startEpoch }
                if (upcoming.isNotEmpty()) return upcoming
            }
        }
        return emptyList()
    }

    fun getProgramsCount(): Int {
        synchronized(programMap) {
            return programMap.values.sumOf { it.size }
        }
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong("last_epg_update_time", 0L)
    }

    fun isCacheStale(): Boolean {
        val last = getLastSyncTime()
        return (System.currentTimeMillis() - last) > 8 * 3600 * 1000L // Mais de 8 horas
    }

    fun syncEpgFromWeb(scope: CoroutineScope, onFinished: ((Boolean, String) -> Unit)? = null) {
        if (isUpdating) return
        isUpdating = true

        scope.launch(Dispatchers.IO) {
            var success = false
            var msg = ""
            val allParsed = mutableMapOf<String, MutableList<EpgProgram>>()

            // 1. Fonte Principal M3UPT (Canais PT + Desporto Internacional TNT, Premier, beIN)
            try {
                fetchAndParseUrl(EPG_M3UPT_URL, allParsed)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Fontes Secundárias Desporto Internacional (Sky Sports UK e Movistar/DAZN Espanha)
            val sportsFeeds = listOf(
                EPG_SPORTS_ES_URL,
                EPG_SPORTS_UK_URL
            )
            for (feedUrl in sportsFeeds) {
                try {
                    fetchAndParseUrl(feedUrl, allParsed)
                } catch (e: Exception) {
                    // Falha numa fonte de desporto não é crítica se a principal funcionou
                }
            }

            // 3. Fallbacks de emergência se nada foi descarregado
            if (allParsed.isEmpty()) {
                val fallbacks = listOf(
                    EPG_PT_FALLBACK_URL,
                    EPG_STRONG8K_URL
                )
                for (fallback in fallbacks) {
                    try {
                        fetchAndParseUrl(fallback, allParsed)
                        if (allParsed.isNotEmpty()) break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            if (allParsed.isNotEmpty()) {
                synchronized(programMap) {
                    programMap.clear()
                    programMap.putAll(allParsed)
                }

                // Guardar na cache persistente
                try {
                    val cacheFile = File(context.filesDir, EPG_CACHE_FILE)
                    cacheFile.writeText(Gson().toJson(allParsed))
                    prefs.edit().putLong("last_epg_update_time", System.currentTimeMillis()).apply()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                withContext(Dispatchers.Main) {
                    epgVersion.intValue++
                }
                success = true
                val totalProgs = allParsed.values.sumOf { it.size }
                msg = "Guia TV atualizado com $totalProgs programas!"
            } else {
                msg = "Falha ao descarregar Guia TV."
            }

            isUpdating = false
            withContext(Dispatchers.Main) {
                onFinished?.invoke(success, msg)
            }
        }
    }

    private fun fetchAndParseUrl(targetUrl: String, destination: MutableMap<String, MutableList<EpgProgram>>) {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 25000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        }
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val gzipStream = GZIPInputStream(conn.inputStream)
            parseXmlGzip(gzipStream, destination)
        }
    }

    private fun parseXmlGzip(input: InputStream, result: MutableMap<String, MutableList<EpgProgram>>) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(InputStreamReader(input, "UTF-8"))

        var eventType = parser.eventType
        var currentChannel: String? = null
        var currentStart: Long = 0L
        var currentStop: Long = 0L
        var currentTitle: String? = null
        var currentEpisode: String? = null

        val now = System.currentTimeMillis()
        val oneDayAgo = now - 24 * 3600 * 1000L
        val twoDaysAhead = now + 48 * 3600 * 1000L

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            val ch = parser.getAttributeValue(null, "channel")
                            if (ch != null && targetEpgIds.contains(ch)) {
                                currentChannel = ch
                                val startStr = parser.getAttributeValue(null, "start")
                                val stopStr = parser.getAttributeValue(null, "stop")
                                currentStart = parseEpgDate(startStr)
                                currentStop = parseEpgDate(stopStr)
                                currentTitle = null
                                currentEpisode = null
                            } else {
                                currentChannel = null
                            }
                        }
                        "title" -> {
                            if (currentChannel != null) {
                                currentTitle = parser.nextText()
                            }
                        }
                        "sub-title", "episode-num" -> {
                            if (currentChannel != null && currentEpisode == null) {
                                currentEpisode = parser.nextText()
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "programme" && currentChannel != null && currentTitle != null) {
                        if (currentStop >= oneDayAgo && currentStart <= twoDaysAhead) {
                            val list = result.getOrPut(currentChannel) { mutableListOf() }
                            list.add(
                                EpgProgram(
                                    channelId = currentChannel,
                                    title = currentTitle,
                                    startEpoch = currentStart,
                                    stopEpoch = currentStop,
                                    episode = currentEpisode
                                )
                            )
                        }
                        currentChannel = null
                    }
                }
            }
            eventType = parser.next()
        }
    }

    private fun parseEpgDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val cleanStr = dateStr.trim()
            // Normaliza formatos XMLTV:
            // "20261001120000 +0000" ou "20261001120000 +01:00" -> remove dois-pontos do offset para o parser RFC822
            val normalized = if (cleanStr.contains("+") || cleanStr.contains("-")) {
                val signIdx = cleanStr.lastIndexOfAny(charArrayOf('+', '-'))
                if (signIdx > 0 && cleanStr.indexOf(':', signIdx) > signIdx) {
                    cleanStr.substring(0, signIdx) + cleanStr.substring(signIdx).replace(":", "")
                } else {
                    cleanStr
                }
            } else {
                "$cleanStr +0000" // Assume UTC caso não venha offset explícito
            }

            val parser = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
            val d = parser.parse(normalized)
            d?.time ?: 0L
        } catch (_: Exception) {
            try {
                // Fallback para datas curtas de 12 dígitos (yyyyMMddHHmm)
                val parser12 = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                parser12.parse(dateStr.trim().take(12))?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
