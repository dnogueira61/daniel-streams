package com.dlive.ptstream.ui.screens

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.ViewParent
import android.view.animation.AccelerateDecelerateInterpolator
import coil.load
import com.dlive.ptstream.data.ChannelLogoHelper
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
                        try {
                            activity.webView.stopLoading()
                            activity.webView.loadUrl("about:blank")
                        } catch (_: Exception) {}
                        activity.finish()
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
    private lateinit var btnTabSports: Button
    private lateinit var btnTabMovies: Button
    private lateinit var btnTabFav: Button
    private lateinit var btnTabAll: Button
    private lateinit var drawerAdapter: DrawerChannelAdapter

    // Connecting Overlay (Breathing Logo)
    private lateinit var connectingOverlay: FrameLayout
    private lateinit var ivConnectingLogo: ImageView
    private lateinit var tvConnectingChannel: TextView
    private lateinit var tvConnectingStatus: TextView
    private var breathingAnimator: ObjectAnimator? = null

    private lateinit var repository: ChannelRepository
    private var currentDrawerTab = TabFilter.PORTUGAL
    private var currentDrawerCategory: String = "Todos"

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
    private val failoverTimeoutMs = 10000L // 10 seconds before trying next server
    private var lastFailoverTime = 0L

    inner class FailoverBridge {
        @android.webkit.JavascriptInterface
        fun onPlaybackError(reason: String) {
            runOnUiThread {
                triggerFailoverDueToPlaybackError(reason)
            }
        }
    }

    private fun showSilentRecoveryHud(serverName: String) {
        runOnUiThread {
            Toast.makeText(this@PlayerActivity, "⚡ A estabilizar sinal... ($serverName)", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerFailoverDueToPlaybackError(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailoverTime < 2500L) return
        lastFailoverTime = now

        handler.removeCallbacks(failoverTimeoutRunnable)

        if (currentFailoverIndex < failoverServers.size - 1) {
            currentFailoverIndex++
            val next = failoverServers[currentFailoverIndex]
            runOnUiThread {
                Toast.makeText(this@PlayerActivity, "⚡ Erro na stream. A tentar ${next.label}...", Toast.LENGTH_SHORT).show()
                applyServerOption(next)
            }
        } else {
            runOnUiThread {
                hideConnectingOverlay()
                Toast.makeText(this@PlayerActivity, "⚠️ Todas as transmissões deste canal falharam", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val failoverTimeoutRunnable = Runnable {
        if (!streamLoadedSuccessfully) {
            triggerFailoverDueToPlaybackError("timeout")
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

        if (!channelId.startsWith("event_") && channelId.isNotBlank()) {
            repository.setLastWatchedChannelId(channelId)
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
        connectingOverlay = findViewById(R.id.connectingOverlay)
        ivConnectingLogo = findViewById(R.id.ivConnectingLogo)
        tvConnectingChannel = findViewById(R.id.tvConnectingChannel)
        tvConnectingStatus = findViewById(R.id.tvConnectingStatus)
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
            cleanupAndFinish()
        }

        findViewById<View>(R.id.btnOpenChannels).setOnClickListener {
            openDrawer()
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            loadCurrentStream()
        }

        findViewById<ImageButton>(R.id.btnPip).setOnClickListener {
            enterPipMode()
        }

        // Landscape / Fullscreen toggle with sensor support
        findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // Landscape overlay buttons
        findViewById<ImageButton>(R.id.btnLandscapeBack).setOnClickListener {
            cleanupAndFinish()
        }

        findViewById<View>(R.id.btnLandscapeChannels).setOnClickListener {
            openDrawer()
        }

        findViewById<View>(R.id.btnLandscapeUnmute).setOnClickListener {
            performSafeUnmute()
        }

        findViewById<ImageButton>(R.id.btnLandscapePip).setOnClickListener {
            enterPipMode()
        }

        val btnLandscapeExitFullscreen = findViewById<ImageButton>(R.id.btnLandscapeExitFullscreen)
        btnLandscapeExitFullscreen.setOnClickListener {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        val btnFullscreen = findViewById<ImageButton>(R.id.btnFullscreen)
        val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
                packageManager.hasSystemFeature("android.hardware.type.television")
        if (isTv) {
            btnLandscapeExitFullscreen.visibility = View.GONE
            btnFullscreen.visibility = View.GONE
        }

        // Gesture HUD & Audio
        gestureHud = findViewById(R.id.gestureHud)
        ivGestureIcon = findViewById(R.id.ivGestureIcon)
        tvGestureText = findViewById(R.id.tvGestureText)
        pbGestureProgress = findViewById(R.id.pbGestureProgress)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun showConnectingOverlay(name: String) {
        connectingOverlay.visibility = View.VISIBLE
        connectingOverlay.alpha = 1f
        tvConnectingChannel.text = name
        tvConnectingStatus.text = "A ligar à transmissão..."

        val localLogo = ChannelLogoHelper.getLocalLogoRes(name)
        val onlineLogo = ChannelLogoHelper.getLogoUrl(name)
        if (localLogo != null) {
            ivConnectingLogo.setImageResource(localLogo)
        } else if (onlineLogo != null) {
            ivConnectingLogo.load(onlineLogo) {
                crossfade(true)
                error(R.drawable.ic_app_logo)
            }
        } else {
            ivConnectingLogo.setImageResource(R.drawable.ic_app_logo)
        }

        startBreathingAnimation()
    }

    private fun startBreathingAnimation() {
        breathingAnimator?.cancel()
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1.08f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1.08f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.78f, 1.0f)
        breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(ivConnectingLogo, scaleX, scaleY, alpha).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun hideConnectingOverlay() {
        if (connectingOverlay.visibility == View.VISIBLE) {
            connectingOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    connectingOverlay.visibility = View.GONE
                    breathingAnimator?.cancel()
                    breathingAnimator = null
                }
                .start()
        }
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
        btnTabSports = findViewById(R.id.btnTabSports)
        btnTabMovies = findViewById(R.id.btnTabMovies)
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

        btnTabPt.setOnClickListener { selectDrawerCategory("Todos", TabFilter.PORTUGAL) }
        btnTabSports.setOnClickListener { selectDrawerCategory("Desporto", TabFilter.PORTUGAL) }
        btnTabMovies.setOnClickListener { selectDrawerCategory("Filmes & Séries", TabFilter.PORTUGAL) }
        btnTabFav.setOnClickListener { selectDrawerCategory("Favoritos", TabFilter.FAVORITES) }
        btnTabAll.setOnClickListener { selectDrawerCategory("Mundo", TabFilter.ALL) }

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

        selectDrawerCategory("Todos", TabFilter.PORTUGAL)
    }

    private fun openDrawer() {
        // 1. Clear any prior search query and prevent keyboard pop-up in landscape
        etDrawerSearch.setText("")
        etDrawerSearch.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etDrawerSearch.windowToken, 0)

        // 2. Refresh drawer list with current tab
        selectDrawerCategory(currentDrawerCategory, currentDrawerTab)

        // 3. Open drawer
        drawerLayout.openDrawer(GravityCompat.START)

        // 4. Focus channel list rather than search box
        handler.postDelayed({
            val list = when {
                currentDrawerTab == TabFilter.ALL -> repository.getChannels(TabFilter.ALL)
                currentDrawerTab == TabFilter.FAVORITES -> repository.getChannels(TabFilter.FAVORITES)
                currentDrawerCategory == "Desporto" -> repository.getChannels(TabFilter.PORTUGAL, "", "Desporto")
                currentDrawerCategory == "Filmes & Séries" -> repository.getChannels(TabFilter.PORTUGAL, "", "Filmes & Séries")
                else -> repository.getChannels(TabFilter.PORTUGAL)
            }
            val index = list.indexOfFirst { it.id == channelId }
            val targetPos = if (index >= 0) index else 0
            rvDrawerChannels.scrollToPosition(targetPos)
            rvDrawerChannels.post {
                val holder = rvDrawerChannels.findViewHolderForAdapterPosition(targetPos)
                holder?.itemView?.requestFocus() ?: rvDrawerChannels.requestFocus()
            }
        }, 150)
    }

    private fun selectDrawerCategory(cat: String, tab: TabFilter) {
        currentDrawerCategory = cat
        currentDrawerTab = tab
        val activeColor = ColorStateList.valueOf(Color.parseColor("#E50914"))
        val inactiveColor = ColorStateList.valueOf(Color.parseColor("#202330"))

        btnTabPt.backgroundTintList = if (cat == "Todos" && tab == TabFilter.PORTUGAL) activeColor else inactiveColor
        btnTabPt.setTextColor(if (cat == "Todos" && tab == TabFilter.PORTUGAL) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabSports.backgroundTintList = if (cat == "Desporto") activeColor else inactiveColor
        btnTabSports.setTextColor(if (cat == "Desporto") Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabMovies.backgroundTintList = if (cat == "Filmes & Séries") activeColor else inactiveColor
        btnTabMovies.setTextColor(if (cat == "Filmes & Séries") Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabFav.backgroundTintList = if (tab == TabFilter.FAVORITES) activeColor else inactiveColor
        btnTabFav.setTextColor(if (tab == TabFilter.FAVORITES) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabAll.backgroundTintList = if (tab == TabFilter.ALL) activeColor else inactiveColor
        btnTabAll.setTextColor(if (tab == TabFilter.ALL) Color.WHITE else Color.parseColor("#9CA3AF"))

        refreshDrawerList()
    }

    private fun refreshDrawerList() {
        val query = etDrawerSearch.text.toString().trim()
        val list = when {
            currentDrawerTab == TabFilter.ALL -> repository.getChannels(TabFilter.ALL, query)
            currentDrawerTab == TabFilter.FAVORITES -> repository.getChannels(TabFilter.FAVORITES, query)
            currentDrawerCategory == "Desporto" -> repository.getChannels(TabFilter.PORTUGAL, query, "Desporto")
            currentDrawerCategory == "Filmes & Séries" -> repository.getChannels(TabFilter.PORTUGAL, query, "Filmes & Séries")
            else -> repository.getChannels(TabFilter.PORTUGAL, query)
        }
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
        repository.setLastWatchedChannelId(channelId)

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
        val options = buildFailoverList()
        if (options.isEmpty()) {
            Toast.makeText(this, "Nenhum servidor alternativo disponível", Toast.LENGTH_SHORT).show()
            return
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
            .setTitle("Servidores Disponíveis")
            .setSingleChoiceItems(titles, currentSelectedIndex) { dialog, which ->
                dialog.dismiss()
                val selected = options[which]
                applyServerOption(selected)
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
        webView.addJavascriptInterface(FailoverBridge(), "AndroidFailover")

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

                    function reportFailover(reason) {
                        try {
                            var b = window.AndroidFailover || (window.top && window.top.AndroidFailover);
                            if (b && typeof b.onPlaybackError === 'function') {
                                b.onPlaybackError(reason);
                            }
                        } catch(e) {}
                    }

                    window.addEventListener('error', function(e) {
                        var msg = (e && e.message ? e.message : '').toLowerCase();
                        if (msg.indexOf('playback') !== -1 || msg.indexOf('stream') !== -1 || msg.indexOf('media') !== -1) {
                            reportFailover('window_error: ' + msg);
                        }
                    }, true);

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
                injectPlaybackErrorWatcher(view)
                handler.postDelayed({
                    hideConnectingOverlay()
                    markStreamSuccess()
                }, 1800)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    triggerFailoverDueToPlaybackError("network_error")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                    triggerFailoverDueToPlaybackError("http_error_${errorResponse?.statusCode}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Bloqueia qualquer popup / publicidade que tente abrir ao clicar no player
                return false
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                hideConnectingOverlay()
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
                val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
                        packageManager.hasSystemFeature("android.hardware.type.television")
                if (!isTv) {
                    val currentOrientation = resources.configuration.orientation
                    if (currentOrientation != Configuration.ORIENTATION_LANDSCAPE) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
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
        val safeUnmuteJs = """
            (function() {
                var fsSel = '[data-fullscreen], .media-control-button[data-fullscreen], .player-fullscreen-button, .jw-icon-fullscreen, .vjs-fullscreen-control, .plyr__control--fullscreen, [data-plyr="fullscreen"], button[title*="fullscreen" i], button[title*="full screen" i], button[aria-label*="fullscreen" i], button[aria-label*="full screen" i], .fullscreen-button, .fullscreen-btn, .btn-fullscreen, .fs-btn, .plyr__controls__item[data-plyr="fullscreen"]';
                document.querySelectorAll(fsSel).forEach(function(b) { b.style.setProperty('display', 'none', 'important'); });

                function unmuteDom(root) {
                    if (!root) return;
                    try {
                        root.querySelectorAll('video, audio').forEach(function(v) {
                            try {
                                if (v.muted) v.muted = false;
                                if (v.defaultMuted) v.defaultMuted = false;
                                v.volume = 1.0;
                            } catch(e) {}
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
                        if (jw && typeof jw.getMute === 'function' && jw.getMute()) {
                            jw.setMute(false);
                            jw.setVolume(100);
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        val delays = listOf(800L, 2000L)
        for (d in delays) {
            handler.postDelayed({
                try {
                    webView.evaluateJavascript(safeUnmuteJs, null)
                } catch (e: Exception) {}
            }, d)
        }
    }

    private fun injectPlaybackErrorWatcher(view: WebView?) {
        val watcherJs = """
            (function() {
                if (window._failoverWatcherInstalled) return;
                window._failoverWatcherInstalled = true;

                var reported = false;
                function reportError(detail) {
                    if (reported) return;
                    reported = true;
                    try {
                        var bridge = window.AndroidFailover || (window.top && window.top.AndroidFailover) || (window.parent && window.parent.AndroidFailover);
                        if (bridge && typeof bridge.onPlaybackError === 'function') {
                            bridge.onPlaybackError(detail);
                        }
                    } catch(e) {}
                }

                function scanVideos(doc) {
                    if (!doc) return;
                    try {
                        var vids = doc.querySelectorAll('video');
                        for (var i = 0; i < vids.length; i++) {
                            var v = vids[i];
                            if (!v._errHooked) {
                                v._errHooked = true;
                                v.addEventListener('error', function() {
                                    var errCode = (this.error ? this.error.code : 0);
                                    if (errCode >= 2) {
                                        reportError('video_code_' + errCode);
                                    }
                                }, true);
                            }
                        }
                    } catch(e) {}
                }

                function scanJWPlayer() {
                    try {
                        if (window.jwplayer && typeof window.jwplayer === 'function') {
                            var jw = window.jwplayer();
                            if (jw && typeof jw.on === 'function' && !jw._errHooked) {
                                jw._errHooked = true;
                                jw.on('error', function(e) {
                                    reportError('jw_error: ' + (e ? e.message : ''));
                                });
                                jw.on('setupError', function(e) {
                                    reportError('jw_setupError');
                                });
                            }
                        }
                    } catch(e) {}
                }

                var errorKeywords = [
                    'playback error',
                    'stream error',
                    'error loading player',
                    'no playable sources',
                    'the media could not be loaded',
                    'this stream is offline',
                    'stream is offline',
                    'stream unavailable',
                    'channel is offline',
                    'video playback was aborted'
                ];

                var errorSelectors = [
                    '.jw-error', '.jw-error-msg', '.vjs-error-display',
                    '.clappr-error', '[class*="stream-offline" i]',
                    '.player-error', '.playback-error'
                ];

                function scanDom(doc) {
                    if (!doc || reported) return;
                    try {
                        for (var s = 0; s < errorSelectors.length; s++) {
                            var el = doc.querySelector(errorSelectors[s]);
                            if (el && el.offsetParent !== null) {
                                var txt = (el.innerText || el.textContent || '').trim();
                                if (txt.length > 0) {
                                    reportError('selector: ' + txt.substring(0, 40));
                                    return;
                                }
                            }
                        }

                        var text = (doc.body ? doc.body.innerText : '') || '';
                        var lower = text.toLowerCase();
                        for (var k = 0; k < errorKeywords.length; k++) {
                            if (lower.indexOf(errorKeywords[k]) !== -1) {
                                reportError('keyword: ' + errorKeywords[k]);
                                return;
                            }
                        }
                    } catch(e) {}
                }

                function checkAll() {
                    if (reported) return;
                    scanVideos(document);
                    scanJWPlayer();
                    scanDom(document);

                    try {
                        var frames = document.querySelectorAll('iframe');
                        for (var f = 0; f < frames.length; f++) {
                            var fdoc = frames[f].contentDocument || frames[f].contentWindow.document;
                            if (fdoc) {
                                scanVideos(fdoc);
                                scanDom(fdoc);
                            }
                        }
                    } catch(e) {}
                }

                var checkTimer = setInterval(checkAll, 500);
                setTimeout(function() { clearInterval(checkTimer); }, 15000);
            })();
        """.trimIndent()
        view?.evaluateJavascript(watcherJs, null)
    }

    private fun performSafeUnmute() {
        val safeUnmuteJs = """
            (function() {
                var unmuted = false;
                function unmuteDom(root) {
                    if (!root) return;
                    try {
                        root.querySelectorAll('video, audio').forEach(function(v) {
                            try {
                                v.muted = false;
                                v.defaultMuted = false;
                                v.volume = 1.0;
                                unmuted = true;
                            } catch(e) {}
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
                            unmuted = true;
                        }
                    }
                } catch(e) {}

                try {
                    var btns = document.querySelectorAll('[aria-label*="unmute" i], [title*="unmute" i], .jw-icon-volume, .vjs-mute-control, .plyr__control[data-plyr="mute"]');
                    btns.forEach(function(b) {
                        try { b.click(); unmuted = true; } catch(e) {}
                    });
                } catch(e) {}

                return unmuted;
            })();
        """.trimIndent()

        webView.evaluateJavascript(safeUnmuteJs) {
            Toast.makeText(this, "🔊 Áudio ativado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCurrentStream() {
        showConnectingOverlay(channelName)
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
                                hls.on(Hls.Events.ERROR, function(event, data) {
                                    if (data && data.fatal) {
                                        try {
                                            if (window.AndroidFailover) window.AndroidFailover.onPlaybackError('hls_fatal_' + data.type);
                                        } catch(e) {}
                                    }
                                });
                            } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                                video.src = src;
                                video.onerror = function() {
                                    try {
                                        if (window.AndroidFailover) window.AndroidFailover.onPlaybackError('video_onerror');
                                    } catch(e) {}
                                };
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
            val list = listOfNotNull(directStreamUrl, backupDirectUrl, backupDirectUrl2)
            list.forEachIndexed { idx, url ->
                val name = when {
                    url.contains("kobra") -> "Servidor Principal (NTV Kobra)"
                    url.contains("falcon") -> "Servidor Alternativo 1 (NTV Falcon)"
                    url.contains("raptor") -> "Servidor Alternativo 2 (NTV Raptor)"
                    url.contains("exmxbxe") || url.contains("timst") -> "Servidor Rápido (TimStreams)"
                    idx == 0 -> "Servidor Principal (Direto)"
                    idx == 1 -> "Servidor Alternativo 1 (Direto)"
                    else -> "Servidor Reserva $idx (Direto)"
                }
                options.add(ServerOption(name, isDirect = true, directUrl = url))
            }
            return options
        }

        // 1. DaddyLive (highest priority if channel has numeric ID)
        val hasDaddyLive = (currentChannel?.id?.toIntOrNull() != null) || (channelId.toIntOrNull() != null)
        if (hasDaddyLive) {
            options.add(ServerOption("Servidor Principal (DaddyLive)", isDirect = false, folder = "stream"))
        }

        // 2. NTV/EpicSports backup
        val ntvUrl = currentChannel?.backupStreamUrl
        if (ntvUrl != null && (ntvUrl.contains("epicsports") || ntvUrl.contains("ntv.st"))) {
            val label = if (hasDaddyLive) "Servidor Alternativo 1 (NTV)" else "Servidor Principal (NTV)"
            options.add(ServerOption(label, isDirect = true, directUrl = ntvUrl))
        }

        // 3. TimStreams backup
        val timstUrl = if (ntvUrl != null && ntvUrl.contains("exmxbxe")) ntvUrl
            else currentChannel?.backupStreamUrl2?.takeIf { it.contains("exmxbxe") }
        if (timstUrl != null) {
            val label = if (options.size == 1) "Servidor Alternativo 1 (TimStreams)" else "Servidor Alternativo 2 (TimStreams)"
            options.add(ServerOption(label, isDirect = true, directUrl = timstUrl))
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
            options.add(ServerOption("Servidor Oficial (M3U)", isDirect = true, directUrl = officialUrl))
        }

        // 5. Non-NTV backup (if backupStreamUrl is not NTV)
        if (ntvUrl != null && !ntvUrl.contains("epicsports") && !ntvUrl.contains("ntv.st") && !ntvUrl.contains("exmxbxe") && options.none { it.directUrl == ntvUrl }) {
            options.add(ServerOption("Servidor Alternativo (Direto)", isDirect = true, directUrl = ntvUrl))
        }

        // 6. DaddyLive mirrors as last resort
        if (hasDaddyLive) {
            options.add(ServerOption("Servidor Reserva 1 (DaddyLive Cast)", isDirect = false, folder = "cast"))
            options.add(ServerOption("Servidor Reserva 2 (DaddyLive Watch)", isDirect = false, folder = "watch"))
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
        hideConnectingOverlay()
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
                        val focused = currentFocus
                        val drawer = findViewById<View>(R.id.channelDrawer)
                        val nextFocus = focused?.focusSearch(View.FOCUS_RIGHT)
                        if (nextFocus != null && drawer != null && isDescendantOf(nextFocus, drawer)) {
                            return super.dispatchKeyEvent(event)
                        } else {
                            drawerLayout.closeDrawer(GravityCompat.START)
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        toggleControlsOverlay()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
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

    private fun isDescendantOf(child: View, parent: View): Boolean {
        var current: ViewParent? = child.parent
        while (current != null) {
            if (current === parent) return true
            current = current.parent
        }
        return false
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

    fun enterPipMode() {
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
                hideConnectingOverlay()
                connectingOverlay.visibility = View.GONE
                breathingAnimator?.cancel()

                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))

                enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Não foi possível ativar PiP", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "PiP requer Android 8.0+", Toast.LENGTH_SHORT).show()
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
            hideConnectingOverlay()
            connectingOverlay.visibility = View.GONE
            breathingAnimator?.cancel()
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
            cleanupAndFinish()
            return
        }

        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }

        openDrawer()
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

        // 1. Auto Unmute
        val swUnmute = Switch(this).apply {
            text = "🔊 Ativar Som Automaticamente"
            setTextColor(Color.WHITE)
            isChecked = repository.isAutoUnmuteEnabled()
            setPadding(0, 10, 0, 14)
        }
        dialogView.addView(swUnmute)

        // 3. Auto Resume Last Channel
        val swAutoResume = Switch(this).apply {
            text = "📺 Abrir Último Canal ao Iniciar"
            setTextColor(Color.WHITE)
            isChecked = repository.isAutoResumeEnabled()
            setPadding(0, 10, 0, 14)
        }
        dialogView.addView(swAutoResume)

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
                repository.setAutoUnmuteEnabled(swUnmute.isChecked)
                repository.setAutoResumeEnabled(swAutoResume.isChecked)
                val domain = etDomain.text.toString().trim()
                if (domain.isNotBlank()) repository.setBaseUrl(domain)
                val timst = etTimstDomain.text.toString().trim()
                if (timst.isNotBlank()) repository.setTimstBaseUrl(timst)
                Toast.makeText(this, "Definições guardadas!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showEpgDialog() {
        val schedule = repository.epgRepository.getChannelSchedule(channelName)
        val current = repository.epgRepository.getCurrentProgram(channelName)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 24)
            setBackgroundColor(Color.parseColor("#12151E"))
        }

        val titleView = TextView(this).apply {
            text = "📅 Programação: $channelName"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        view.addView(titleView)

        if (current != null) {
            val curCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 16f
                    setStroke(2, Color.parseColor("#38BDF8"))
                }
            }
            val tag = TextView(this).apply {
                text = "🔴 A DAR AGORA • ${current.timeRange}"
                textSize = 12f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val curTitle = TextView(this).apply {
                text = current.title
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 6, 0, 0)
            }
            curCard.addView(tag)
            curCard.addView(curTitle)
            view.addView(curCard)

            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20)
            }
            view.addView(spacer)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650)
        }
        val progList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val upcoming = schedule.filter { it != current }
        if (upcoming.isEmpty() && current == null) {
            val emptyTv = TextView(this).apply {
                text = "Guia de programação não disponível para este canal."
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 20, 0, 20)
            }
            progList.addView(emptyTv)
        } else {
            val upHeader = TextView(this).apply {
                text = "A SEGUIR:"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 10)
            }
            progList.addView(upHeader)

            for (prog in upcoming.take(15)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }
                val timeTv = TextView(this).apply {
                    text = prog.timeRange.split("-").firstOrNull()?.trim() ?: ""
                    textSize = 13f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(140, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val nameTv = TextView(this).apply {
                    text = prog.title
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(timeTv)
                row.addView(nameTv)
                progList.addView(row)
            }
        }

        scroll.addView(progList)
        view.addView(scroll)

        dialog.setView(view)
        dialog.setPositiveButton("Fechar", null)
        dialog.show()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)

        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
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
                val screenWidth = resources.displayMetrics.widthPixels

                if (!isSwipingGesture) {
                    val isEdgeLeft = touchStartX < screenWidth * 0.20f
                    val isEdgeRight = touchStartX > screenWidth * 0.80f
                    if ((isEdgeLeft || isEdgeRight) && Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX) * 1.2f) {
                        isSwipingGesture = true
                        gestureMode = if (isEdgeLeft) GestureMode.VOLUME else GestureMode.BRIGHTNESS
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
                }

                val deltaX = ev.x - touchStartX
                val deltaY = touchStartY - ev.y // UP is positive, DOWN is negative
                val absX = Math.abs(deltaX)
                val absY = Math.abs(deltaY)
                val screenWidth = resources.displayMetrics.widthPixels
                val minSwipeDist = 110f

                val isEdgeBackZone = touchStartX < screenWidth * 0.12f || touchStartX > screenWidth * 0.88f
                val isCenterZone = touchStartX >= screenWidth * 0.20f && touchStartX <= screenWidth * 0.80f

                if (!isEdgeBackZone && absX > minSwipeDist && absX > absY * 1.2f) {
                    // Gestos horizontais:
                    if (deltaX < 0) {
                        // Deslizar para a esquerda: Canal Seguinte
                        zapNextChannel()
                    } else {
                        // Deslizar para a direita: Canal Anterior
                        zapPreviousChannel()
                    }
                    return true
                } else if (isCenterZone && absY > minSwipeDist && absY > absX * 1.2f) {
                    // Gestos verticais no centro:
                    if (deltaY < 0) {
                        // Deslizar para baixo no centro: Abre Canais
                        openDrawer()
                    } else {
                        // Deslizar para cima: Abre EPG
                        showEpgDialog()
                    }
                    return true
                } else if (absX < 25f && absY < 25f) {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val wasOverlayVisible = if (isLandscape) {
                        landscapeOverlay.visibility == View.VISIBLE
                    } else {
                        playerHeader.visibility == View.VISIBLE
                    }

                    if (wasOverlayVisible) {
                        val touchY = ev.y
                        val overlayHeight = if (isLandscape) landscapeOverlay.height.toFloat() else playerHeader.height.toFloat()
                        if (touchY > overlayHeight && overlayHeight > 0) {
                            toggleControlsOverlay()
                            return true
                        }
                        return super.dispatchTouchEvent(ev)
                    } else {
                        toggleControlsOverlay()
                        return true
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
        try {
            val homeIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_DONT_AUTO_RESUME", true)
            }
            startActivity(homeIntent)
        } catch (_: Exception) {}
        finish()
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
