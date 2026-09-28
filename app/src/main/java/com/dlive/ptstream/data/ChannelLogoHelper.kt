package com.dlive.ptstream.data

import com.dlive.ptstream.R

object ChannelLogoHelper {

    private const val FALLITO_LOGO_BASE = "https://raw.githubusercontent.com/Fallito/daddylive/main/logos/"

    fun getLogoUrl(channelName: String): String? {
        val lower = channelName.lowercase().trim()

        return when {
            // Sport TV Portugal (1 to 6 and +)
            lower.contains("sport tv 1") || lower.contains("sport tv1") -> "${FALLITO_LOGO_BASE}sport-tv-1-portugal.png"
            lower.contains("sport tv 2") || lower.contains("sport tv2") -> "${FALLITO_LOGO_BASE}sport-tv-2-portugal.png"
            lower.contains("sport tv 3") || lower.contains("sport tv3") -> "${FALLITO_LOGO_BASE}sport-tv-3-portugal.png"
            lower.contains("sport tv 4") || lower.contains("sport tv4") -> "${FALLITO_LOGO_BASE}sport-tv-4-portugal.png"
            lower.contains("sport tv 5") || lower.contains("sport tv5") -> "${FALLITO_LOGO_BASE}sport-tv-5-portugal.png"
            lower.contains("sport tv 6") || lower.contains("sport tv6") -> "${FALLITO_LOGO_BASE}sport-tv-6-portugal.png"
            lower.contains("sport tv+") || lower.contains("sport tv +") || lower.contains("sport tv plus") -> "${FALLITO_LOGO_BASE}sport-tv-1-portugal.png"
            lower.contains("sport tv") -> "${FALLITO_LOGO_BASE}sport-tv-1-portugal.png"

            // Eleven Sports / DAZN Portugal (1 to 6)
            (lower.contains("eleven") && lower.contains("1")) || (lower.contains("dazn") && lower.contains("1")) -> "${FALLITO_LOGO_BASE}eleven-sports-1.png"
            (lower.contains("eleven") && lower.contains("2")) || (lower.contains("dazn") && lower.contains("2")) -> "${FALLITO_LOGO_BASE}eleven-sports-2.png"
            (lower.contains("eleven") && lower.contains("3")) || (lower.contains("dazn") && lower.contains("3")) -> "${FALLITO_LOGO_BASE}eleven-sports-3.png"
            (lower.contains("eleven") && lower.contains("4")) || (lower.contains("dazn") && lower.contains("4")) -> "${FALLITO_LOGO_BASE}eleven-sports-4.png"
            (lower.contains("eleven") && lower.contains("5")) || (lower.contains("dazn") && lower.contains("5")) -> "${FALLITO_LOGO_BASE}eleven-sports-5.png"
            (lower.contains("eleven") && lower.contains("6")) || (lower.contains("dazn") && lower.contains("6")) -> "${FALLITO_LOGO_BASE}dazn-1.png"
            lower.contains("eleven") -> "${FALLITO_LOGO_BASE}eleven-sports-1.png"
            lower.contains("dazn") -> "${FALLITO_LOGO_BASE}dazn-1.png"

            // Benfica TV
            lower.contains("benfica") || lower.contains("btv") -> "${FALLITO_LOGO_BASE}benfica-tv.png"

            // Sporting TV
            lower.contains("sporting") -> "${FALLITO_LOGO_BASE}sporting-tv-portugal.png"

            // Porto Canal
            lower.contains("porto canal") || (lower.contains("porto") && lower.contains("tv")) -> "${FALLITO_LOGO_BASE}porto-canal-portugal.png"

            // Canal 11
            lower.contains("canal 11") || lower.contains("canal11") -> "${FALLITO_LOGO_BASE}canal11.png"

            // Generalistas Portugal
            lower.contains("rtp 1") || lower.contains("rtp1") -> "${FALLITO_LOGO_BASE}rtp-1-portugal.png"
            lower.contains("rtp 2") || lower.contains("rtp2") -> "${FALLITO_LOGO_BASE}rtp-2-portugal.png"
            lower.contains("rtp 3") || lower.contains("rtp3") || lower.contains("rtp informação") -> "${FALLITO_LOGO_BASE}rtp-3-portugal.png"
            lower.contains("rtp") -> "${FALLITO_LOGO_BASE}rtp-1-portugal.png"

            lower.contains("sic") -> "${FALLITO_LOGO_BASE}sic-portugal.png"

            lower.contains("tvi reality") -> "${FALLITO_LOGO_BASE}tvi-reality-portugal.png"
            lower.contains("tvi") || lower.contains("cnn portugal") -> "${FALLITO_LOGO_BASE}tvi-portugal.png"

            lower.contains("cmtv") || lower.contains("cm tv") -> "${FALLITO_LOGO_BASE}cmtv.png"

            lower.contains("axn movies") || lower.contains("axn") -> "${FALLITO_LOGO_BASE}axn-movies.png"

            // Top Football & Sports Worldwide (UK, US, ES, etc.)
            lower.contains("sky sports premier league") -> "${FALLITO_LOGO_BASE}sky-sports-premier-league-uk.png"
            lower.contains("sky sports main event") -> "${FALLITO_LOGO_BASE}sky-sports-main-event-uk.png"
            lower.contains("sky sports football") -> "${FALLITO_LOGO_BASE}sky-sports-football-uk.png"
            lower.contains("sky sports f1") -> "${FALLITO_LOGO_BASE}sky-sports-f1-uk.png"
            lower.contains("sky sports cricket") -> "${FALLITO_LOGO_BASE}sky-sports-cricket-uk.png"
            lower.contains("sky sports golf") -> "${FALLITO_LOGO_BASE}sky-sports-golf-uk.png"
            lower.contains("sky sports news") -> "${FALLITO_LOGO_BASE}sky-sports-news-uk.png"
            lower.contains("sky sports") -> "${FALLITO_LOGO_BASE}sky-sports-main-event-uk.png"

            lower.contains("tnt sports 1") -> "${FALLITO_LOGO_BASE}tnt-sports-1-uk.png"
            lower.contains("tnt sports 2") -> "${FALLITO_LOGO_BASE}tnt-sports-2-uk.png"
            lower.contains("tnt sports 3") -> "${FALLITO_LOGO_BASE}tnt-sports-3-uk.png"
            lower.contains("tnt sports 4") -> "${FALLITO_LOGO_BASE}tnt-sports-4-uk.png"
            lower.contains("tnt sports") -> "${FALLITO_LOGO_BASE}tnt-sports-1-uk.png"

            lower.contains("eurosport 1") -> "${FALLITO_LOGO_BASE}eurosport-1.png"
            lower.contains("eurosport 2") -> "${FALLITO_LOGO_BASE}eurosport-2.png"
            lower.contains("eurosport") -> "${FALLITO_LOGO_BASE}eurosport-1.png"

            lower.contains("espn 1") || lower.contains("espn usa") -> "${FALLITO_LOGO_BASE}espn-1.png"
            lower.contains("espn 2") -> "${FALLITO_LOGO_BASE}espn-2.png"
            lower.contains("espn") -> "${FALLITO_LOGO_BASE}espn-1.png"

            lower.contains("bein sports 1") -> "${FALLITO_LOGO_BASE}bein-sports-1.png"
            lower.contains("bein sports 2") -> "${FALLITO_LOGO_BASE}bein-sports-2.png"
            lower.contains("bein sports 3") -> "${FALLITO_LOGO_BASE}bein-sports-3.png"
            lower.contains("bein sports") -> "${FALLITO_LOGO_BASE}bein-sports-1.png"

            lower.contains("optus sport") -> "${FALLITO_LOGO_BASE}optus-sport-1-au.png"
            lower.contains("match tv") || lower.contains("match premier") || lower.contains("match football") -> "${FALLITO_LOGO_BASE}match-tv-ru.png"

            else -> null
        }
    }

    fun getDefaultOnlineLogo(channelName: String): String? {
        return getLogoUrl(channelName)
    }

    fun getLocalLogoRes(channelName: String): Int? {
        val lower = channelName.lowercase()
        return when {
            lower.contains("sport tv") -> R.drawable.ic_logo_sporttv
            lower.contains("benfica") -> R.drawable.ic_logo_benfica
            lower.contains("sporting") -> R.drawable.ic_logo_sporting
            lower.contains("porto canal") || (lower.contains("porto") && lower.contains("tv")) -> R.drawable.ic_logo_porto
            lower.contains("canal 11") -> R.drawable.ic_logo_canal11
            lower.contains("rtp") -> R.drawable.ic_logo_rtp
            lower.contains("sic") -> R.drawable.ic_logo_sic
            lower.contains("tvi") -> R.drawable.ic_logo_tvi
            lower.contains("cmtv") -> R.drawable.ic_logo_cmtv
            lower.contains("dazn") -> R.drawable.ic_logo_dazn
            lower.contains("eleven") -> R.drawable.ic_logo_eleven
            else -> null
        }
    }
}
