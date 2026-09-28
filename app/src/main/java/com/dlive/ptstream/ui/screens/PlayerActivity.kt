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
    private var isBackupSelected: Boolean = false
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

        btnTabPt.setOnClickListener { selectDrawerTab(TabFilter.PORTUGAL) }
        btnTabTimst.setOnClickListener { selectDrawerTab(TabFilter.TIMSTREAMS) }
        btnTabFav.setOnClickListener { selectDrawerTab(TabFilter.FAVORITES) }
        btnTabAll.setOnClickListener { selectDrawerTab(TabFilter.ALL) }

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
        if (newChannel.id == channelId && directStreamUrl == null) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }

        channelId = newChannel.id
        channelName = newChannel.name
        backupDirectUrl = newChannel.backupStreamUrl

        if (newChannel.id.startsWith("timst-") || newChannel.id.startsWith("ntv-") || newChannel.category == "TimStreams") {
            directStreamUrl = newChannel.backupStreamUrl
            isBackupSelected = false
        } else {
            directStreamUrl = null
            isBackupSelected = false
        }

        tvChannelTitle.text = channelName
        tvLandscapeTitle.text = channelName

        drawerLayout.closeDrawer(GravityCompat.START)

        val tag = if (newChannel.isPortuguese) "PT 🇵🇹" else newChannel.country
        showOsdBanner(channelId, channelName, tag)

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
        val currentChannel = repository.getChannels(TabFilter.ALL).firstOrNull { it.id == channelId }
        val hasBackup = currentChannel?.backupStreamUrl != null || backupDirectUrl != null

        val label = if (isBackupSelected) {
            val backupName = if (currentChannel?.backupStreamUrl?.contains("epicsports") == true || currentChannel?.backupStreamUrl?.contains("ntv.st") == true) "NTV" else "TimST"
            "S2 ($backupName)"
        } else if (directStreamUrl != null) {
            if (directStreamUrl!!.contains("epicsports") || directStreamUrl!!.contains("ntv.st")) "NTV Direto" else "TimST Principal"
        } else {
            when (currentFolder) {
                "stream" -> "S1 (DLive)"
                "cast" -> if (hasBackup) "S3 (Cast)" else "S2 (Cast)"
                "watch" -> if (hasBackup) "S4 (Watch)" else "S3 (Watch)"
                "player" -> if (hasBackup) "S5 (Player)" else "S4 (Player)"
                "plus" -> if (hasBackup) "S6 (Plus)" else "S5 (Plus)"
                else -> currentFolder.uppercase()
            }
        }
        tvServerBadge.text = "$label ▾"
        tvLandscapeServerBadge.text = "$label ▾"
    }

    private fun showServerSelectionDialog() {
        val currentChannel = repository.getChannels(TabFilter.ALL).firstOrNull { it.id == channelId }
        val hasBackup = currentChannel?.backupStreamUrl != null || backupDirectUrl != null

        val options: List<String>
        val currentSelectedIndex: Int

        if (directStreamUrl != null) {
            val serverName = if (directStreamUrl!!.contains("epicsports") || directStreamUrl!!.contains("ntv.st")) "NTV / EpicSports (Full HD Direto)" else "TimStreams (Full HD Direto)"
            options = if (backupDirectUrl != null && backupDirectUrl != directStreamUrl) {
                listOf(
                    "Servidor 1: $serverName",
                    "Servidor 2: Backup (Direto)"
                )
            } else {
                listOf("Servidor 1: $serverName")
            }
            currentSelectedIndex = if (isBackupSelected) 1 else 0
        } else {
            if (hasBackup) {
                val backupTitle = if (currentChannel?.backupStreamUrl?.contains("epicsports") == true || currentChannel?.backupStreamUrl?.contains("ntv.st") == true) {
                    "Servidor 2: NTV / EpicSports (Full HD Direto)"
                } else {
                    "Servidor 2: TimStreams (Full HD Direto)"
                }
                options = listOf(
                    "Servidor 1: DaddyLive (Stream Web • Recomendado)",
                    backupTitle,
                    "Servidor 3: Cast (Muito Estável)",
                    "Servidor 4: Watch (Espelho)",
                    "Servidor 5: Player (HTML5)",
                    "Servidor 6: Plus (Alta Definição • 1080p)"
                )
                currentSelectedIndex = if (isBackupSelected) {
                    1
                } else {
                    when (currentFolder) {
                        "stream" -> 0
                        "cast" -> 2
                        "watch" -> 3
                        "player" -> 4
                        "plus" -> 5
                        else -> 0
                    }
                }
            } else {
                options = listOf(
                    "Servidor 1: Stream (Rápido • Recomendado)",
                    "Servidor 2: Cast (Muito Estável)",
                    "Servidor 3: Watch (Espelho)",
                    "Servidor 4: Player (HTML5)",
                    "Servidor 5: Plus (Alta Definição • 1080p)"
                )
                currentSelectedIndex = serverFolders.indexOf(currentFolder).coerceAtLeast(0)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Selecionar Servidor")
            .setSingleChoiceItems(options.toTypedArray(), currentSelectedIndex) { dialog, which ->
                dialog.dismiss()
                if (directStreamUrl != null) {
                    isBackupSelected = (which == 1)
                } else if (hasBackup) {
                    when (which) {
                        0 -> {
                            isBackupSelected = false
                            currentFolder = "stream"
                        }
                        1 -> {
                            isBackupSelected = true
                        }
                        2 -> {
                            isBackupSelected = false
                            currentFolder = "cast"
                        }
                        3 -> {
                            isBackupSelected = false
                            currentFolder = "watch"
                        }
                        4 -> {
                            isBackupSelected = false
                            currentFolder = "player"
                        }
                        5 -> {
                            isBackupSelected = false
                            currentFolder = "plus"
                        }
                    }
                } else {
                    isBackupSelected = false
                    currentFolder = serverFolders.getOrElse(which) { "stream" }
                }
                updateServerBadgeText()
                loadCurrentStream()
            }
            .setNegativeButton("Cancelar", null)
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
        handler.postDelayed({
            val js = """
                (function() {
                    var fsSel = '[data-fullscreen], .media-control-button[data-fullscreen], .player-fullscreen-button, .jw-icon-fullscreen, .vjs-fullscreen-control, .plyr__control--fullscreen, [data-plyr="fullscreen"], button[title*="fullscreen" i], button[title*="full screen" i], button[aria-label*="fullscreen" i], button[aria-label*="full screen" i], .fullscreen-button, .fullscreen-btn, .btn-fullscreen, .fs-btn, .plyr__controls__item[data-plyr="fullscreen"]';
                    document.querySelectorAll(fsSel).forEach(function(b) { b.style.setProperty('display', 'none', 'important'); });

                    var btn = document.getElementById('unmute') || document.querySelector('.unmute-btn');
                    if (btn) { btn.click(); btn.style.display = 'none'; }
                    var iframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        try {
                            var doc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            if (doc) {
                                var innerBtn = doc.getElementById('unmute') || doc.querySelector('.unmute-btn');
                                if (innerBtn) { innerBtn.click(); innerBtn.style.display = 'none'; }
                                doc.querySelectorAll(fsSel).forEach(function(b) { b.style.setProperty('display', 'none', 'important'); });
                            }
                        } catch(e) {}
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }, 2200)
    }

    private fun loadCurrentStream() {
        progressBar.visibility = View.VISIBLE

        if (directStreamUrl != null) {
            val targetUrl = if (isBackupSelected && backupDirectUrl != null) backupDirectUrl!! else directStreamUrl!!
            val referer = if (targetUrl.contains("epicsports") || targetUrl.contains("ntv.st")) {
                "https://ntv.st/"
            } else {
                "${repository.getTimstBaseUrl()}/"
            }
            val headers = mapOf("Referer" to referer)
            webView.loadUrl(targetUrl, headers)
            return
        }

        val currentChannel = repository.getChannels(TabFilter.ALL).firstOrNull { it.id == channelId }
        if (isBackupSelected && currentChannel?.backupStreamUrl != null) {
            val targetUrl = currentChannel.backupStreamUrl!!
            val referer = if (targetUrl.contains("epicsports") || targetUrl.contains("ntv.st")) {
                "https://ntv.st/"
            } else {
                "${repository.getTimstBaseUrl()}/"
            }
            val headers = mapOf("Referer" to referer)
            webView.loadUrl(targetUrl, headers)
            return
        }

        val baseUrl = repository.getBaseUrl()
        val url = "$baseUrl/$currentFolder/stream-$channelId.php"
        webView.loadUrl(url)
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
