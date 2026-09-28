package com.dlive.ptstream.data

import com.google.gson.annotations.SerializedName

enum class ChannelStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN
}

data class Channel(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("country") var country: String = "PT",
    @SerializedName("category") var category: String = "Geral",
    @SerializedName("isPt") var isPt: Boolean = false,
    var isFavorite: Boolean = false,
    @SerializedName("logoUrl") var logoUrl: String? = null,
    @SerializedName("backupStreamUrl") var backupStreamUrl: String? = null,
    @SerializedName("backupStreamUrl2") var backupStreamUrl2: String? = null,
    var status: ChannelStatus? = ChannelStatus.UNKNOWN
) {
    val safeStatus: ChannelStatus get() = status ?: ChannelStatus.UNKNOWN

    val isPortuguese: Boolean
        get() = isPt || country.equals("PT", ignoreCase = true) || isPortugueseChannelName(name)

    companion object {
        fun isPortugueseChannelName(name: String): Boolean {
            val lower = name.lowercase()
            if (lower.contains("poland") || lower.contains("slovenia") || lower.contains("germany") || lower.contains("italia") || lower.contains("spain")) {
                return false
            }
            return lower.contains("portugal") ||
                    lower.contains("sport tv") ||
                    lower.contains("benfica") ||
                    lower.contains("sporting tv") ||
                    lower.contains("porto canal") ||
                    lower.contains("canal 11") ||
                    lower.contains("rtp") ||
                    lower.contains("sic") ||
                    lower.contains("tvi") ||
                    lower.contains("cmtv") ||
                    lower.contains("tvcine") ||
                    lower.contains("hollywood") ||
                    lower.contains("cinemundo") ||
                    lower.contains("a bola tv") ||
                    lower.contains("nos studios") ||
                    lower.contains("eleven sports") ||
                    (lower.contains("dazn") && (lower.contains("portugal") || lower.contains("pt")))
        }
    }
}

data class LiveEvent(
    val id: String,
    val name: String,
    val logo: String? = null,
    val genre: Int = 1,
    val genreName: String = "Futebol",
    val time: String = "",
    val viewers: Int = 0,
    val streams: List<EventStream> = emptyList(),
    val isSoccer: Boolean = true
)

data class EventStream(
    val name: String,
    val url: String
)
