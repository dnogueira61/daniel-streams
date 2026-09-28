package com.dlive.ptstream.ui.screens

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import kotlin.math.roundToInt
import com.dlive.ptstream.R
import com.dlive.ptstream.MainActivity
import com.dlive.ptstream.data.Channel
import com.dlive.ptstream.data.ChannelRepository
import com.dlive.ptstream.data.TabFilter
import android.content.res.ColorStateList
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference

class PlayerActivity : ComponentActivity() {

    companion object {
        private var activeInstance: WeakReference<PlayerActivity>? = null

        fun closeActivePip() {
            activeInstance?.get()?.let { activity ->
                try {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.cleanupAndFinish()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            activeInstance = null
        }
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var customViewContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvChannelTitle: TextView
    private lateinit var btnServerSelect: View
    private lateinit var tvServerBadge: TextView
    private lateinit var btnLandscapeServer: View
    private lateinit var tvLandscapeServerBadge: TextView
    private lateinit var playerHeader: View
    private lateinit var landscapeOverlay: View
    private lateinit var tvLandscapeTitle: TextView

    // OSD Banner
    private lateinit var osdBanner: View
    private lateinit var tvOsdId: TextView
    private lateinit var tvOsdName: TextView
    private lateinit var tvOsdTag: TextView

    // Gesture HUD & Controls
    private lateinit var gestureHud: View
    private lateinit var ivGestureIcon: ImageView
    private lateinit var tvGestureText: TextView
    private lateinit var pbGestureProgress: ProgressBar
    private lateinit var audioManager: AudioManager

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isSwipingGesture = false
    private var gestureMode = GestureMode.NONE
    private var initialVolume = 0
    private var initialBrightness = 0.5f

    private enum class GestureMode {
        NONE, VOLUME, BRIGHTNESS
    }

    // Drawer Views
    private lateinit var rvDrawerChannels: RecyclerView
    private lateinit var etDrawerSearch: EditText
    private lateinit var tvDrawerEmpty: TextView
    private lateinit var btnTabPt: Button
    private lateinit var btnTabTimst: Button
    private lateinit var btnTabFav: Button
    private lateinit var btnTabAll: Button
    private lateinit var drawerAdapter: DrawerChannelAdapter

    private lateinit var repository: ChannelRepository
    private var currentDrawerTab = TabFilter.PORTUGAL

    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customView: View? = null

    private var channelId: String = ""
    private var channelName: String = ""
    private var directStreamUrl: String? = null
    private var backupDirectUrl: String? = null
    private var backupDirectUrl2: String? = null
    private var isBackupSelected: Boolean = false
    private var activeDirectUrl: String? = null
    private var currentFolder: String = "stream"

    private val handler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            landscapeOverlay.visibility = View.GONE
        }
    }
    private val osdHideRunnable = Runnable {
        osdBanner.visibility = View.GONE
    }
    private val gestureHudHideRunnable = Runnable {
        gestureHud.visibility = View.GONE
    }

    private val serverFolders = listOf("stream", "cast", "watch", "player", "plus")

    // Auto-failover: ordered list of servers to try
    private var failoverServers: List<ServerOption> = emptyList()
    private var currentFailoverIndex = 0
    private var streamLoadedSuccessfully = false
    private val failoverTimeoutMs = 15000L // 15 seconds before trying next server
    private val failoverTimeoutRunnable = Runnable {
        if (!streamLoadedSuccessfully && currentFailoverIndex < failoverServers.size - 1) {
            currentFailoverIndex++
            val next = failoverServers[currentFailoverIndex]
            runOnUiThread {
                Toast.makeText(this, "A tentar servidor seguinte...", Toast.LENGTH_SHORT).show()
                applyServerOption(next)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = WeakReference(this)
        setContentView(R.layout.activity_player)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackOrPip()
            }
        })

        repository = ChannelRepository(this)
        currentFolder = repository.getDefaultServer()

        channelId = intent.getStringExtra("EXTRA_CHANNEL_ID") ?: ""
        channelName = intent.getStringExtra("EXTRA_CHANNEL_NAME") ?: "Stream"
        directStreamUrl = intent.getStringExtra("EXTRA_DIRECT_STREAM_URL")
        backupDirectUrl = intent.getStringExtra("EXTRA_BACKUP_STREAM_URL")
        backupDirectUrl2 = intent.getStringExtra("EXTRA_BACKUP_STREAM_URL2")
        activeDirectUrl = directStreamUrl

        if (channelId.isBlank() && directStreamUrl.isNullOrBlank()) {
            Toast.makeText(this, "ID de canal inválido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupDrawer()
        setupServerBadge()
        setupWebView()
        loadCurrentStream()
        showOsdBanner(if (directStreamUrl != null) "LIVE" else channelId, channelName, if (directStreamUrl != null) "DIRETO ⚽" else "PT 🇵🇹")

        // Detect TV or landscape device (tablets / TV sticks)
        val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
                packageManager.hasSystemFeature("android.hardware.type.television")
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isTv && !isLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        applyFullscreenMode(isLandscape || isTv)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        webView = findViewById(R.id.webView)
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        customViewContainer = findViewById(R.id.customViewContainer)
        customViewContainer.isFocusable = false
        customViewContainer.isFocusableInTouchMode = false
        progressBar = findViewById(R.id.progressBar)
        tvChannelTitle = findViewById(R.id.tvChannelTitle)
        btnServerSelect = findViewById(R.id.btnServerSelect)
        tvServerBadge = findViewById(R.id.tvServerBadge)
        btnLandscapeServer = findViewById(R.id.btnLandscapeServer)
        tvLandscapeServerBadge = findViewById(R.id.tvLandscapeServerBadge)
        playerHeader = findViewById(R.id.playerHeader)
        landscapeOverlay = findViewById(R.id.landscapeOverlay)
        tvLandscapeTitle = findViewById(R.id.tvLandscapeTitle)

        // OSD Banner
        osdBanner = findViewById(R.id.osdBanner)
        tvOsdId = findViewById(R.id.tvOsdId)
        tvOsdName = findViewById(R.id.tvOsdName)
        tvOsdTag = findViewById(R.id.tvOsdTag)

        tvChannelTitle.text = channelName
        tvLandscapeTitle.text = channelName

        // Server Selector Badges
        btnServerSelect.setOnClickListener {
            showServerSelectionDialog()
        }

        btnLandscapeServer.setOnClickListener {
            showServerSelectionDialog()
        }

        // Portrait Header buttons
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            handleBackOrPip()
        }

        findViewById<View>(R.id.btnOpenChannels).setOnClickListener {
            openDrawer()
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            loadCurrentStream()
        }

        findViewById<ImageButton>(R.id.btnPip).setOnClickListener {
            enterPipMode(bringHomeToFront = true)
        }

        // Landscape / Fullscreen toggle with sensor support
        findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // Landscape overlay buttons
        findViewById<ImageButton>(R.id.btnLandscapeBack).setOnClickListener {
            handleBackOrPip()
        }

        findViewById<View>(R.id.btnLandscapeChannels).setOnClickListener {
            openDrawer()
        }

        findViewById<ImageButton>(R.id.btnLandscapePip).setOnClickListener {
            enterPipMode(bringHomeToFront = true)
        }

        findViewById<ImageButton>(R.id.btnLandscapeExitFullscreen).setOnClickListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Gesture HUD & Audio
        gestureHud = findViewById(R.id.gestureHud)
        ivGestureIcon = findViewById(R.id.ivGestureIcon)
        tvGestureText = findViewById(R.id.tvGestureText)
        pbGestureProgress = findViewById(R.id.pbGestureProgress)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun showGestureHud(isVolume: Boolean, percent: Int) {
        handler.removeCallbacks(gestureHudHideRunnable)
        gestureHud.visibility = View.VISIBLE
        pbGestureProgress.progress = percent.coerceIn(0, 100)
        if (isVolume) {
            ivGestureIcon.setImageResource(R.drawable.ic_gesture_volume)
            tvGestureText.text = "Volume: $percent%"
        } else {
            ivGestureIcon.setImageResource(R.drawable.ic_gesture_brightness)
            tvGestureText.text = "Brilho: $percent%"
        }
    }

    private fun setupDrawer() {
        rvDrawerChannels = findViewById(R.id.rvDrawerChannels)
        etDrawerSearch = findViewById(R.id.etDrawerSearch)
        tvDrawerEmpty = findViewById(R.id.tvDrawerEmpty)
        btnTabPt = findViewById(R.id.btnTabPt)
        btnTabTimst = findViewById(R.id.btnTabTimst)
        btnTabFav = findViewById(R.id.btnTabFav)
        btnTabAll = findViewById(R.id.btnTabAll)

        findViewById<ImageButton>(R.id.btnCloseDrawer).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<ImageButton>(R.id.btnDrawerSettings).setOnClickListener {
            showSettingsDialog()
        }

        rvDrawerChannels.layoutManager = LinearLayoutManager(this)
        drawerAdapter = DrawerChannelAdapter(
            channels = emptyList(),
            activeChannelId = channelId,
            onChannelSelected = { selected ->
                switchChannel(selected)
            },
            onFavoriteToggled = { channel ->
                repository.toggleFavorite(channel.id)
                refreshDrawerList()
            },
            onHideChannel = { channel ->
                AlertDialog.Builder(this)
                    .setTitle("Ocultar Canal")
                    .setMessage("Pretende ocultar \"${channel.name}\" da lista de canais?")
                    .setPositiveButton("Ocultar") { _, _ ->
                        repository.hideChannel(channel.id)
                        Toast.makeText(this, "Canal \"${channel.name}\" ocultado.", Toast.LENGTH_SHORT).show()
                        refreshDrawerList()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )
        rvDrawerChannels.adapter = drawerAdapter

        btnTabPt.text = "Canais"
        btnTabTimst.visibility = View.GONE
        btnTabAll.visibility = View.VISIBLE
        btnTabAll.text = "Mundo"
        btnTabPt.setOnClickListener { selectDrawerTab(TabFilter.PORTUGAL) }
        btnTabAll.setOnClickListener { selectDrawerTab(TabFilter.ALL) }
        btnTabFav.setOnClickListener { selectDrawerTab(TabFilter.FAVORITES) }

        etDrawerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshDrawerList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etDrawerSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(etDrawerSearch.windowToken, 0)
                etDrawerSearch.clearFocus()
                rvDrawerChannels.requestFocus()
                true
            } else false
        }

        selectDrawerTab(TabFilter.PORTUGAL)
    }

    private fun openDrawer() {
        // 1. Clear any prior search query and prevent keyboard pop-up in landscape
        etDrawerSearch.setText("")
        etDrawerSearch.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etDrawerSearch.windowToken, 0)

        // 2. Refresh drawer list with current tab
        selectDrawerTab(currentDrawerTab)

        // 3. Open drawer
        drawerLayout.openDrawer(GravityCompat.START)

        // 4. Focus channel list rather than search box
        handler.postDelayed({
            val list = repository.getChannels(currentDrawerTab)
            val index = list.indexOfFirst { it.id == channelId }
            if (index >= 0) {
                rvDrawerChannels.scrollToPosition(index)
            }
            rvDrawerChannels.requestFocus()
        }, 150)
    }

    private fun selectDrawerTab(tab: TabFilter) {
        currentDrawerTab = tab
        val activeColor = ColorStateList.valueOf(Color.parseColor("#E50914"))
        val inactiveColor = ColorStateList.valueOf(Color.parseColor("#202330"))

        btnTabPt.backgroundTintList = if (tab == TabFilter.PORTUGAL) activeColor else inactiveColor
        btnTabPt.setTextColor(if (tab == TabFilter.PORTUGAL) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabTimst.backgroundTintList = if (tab == TabFilter.TIMSTREAMS) activeColor else inactiveColor
        btnTabTimst.setTextColor(if (tab == TabFilter.TIMSTREAMS) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabFav.backgroundTintList = if (tab == TabFilter.FAVORITES) activeColor else inactiveColor
        btnTabFav.setTextColor(if (tab == TabFilter.FAVORITES) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabAll.backgroundTintList = if (tab == TabFilter.ALL) activeColor else inactiveColor
        btnTabAll.setTextColor(if (tab == TabFilter.ALL) Color.WHITE else Color.parseColor("#9CA3AF"))

        refreshDrawerList()
    }

    private fun refreshDrawerList() {
        val query = etDrawerSearch.text.toString().trim()
        val list = repository.getChannels(currentDrawerTab, query)
        drawerAdapter.updateChannels(list, channelId)

        if (list.isEmpty()) {
            tvDrawerEmpty.visibility = View.VISIBLE
            rvDrawerChannels.visibility = View.GONE
        } else {
            tvDrawerEmpty.visibility = View.GONE
            rvDrawerChannels.visibility = View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
        if (newChannel.id == channelId && activeDirectUrl == null && directStreamUrl == null) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        channelId = newChannel.id
        channelName = newChannel.name
        backupDirectUrl = newChannel.backupStreamUrl

        if (newChannel.id.toIntOrNull() == null) {
            activeDirectUrl = newChannel.backupStreamUrl
            directStreamUrl = newChannel.backupStreamUrl
            isBackupSelected = false
        } else {
            activeDirectUrl = null
            directStreamUrl = null
            isBackupSelected = false
            currentFolder = repository.getDefaultServer()
        }

        tvChannelTitle.text = channelName
        tvLandscapeTitle.text = channelName

        drawerLayout.closeDrawer(GravityCompat.START)

        showOsdBanner(channelId, channelName, "PT 🇵🇹")

        // Reset failover state for the new channel
        failoverServers = emptyList()
        currentFailoverIndex = 0
        streamLoadedSuccessfully = false
        handler.removeCallbacks(failoverTimeoutRunnable)

        setupServerBadge()
        loadCurrentStream()
    }

    private fun showOsdBanner(id: String, name: String, tag: String) {
        tvOsdId.text = id
        tvOsdName.text = name
        tvOsdTag.text = tag

        osdBanner.visibility = View.VISIBLE
        handler.removeCallbacks(osdHideRunnable)
        handler.postDelayed(osdHideRunnable, 3000)
    }

    private fun setupServerBadge() {
        updateServerBadgeText()
    }

    private fun updateServerBadgeText() {
        val label = if (activeDirectUrl != null) {
            val u = activeDirectUrl!!
            when {
                u.contains("impresa.pt") -> "S (SIC)"
                u.contains("rtp.pt") -> "S (RTP)"
                u.contains("TVI") || u.contains("github") -> "S (TVI)"
                u.contains("cloudfront") -> "S (C11)"
                u.contains("fastly") -> "S (Porto)"
                u.contains("exmxbxe") -> "S (TimST)"
                u.contains("epicsports") || u.contains("ntv.st") -> "S (NTV)"
                else -> "S (Direto)"
            }
        } else {
            when (currentFolder) {
                "stream" -> "S (DLive)"
                "cast" -> "S (Cast)"
                "watch" -> "S (Watch)"
                "player" -> "S (Player)"
                "plus" -> "S (Plus)"
                else -> "S (${currentFolder.uppercase()})"
            }
        }
        tvServerBadge.text = "$label ▾"
        tvLandscapeServerBadge.text = "$label ▾"
    }

    private data class ServerOption(
        val label: String,
        val isDirect: Boolean,
        val directUrl: String? = null,
        val folder: String? = null
    )

    private fun showServerSelectionDialog() {
        val currentChannel = repository.getChannels(TabFilter.PORTUGAL).firstOrNull { it.id == channelId }
            ?: repository.getChannels(TabFilter.ALL).firstOrNull { it.id == channelId }

        val options = mutableListOf<ServerOption>()

        // 1. Direct stream 1
        val s1Direct = if (currentChannel != null && currentChannel.id.toIntOrNull() == null) {
            currentChannel.backupStreamUrl ?: directStreamUrl
        } else if (directStreamUrl != null) {
            directStreamUrl
        } else null

        if (s1Direct != null) {
            val s1Title = when {
                s1Direct.contains("impresa.pt") -> "Servidor 1: SIC Oficial (Full HD Direto)"
                s1Direct.contains("rtp.pt") -> "Servidor 1: RTP Oficial (Direto)"
                s1Direct.contains("github.com") || s1Direct.contains("TVI") -> "Servidor 1: TVI Oficial (Direto)"
                s1Direct.contains("cloudfront") -> "Servidor 1: Canal 11 Oficial (Direto)"
                s1Direct.contains("fastly") -> "Servidor 1: Porto Canal Oficial (Direto)"
                s1Direct.contains("exmxbxe") -> "Servidor 1: TimStreams (Full HD Direto)"
                s1Direct.contains("epicsports") || s1Direct.contains("ntv.st") -> "Servidor 1: NTV / EpicSports (Full HD Direto)"
                else -> "Servidor 1: Direto Principal"
            }
            options.add(ServerOption(s1Title, isDirect = true, directUrl = s1Direct))
        }

        // 2. DaddyLive
        val hasDaddyLive = (currentChannel?.id?.toIntOrNull() != null) || (channelId.toIntOrNull() != null)
        if (hasDaddyLive) {
            options.add(ServerOption("Servidor ${options.size + 1}: DaddyLive (Stream Web • Recomendado)", isDirect = false, folder = "stream"))
        }

        // 3. Backup direct stream 1
        val s2Direct = if (currentChannel != null && currentChannel.id.toIntOrNull() == null) {
            currentChannel.backupStreamUrl2
        } else {
            currentChannel?.backupStreamUrl ?: backupDirectUrl
        }

        if (s2Direct != null && options.none { it.directUrl == s2Direct }) {
            val s2Title = when {
                s2Direct.contains("epicsports") || s2Direct.contains("ntv.st") -> "Servidor ${options.size + 1}: NTV / EpicSports (Full HD Direto)"
                s2Direct.contains("exmxbxe") -> "Servidor ${options.size + 1}: TimStreams (Full HD Direto)"
                s2Direct.contains("rtp.pt") -> "Servidor ${options.size + 1}: RTP Oficial (Direto)"
                s2Direct.contains("impresa.pt") -> "Servidor ${options.size + 1}: SIC Oficial (Direto)"
                else -> "Servidor ${options.size + 1}: Servidor Backup (Direto)"
            }
            options.add(ServerOption(s2Title, isDirect = true, directUrl = s2Direct))
        }

        // 4. Backup direct stream 2
        val s3Direct = currentChannel?.backupStreamUrl2
        if (s3Direct != null && options.none { it.directUrl == s3Direct }) {
            val s3Title = when {
                s3Direct.contains("rtp.pt") -> "Servidor ${options.size + 1}: RTP Oficial M3UPT (Direto)"
                s3Direct.contains("epicsports") || s3Direct.contains("ntv.st") -> "Servidor ${options.size + 1}: NTV / EpicSports (Full HD Direto)"
                s3Direct.contains("exmxbxe") -> "Servidor ${options.size + 1}: TimStreams (Full HD Direto)"
                else -> "Servidor ${options.size + 1}: Servidor Backup 2 (Direto)"
            }
            options.add(ServerOption(s3Title, isDirect = true, directUrl = s3Direct))
        }

        // 5. DaddyLive mirrors
        if (hasDaddyLive) {
            options.add(ServerOption("Servidor Espelho: Cast (Muito Estável)", isDirect = false, folder = "cast"))
            options.add(ServerOption("Servidor Espelho: Watch", isDirect = false, folder = "watch"))
            options.add(ServerOption("Servidor Espelho: Player (HTML5)", isDirect = false, folder = "player"))
            options.add(ServerOption("Servidor Espelho: Plus (1080p)", isDirect = false, folder = "plus"))
        }

        if (options.isEmpty()) {
            options.add(ServerOption("Servidor 1: Stream Padrão", isDirect = false, folder = "stream"))
        }

        val titles = options.map { it.label }.toTypedArray()
        val currentSelectedIndex = options.indexOfFirst { opt ->
            if (opt.isDirect) {
                activeDirectUrl == opt.directUrl
            } else {
                activeDirectUrl == null && currentFolder == opt.folder
            }
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Selecionar Servidor")
            .setSingleChoiceItems(titles, currentSelectedIndex) { dialog, which ->
                dialog.dismiss()
                val selected = options[which]
                if (selected.isDirect) {
                    activeDirectUrl = selected.directUrl
                } else {
                    activeDirectUrl = null
                    currentFolder = selected.folder ?: "stream"
                }
                updateServerBadgeText()
                loadCurrentStream()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.allowFileAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        webView.setBackgroundColor(Color.BLACK)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isClickable = true
        webView.requestFocus()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val startScript = """
                (function() {
                    window.open = function() { return null; };
                    window.alert = function() {};
                    window.confirm = function() { return true; };
                    window.prompt = function() { return null; };
                    
                    var origCreateElement = document.createElement;
                    document.createElement = function(tag) {
                        var el = origCreateElement.call(document, tag);
                        if (tag.toLowerCase() === 'a') {
                            el.target = '_self';
                        }
                        return el;
                    };

                    function cleanPlayer() {
                        var style = document.getElementById('dlive-clean-style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = 'dlive-clean-style';
                            style.innerHTML = 'header, footer, .sidebar, .navbar, .mobileBottomNav, #chatangoMount, .drawer, .api-container, [id^="histats"], iframe:not(#thatframe):not([id^="player"]) { display: none !important; } ' +
                                              'html, body { margin:0 !important; padding:0 !important; background-color:#000 !important; overflow:hidden !important; width:100% !important; height:100% !important; } ' +
                                              'iframe#thatframe, .preview-wrap, #player { position:fixed !important; top:0 !important; left:0 !important; width:100% !important; height:100% !important; z-index:2147483640 !important; pointer-events:auto !important; border:none !important; } ' +
                                              '[data-fullscreen], .media-control-button[data-fullscreen], .player-fullscreen-button, .jw-icon-fullscreen, .vjs-fullscreen-control, .plyr__control--fullscreen, [data-plyr="fullscreen"], button[title*="fullscreen" i], button[title*="full screen" i], button[aria-label*="fullscreen" i], button[aria-label*="full screen" i], button[title*="ecrã inteiro" i], button[aria-label*="ecrã inteiro" i], .fullscreen-button, .fullscreen-btn, .btn-fullscreen, .fs-btn, .plyr__controls__item[data-plyr="fullscreen"] { display: none !important; pointer-events: none !important; visibility: hidden !important; width: 0 !important; height: 0 !important; }';
                            document.head.appendChild(style);
                        }
                    }

                    function performUnmute() {
                        var media = document.querySelectorAll('video, audio');
                        for (var i = 0; i < media.length; i++) {
                            try {
                                var m = media[i];
                                if (m.muted) m.muted = false;
                                if (m.defaultMuted) m.defaultMuted = false;
                                m.volume = 1.0;
                                if (m.paused) m.play().catch(function(){});
                            } catch(e) {}
                        }

                        var btnSelectors = [
                            '#unmuteBtn', '#unmute', '.unmute-btn', '.unmute',
                            '[id*="unmute" i]', '[class*="unmute" i]',
                            'button[aria-label*="unmute" i]', 'button[title*="unmute" i]',
                            '.jw-icon-volume', '.jw-icon-volume-off', '.vjs-mute-control'
                        ];
                        for (var s = 0; s < btnSelectors.length; s++) {
                            var btns = document.querySelectorAll(btnSelectors[s]);
                            for (var b = 0; b < btns.length; b++) {
                                try {
                                    btns[b].click();
                                } catch(e) {}
                            }
                        }

                        try {
                            if (window.jwplayer && typeof window.jwplayer === 'function') {
                                var jw = window.jwplayer();
                                if (jw && typeof jw.setMute === 'function') {
                                    jw.setMute(false);
                                    jw.setVolume(100);
                                }
                            }
                        } catch(e) {}
                    }

                    var unmuteInterval = setInterval(performUnmute, 350);
                    setTimeout(function() { clearInterval(unmuteInterval); }, 12000);

                    window.addEventListener('click', performUnmute, true);
                    window.addEventListener('touchstart', performUnmute, true);

                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', cleanPlayer);
                    } else {
                        cleanPlayer();
                    }
                })();
            """.trimIndent()
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, startScript, setOf("*"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase() ?: return null
                val adBlockPatterns = listOf(
                    "piousshiners", "nanisms", "histats", "premiumvertising",
                    "dzlhwcoblmc", "azrmpjyh", "smartbanner", "adsco",
                    "popads", "popunder", "creativecdn", "doubleclick",
                    "googlesyndication", "monetag", "trafficjunky",
                    "adservice", "chatango", "onclicksuper", "syndication",
                    "exdynsrv", "adsystem", "adnxs", "burstyflavia",
                    "profitableratecpmnetwork", "cleverwebserver", "adsboosters"
                )
                if (adBlockPatterns.any { url.contains(it) }) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = request.url?.host ?: ""

                if (request?.isForMainFrame == false) {
                    return false
                }

                val isAllowed = host.contains("dlive.sx") ||
                        host.contains("daddylive") ||
                        host.contains("thedaddy") ||
                        host.contains("dlhd") ||
                        host.contains("wideiptv") ||
                        host.contains("assetrage") ||
                        host.contains("exmxbxe") ||
                        host.contains("timst") ||
                        host.contains("tim-streams") ||
                        host.contains("ntv.st") ||
                        host.contains("epicsports") ||
                        host.contains(".cfd") ||
                        url.startsWith("blob:") ||
                        url.startsWith("data:")

                if (!isAllowed) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                injectCleanPlayerStyle(view)
                startAutoUnmuteSequence()
                // Mark success slightly delayed to allow player to initialize
                handler.postDelayed({ markStreamSuccess() }, 3000)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    // Main frame failed to load — trigger failover immediately
                    handler.removeCallbacks(failoverTimeoutRunnable)
                    if (currentFailoverIndex < failoverServers.size - 1) {
                        currentFailoverIndex++
                        val next = failoverServers[currentFailoverIndex]
                        runOnUiThread {
                            Toast.makeText(this@PlayerActivity, "Stream indisponível. A tentar alternativa...", Toast.LENGTH_SHORT).show()
                            applyServerOption(next)
                        }
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view
                customViewCallback = callback
                customViewContainer.addView(view)
                customViewContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                customViewContainer.removeView(customView)
                customView = null
                customViewContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun injectCleanPlayerStyle(view: WebView?) {
        val js = """
            (function() {
                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = 'header, footer, nav, .site-header, .site-footer, .watch-channel-header, .watch-controls-bar, .watch-sidebar, .watch-chat, .chat-panel, #shareCodeOverlay, .sidebar, .navbar, .mobileBottomNav, #chatangoMount, .drawer, .api-container, [id^="histats"], iframe:not(#thatframe):not([id^="player"]):not(#streamPlayer) { display: none !important; } ' +
                                  'html, body { margin:0 !important; padding:0 !important; background-color:#000 !important; overflow:hidden !important; width:100% !important; height:100% !important; } ' +
                                  'iframe#thatframe, .preview-wrap, #player, iframe#streamPlayer, .watch-player-wrapper, video#video { position:fixed !important; top:0 !important; left:0 !important; width:100% !important; height:100% !important; z-index:2147483640 !important; pointer-events:auto !important; border:none !important; } ' +
                                  '[data-fullscreen], .media-control-button[data-fullscreen], .player-fullscreen-button, .jw-icon-fullscreen, .vjs-fullscreen-control, .plyr__control--fullscreen, [data-plyr="fullscreen"], button[title*="fullscreen" i], button[title*="full screen" i], button[aria-label*="fullscreen" i], button[aria-label*="full screen" i], button[title*="ecrã inteiro" i], button[aria-label*="ecrã inteiro" i], .fullscreen-button, .fullscreen-btn, .btn-fullscreen, .fs-btn, .plyr__controls__item[data-plyr="fullscreen"] { display: none !important; pointer-events: none !important; visibility: hidden !important; width: 0 !important; height: 0 !important; }';
                document.head.appendChild(style);

                window.open = function() { return null; };

                var children = document.body.children;
                for (var i = 0; i < children.length; i++) {
                    var el = children[i];
                    if (el.tagName !== 'SCRIPT' && el.tagName !== 'STYLE' && el.id !== 'thatframe' && el.id !== 'player' && el.id !== 'streamPlayer' && !el.classList.contains('preview-wrap') && !el.classList.contains('watch-player-wrapper') && !el.contains(document.getElementById('thatframe')) && !el.contains(document.getElementById('streamPlayer'))) {
                        el.style.display = 'none';
                    }
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun startAutoUnmuteSequence() {
        if (!repository.isAutoUnmuteEnabled()) return
        val unmuteJs = """
            (function() {
                var fsSel = '[data-fullscreen], .media-control-button[data-fullscreen], .player-fullscreen-button, .jw-icon-fullscreen, .vjs-fullscreen-control, .plyr__control--fullscreen, [data-plyr="fullscreen"], button[title*="fullscreen" i], button[title*="full screen" i], button[aria-label*="fullscreen" i], button[aria-label*="full screen" i], .fullscreen-button, .fullscreen-btn, .btn-fullscreen, .fs-btn, .plyr__controls__item[data-plyr="fullscreen"]';
                document.querySelectorAll(fsSel).forEach(function(b) { b.style.setProperty('display', 'none', 'important'); });

                function unmuteDom(root) {
                    if (!root) return;
                    try {
                        root.querySelectorAll('video, audio').forEach(function(v) {
                            try {
                                v.muted = false;
                                v.defaultMuted = false;
                                v.volume = 1.0;
                                if (v.paused) v.play().catch(function(){});
                            } catch(e) {}
                        });
                        var btnSelectors = [
                            '#unmuteBtn', '#unmute', '.unmute-btn', '.unmute',
                            '[id*="unmute" i]', '[class*="unmute" i]',
                            'button[aria-label*="unmute" i]', 'button[title*="unmute" i]',
                            '.jw-icon-volume', '.jw-icon-volume-off', '.vjs-mute-control'
                        ];
                        btnSelectors.forEach(function(sel) {
                            root.querySelectorAll(sel).forEach(function(b) {
                                try { b.click(); } catch(e) {}
                            });
                        });
                    } catch(e) {}
                }

                unmuteDom(document);

                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    try {
                        var doc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                        if (doc) unmuteDom(doc);
                    } catch(e) {}
                }

                try {
                    if (window.jwplayer && typeof window.jwplayer === 'function') {
                        var jw = window.jwplayer();
                        if (jw && typeof jw.setMute === 'function') {
                            jw.setMute(false);
                            jw.setVolume(100);
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        val delays = listOf(500L, 1200L, 2500L, 4500L)
        for (d in delays) {
            handler.postDelayed({
                try {
                    webView.evaluateJavascript(unmuteJs, null)
                } catch (e: Exception) {}
            }, d)
        }
    }

    private fun loadCurrentStream() {
        progressBar.visibility = View.VISIBLE
        streamLoadedSuccessfully = false
        handler.removeCallbacks(failoverTimeoutRunnable)

        // Build failover list on first load for this channel
        if (failoverServers.isEmpty()) {
            failoverServers = buildFailoverList()
            currentFailoverIndex = 0
        }

        val targetDirect = activeDirectUrl ?: directStreamUrl
        if (targetDirect != null) {
            val referer = when {
                targetDirect.contains("impresa.pt") -> "https://sic.pt/"
                targetDirect.contains("rtp.pt") -> "https://www.rtp.pt/"
                targetDirect.contains("TVI") || targetDirect.contains("iol.pt") || targetDirect.contains("raw.githubusercontent.com") -> "https://tviplayer.iol.pt/"
                targetDirect.contains("epicsports") || targetDirect.contains("ntv.st") -> "https://ntv.st/"
                else -> "${repository.getTimstBaseUrl()}/"
            }
            if (targetDirect.contains(".m3u8")) {
                val hlsHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            * { margin:0; padding:0; background:#000; overflow:hidden; }
                            video { width:100vw; height:100vh; object-fit:contain; }
                        </style>
                        <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
                    </head>
                    <body>
                        <video id="v" autoplay controls playsinline></video>
                        <script>
                            var video = document.getElementById('v');
                            var src = '$targetDirect';
                            if (Hls.isSupported()) {
                                var hls = new Hls({ enableWorker: true });
                                hls.loadSource(src);
                                hls.attachMedia(video);
                                hls.on(Hls.Events.MANIFEST_PARSED, function() { video.play().catch(function(){}); });
                            } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                                video.src = src;
                                video.play().catch(function(){});
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(referer, hlsHtml, "text/html", "UTF-8", null)
            } else {
                val headers = mapOf("Referer" to referer)
                webView.loadUrl(targetDirect, headers)
            }
            // Start failover timeout
            handler.postDelayed(failoverTimeoutRunnable, failoverTimeoutMs)
            return
        }

        val baseUrl = repository.getBaseUrl()
        val url = "$baseUrl/$currentFolder/stream-$channelId.php"
        webView.loadUrl(url)
        // Start failover timeout for WebView streams too
        handler.postDelayed(failoverTimeoutRunnable, failoverTimeoutMs)
    }

    private fun buildFailoverList(): List<ServerOption> {
        val currentChannel = repository.getChannels(TabFilter.PORTUGAL).firstOrNull { it.id == channelId }
            ?: repository.getChannels(TabFilter.ALL).firstOrNull { it.id == channelId }

        val options = mutableListOf<ServerOption>()

        if (currentChannel == null) {
            if (!directStreamUrl.isNullOrBlank()) {
                options.add(ServerOption("Servidor 1", isDirect = true, directUrl = directStreamUrl))
            }
            if (!backupDirectUrl.isNullOrBlank()) {
                options.add(ServerOption("Servidor 2", isDirect = true, directUrl = backupDirectUrl))
            }
            if (!backupDirectUrl2.isNullOrBlank()) {
                options.add(ServerOption("Servidor 3", isDirect = true, directUrl = backupDirectUrl2))
            }
            return options
        }

        // 1. DaddyLive (highest priority if channel has numeric ID)
        val hasDaddyLive = (currentChannel?.id?.toIntOrNull() != null) || (channelId.toIntOrNull() != null)
        if (hasDaddyLive) {
            options.add(ServerOption("DaddyLive", isDirect = false, folder = "stream"))
        }

        // 2. NTV/EpicSports backup
        val ntvUrl = currentChannel?.backupStreamUrl
        if (ntvUrl != null && (ntvUrl.contains("epicsports") || ntvUrl.contains("ntv.st"))) {
            options.add(ServerOption("NTV", isDirect = true, directUrl = ntvUrl))
        }

        // 3. TimStreams backup
        val timstUrl = if (ntvUrl != null && ntvUrl.contains("exmxbxe")) ntvUrl
            else currentChannel?.backupStreamUrl2?.takeIf { it.contains("exmxbxe") }
        if (timstUrl != null) {
            options.add(ServerOption("TimStreams", isDirect = true, directUrl = timstUrl))
        }

        // 4. M3UPT / Official direct stream
        val officialUrl = currentChannel?.backupStreamUrl2?.takeIf {
            it.contains("rtp.pt") || it.contains("impresa.pt") || it.contains("github.com") ||
            it.contains("cloudfront") || it.contains("fastly") || it.contains("livextend")
        } ?: currentChannel?.backupStreamUrl?.takeIf {
            it.contains("rtp.pt") || it.contains("impresa.pt") || it.contains("github.com") ||
            it.contains("cloudfront") || it.contains("fastly") || it.contains("livextend")
        }
        if (officialUrl != null && options.none { it.directUrl == officialUrl }) {
            options.add(ServerOption("Oficial", isDirect = true, directUrl = officialUrl))
        }

        // 5. Non-NTV backup (if backupStreamUrl is not NTV)
        if (ntvUrl != null && !ntvUrl.contains("epicsports") && !ntvUrl.contains("ntv.st") && !ntvUrl.contains("exmxbxe") && options.none { it.directUrl == ntvUrl }) {
            options.add(ServerOption("Backup", isDirect = true, directUrl = ntvUrl))
        }

        // 6. DaddyLive mirrors as last resort
        if (hasDaddyLive) {
            options.add(ServerOption("Cast", isDirect = false, folder = "cast"))
            options.add(ServerOption("Watch", isDirect = false, folder = "watch"))
        }

        return options
    }

    private fun applyServerOption(option: ServerOption) {
        handler.removeCallbacks(failoverTimeoutRunnable)
        streamLoadedSuccessfully = false
        if (option.isDirect) {
            activeDirectUrl = option.directUrl
        } else {
            activeDirectUrl = null
            currentFolder = option.folder ?: "stream"
        }
        updateServerBadgeText()
        loadCurrentStream()
    }

    /**
     * Called from WebView when video starts playing (progress > 90%)
     */
    private fun markStreamSuccess() {
        streamLoadedSuccessfully = true
        handler.removeCallbacks(failoverTimeoutRunnable)
    }

    private fun zapNextChannel() {
        val list = repository.getChannels(currentDrawerTab)
        if (list.isEmpty()) return
        val currentIndex = list.indexOfFirst { it.id == channelId }
        val nextIndex = if (currentIndex in 0 until list.size - 1) currentIndex + 1 else 0
        switchChannel(list[nextIndex])
    }

    private fun zapPreviousChannel() {
        val list = repository.getChannels(currentDrawerTab)
        if (list.isEmpty()) return
        val currentIndex = list.indexOfFirst { it.id == channelId }
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else list.size - 1
        switchChannel(list[prevIndex])
    }

    private fun toggleControlsOverlay() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            if (landscapeOverlay.visibility == View.VISIBLE) {
                landscapeOverlay.visibility = View.GONE
                handler.removeCallbacks(overlayHideRunnable)
            } else {
                landscapeOverlay.visibility = View.VISIBLE
                findViewById<ImageButton>(R.id.btnLandscapeChannels)?.requestFocus()
                handler.removeCallbacks(overlayHideRunnable)
                handler.postDelayed(overlayHideRunnable, 5000)
            }
        } else {
            playerHeader.visibility = if (playerHeader.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        zapPreviousChannel()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        zapNextChannel()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> {
                    if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        openDrawer()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        toggleControlsOverlay()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                        return true
                    }
                    handleBackOrPip()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyFullscreenMode(isLandscape)
    }

    private fun applyFullscreenMode(isLandscape: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (isLandscape) {
            playerHeader.visibility = View.GONE
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

            landscapeOverlay.visibility = View.VISIBLE
            handler.removeCallbacks(overlayHideRunnable)
            handler.postDelayed(overlayHideRunnable, 3000)

            injectCleanPlayerStyle(webView)
        } else {
            playerHeader.visibility = View.VISIBLE
            landscapeOverlay.visibility = View.GONE
            handler.removeCallbacks(overlayHideRunnable)

            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun enterPipMode(bringHomeToFront: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                }

                // Immediately hide header, overlays, banner, progress before entering PiP
                playerHeader.visibility = View.GONE
                landscapeOverlay.visibility = View.GONE
                osdBanner.visibility = View.GONE
                progressBar.visibility = View.GONE
                gestureHud.visibility = View.GONE

                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))

                val visibleRect = android.graphics.Rect()
                if (webView.getGlobalVisibleRect(visibleRect)) {
                    builder.setSourceRectHint(visibleRect)
                }

                val entered = enterPictureInPictureMode(builder.build())
                if (entered && bringHomeToFront) {
                    val homeIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(homeIntent)
                } else if (!entered && bringHomeToFront) {
                    cleanupAndFinish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (bringHomeToFront) {
                    cleanupAndFinish()
                } else {
                    Toast.makeText(this, "Não foi possível ativar PiP", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            if (bringHomeToFront) {
                cleanupAndFinish()
            } else {
                Toast.makeText(this, "PiP requer Android 8.0+", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        playerHeader.visibility = View.GONE
        landscapeOverlay.visibility = View.GONE
        osdBanner.visibility = View.GONE
        progressBar.visibility = View.GONE
        gestureHud.visibility = View.GONE
        enterPipMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerHeader.visibility = View.GONE
            landscapeOverlay.visibility = View.GONE
            osdBanner.visibility = View.GONE
            progressBar.visibility = View.GONE
            gestureHud.visibility = View.GONE
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        } else {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyFullscreenMode(isLandscape)
        }
    }

    private fun handleBackOrPip() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }

        if (repository.isAutoPipOnBack() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode(bringHomeToFront = true)
            return
        }

        cleanupAndFinish()
    }

    private fun showSettingsDialog() {
        val scroll = ScrollView(this)
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 24)
            setBackgroundColor(Color.parseColor("#141722"))
        }
        scroll.addView(dialogView)

        val title = TextView(this).apply {
            text = "⚙️ Definições"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        dialogView.addView(title)

        // 1. Auto PiP
        val swPip = Switch(this).apply {
            text = "📺 PiP Automático ao Voltar"
            setTextColor(Color.WHITE)
            isChecked = repository.isAutoPipOnBack()
            setPadding(0, 10, 0, 14)
        }
        dialogView.addView(swPip)

        // 2. Auto Unmute
        val swUnmute = Switch(this).apply {
            text = "🔊 Ativar Som Automaticamente"
            setTextColor(Color.WHITE)
            isChecked = repository.isAutoUnmuteEnabled()
            setPadding(0, 10, 0, 14)
        }
        dialogView.addView(swUnmute)

        // 3. TimStreams Base Domain
        val tvTimstTitle = TextView(this).apply {
            text = "🔗 Domínio TimStreams (Jogos/Backup):"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            setPadding(0, 14, 0, 6)
        }
        dialogView.addView(tvTimstTitle)

        val etTimstDomain = EditText(this).apply {
            setText(repository.getTimstBaseUrl())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#202432"))
            setPadding(24, 16, 24, 16)
            textSize = 14f
            isSingleLine = true
        }
        dialogView.addView(etTimstDomain)

        // 4. DaddyLive Base Domain
        val tvDomainTitle = TextView(this).apply {
            text = "🔗 Domínio DaddyLive (Base URL):"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 13f
            setPadding(0, 14, 0, 6)
        }
        dialogView.addView(tvDomainTitle)

        val etDomain = EditText(this).apply {
            setText(repository.getBaseUrl())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#202432"))
            setPadding(24, 16, 24, 16)
            textSize = 14f
            isSingleLine = true
        }
        dialogView.addView(etDomain)

        // 5. Sync button
        val btnSync = Button(this).apply {
            text = "🔄 Sincronizar Canais Online Agora"
            setBackgroundColor(Color.parseColor("#E50914"))
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            layoutParams = params
            setOnClickListener {
                isEnabled = false
                text = "A sincronizar..."
                repository.syncChannelsFromWeb(lifecycleScope) { success ->
                    runOnUiThread {
                        isEnabled = true
                        text = "🔄 Sincronizar Canais Online Agora"
                        val msg = if (success) "Canais sincronizados com sucesso!" else "Falha ao sincronizar online."
                        Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_SHORT).show()
                        refreshDrawerList()
                    }
                }
            }
        }
        // 6. Restore hidden channels button
        val hiddenIds = repository.getHiddenChannelIds()
        if (hiddenIds.isNotEmpty()) {
            val btnRestoreHidden = Button(this).apply {
                text = "👁️ Restaurar Canais Ocultos (${hiddenIds.size})"
                setBackgroundColor(Color.parseColor("#374151"))
                setTextColor(Color.WHITE)
                setPadding(0, 10, 0, 10)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
                layoutParams = params
                setOnClickListener {
                    repository.unhideAllChannels()
                    Toast.makeText(this@PlayerActivity, "Canais restaurados!", Toast.LENGTH_SHORT).show()
                    visibility = View.GONE
                    refreshDrawerList()
                }
            }
            dialogView.addView(btnRestoreHidden)
        }

        AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Guardar") { _, _ ->
                repository.setAutoPipOnBack(swPip.isChecked)
                repository.setAutoUnmuteEnabled(swUnmute.isChecked)
                val domain = etDomain.text.toString().trim()
                if (domain.isNotBlank()) repository.setBaseUrl(domain)
                val timst = etTimstDomain.text.toString().trim()
                if (timst.isNotBlank()) repository.setTimstBaseUrl(timst)
                Toast.makeText(this, "Definições guardadas!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape || drawerLayout.isDrawerOpen(GravityCompat.START)) {
            return super.dispatchTouchEvent(ev)
        }

        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchStartX = ev.x
                touchStartY = ev.y
                isSwipingGesture = false
                gestureMode = GestureMode.NONE

                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val lp = window.attributes
                initialBrightness = if (lp.screenBrightness >= 0f) {
                    lp.screenBrightness
                } else {
                    try {
                        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
                    } catch (e: Exception) {
                        0.5f
                    }
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val deltaX = ev.x - touchStartX
                val deltaY = touchStartY - ev.y // Swiping UP is positive, DOWN is negative
                val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

                if (!isSwipingGesture) {
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX) * 1.2f) {
                        isSwipingGesture = true
                        val screenWidth = resources.displayMetrics.widthPixels
                        gestureMode = if (touchStartX < screenWidth / 2f) GestureMode.VOLUME else GestureMode.BRIGHTNESS
                    }
                }

                if (isSwipingGesture) {
                    val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                    val deltaFraction = deltaY / (screenHeight * 0.7f)

                    if (gestureMode == GestureMode.VOLUME) {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetVol = ((initialVolume.toFloat() / maxVol + deltaFraction) * maxVol).roundToInt().coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                        val percent = (targetVol * 100 / maxVol)
                        showGestureHud(isVolume = true, percent = percent)
                    } else if (gestureMode == GestureMode.BRIGHTNESS) {
                        val targetBrightness = (initialBrightness + deltaFraction).coerceIn(0.01f, 1.0f)
                        val lp = window.attributes
                        lp.screenBrightness = targetBrightness
                        window.attributes = lp
                        val percent = (targetBrightness * 100).roundToInt()
                        showGestureHud(isVolume = false, percent = percent)
                    }
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (isSwipingGesture) {
                    isSwipingGesture = false
                    gestureMode = GestureMode.NONE
                    handler.removeCallbacks(gestureHudHideRunnable)
                    handler.postDelayed(gestureHudHideRunnable, 900)
                    return true
                } else {
                    // Tap toggle overlay
                    if (landscapeOverlay.visibility == View.VISIBLE) {
                        landscapeOverlay.visibility = View.GONE
                        handler.removeCallbacks(overlayHideRunnable)
                    } else {
                        landscapeOverlay.visibility = View.VISIBLE
                        handler.removeCallbacks(overlayHideRunnable)
                        handler.postDelayed(overlayHideRunnable, 4000)
                    }
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackOrPip()
    }

    override fun onPause() {
        super.onPause()
        webView.resumeTimers()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            webView.resumeTimers()
        }
    }

    fun cleanupAndFinish() {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } catch (_: Exception) {}
        finish()
        try {
            val homeIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(homeIntent)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (activeInstance?.get() == this) {
            activeInstance = null
        }
        handler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
