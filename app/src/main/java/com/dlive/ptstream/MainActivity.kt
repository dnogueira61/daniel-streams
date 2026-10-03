package com.dlive.ptstream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dlive.ptstream.data.ChannelRepository
import com.dlive.ptstream.ui.screens.HomeScreen
import com.dlive.ptstream.ui.screens.PlayerActivity
import com.dlive.ptstream.ui.theme.DLivePTStreamTheme

import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {

    companion object {
        private var hasAutoResumedOnLaunch = false
    }

    private lateinit var channelRepository: ChannelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channelRepository = ChannelRepository(this)
        channelRepository.syncChannelsFromWeb(lifecycleScope)
        if (channelRepository.epgRepository.getProgramsCount() == 0 || channelRepository.epgRepository.isCacheStale()) {
            channelRepository.epgRepository.syncEpgFromWeb(lifecycleScope)
        }

        val dontResume = intent.getBooleanExtra("EXTRA_DONT_AUTO_RESUME", false)
        if (!dontResume && !hasAutoResumedOnLaunch && savedInstanceState == null && channelRepository.isAutoResumeEnabled()) {
            hasAutoResumedOnLaunch = true
            val lastId = channelRepository.getLastWatchedChannelId()
            if (lastId != null) {
                val lastChannel = channelRepository.getChannels(com.dlive.ptstream.data.TabFilter.PORTUGAL).firstOrNull { it.id == lastId }
                    ?: channelRepository.getChannels(com.dlive.ptstream.data.TabFilter.ALL).firstOrNull { it.id == lastId }
                if (lastChannel != null) {
                    launchPlayer(lastChannel)
                }
            }
        }

        setContent {
            var themeMode by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(channelRepository.getThemeMode())
            }
            var accentColor by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(channelRepository.getAccentColor())
            }

            DLivePTStreamTheme(themeMode = themeMode, accentColor = accentColor) {
                HomeScreen(
                    repository = channelRepository,
                    onThemeChange = { mode, accent ->
                        themeMode = mode
                        accentColor = accent
                    },
                    onChannelClick = { channel, directUrl ->
                        launchPlayer(channel, directUrl)
                    }
                )
            }
        }
    }

    private fun launchPlayer(channel: com.dlive.ptstream.data.Channel, directUrl: String? = null) {
        PlayerActivity.closeActivePip()
        val timstUrl = when {
            channel.backupStreamUrl?.let { it.contains("exmxbxe") || it.contains("timst") } == true -> channel.backupStreamUrl
            channel.backupStreamUrl2?.let { it.contains("exmxbxe") || it.contains("timst") } == true -> channel.backupStreamUrl2
            else -> null
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("EXTRA_CHANNEL_ID", channel.id)
            putExtra("EXTRA_CHANNEL_NAME", channel.name)
            if (channel.backupStreamUrl != null) {
                putExtra("EXTRA_BACKUP_STREAM_URL", channel.backupStreamUrl)
            }
            if (channel.backupStreamUrl2 != null) {
                putExtra("EXTRA_BACKUP_STREAM_URL2", channel.backupStreamUrl2)
            }

            if (!directUrl.isNullOrBlank()) {
                putExtra("EXTRA_DIRECT_STREAM_URL", directUrl)
            } else if (timstUrl != null) {
                // Preferência prioritária para TimStreams conforme solicitado pelo utilizador
                putExtra("EXTRA_DIRECT_STREAM_URL", timstUrl)
            } else if (channel.id.toIntOrNull() == null) {
                putExtra("EXTRA_DIRECT_STREAM_URL", channel.backupStreamUrl)
            }
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
