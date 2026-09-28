package com.dlive.ptstream.ui.screens

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dlive.ptstream.R
import com.dlive.ptstream.data.Channel
import com.dlive.ptstream.data.ChannelLogoHelper
import com.dlive.ptstream.data.EpgRepository

class DrawerChannelAdapter(
    private var channels: List<Channel>,
    private var activeChannelId: String,
    private val epgRepository: EpgRepository? = null,
    private val onChannelSelected: (Channel) -> Unit,
    private val onFavoriteToggled: (Channel) -> Unit,
    private val onHideChannel: ((Channel) -> Unit)? = null
) : RecyclerView.Adapter<DrawerChannelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.channelRowRoot)
        val viewPlayingDot: View = view.findViewById(R.id.viewPlayingDot)
        val ivLogo: ImageView = view.findViewById(R.id.ivDrawerChannelLogo)
        val tvId: TextView = view.findViewById(R.id.tvDrawerChannelId)
        val tvName: TextView = view.findViewById(R.id.tvDrawerChannelName)
        val tvCategory: TextView = view.findViewById(R.id.tvDrawerChannelCategory)
        val btnFavorite: ImageButton = view.findViewById(R.id.btnDrawerFavorite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_drawer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        val isCurrent = channel.id == activeChannelId

        holder.tvId.text = channel.id
        holder.tvName.text = channel.name

        // Logo binding
        val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
        val onlineLogo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)

        if (onlineLogo != null) {
            holder.ivLogo.visibility = View.VISIBLE
            holder.tvId.visibility = View.GONE
            holder.ivLogo.load(onlineLogo) {
                crossfade(true)
                if (localLogo != null) error(localLogo)
            }
        } else if (localLogo != null) {
            holder.ivLogo.visibility = View.VISIBLE
            holder.tvId.visibility = View.GONE
            holder.ivLogo.setImageResource(localLogo)
        } else {
            holder.ivLogo.visibility = View.GONE
            holder.tvId.visibility = View.VISIBLE
        }

        val epgProgram = epgRepository?.getCurrentProgram(channel.name)
        val subtitleText = if (epgProgram != null && epgProgram.title.isNotBlank()) {
            val start = epgProgram.timeRange.substringBefore(" -")
            "🔴 $start • ${epgProgram.title}"
        } else {
            val hasTimst = !channel.backupStreamUrl.isNullOrBlank()
            buildString {
                if (channel.isPortuguese) append("PT 🇵🇹") else append(channel.country)
                append(" • ")
                append(channel.category)
                if (hasTimst) append(" • ⚡ Timst")
            }
        }
        holder.tvCategory.text = subtitleText
        holder.tvCategory.setTextColor(if (epgProgram != null) Color.parseColor("#38BDF8") else Color.parseColor("#9CA3AF"))

        // Active playing indicator
        if (isCurrent) {
            holder.viewPlayingDot.visibility = View.VISIBLE
            holder.tvName.setTextColor(Color.parseColor("#E50914"))
        } else {
            holder.viewPlayingDot.visibility = View.GONE
            holder.tvName.setTextColor(Color.WHITE)
        }
        holder.root.setBackgroundResource(R.drawable.selector_channel_row)

        // TV Remote D-Pad Focus scaling
        holder.root.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                holder.tvName.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                if (isCurrent) {
                    holder.tvName.setTextColor(Color.parseColor("#E50914"))
                }
            }
        }

        // Star favorite
        if (channel.isFavorite) {
            holder.btnFavorite.setImageResource(android.R.drawable.star_on)
        } else {
            holder.btnFavorite.setImageResource(android.R.drawable.star_off)
        }

        holder.root.setOnClickListener {
            onChannelSelected(channel)
        }

        holder.root.setOnLongClickListener {
            onHideChannel?.invoke(channel)
            true
        }

        holder.btnFavorite.setOnClickListener {
            onFavoriteToggled(channel)
        }
    }

    override fun getItemCount(): Int = channels.size

    fun getChannels(): List<Channel> = channels

    fun updateChannels(newChannels: List<Channel>, newActiveId: String) {
        this.channels = newChannels
        this.activeChannelId = newActiveId
        notifyDataSetChanged()
    }
}
