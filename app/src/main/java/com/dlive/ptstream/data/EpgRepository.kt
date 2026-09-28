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
            val sdf = SimpleDateFormat("HH'h'mm", Locale.getDefault())
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

    private val EPG_CACHE_FILE = "epg_cache.json"
    private val EPG_URL = "https://JohnPulse.github.io/iptv-epg/epg-strong8k.xml.gz"

    // Key: Normalized channel alias / EPG channel ID -> List of programmes
    private val programMap = mutableMapOf<String, MutableList<EpgProgram>>()

    val epgVersion = mutableIntStateOf(0)
    var isUpdating = false
        private set

    // Channel name keywords mapped to EPG Channel IDs
    private val channelToEpgIds = mapOf(
        "rtp 1" to listOf("RTP.1.HD.pt", "rtp1.pt"),
        "rtp 2" to listOf("RTP.2.HD.pt", "rtp2.pt"),
        "sic" to listOf("SIC.HD.pt", "sic.pt"),
        "tvi" to listOf("TVI.HD.pt", "tvi.pt"),
        "sic notícias" to listOf("SIC.Notícias.HD.pt", "sicnoticias.pt"),
        "rtp 3" to listOf("RTP.3.HD.pt", "rtp3.pt"),
        "cnn portugal" to listOf("CNN.pt", "cnnportugal.pt"),
        "porto canal" to listOf("Porto.Canal.HD.pt", "porto.pt"),
        "cmtv" to listOf("CMTV.HD.pt", "cmtv.pt"),
        "canal 11" to listOf("Canal11.pt", "canal11"),
        "v+ tvi" to listOf("V+.TVI.pt", "TVI.HD.pt"),
        "rtp memória" to listOf("RTP.Memória.pt", "rtpmemoria.pt"),
        "rtp açores" to listOf("RTP.Açores.pt"),
        "rtp madeira" to listOf("RTP.Madeira.pt", "rtpmadeira.pt"),
        "rtp áfrica" to listOf("RTP.África.pt"),
        "sport tv 1" to listOf("SPORT.TV1.HD.pt", "SportTV1.pt"),
        "sport tv 2" to listOf("SPORT.TV2.HD.pt", "SportTV2.pt"),
        "sport tv 3" to listOf("SPORT.TV3.HD.pt", "SportTV3.pt"),
        "sport tv 4" to listOf("SPORT.TV4.HD.pt", "SportTV4.pt"),
        "sport tv 5" to listOf("SPORT.TV5.HD.pt", "SportTV5.pt"),
        "sport tv 6" to listOf("SPORT.TV6.HD.pt", "SportTV6.pt"),
        "sport tv 7" to listOf("SPORT.TV7.HD.pt", "SportTV7.pt"),
        "sport tv+" to listOf("SPORT.TV+.HD.pt", "SportTVPlus.pt"),
        "dazn 1" to listOf("DAZN.1.pt", "ElevenSports1.pt"),
        "dazn 2" to listOf("DAZN.2.pt", "ElevenSports2.pt"),
        "dazn 3" to listOf("DAZN.3.pt", "ElevenSports3.pt"),
        "dazn 4" to listOf("DAZN.4.pt", "ElevenSports4.pt"),
        "dazn 5" to listOf("DAZN.5.pt", "ElevenSports5.pt"),
        "dazn 6" to listOf("DAZN.6.pt"),
        "benfica tv" to listOf("BTV1.HD.pt", "BenficaTV1.pt"),
        "sporting tv" to listOf("Sporting.TV.HD.pt", "SportingTV.pt"),
        "a bola tv" to listOf("A.Bola.TV.HD.pt", "abolatv.pt"),
        "eurosport 1" to listOf("Eurosport.1.HD.pt", "Eurosport1.pt"),
        "eurosport 2" to listOf("Eurosport.2.HD.pt", "Eurosport2.pt"),
        "tvcine top" to listOf("TVCine.TOP.HD.pt", "tvcinetop.pt"),
        "tvcine edition" to listOf("TVCine.EDITION.HD.pt", "tvcineedition.pt"),
        "tvcine emotion" to listOf("TVCine.EMOTION.HD.pt", "tvcineemotion.pt"),
        "tvcine action" to listOf("TVCine.ACTION.HD.pt", "tvcineaction.pt"),
        "canal hollywood" to listOf("Canal.Hollywood.HD.pt", "canalhollywood.pt"),
        "cinemundo" to listOf("Cinemundo.HD.pt", "cinemundo.pt"),
        "nos studios" to listOf("canalnoshd.pt"),
        "star channel" to listOf("Star.Channel.HD.pt", "Fox.pt"),
        "star movies" to listOf("Star.Movies.HD.pt", "FoxMovies.pt"),
        "star comedy" to listOf("Star.Comedy.HD.pt", "FoxComedy.pt"),
        "star crime" to listOf("Star.Crime.HD.pt", "FoxCrime.pt"),
        "star life" to listOf("Star.Life.HD.pt", "FoxLife.pt"),
        "axn" to listOf("AXN.HD.pt", "axn.pt"),
        "axn movies" to listOf("AXN.Movies.HD.pt", "axnmovies.pt"),
        "axn white" to listOf("AXN.White.HD.pt", "axnwhite.pt"),
        "amc" to listOf("AMC.HD.pt", "amc.pt"),
        "amc break" to listOf("AMC.Break.HD.pt", "amcbreak.pt"),
        "amc crime" to listOf("AMC.Crime.HD.pt", "Crime.Investigation.pt"),
        "syfy" to listOf("Syfy.HD.pt", "syfy.pt"),
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

    fun getCurrentProgram(channelName: String): EpgProgram? {
        val lower = channelName.lowercase().trim()
        val epgIds = channelToEpgIds.entries.firstOrNull { (key, _) ->
            lower.contains(key) || key.contains(lower)
        }?.value ?: listOf(channelName)

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

    fun getProgramsCount(): Int {
        synchronized(programMap) {
            return programMap.values.sumOf { it.size }
        }
    }

    fun syncEpgFromWeb(scope: CoroutineScope, onFinished: ((Boolean, String) -> Unit)? = null) {
        if (isUpdating) return
        isUpdating = true

        scope.launch(Dispatchers.IO) {
            var success = false
            var msg = ""
            try {
                val conn = (URL(EPG_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val gzipStream = GZIPInputStream(conn.inputStream)
                    val parsed = parseXmlGzip(gzipStream)

                    if (parsed.isNotEmpty()) {
                        synchronized(programMap) {
                            programMap.clear()
                            programMap.putAll(parsed)
                        }

                        // Save cache
                        val cacheFile = File(context.filesDir, EPG_CACHE_FILE)
                        cacheFile.writeText(Gson().toJson(parsed))

                        withContext(Dispatchers.Main) {
                            epgVersion.intValue++
                        }
                        success = true
                        val totalProgs = parsed.values.sumOf { it.size }
                        msg = "Guia TV atualizado com $totalProgs programas!"
                    } else {
                        msg = "Nenhum programa correspondente encontrado."
                    }
                } else {
                    msg = "Erro HTTP ao descarregar EPG: ${conn.responseCode}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                msg = "Falha ao atualizar EPG: ${e.localizedMessage ?: "Erro de rede"}"
            } finally {
                isUpdating = false
            }

            withContext(Dispatchers.Main) {
                onFinished?.invoke(success, msg)
            }
        }
    }

    private fun parseXmlGzip(input: InputStream): Map<String, MutableList<EpgProgram>> {
        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        val dateParser = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

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
                                currentStart = parseEpgDate(dateParser, startStr)
                                currentStop = parseEpgDate(dateParser, stopStr)
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
                        // Keep only relevant recent & upcoming programmes (from 1 day ago to 2 days ahead)
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

        return result
    }

    private fun parseEpgDate(parser: SimpleDateFormat, dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val d = parser.parse(dateStr)
            d?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
