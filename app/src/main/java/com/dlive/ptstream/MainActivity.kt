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

class MainActivity : ComponentActivity() {

    private lateinit var channelRepository: ChannelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        channelRepository = ChannelRepository(this)
        channelRepository.syncChannelsFromWeb(lifecycleScope)

        setContent {
            DLivePTStreamTheme {
                HomeScreen(
                    repository = channelRepository,
                    onChannelClick = { channel ->
                        PlayerActivity.closeActivePip()
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra("EXTRA_CHANNEL_ID", channel.id)
                            putExtra("EXTRA_CHANNEL_NAME", channel.name)
                            if (channel.id.startsWith("timst-") || channel.id.startsWith("ntv-") || channel.category == "TimStreams") {
                                putExtra("EXTRA_DIRECT_STREAM_URL", channel.backupStreamUrl)
                            } else if (channel.backupStreamUrl != null) {
                                putExtra("EXTRA_BACKUP_STREAM_URL", channel.backupStreamUrl)
                            }
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}
