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
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import android.net.Uri
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
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.PowerManager
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var isActivityResumed = false
    private var isScreenOffReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pauseMediaPlayback()
                }
                Intent.ACTION_SCREEN_ON -> {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (powerManager?.isInteractive == true && (isInPictureInPictureMode || isActivityResumed)) {
                        resumeMediaPlayback()
                    }
                }
            }
        }
    }

    // Drawer Views
    private lateinit var rvDrawerChannels: RecyclerView
    private lateinit var etDrawerSearch: EditText
    private lateinit var tvDrawerEmpty: TextView
    private lateinit var btnTabPt: Button
    private lateinit var btnTabSports: Button
    private lateinit var btnTabGames: Button
    private lateinit var btnTabAll: Button
    private lateinit var btnTabFav: Button
    private lateinit var drawerAdapter: DrawerChannelAdapter
    private var lastOverlayShowTime = 0L

    // Connecting Overlay (Breathing Logo)
    private lateinit var connectingOverlay: FrameLayout
    private lateinit var ivConnectingLogo: ImageView
    private lateinit var tvConnectingChannel: TextView
    private lateinit var tvConnectingStatus: TextView
    private var breathingAnimator: ObjectAnimator? = null

    private lateinit var repository: ChannelRepository
    private var currentDrawerTab = TabFilter.PORTUGAL
    private var currentDrawerCategory: String = "Todos"

    private var tvPlayerClock: TextView? = null
    private var tvLandscapeClock: TextView? = null

    private val isTv: Boolean
        get() = repository.isTv()

    private val clockTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Europe/Lisbon")
    }

    private val clockTickRunnable = object : Runnable {
        override fun run() {
            updateClockDisplay()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun updateClockDisplay() {
        val show = repository.isShowClockEnabled()
        if (!show) {
            tvPlayerClock?.visibility = View.GONE
            tvLandscapeClock?.visibility = View.GONE
            return
        }
        val timeStr = clockTimeFormatter.format(Date())
        tvPlayerClock?.apply {
            text = timeStr
            visibility = View.VISIBLE
        }
        tvLandscapeClock?.apply {
            text = timeStr
            visibility = View.VISIBLE
        }
    }

    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customView: View? = null

    private var channelId: String = ""
    private var channelName: String = ""
    private var directStreamUrl: String? = null
    private var backupDirectUrl: String? = null
    private var backupDirectUrl2: String? = null
    private var isBackupSelected: Boolean = false
    private var activeDirectUrl: String? = null
    private var isDirectStreamActive: Boolean = false
    private var currentFolder: String = "stream"

    private val handler = Handler(Looper.getMainLooper())
    private var activeDialog: Dialog? = null

    private val overlayHideRunnable = Runnable {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            landscapeOverlay.visibility = View.GONE
        }
    }

    private fun resetOverlayHideTimer() {
        handler.removeCallbacks(overlayHideRunnable)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            landscapeOverlay.visibility == View.VISIBLE) {
            handler.postDelayed(overlayHideRunnable, 5000)
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
    private val failoverTimeoutMs = 4500L // 4.5 seconds before trying next server
    private var lastFailoverTime = 0L

    inner class FailoverBridge {
        @android.webkit.JavascriptInterface
        fun onPlaybackError(reason: String) {
            runOnUiThread {
                triggerFailoverDueToPlaybackError(reason)
            }
        }

        @android.webkit.JavascriptInterface
        fun onPlaybackStarted() {
            runOnUiThread {
                streamLoadedSuccessfully = true
                handler.removeCallbacks(failoverTimeoutRunnable)
                progressBar.visibility = View.GONE
                connectingOverlay.visibility = View.GONE
            }
        }
    }

    private fun showSilentRecoveryHud(serverName: String) {
        runOnUiThread {
            Toast.makeText(this@PlayerActivity, "⚡ A estabilizar sinal... ($serverName)", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerFailoverDueToPlaybackError(reason: String) {
        android.util.Log.w("PlayerFailover", "Triggering failover due to: $reason")
        val now = System.currentTimeMillis()
        if (now - lastFailoverTime < 1800L) return
        lastFailoverTime = now

        handler.removeCallbacks(failoverTimeoutRunnable)

        if (currentFailoverIndex < failoverServers.size - 1) {
            currentFailoverIndex++
            val next = failoverServers[currentFailoverIndex]
            runOnUiThread {
                Toast.makeText(this@PlayerActivity, "⚡ Erro na stream. A tentar ${next.label}...", Toast.LENGTH_SHORT).show()
                applyServerOption(next, currentFailoverIndex)
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
        try { WebView(this).resumeTimers() } catch (_: Exception) {}

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            registerReceiver(screenStateReceiver, screenFilter)
            isScreenOffReceiverRegistered = true
        } catch (_: Exception) {}

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackOrPip()
            }
        })

        repository = ChannelRepository(this)
        currentFolder = repository.getDefaultServer()

        if (repository.epgRepository.getProgramsCount() == 0 || repository.epgRepository.isCacheStale()) {
            repository.epgRepository.syncEpgFromWeb(lifecycleScope)
        }
        if (repository.getLiveEvents().isEmpty()) {
            repository.fetchLiveMatches(lifecycleScope) {}
        }

        channelId = intent.getStringExtra("EXTRA_CHANNEL_ID") ?: ""
        channelName = intent.getStringExtra("EXTRA_CHANNEL_NAME") ?: "Stream"
        directStreamUrl = intent.getStringExtra("EXTRA_DIRECT_STREAM_URL")
        backupDirectUrl = intent.getStringExtra("EXTRA_BACKUP_STREAM_URL")
        backupDirectUrl2 = intent.getStringExtra("EXTRA_BACKUP_STREAM_URL2")

        val timstCandidate = listOfNotNull(directStreamUrl, backupDirectUrl, backupDirectUrl2)
            .firstOrNull { it.contains("exmxbxe") || it.contains("timst") || it.contains("grandemx") }
        if (timstCandidate != null && directStreamUrl == null) {
            directStreamUrl = timstCandidate
        }
        activeDirectUrl = directStreamUrl
        isDirectStreamActive = (directStreamUrl != null)

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

        tvPlayerClock = findViewById(R.id.tvPlayerClock)
        tvLandscapeClock = findViewById(R.id.tvLandscapeClock)
        updateClockDisplay()

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

        // Portrait Quick Unmute Button
        findViewById<ImageButton>(R.id.btnQuickUnmute)?.setOnClickListener {
            performSafeUnmute()
        }

        findViewById<ImageButton>(R.id.btnPip).setOnClickListener {
            enterPipMode()
        }

        // Fullscreen toggle (Portrait -> Landscape)
        val btnFullscreen = findViewById<ImageButton>(R.id.btnFullscreen)
        btnFullscreen.setOnClickListener {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (!isLandscape) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
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
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
                packageManager.hasSystemFeature("android.hardware.type.television")
        if (isTv) {
            // Google TV (Opção B):
            // Deixar APENAS: [Canais] à esquerda e [Streams (S1 ▾)] à direita!
            // Esconder botões supérfluos da TV (Voltar, Rodar/Fullscreen)
            findViewById<View>(R.id.btnLandscapeBack)?.visibility = View.GONE
            btnLandscapeExitFullscreen.visibility = View.GONE
            btnFullscreen.visibility = View.GONE

            // Encadeamento do comando D-Pad entre Canais <-> Servidor/Streams
            findViewById<View>(R.id.btnLandscapeChannels)?.apply {
                nextFocusLeftId = View.NO_ID
                nextFocusRightId = R.id.btnLandscapeServer
            }
            findViewById<View>(R.id.btnLandscapeServer)?.apply {
                nextFocusLeftId = R.id.btnLandscapeChannels
                nextFocusRightId = View.NO_ID
            }
        } else {
            // Android Telemóvel / Tablet em Landscape
            findViewById<View>(R.id.btnLandscapeBack)?.nextFocusRightId = R.id.btnLandscapeChannels
            findViewById<View>(R.id.btnLandscapeChannels)?.apply {
                nextFocusLeftId = R.id.btnLandscapeBack
                nextFocusRightId = R.id.btnLandscapeServer
            }
            findViewById<View>(R.id.btnLandscapeServer)?.apply {
                nextFocusLeftId = R.id.btnLandscapeChannels
                nextFocusRightId = R.id.btnLandscapeExitFullscreen
            }
            btnLandscapeExitFullscreen.apply {
                nextFocusLeftId = R.id.btnLandscapeServer
                nextFocusRightId = View.NO_ID
            }
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
        btnTabGames = findViewById(R.id.btnTabGames)
        btnTabAll = findViewById(R.id.btnTabAll)
        btnTabFav = findViewById(R.id.btnTabFav)

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
            activeChannelName = channelName,
            epgRepository = repository.epgRepository,
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
        btnTabSports.setOnClickListener { selectDrawerCategory("Desporto", TabFilter.ALL) }
        btnTabGames.setOnClickListener { selectDrawerCategory("Jogos Hoje", TabFilter.LIVE_GAMES) }
        btnTabAll.setOnClickListener { selectDrawerCategory("Mundo", TabFilter.ALL) }
        btnTabFav.setOnClickListener { selectDrawerCategory("Favoritos", TabFilter.FAVORITES) }

        // Touch swipe fix: prevent DrawerLayout from intercepting horizontal swipes on drawer tabs
        val scrollDrawerTabs = findViewById<HorizontalScrollView>(R.id.scrollDrawerTabs)
        scrollDrawerTabs?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        // Tablet & Touchscreen soft keyboard trigger
        etDrawerSearch.setOnClickListener {
            etDrawerSearch.isFocusable = true
            etDrawerSearch.isFocusableInTouchMode = true
            etDrawerSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etDrawerSearch, InputMethodManager.SHOW_IMPLICIT)
        }
        etDrawerSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etDrawerSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }

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

        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerStateChanged(newState: Int) {
                if (newState == androidx.drawerlayout.widget.DrawerLayout.STATE_DRAGGING && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    syncDrawerToActiveChannel()
                }
            }
            override fun onDrawerOpened(drawerView: View) {
                scrollToActiveChannel()
                rvDrawerChannels.postDelayed({ scrollToActiveChannel() }, 100)
            }
        })

        syncDrawerToActiveChannel()
    }

    private fun syncDrawerToActiveChannel() {
        val isLiveEvent = channelId.startsWith("event_") || repository.getLiveEvents().any {
            it.id == channelId.removePrefix("event_") || it.name.equals(channelName, ignoreCase = true)
        }

        if (isLiveEvent) {
            selectDrawerCategory("Jogos Hoje", TabFilter.LIVE_GAMES)
            return
        }

        val currentChannel = repository.getChannels(TabFilter.ALL).firstOrNull {
            it.id == channelId || it.name.equals(channelName, ignoreCase = true)
        }

        if (currentChannel != null) {
            if (currentDrawerTab == TabFilter.FAVORITES && currentChannel.isFavorite) {
                selectDrawerCategory("Favoritos", TabFilter.FAVORITES)
            } else if (currentChannel.category.contains("Desporto", ignoreCase = true) || currentChannel.category.contains("Sport", ignoreCase = true)) {
                selectDrawerCategory("Desporto", TabFilter.ALL)
            } else if (currentChannel.isPortuguese) {
                selectDrawerCategory("Todos", TabFilter.PORTUGAL)
            } else {
                selectDrawerCategory("Mundo", TabFilter.ALL)
            }
        } else {
            selectDrawerCategory("Todos", TabFilter.PORTUGAL)
        }
    }

    private fun scrollToActiveChannel() {
        val list = drawerAdapter.getChannels()
        val index = list.indexOfFirst {
            it.id == channelId || (channelName.isNotBlank() && it.name.equals(channelName, ignoreCase = true))
        }
        if (index in 0 until drawerAdapter.itemCount) {
            val lm = rvDrawerChannels.layoutManager as? LinearLayoutManager ?: return
            val offset = if (index == 0) 0 else (resources.displayMetrics.density * 50).toInt()
            try {
                lm.scrollToPositionWithOffset(index, offset)
            } catch (_: Exception) {}
            rvDrawerChannels.post {
                try {
                    lm.scrollToPositionWithOffset(index, offset)
                } catch (_: Exception) {}
            }
            rvDrawerChannels.postDelayed({
                try {
                    val holder = rvDrawerChannels.findViewHolderForAdapterPosition(index)
                    holder?.itemView?.requestFocus()
                } catch (_: Exception) {}
            }, 80)
        }
    }

    private fun openDrawer() {
        // 1. Clear any prior search query and prevent keyboard pop-up in landscape
        etDrawerSearch.setText("")
        etDrawerSearch.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etDrawerSearch.windowToken, 0)

        // 2. Sync tab and category to active channel/stream
        syncDrawerToActiveChannel()

        // 3. Open drawer
        drawerLayout.openDrawer(GravityCompat.START)

        // 4. Scroll immediately and post-animation to guarantee correct channel position
        scrollToActiveChannel()
        handler.postDelayed({
            scrollToActiveChannel()
        }, 150)
        handler.postDelayed({
            scrollToActiveChannel()
        }, 320)
    }

    private fun selectDrawerCategory(cat: String, tab: TabFilter) {
        currentDrawerCategory = cat
        currentDrawerTab = tab
        val activeColor = ColorStateList.valueOf(Color.parseColor("#E50914"))
        val inactiveColor = ColorStateList.valueOf(Color.parseColor("#202330"))

        btnTabPt.backgroundTintList = if (cat == "Todos" && tab == TabFilter.PORTUGAL) activeColor else inactiveColor
        btnTabPt.setTextColor(if (cat == "Todos" && tab == TabFilter.PORTUGAL) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabSports.backgroundTintList = if (cat == "Desporto" && tab == TabFilter.ALL) activeColor else inactiveColor
        btnTabSports.setTextColor(if (cat == "Desporto" && tab == TabFilter.ALL) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabGames.backgroundTintList = if (tab == TabFilter.LIVE_GAMES) activeColor else inactiveColor
        btnTabGames.setTextColor(if (tab == TabFilter.LIVE_GAMES) Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabAll.backgroundTintList = if (tab == TabFilter.ALL && cat == "Mundo") activeColor else inactiveColor
        btnTabAll.setTextColor(if (tab == TabFilter.ALL && cat == "Mundo") Color.WHITE else Color.parseColor("#9CA3AF"))

        btnTabFav.backgroundTintList = if (tab == TabFilter.FAVORITES) activeColor else inactiveColor
        btnTabFav.setTextColor(if (tab == TabFilter.FAVORITES) Color.WHITE else Color.parseColor("#9CA3AF"))

        val targetBtn = when {
            cat == "Todos" && tab == TabFilter.PORTUGAL -> btnTabPt
            cat == "Desporto" && tab == TabFilter.ALL -> btnTabSports
            tab == TabFilter.LIVE_GAMES -> btnTabGames
            tab == TabFilter.FAVORITES -> btnTabFav
            else -> btnTabAll
        }
        val scrollTabs = findViewById<HorizontalScrollView>(R.id.scrollDrawerTabs)
        scrollTabs?.post {
            scrollTabs.smoothScrollTo(targetBtn.left - 20, 0)
        }

        refreshDrawerList()
    }

    private val drawerTabList = listOf(
        Pair("Todos", TabFilter.PORTUGAL),
        Pair("Desporto", TabFilter.ALL),
        Pair("Jogos Hoje", TabFilter.LIVE_GAMES),
        Pair("Mundo", TabFilter.ALL),
        Pair("Favoritos", TabFilter.FAVORITES)
    )

    fun cycleDrawerTab(forward: Boolean) {
        val currentIndex = drawerTabList.indexOfFirst {
            it.first == currentDrawerCategory && it.second == currentDrawerTab
        }.let { if (it == -1) 0 else it }

        val nextIndex = if (forward) {
            (currentIndex + 1) % drawerTabList.size
        } else {
            if (currentIndex - 1 < 0) drawerTabList.size - 1 else currentIndex - 1
        }

        val (cat, tab) = drawerTabList[nextIndex]
        selectDrawerCategory(cat, tab)

        rvDrawerChannels.postDelayed({
            try {
                if (drawerAdapter.itemCount > 0) {
                    rvDrawerChannels.scrollToPosition(0)
                    val holder = rvDrawerChannels.findViewHolderForAdapterPosition(0)
                    if (holder != null) {
                        holder.itemView.requestFocus()
                    } else {
                        rvDrawerChannels.requestFocus()
                    }
                }
            } catch (_: Exception) {}
        }, 150)
    }

    private fun refreshDrawerList() {
        val query = etDrawerSearch.text.toString().trim()
        if (currentDrawerTab == TabFilter.LIVE_GAMES) {
            val events = repository.getLiveEvents(query)
            if (events.isEmpty() && query.isBlank()) {
                repository.fetchLiveMatches(lifecycleScope) {
                    refreshDrawerList()
                }
            }
            val list = events.map { ev ->
                Channel(
                    id = "event_${ev.id}",
                    name = ev.name,
                    category = "${if (ev.time.isNotBlank()) "🔴 " + ev.time else "🔴 Em Direto"} • ${ev.genreName}",
                    country = "LIVE",
                    isPt = false,
                    logoUrl = ev.logo,
                    backupStreamUrl = ev.streams.firstOrNull()?.url,
                    backupStreamUrl2 = if (ev.streams.size > 1) ev.streams[1].url else null
                )
            }
            val updateAction = {
                drawerAdapter.updateChannels(list, channelId, channelName)
                if (list.isEmpty()) {
                    tvDrawerEmpty.visibility = View.VISIBLE
                    rvDrawerChannels.visibility = View.GONE
                } else {
                    tvDrawerEmpty.visibility = View.GONE
                    rvDrawerChannels.visibility = View.VISIBLE
                }
                scrollToActiveChannel()
            }
            if (rvDrawerChannels.isComputingLayout || rvDrawerChannels.isAnimating) {
                rvDrawerChannels.post(updateAction)
            } else {
                updateAction()
            }
            return
        }

        val list = when {
            currentDrawerCategory == "Desporto" -> repository.getChannels(TabFilter.ALL, query, "Desporto")
            currentDrawerTab == TabFilter.ALL -> repository.getChannels(TabFilter.ALL, query).filter { !it.isPortuguese }
            currentDrawerTab == TabFilter.FAVORITES -> repository.getChannels(TabFilter.FAVORITES, query)
            else -> repository.getChannels(TabFilter.PORTUGAL, query)
        }
        val updateAction = {
            drawerAdapter.updateChannels(list, channelId, channelName)
            if (list.isEmpty()) {
                tvDrawerEmpty.visibility = View.VISIBLE
                rvDrawerChannels.visibility = View.GONE
            } else {
                tvDrawerEmpty.visibility = View.GONE
                rvDrawerChannels.visibility = View.VISIBLE
            }
            scrollToActiveChannel()
        }
        if (rvDrawerChannels.isComputingLayout || rvDrawerChannels.isAnimating) {
            rvDrawerChannels.post(updateAction)
        } else {
            updateAction()
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
        backupDirectUrl2 = newChannel.backupStreamUrl2
        repository.setLastWatchedChannelId(channelId)

        val timstUrl = newChannel.timStreamsUrl

        if (timstUrl != null) {
            isDirectStreamActive = true
            activeDirectUrl = timstUrl
            directStreamUrl = timstUrl
            isBackupSelected = false
        } else if (newChannel.id.toIntOrNull() == null) {
            isDirectStreamActive = true
            activeDirectUrl = newChannel.backupStreamUrl
            directStreamUrl = newChannel.backupStreamUrl
            isBackupSelected = false
        } else {
            isDirectStreamActive = false
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
                u.contains("exmxbxe") || u.contains("grandemx") || u.contains("timst") -> "S (TimST)"
                else -> "S (Direto)"
            }
        } else {
            when (currentFolder) {
                "stream" -> "S (DLive 1)"
                "cast" -> "S (DLive 2)"
                "watch" -> "S (DLive 3)"
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

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Servidores Disponíveis")
            .setSingleChoiceItems(titles, currentSelectedIndex) { d, which ->
                d.dismiss()
                val selected = options[which]
                applyServerOption(selected, which)
            }
            .setNegativeButton("Fechar", null)
            .create()

        activeDialog = dialog
        dialog.setOnDismissListener {
            activeDialog = null
            resetOverlayHideTimer()
        }
        dialog.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.resumeTimers()
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
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.isClickable = true
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
                        if (tag && tag.toLowerCase() === 'a') {
                            el.target = '_self';
                        }
                        return el;
                    };

                    function cleanPlayer() {
                        var style = document.getElementById('dlive-clean-style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = 'dlive-clean-style';
                            style.innerHTML = 'header, footer, .sidebar, .navbar, .mobileBottomNav, #chatangoMount, .drawer, .api-container, [id^="histats"], iframe:not(#thatframe):not([id^="player"]):not(#streamPlayer) { display: none !important; } ' +
                                              'html, body { margin:0 !important; padding:0 !important; background-color:#000 !important; overflow:hidden !important; width:100% !important; height:100% !important; } ' +
                                              'iframe#thatframe, .preview-wrap, #player, iframe#streamPlayer, .watch-player-wrapper, video#video, #player_prog, #player_prog video, .vjs-tech { position:fixed !important; top:0 !important; left:0 !important; width:100% !important; height:100% !important; z-index:2147483640 !important; pointer-events:auto !important; border:none !important; } ' +
                                              '#unmuteBtn, .unmute-btn, .embedme-unmute, [class*="unmute" i] { display: none !important; } ' +
                                              '[data-fullscreen], .media-control-button[data-fullscreen], .player-fullscreen-button, .jw-icon-fullscreen, .vjs-fullscreen-control, .plyr__control--fullscreen, [data-plyr="fullscreen"], button[title*="fullscreen" i], button[title*="full screen" i], button[aria-label*="fullscreen" i], button[aria-label*="full screen" i], button[title*="ecrã inteiro" i], button[aria-label*="ecrã inteiro" i], .fullscreen-button, .fullscreen-btn, .btn-fullscreen, .fs-btn, .plyr__controls__item[data-plyr="fullscreen"] { display: none !important; pointer-events: none !important; visibility: hidden !important; width: 0 !important; height: 0 !important; }';
                            document.head.appendChild(style);
                        }
                    }

                    function kickstartAndUnmute() {
                        try {
                            // 1. Click 'Tap to play' message if present
                            var msg = document.getElementById('msg');
                            if (msg) {
                                try { msg.click(); } catch(e) {}
                            }

                            // 2. Click Unmute button if present
                            var unmuteBtn = document.getElementById('unmute') || document.getElementById('unmuteBtn') || document.querySelector('.unmute-button, .unmute-btn, [aria-label*="unmute" i]');
                            if (unmuteBtn) {
                                try { unmuteBtn.click(); } catch(e) {}
                            }

                            // 3. Play video/audio tags without breaking autoplay policy
                            var vids = document.querySelectorAll('video, audio');
                            for (var i = 0; i < vids.length; i++) {
                                var v = vids[i];
                                if (!v._startedHooked) {
                                    v._startedHooked = true;
                                    v.addEventListener('playing', function() {
                                        try {
                                            if (window.AndroidFailover && typeof window.AndroidFailover.onPlaybackStarted === 'function') {
                                                window.AndroidFailover.onPlaybackStarted();
                                            }
                                        } catch(e) {}
                                    });
                                }
                                if (v.paused) {
                                    v.play().catch(function(){});
                                }
                            }

                            // 4. Handle JWPlayer
                            if (window.jwplayer && typeof window.jwplayer === 'function') {
                                var jw = window.jwplayer();
                                if (jw) {
                                    if (!jw._startedHooked && typeof jw.on === 'function') {
                                        jw._startedHooked = true;
                                        jw.on('play', function() {
                                            try {
                                                if (window.AndroidFailover && typeof window.AndroidFailover.onPlaybackStarted === 'function') {
                                                    window.AndroidFailover.onPlaybackStarted();
                                                }
                                            } catch(e) {}
                                        });
                                    }
                                    if (typeof jw.getState === 'function' && jw.getState() !== 'playing') {
                                        jw.play();
                                    }
                                }
                            }
                        } catch(e) {}
                    }

                    // Run kickstart loop during initial buffering, but stop as soon as stream is playing
                    var _kickstarted = false;
                    var kickstartInterval = setInterval(function() {
                        // If already playing with audio, stop the interval immediately
                        try {
                            var isVideoPlaying = false;
                            var vv = document.querySelector('video');
                            if (vv && !vv.paused && !vv.muted && vv.volume > 0) { isVideoPlaying = true; }
                            if (!isVideoPlaying && window.jwplayer && typeof window.jwplayer === 'function') {
                                var jw = window.jwplayer();
                                if (jw && typeof jw.getState === 'function' && jw.getState() === 'playing' &&
                                    typeof jw.getMute === 'function' && !jw.getMute()) {
                                    isVideoPlaying = true;
                                }
                            }
                            if (isVideoPlaying && _kickstarted) {
                                clearInterval(kickstartInterval);
                                return;
                            }
                        } catch(e) {}
                        kickstartAndUnmute();
                        _kickstarted = true;
                    }, 1000);
                    setTimeout(function() { clearInterval(kickstartInterval); }, 14000);

                    // Cross-frame message handling
                    window.addEventListener('message', function(ev) {
                        if (!ev || !ev.data) return;
                        var d = ev.data;
                        if (d === 'FORCE_UNMUTE' || (d && d.type === 'FORCE_UNMUTE') || d === 'FORCE_PLAY' || (d && d.type === 'FORCE_PLAY')) {
                            kickstartAndUnmute();
                        } else if (d === 'TOGGLE_PLAY_PAUSE' || (d && d.type === 'TOGGLE_PLAY_PAUSE')) {
                            try {
                                var v = document.querySelector('video');
                                if (v) {
                                    if (v.paused) v.play().catch(function(){});
                                    else v.pause();
                                } else if (window.jwplayer && typeof window.jwplayer === 'function') {
                                    var jw = window.jwplayer();
                                    if (jw && typeof jw.getState === 'function') {
                                        if (jw.getState() === 'playing') jw.pause();
                                        else jw.play();
                                    }
                                }
                            } catch(e) {}
                        }
                    });

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
                val rawUrl = request?.url?.toString() ?: return null
                val urlLower = rawUrl.lowercase()
                val adBlockPatterns = listOf(
                    "piousshiners", "nanisms", "histats", "premiumvertising",
                    "dzlhwcoblmc", "azrmpjyh", "smartbanner", "adsco",
                    "popads", "popunder", "creativecdn", "doubleclick",
                    "googlesyndication", "monetag", "trafficjunky",
                    "adservice", "chatango", "onclicksuper", "syndication",
                    "exdynsrv", "adsystem", "adnxs", "burstyflavia",
                    "profitableratecpmnetwork", "cleverwebserver", "adsboosters",
                    "reliedhounder", "canine.tools", "plausible"
                )
                if (adBlockPatterns.any { urlLower.contains(it) }) {
                    return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = request.url?.host?.lowercase() ?: ""

                if (!request.isForMainFrame) {
                    return false
                }

                val currentTimstHost = try { Uri.parse(repository.getTimstBaseUrl()).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                val currentBaseHost = try { Uri.parse(repository.getBaseUrl()).host?.lowercase() ?: "" } catch (_: Exception) { "" }

                val isAllowed = host.contains("dlive") ||
                        host.contains("daddylive") ||
                        host.contains("thedaddy") ||
                        host.contains("dlhd") ||
                        host.contains("dlstreams") ||
                        host.contains("dembed") ||
                        host.contains("grandemx") ||
                        host.contains("wideiptv") ||
                        host.contains("assetrage") ||
                        host.contains("exmxbxe") ||
                        host.contains("timst") ||
                        host.contains("tim-streams") ||
                        host.contains("world-proxifier") ||
                        host.contains("dreamstream") ||
                        host.contains("hesgoal") ||
                        host.contains("cdnlive") ||
                        host.contains("cowedd") ||
                        host.contains("strmfree") ||
                        host.contains("streamfree") ||
                        host.contains("embedindia") ||
                        host.contains("ppv") ||
                        host.contains("ppvservices") ||
                        host.contains("cdn-lab") ||
                        (currentTimstHost.isNotEmpty() && host.contains(currentTimstHost)) ||
                        (currentBaseHost.isNotEmpty() && host.contains(currentBaseHost)) ||
                        host.endsWith(".cfd") ||
                        host.endsWith(".pk") ||
                        host.endsWith(".cx") ||
                        host.endsWith(".st") ||
                        host.endsWith(".top") ||
                        host.endsWith(".sbs") ||
                        host.endsWith(".ru") ||
                        host.endsWith(".org") ||
                        url.startsWith("blob:") ||
                        url.startsWith("data:")

                if (!isAllowed) {
                    return true
                }
                return false
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: true
                android.util.Log.e("PlayerActivity", "WebView renderer process gone (crashed=$didCrash)")
                try {
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                } catch (_: Exception) {}
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@PlayerActivity, "A restabelecer motor de vídeo...", Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }
                return true
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
                                  'iframe#thatframe, .preview-wrap, #player, iframe#streamPlayer, .watch-player-wrapper, video#video, #player_prog, #player_prog video, .vjs-tech { position:fixed !important; top:0 !important; left:0 !important; width:100% !important; height:100% !important; z-index:2147483640 !important; pointer-events:auto !important; border:none !important; } ' +
                                  '#unmuteBtn, .unmute-btn, .embedme-unmute, [class*="unmute" i] { display: none !important; } ' +
                                  '.jw-controlbar { opacity: 0 !important; pointer-events: none !important; }';
                document.head.appendChild(style);

                window.open = function() { return null; };
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun startAutoUnmuteSequence() {
        if (!repository.isAutoUnmuteEnabled()) return
        val delays = listOf(600L, 1500L, 2800L, 4500L, 7000L)
        for (d in delays) {
            handler.postDelayed({
                try {
                    performSafeUnmute(showToast = false)
                } catch (_: Exception) {}
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

    private fun simulateTouchOnWebView(x: Float, y: Float) {
        if (webView.width <= 0 || webView.height <= 0) return
        try {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
            webView.dispatchTouchEvent(down)
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        } catch (_: Exception) {}
    }

    private fun performSafeUnmute(showToast: Boolean = true) {
        val safeUnmuteJs = """
            (function() {
                var unmuted = false;
                function doUnmute(doc, win) {
                    if (!doc) return;
                    try {
                        var msg = doc.getElementById('msg');
                        if (msg) {
                            try { msg.click(); unmuted = true; } catch(e) {}
                        }
                        var btn = doc.getElementById('unmute') || doc.getElementById('unmuteBtn') || doc.querySelector('.unmute-button, .unmute-btn, [aria-label*="unmute" i]');
                        if (btn) {
                            try { btn.click(); unmuted = true; } catch(e) {}
                        }
                        var vids = doc.querySelectorAll('video, audio');
                        for (var i = 0; i < vids.length; i++) {
                            var v = vids[i];
                            if (v.muted || v.defaultMuted) {
                                v.muted = false;
                                v.defaultMuted = false;
                                v.volume = 1.0;
                                unmuted = true;
                            }
                            if (v.paused) {
                                v.play().catch(function(){});
                                unmuted = true;
                            }
                        }
                        if (win && win.jwplayer && typeof win.jwplayer === 'function') {
                            var jw = win.jwplayer();
                            if (jw) {
                                if (jw.setMute) jw.setMute(false);
                                if (jw.setVolume) jw.setVolume(100);
                                if (jw.getState && jw.getState() !== 'playing') {
                                    jw.play();
                                }
                                unmuted = true;
                            }
                        }
                    } catch(e) {}
                }

                doUnmute(document, window);
                var iframes = document.querySelectorAll('iframe');
                for (var i = 0; i < iframes.length; i++) {
                    try {
                        iframes[i].contentWindow.postMessage('FORCE_UNMUTE', '*');
                        iframes[i].contentWindow.postMessage('FORCE_PLAY', '*');
                    } catch(e) {}
                    try {
                        var doc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                        if (doc) doUnmute(doc, iframes[i].contentWindow);
                    } catch(e) {}
                }
                return unmuted;
            })();
        """.trimIndent()

        webView.evaluateJavascript(safeUnmuteJs, null)

        if (showToast) {
            Toast.makeText(this, "🔊 Áudio ativado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayPause() {
        val js = """
            (function() {
                var status = 'unknown';
                function doToggle(doc, win) {
                    if (!doc) return null;
                    try {
                        var v = doc.querySelector('video');
                        if (v) {
                            if (v.paused) {
                                v.play().catch(function(){});
                                return 'playing';
                            } else {
                                v.pause();
                                return 'paused';
                            }
                        }
                    } catch(e) {}
                    try {
                        if (win && win.jwplayer && typeof win.jwplayer === 'function') {
                            var jw = win.jwplayer();
                            if (jw && typeof jw.getState === 'function') {
                                if (jw.getState() === 'playing') {
                                    jw.pause();
                                    return 'paused';
                                } else {
                                    jw.play();
                                    return 'playing';
                                }
                            }
                        }
                    } catch(e) {}
                    return null;
                }

                var r = doToggle(document, window);
                if (r) return r;

                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                    try {
                        frames[i].contentWindow.postMessage('TOGGLE_PLAY_PAUSE', '*');
                        status = 'toggled';
                    } catch(e) {}
                    try {
                        var fdoc = frames[i].contentDocument || frames[i].contentWindow.document;
                        if (fdoc) {
                            var fr = doToggle(fdoc, frames[i].contentWindow);
                            if (fr) return fr;
                        }
                    } catch(e) {}
                }
                return status;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            when (result?.replace("\"", "")) {
                "playing" -> Toast.makeText(this@PlayerActivity, "▶️ A reproduzir", Toast.LENGTH_SHORT).show()
                "paused" -> Toast.makeText(this@PlayerActivity, "⏸️ Em pausa", Toast.LENGTH_SHORT).show()
                "toggled" -> Toast.makeText(this@PlayerActivity, "▶️/⏸️ Play/Pausa", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun resolveTimStreamsToHls(rawUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            var target = rawUrl.trim()
            val activeBase = repository.getTimstBaseUrl().trim().let { if (it.endsWith("/")) it.dropLast(1) else it }
            when {
                target.contains("exmxbxe.cfd") -> target = target.replace("https://exmxbxe.cfd", activeBase).replace("http://exmxbxe.cfd", activeBase)
                target.contains("timst.top") -> target = target.replace("https://timst.top", activeBase).replace("http://timst.top", activeBase)
            }
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                target = "https://$target"
            }

            var conn = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                setRequestProperty("Referer", "https://timst.top/")
            }

            val respCode = conn.responseCode
            if (respCode in 300..399) {
                val loc = conn.getHeaderField("Location")
                if (!loc.isNullOrBlank()) {
                    val redirectUrl = if (loc.startsWith("http")) loc else "https://grandemx.org$loc"
                    conn = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2000
                        readTimeout = 2000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                        setRequestProperty("Referer", "https://timst.top/")
                    }
                }
            } else if (respCode !in 200..299) {
                return@withContext null
            }

            val html = conn.inputStream.bufferedReader().use { it.readText() }

            // 1. Decode XOR obfuscated array if present
            val regex = Regex("""var\s+([_a-zA-Z0-9]+)\s*=\s*\[([0-9,\s]+)\]\s*,\s*([_a-zA-Z0-9]+)\s*=\s*(\d+)\s*,\s*([_a-zA-Z0-9]+)\s*=\s*(\d+)""")
            val match = regex.find(html)
            if (match != null) {
                val numbersStr = match.groupValues[2]
                val sk3 = match.groupValues[4].toInt()
                val iw9 = match.groupValues[6].toInt()
                val sb = java.lang.StringBuilder()
                val tokens = numbersStr.split(',')
                for (token in tokens) {
                    val trimmed = token.trim()
                    if (trimmed.isNotEmpty()) {
                        val num = trimmed.toInt()
                        val ch = (((num xor sk3) - iw9 + 256) % 256).toChar()
                        sb.append(ch)
                    }
                }
                val decoded = sb.toString()
                val signedMatch = Regex("""SIGNED_URL\s*=\s*"([^"]+)"""").find(decoded)
                    ?: Regex("""https://[^\s"']+\.m3u8""").find(decoded)
                if (signedMatch != null) {
                    val m3u8 = if (signedMatch.groupValues.size > 1) signedMatch.groupValues[1] else signedMatch.value
                    if (m3u8.isNotBlank()) {
                        val valid = validateHlsStreamAlive(m3u8)
                        if (valid) return@withContext m3u8 else return@withContext null
                    }
                }
            }

            // 2. Direct plain text search fallback
            val plainMatch = Regex("""SIGNED_URL\s*=\s*"([^"]+)"""").find(html)
                ?: Regex("""https://[^\s"']+\.m3u8""").find(html)
            if (plainMatch != null) {
                val m3u8 = if (plainMatch.groupValues.size > 1) plainMatch.groupValues[1] else plainMatch.value
                if (m3u8.isNotBlank()) {
                    val valid = validateHlsStreamAlive(m3u8)
                    if (valid) return@withContext m3u8 else return@withContext null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TimStreamsResolver", "Error resolving TimStreams HLS: ${e.message}")
        }
        null
    }

    private fun validateHlsStreamAlive(m3u8Url: String): Boolean {
        return try {
            val pConn = (URL(m3u8Url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                setRequestProperty("Referer", "https://grandemx.org/")
            }
            if (pConn.responseCode in 200..299) {
                val sample = pConn.inputStream.bufferedReader().use { it.readText() }
                val expMatch = Regex("""x-expires=(\d+)""").find(sample)
                if (expMatch != null) {
                    val exp = expMatch.groupValues[1].toLongOrNull() ?: 0L
                    val nowSec = System.currentTimeMillis() / 1000L
                    if (exp in 1..nowSec) {
                        android.util.Log.w("TimStreamsResolver", "TimStreams chunks expired (${nowSec - exp}s ago) -> Stream dead upstream!")
                        return false
                    }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("TimStreamsResolver", "Failed validating TimStreams playlist alive: ${e.message}")
            false
        }
    }

    private fun loadHlsStream(m3u8Url: String, referer: String) {
        val hlsHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * { margin:0; padding:0; background:#000 !important; overflow:hidden; }
                    video { position:fixed; top:0; left:0; width:100vw; height:100vh; object-fit:contain; z-index:1; background:#000 !important; }
                    video::-webkit-media-controls { display:none !important; }
                    video::-webkit-media-controls-enclosure { display:none !important; }
                    video::-webkit-media-controls-panel { display:none !important; }
                </style>
                <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
            </head>
            <body>
                <video id="v" autoplay playsinline style="background:#000;"></video>
                <script>
                    var video = document.getElementById('v');
                    var src = '$m3u8Url';

                    function notifyStarted() {
                        try {
                            if (window.AndroidFailover && typeof window.AndroidFailover.onPlaybackStarted === 'function') {
                                window.AndroidFailover.onPlaybackStarted();
                            }
                        } catch(e) {}
                    }

                    video.addEventListener('playing', function() {
                        notifyStarted();
                    });

                    function startPlay() {
                        var p = video.play();
                        if (p !== undefined) {
                            p.catch(function(err) {
                                video.muted = true;
                                video.play().catch(function(){});
                            });
                        }
                    }

                    if (Hls.isSupported()) {
                        var hls = new Hls({
                            enableWorker: true,
                            maxBufferLength: 10,
                            fragLoadingMaxRetry: 1,
                            levelLoadingMaxRetry: 1,
                            manifestLoadingMaxRetry: 1,
                            fragLoadingTimeOut: 2000,
                            manifestLoadingTimeOut: 2000
                        });
                        hls.loadSource(src);
                        hls.attachMedia(video);
                        hls.on(Hls.Events.MANIFEST_PARSED, function() {
                            startPlay();
                        });
                        hls.on(Hls.Events.ERROR, function(event, data) {
                            if (data) {
                                if (data.fatal || data.details === 'fragLoadError' || (data.response && data.response.code >= 400)) {
                                    try {
                                        if (window.AndroidFailover && typeof window.AndroidFailover.onPlaybackError === 'function') {
                                            window.AndroidFailover.onPlaybackError('hls_err_' + (data.details || data.type));
                                        }
                                    } catch(e) {}
                                }
                            }
                        });
                    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                        video.src = src;
                        video.onerror = function() {
                            try {
                                if (window.AndroidFailover && typeof window.AndroidFailover.onPlaybackError === 'function') {
                                    window.AndroidFailover.onPlaybackError('video_onerror');
                                }
                            } catch(e) {}
                        };
                        startPlay();
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(referer, hlsHtml, "text/html", "UTF-8", null)
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

        if (isDirectStreamActive && !activeDirectUrl.isNullOrBlank()) {
            val rawDirect = activeDirectUrl!!
            if (Channel.isTimStreamsUrl(rawDirect)) {
                lifecycleScope.launch {
                    val resolvedM3u8 = resolveTimStreamsToHls(rawDirect)
                    if (isFinishing || isDestroyed) return@launch
                    if (!resolvedM3u8.isNullOrBlank()) {
                        android.util.Log.d("PlayerActivity", "Resolved TimStreams HLS: $resolvedM3u8")
                        loadHlsStream(resolvedM3u8, "https://grandemx.org/")
                        handler.postDelayed(failoverTimeoutRunnable, failoverTimeoutMs)
                    } else {
                        android.util.Log.w("PlayerActivity", "TimStreams offline ou inacessível -> Failover imediato para o próximo servidor")
                        triggerFailoverDueToPlaybackError("timstreams_offline")
                    }
                }
                return
            }

            val targetDirect = rawDirect
            val referer = when {
                targetDirect.contains("impresa.pt") -> "https://sic.pt/"
                targetDirect.contains("rtp.pt") -> "https://www.rtp.pt/"
                targetDirect.contains("TVI") || targetDirect.contains("iol.pt") || targetDirect.contains("raw.githubusercontent.com") -> "https://tviplayer.iol.pt/"
                targetDirect.contains("twitch.tv") -> "https://dlive.sx/"
                targetDirect.contains("cdnlivetv") || targetDirect.contains("streamsports") -> "https://streamsports99.ru/"
                targetDirect.contains("embed.st") || targetDirect.contains("streamed") -> "https://streamed.pk/"
                targetDirect.contains("strmfree") || targetDirect.contains("streamfree") -> "https://strmfree.st/"
                targetDirect.contains("ppv") || targetDirect.contains("embedindia") -> "https://ppv.st/"
                else -> "${repository.getTimstBaseUrl()}/"
            }
            if (targetDirect.contains(".m3u8")) {
                loadHlsStream(targetDirect, referer)
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
        val currentChannel = repository.getChannelById(channelId)
            ?: repository.getAllCachedChannels().firstOrNull { it.id == channelId }

        val options = mutableListOf<ServerOption>()

        if (currentChannel == null) {
            val list = listOfNotNull(directStreamUrl, backupDirectUrl, backupDirectUrl2)
            list.forEachIndexed { idx, url ->
                val name = when {
                    url.contains("grandemx") || url.contains("exmxbxe") || url.contains("timst") -> "Servidor Principal (TimStreams 1080p)"
                    idx == 0 -> "Servidor Principal (Direto)"
                    idx == 1 -> "Servidor Alternativo 1 (Direto)"
                    else -> "Servidor Reserva $idx (Direto)"
                }
                options.add(ServerOption(name, isDirect = true, directUrl = url))
            }
            if (channelId.toIntOrNull() != null) {
                options.add(ServerOption("Servidor Alternativo ${options.size} (DaddyLive - Player 1)", isDirect = false, folder = "stream"))
                options.add(ServerOption("Servidor Alternativo ${options.size} (DaddyLive - Player 2 / Cast)", isDirect = false, folder = "cast"))
            }
            return options
        }

        // 1. TimStreams backup (HIGHEST PRIORITY when available)
        val backupUrl = currentChannel.backupStreamUrl ?: backupDirectUrl
        val rawTimst = if (backupUrl != null && (backupUrl.contains("grandemx") || backupUrl.contains("exmxbxe") || backupUrl.contains("timst"))) backupUrl
            else (currentChannel.backupStreamUrl2 ?: backupDirectUrl2)?.takeIf { it.contains("grandemx") || it.contains("exmxbxe") || it.contains("timst") }
            ?: directStreamUrl?.takeIf { it.contains("grandemx") || it.contains("exmxbxe") || it.contains("timst") }
        val timstUrl = rawTimst?.let { url ->
            val base = repository.getTimstBaseUrl().trim().let { if (it.endsWith("/")) it.dropLast(1) else it }
            when {
                url.contains("exmxbxe.cfd") -> url.replace("https://exmxbxe.cfd", base).replace("http://exmxbxe.cfd", base)
                url.contains("grandemx.org") -> url.replace("https://grandemx.org", base).replace("http://grandemx.org", base)
                url.contains("timst.top") -> url.replace("https://timst.top", base).replace("http://timst.top", base)
                else -> url
            }
        }
        if (timstUrl != null) {
            options.add(ServerOption("Servidor Principal (TimStreams 1080p)", isDirect = true, directUrl = timstUrl))
        }

        // 2. DaddyLive (Player 1 e Player 2 como backups)
        val hasDaddyLive = (currentChannel.id.toIntOrNull() != null) || (channelId.toIntOrNull() != null)
        if (hasDaddyLive) {
            val label1 = if (timstUrl != null) "Servidor Alternativo 1 (DaddyLive - Player 1)" else "Servidor Principal (DaddyLive - Player 1)"
            options.add(ServerOption(label1, isDirect = false, folder = "stream"))

            val label2 = if (timstUrl != null) "Servidor Alternativo 2 (DaddyLive - Player 2 / Cast)" else "Servidor Alternativo 1 (DaddyLive - Player 2 / Cast)"
            options.add(ServerOption(label2, isDirect = false, folder = "cast"))
        }

        // 3. M3UPT / Official direct stream (Higher quality and stability)
        val officialUrl = currentChannel.backupStreamUrl2?.takeIf {
            it.contains("rtp.pt") || it.contains("impresa.pt") || it.contains("github.com") ||
            it.contains("cloudfront") || it.contains("fastly") || it.contains("livextend")
        } ?: currentChannel.backupStreamUrl?.takeIf {
            it.contains("rtp.pt") || it.contains("impresa.pt") || it.contains("github.com") ||
            it.contains("cloudfront") || it.contains("fastly") || it.contains("livextend")
        }
        if (officialUrl != null && options.none { it.directUrl == officialUrl }) {
            val label = if (options.isEmpty()) "Servidor Oficial (Emissão Direta)" else "Servidor Alternativo ${options.size} (Emissão Direta)"
            options.add(ServerOption(label, isDirect = true, directUrl = officialUrl))
        }

        // 4. Non-Timst direct backup (e.g. other direct streams)
        if (backupUrl != null && !backupUrl.contains("exmxbxe") && !backupUrl.contains("grandemx") && !backupUrl.contains("timst") && options.none { it.directUrl == backupUrl }) {
            options.add(ServerOption("Servidor Alternativo ${options.size} (Direto)", isDirect = true, directUrl = backupUrl))
        }

        return options
    }

    private fun applyServerOption(option: ServerOption, manualIndex: Int = -1) {
        handler.removeCallbacks(failoverTimeoutRunnable)
        streamLoadedSuccessfully = false
        if (manualIndex >= 0) {
            currentFailoverIndex = manualIndex
        } else {
            val idx = failoverServers.indexOf(option)
            if (idx >= 0) currentFailoverIndex = idx
        }
        isDirectStreamActive = option.isDirect
        if (option.isDirect) {
            activeDirectUrl = option.directUrl
            directStreamUrl = option.directUrl
        } else {
            activeDirectUrl = null
            directStreamUrl = null
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

    private fun showControlsOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        lastOverlayShowTime = SystemClock.uptimeMillis()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            landscapeOverlay.visibility = View.VISIBLE
            // Do NOT auto-focus btnLandscapeServer on first show — it would cause the next OK press
            // to immediately open the server dialog before the user can navigate
            handler.removeCallbacks(overlayHideRunnable)
            handler.postDelayed(overlayHideRunnable, 4000)
        } else {
            playerHeader.visibility = View.VISIBLE
        }
    }

    private fun hideControlsOverlay() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            landscapeOverlay.visibility = View.GONE
            handler.removeCallbacks(overlayHideRunnable)
        } else {
            playerHeader.visibility = View.GONE
        }
    }

    private fun toggleControlsOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isVisible = if (isLandscape) landscapeOverlay.visibility == View.VISIBLE else playerHeader.visibility == View.VISIBLE
        if (isVisible) {
            hideControlsOverlay()
        } else {
            showControlsOverlay()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Se houver algum diálogo aberto (ex: Servidores, Definições, EPG), passar todas as teclas para o diálogo
        if (activeDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }

        // 1. Filtrar ACTION_UP para a tecla OK se a barra de controlos acabou de abrir
        // Evita que o evento de libertar o botão ative imediatamente o botão focado (ex: Servidores)
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (SystemClock.uptimeMillis() - lastOverlayShowTime < 600L) {
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            // Evita repetição rápida ao manter o botão premido no comando TV
            if (event.repeatCount > 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                }
            }

            val isOverlayVisible = landscapeOverlay.visibility == View.VISIBLE || playerHeader.visibility == View.VISIBLE
            val isDrawerOpen = drawerLayout.isDrawerOpen(GravityCompat.START)

            when (event.keyCode) {
                // 1. Áudio / Som dedicado no comando (Audio Track, Language, code 256, etc.)
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
                KeyEvent.KEYCODE_LANGUAGE_SWITCH,
                KeyEvent.KEYCODE_TV_AUDIO_DESCRIPTION,
                KeyEvent.KEYCODE_PROG_YELLOW,
                256 -> { // Raw keycode 256 used for AUDIO key on G10/G20 Google TV remotes
                    performSafeUnmute(showToast = true)
                    return true
                }

                // Volume Up: assegura unmute da stream e deixa o sistema aumentar o volume do hardware
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    performSafeUnmute(showToast = false)
                    return super.dispatchKeyEvent(event)
                }

                // Volume Mute: deixa o sistema fazer mute de hardware e não engole a tecla
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> {
                    return super.dispatchKeyEvent(event)
                }

                // Play / Pause (▶|| no comando)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlayPause()
                    return true
                }

                // Info / OSD / Legendas
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_CAPTIONS -> {
                    showOsdBanner(if (directStreamUrl != null) "LIVE" else channelId, channelName, if (directStreamUrl != null) "DIRETO ⚽" else "PT 🇵🇹")
                    return true
                }

                // Tecla Definições (engrenagem no comando ⚙)
                KeyEvent.KEYCODE_SETTINGS -> {
                    showSettingsDialog()
                    return true
                }

                // Tecla TV no comando (ícone de TV 📺 ao lado de Home)
                KeyEvent.KEYCODE_TV,
                KeyEvent.KEYCODE_WINDOW,
                KeyEvent.KEYCODE_TV_DATA_SERVICE -> {
                    if (isDrawerOpen) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        openDrawer()
                    }
                    return true
                }

                // Tecla Guia / EPG ([||| TV] no centro do comando ou botão azul)
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    showEpgDialog()
                    return true
                }

                // Menu (☰) / MEDIA / Source / Botão Verde: Atalho para diálogo de Servidores
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DVR,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_TV_INPUT,
                KeyEvent.KEYCODE_PAIRING,
                KeyEvent.KEYCODE_PROG_GREEN -> {
                    showServerSelectionDialog()
                    return true
                }

                // Botão Vermelho (círculo play/marcador no comando): Alterna gaveta de canais
                KeyEvent.KEYCODE_PROG_RED,
                KeyEvent.KEYCODE_BUTTON_1 -> {
                    if (isDrawerOpen) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        openDrawer()
                    }
                    return true
                }

                // Botão Grelha de Apps (⠿ no comando): Ativa Picture-in-Picture
                KeyEvent.KEYCODE_ALL_APPS,
                KeyEvent.KEYCODE_APP_SWITCH -> {
                    enterPipMode()
                    return true
                }

                // Seta Direita (DPAD_RIGHT)
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isDrawerOpen) {
                        val focused = currentFocus
                        if (focused == etDrawerSearch) {
                            return super.dispatchKeyEvent(event)
                        }
                        cycleDrawerTab(forward = true)
                        return true
                    } else if (isOverlayVisible) {
                        resetOverlayHideTimer()
                        return super.dispatchKeyEvent(event)
                    } else {
                        showEpgDialog()
                        return true
                    }
                }

                // Seta Esquerda (DPAD_LEFT)
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isDrawerOpen) {
                        val focused = currentFocus
                        if (focused == etDrawerSearch) {
                            return super.dispatchKeyEvent(event)
                        }
                        cycleDrawerTab(forward = false)
                        return true
                    } else if (isOverlayVisible) {
                        resetOverlayHideTimer()
                        return super.dispatchKeyEvent(event)
                    } else {
                        openDrawer()
                        return true
                    }
                }

                // Seta Cima (DPAD_UP): Abre a barra de opções/controlos quando o vídeo está em reprodução
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isDrawerOpen) {
                        return super.dispatchKeyEvent(event)
                    } else if (isOverlayVisible) {
                        resetOverlayHideTimer()
                        val serverBtn = findViewById<View>(R.id.btnLandscapeServer)
                        if (currentFocus == null || !isDescendantOf(currentFocus!!, landscapeOverlay)) {
                            serverBtn?.requestFocus()
                        }
                        return true
                    } else {
                        showControlsOverlay()
                        return true
                    }
                }

                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (isDrawerOpen) {
                        cycleDrawerTab(forward = false)
                        return true
                    }
                    zapPreviousChannel()
                    return true
                }

                // Seta Baixo (DPAD_DOWN): Fecha a barra se aberta, ou abre a gaveta de canais
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isDrawerOpen) {
                        return super.dispatchKeyEvent(event)
                    } else if (isOverlayVisible) {
                        resetOverlayHideTimer()
                        hideControlsOverlay()
                        return true
                    } else {
                        openDrawer()
                        return true
                    }
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (isDrawerOpen) {
                        cycleDrawerTab(forward = true)
                        return true
                    }
                    zapNextChannel()
                    return true
                }

                // Tecla OK (DPAD_CENTER / ENTER)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (isDrawerOpen) {
                        return super.dispatchKeyEvent(event)
                    } else if (isOverlayVisible) {
                        // Evita clique acidental imediato após abrir a barra
                        if (SystemClock.uptimeMillis() - lastOverlayShowTime < 600L) {
                            return true
                        }
                        resetOverlayHideTimer()
                        val focused = currentFocus
                        if (focused != null && isDescendantOf(focused, landscapeOverlay)) {
                            focused.performClick()
                            return true
                        } else {
                            // Focus btnLandscapeChannels first (more useful than Server dialog)
                            val channelsBtn = findViewById<View>(R.id.btnLandscapeChannels)
                            val serverBtn = findViewById<View>(R.id.btnLandscapeServer)
                            if (channelsBtn != null && channelsBtn.isFocusable) {
                                channelsBtn.requestFocus()
                            } else {
                                serverBtn?.requestFocus()
                            }
                            return true
                        }
                    } else {
                        showControlsOverlay()
                        // Kickstart playback immediately se a stream estiver parada em "Tap to play"
                        performSafeUnmute(showToast = false)
                        return true
                    }
                }

                // Tecla Voltar (BACK)
                KeyEvent.KEYCODE_BACK -> {
                    if (isOverlayVisible) {
                        hideControlsOverlay()
                        return true
                    }
                    if (isDrawerOpen) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            playerHeader.visibility = View.GONE
            landscapeOverlay.visibility = View.GONE
            return
        }
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyFullscreenMode(isLandscape)
    }

    private fun applyFullscreenMode(isLandscape: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            playerHeader.visibility = View.GONE
            landscapeOverlay.visibility = View.GONE
            return
        }
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
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive == false) {
                // Ecrã desligado - pausar vídeo e não gastar bateria em segundo plano
                pauseMediaPlayback()
                return
            }
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
                val visibleRect = Rect()
                webView.getGlobalVisibleRect(visibleRect)
                if (visibleRect.width() > 0 && visibleRect.height() > 0) {
                    builder.setSourceRectHint(visibleRect)
                }

                enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                e.printStackTrace()
                cleanupAndFinish()
            }
        } else {
            cleanupAndFinish()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isTv) return
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
            return
        }

        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isOverlayVisible = if (isLandscape) landscapeOverlay.visibility == View.VISIBLE else playerHeader.visibility == View.VISIBLE
        if (isOverlayVisible) {
            hideControlsOverlay()
            return
        }

        if (!isTv && repository.isAutoPipOnBack() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode()
        } else {
            cleanupAndFinish()
        }
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

        // 4. Digital Clock
        val swClock = Switch(this).apply {
            text = "🕒 Mostrar Relógio no Ecrã (Lisboa)"
            setTextColor(Color.WHITE)
            isChecked = repository.isShowClockEnabled()
            setPadding(0, 10, 0, 14)
        }
        swClock.setOnCheckedChangeListener { _, isChecked ->
            repository.setShowClockEnabled(isChecked)
            updateClockDisplay()
        }
        dialogView.addView(swClock)

        // 5. Back Button / PiP
        if (!isTv) {
            val swPip = Switch(this).apply {
                text = "📱 Ativar PiP ao Premir Voltar"
                setTextColor(Color.WHITE)
                isChecked = repository.isAutoPipOnBack()
                setPadding(0, 10, 0, 14)
            }
            swPip.setOnCheckedChangeListener { _, isChecked ->
                repository.setAutoPipOnBack(isChecked)
            }
            dialogView.addView(swPip)
        } else {
            val tvTvBack = TextView(this).apply {
                text = "📺 Comando TV: O botão Voltar sai do reprodutor e regressa aos canais."
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                setPadding(0, 8, 0, 12)
            }
            dialogView.addView(tvTvBack)
        }

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

        // Google TV D-Pad Focus Fix: Prevent getting trapped in EditTexts
        etTimstDomain.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    etDomain.requestFocus()
                    return@setOnKeyListener true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    swAutoResume.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }

        etDomain.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    btnSync.requestFocus()
                    return@setOnKeyListener true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    etTimstDomain.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }

        // Google TV: btnSync DPAD_DOWN should move focus to Guardar button (added below)
        // We'll set this after building the dialog so we can reference the positive button
        dialogView.addView(btnSync)

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

        // Add inline Save button for Google TV D-Pad navigation
        val btnSave = Button(this).apply {
            text = "✅ Guardar"
            setBackgroundColor(Color.parseColor("#16a34a"))
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            layoutParams = params
        }
        dialogView.addView(btnSave)

        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .create()

        btnSave.setOnClickListener {
            repository.setAutoUnmuteEnabled(swUnmute.isChecked)
            repository.setAutoResumeEnabled(swAutoResume.isChecked)
            val domain = etDomain.text.toString().trim()
            if (domain.isNotBlank()) repository.setBaseUrl(domain)
            val timst = etTimstDomain.text.toString().trim()
            if (timst.isNotBlank()) repository.setTimstBaseUrl(timst)
            Toast.makeText(this, "Definições guardadas!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // D-Pad: btnSync → DPAD_DOWN → btnSave
        btnSync.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                btnSave.requestFocus()
                return@setOnKeyListener true
            }
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                etDomain.requestFocus()
                return@setOnKeyListener true
            }
            false
        }

        activeDialog = dialog
        dialog.setOnDismissListener {
            activeDialog = null
            resetOverlayHideTimer()
        }
        dialog.show()
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
                text = "Guia de programação a carregar ou indisponível..."
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 20, 0, 20)
            }
            progList.addView(emptyTv)

            if (repository.epgRepository.getProgramsCount() == 0 || repository.epgRepository.isCacheStale()) {
                repository.epgRepository.syncEpgFromWeb(lifecycleScope) { success, _ ->
                    if (success && !isFinishing && !isDestroyed) {
                        runOnUiThread {
                            try {
                                activeDialog?.dismiss()
                                showEpgDialog()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
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
        val created = dialog.create()
        activeDialog = created
        created.setOnDismissListener {
            activeDialog = null
            resetOverlayHideTimer()
        }
        created.show()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return super.dispatchTouchEvent(ev)
        }

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

                val density = resources.displayMetrics.density
                val touchSlop = (ViewConfiguration.get(this).scaledTouchSlop.toFloat()).coerceAtLeast(28f * density)
                val minSwipeDist = 70f * density

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
                } else if (absX < touchSlop && absY < touchSlop) {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val wasOverlayVisible = if (isLandscape) {
                        landscapeOverlay.visibility == View.VISIBLE
                    } else {
                        playerHeader.visibility == View.VISIBLE
                    }

                    if (wasOverlayVisible) {
                        val isTouchOnControls = (isLandscape && isTouchInsideView(landscapeOverlay, ev.rawX, ev.rawY)) ||
                                (!isLandscape && isTouchInsideView(playerHeader, ev.rawX, ev.rawY)) ||
                                isTouchInsideView(osdBanner, ev.rawX, ev.rawY)

                        // Se o toque foi nos botões/controlos, entrega normalmente o clique!
                        if (isTouchOnControls) {
                            return super.dispatchTouchEvent(ev)
                        }
                        // Se o toque foi na área de vídeo livre fora dos controlos, recolhe os controlos
                        hideControlsOverlay()
                    } else {
                        // Se os controlos estavam escondidos, exibe os controlos nativos
                        showControlsOverlay()
                    }
                    // Consome o toque aqui para que a WebView NÃO receba cliques de anúncios,
                    // popunders ou pausas que causam ecrã preto!
                    return true
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchInsideView(view: View?, rawX: Float, rawY: Float): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        val rect = android.graphics.Rect()
        view.getGlobalVisibleRect(rect)
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackOrPip()
    }

    private fun pauseMediaPlayback() {
        try {
            val pauseJs = """
                (function() {
                    try {
                        document.querySelectorAll('video, audio').forEach(function(v) {
                            try { v.pause(); } catch(e) {}
                        });
                        var frames = document.querySelectorAll('iframe');
                        for (var i = 0; i < frames.length; i++) {
                            try {
                                frames[i].contentWindow.postMessage('PAUSE_PLAYBACK', '*');
                                var fdoc = frames[i].contentDocument || frames[i].contentWindow.document;
                                if (fdoc) {
                                    fdoc.querySelectorAll('video, audio').forEach(function(v) {
                                        try { v.pause(); } catch(e) {}
                                    });
                                }
                            } catch(e) {}
                        }
                    } catch(e) {}
                })();
            """.trimIndent()
            webView.evaluateJavascript(pauseJs, null)
            webView.onPause()
        } catch (_: Exception) {}
    }

    private fun resumeMediaPlayback() {
        try {
            webView.onResume()
            webView.resumeTimers()
            val resumeJs = """
                (function() {
                    try {
                        document.querySelectorAll('video').forEach(function(v) {
                            try { v.play().catch(function(){}); } catch(e) {}
                        });
                        var frames = document.querySelectorAll('iframe');
                        for (var i = 0; i < frames.length; i++) {
                            try {
                                frames[i].contentWindow.postMessage('RESUME_PLAYBACK', '*');
                                var fdoc = frames[i].contentDocument || frames[i].contentWindow.document;
                                if (fdoc) {
                                    fdoc.querySelectorAll('video').forEach(function(v) {
                                        try { v.play().catch(function(){}); } catch(e) {}
                                    });
                                }
                            } catch(e) {}
                        }
                    } catch(e) {}
                })();
            """.trimIndent()
            webView.evaluateJavascript(resumeJs, null)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resumeMediaPlayback()
        handler.post(clockTickRunnable)
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        handler.removeCallbacks(clockTickRunnable)
        if (!isInPictureInPictureMode) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pauseMediaPlayback()
        }
    }

    override fun onStop() {
        super.onStop()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenInteractive = powerManager?.isInteractive ?: true
        if (!isScreenInteractive || !isInPictureInPictureMode) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pauseMediaPlayback()
        }
    }

    fun cleanupAndFinish() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            pauseMediaPlayback()
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
        if (isScreenOffReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {}
            isScreenOffReceiverRegistered = false
        }
        if (activeInstance?.get() == this) {
            activeInstance = null
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacksAndMessages(null)
        try {
            pauseMediaPlayback()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
