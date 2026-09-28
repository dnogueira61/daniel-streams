package com.dlive.ptstream.data

import com.dlive.ptstream.R

object ChannelLogoHelper {

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

    fun getDefaultOnlineLogo(channelName: String): String? {
        val lower = channelName.lowercase()
        return when {
            lower.contains("sport tv") -> "https://cdn.jsdelivr.net/gh/willthequeencome/img-cdn/sportv-pt.png"
            lower.contains("eleven") -> "https://cdn.jsdelivr.net/gh/willthequeencome/img-cdn/100659.png"
            lower.contains("dazn") && lower.contains("portugal") -> "https://image.discovery.indazn.com/ca/v2/ca/image?id=1o4eamzb4env61ddc8wv4ra06l_image-header_pRow_1720521601000&quality=70"
            else -> null
        }
    }
}
