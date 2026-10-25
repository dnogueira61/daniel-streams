package com.dlive.ptstream.ui.screens

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dlive.ptstream.R
import com.dlive.ptstream.data.Channel
import com.dlive.ptstream.data.ChannelRepository
import com.dlive.ptstream.data.EventStream
import com.dlive.ptstream.data.LiveEvent
import com.dlive.ptstream.data.SportsMatchHelper

class MultiViewActivity : ComponentActivity() {

    private lateinit var repository: ChannelRepository
    private lateinit var llRowBottom: LinearLayout
    private lateinit var btnMode2: Button
    private lateinit var btnMode4: Button
    private lateinit var tvActiveFeedsCount: TextView
    private lateinit var flFullscreenSlotContainer: FrameLayout

    private val slots = ArrayList<MultiViewSlot>()
    private var is4ScreensMode = true
    private var activeAudioSlotIndex = 0
    private var fullscreenSlotIndex = -1

    data class SlotData(
        val title: String,
        val url: String,
        val isDirect: Boolean = true,
        val channelId: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system bars for immersive viewing
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_multiview)

        repository = ChannelRepository(this)

        initViews()

        autoPopulateTopLiveMatches()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenSlotIndex >= 0) {
                    exitSlotFullscreen()
                } else {
                    finish()
                }
            }
        })
    }

    private fun initViews() {
        llRowBottom = findViewById(R.id.llRowBottom)
        btnMode2 = findViewById(R.id.btnMode2)
        btnMode4 = findViewById(R.id.btnMode4)
        tvActiveFeedsCount = findViewById(R.id.tvActiveFeedsCount)
        flFullscreenSlotContainer = findViewById(R.id.flFullscreenSlotContainer)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (fullscreenSlotIndex >= 0) exitSlotFullscreen() else finish()
        }

        findViewById<ImageButton>(R.id.btnClearAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar MultiView")
                .setMessage("Pretende fechar todas as transmissões ativas?")
                .setPositiveButton("Limpar") { _, _ -> clearAllSlots() }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnMode2.setOnClickListener { switchScreenMode(fourScreens = false) }
        btnMode4.setOnClickListener { switchScreenMode(fourScreens = true) }

        val containerIds = listOf(
            R.id.slot0Container,
            R.id.slot1Container,
            R.id.slot2Container,
            R.id.slot3Container
        )

        slots.clear()
        for (i in containerIds.indices) {
            val container = findViewById<FrameLayout>(containerIds[i])
            val slot = MultiViewSlot(i, container)
            slots.add(slot)
        }

        updateFeedsCounter()
    }

    private fun switchScreenMode(fourScreens: Boolean) {
        if (fullscreenSlotIndex >= 0) exitSlotFullscreen()

        is4ScreensMode = fourScreens
        if (fourScreens) {
            llRowBottom.visibility = View.VISIBLE
            btnMode4.setBackgroundColor(Color.parseColor("#2563EB"))
            btnMode4.setTextColor(Color.WHITE)
            btnMode2.setBackgroundColor(Color.TRANSPARENT)
            btnMode2.setTextColor(Color.parseColor("#9CA3AF"))
        } else {
            llRowBottom.visibility = View.GONE
            btnMode2.setBackgroundColor(Color.parseColor("#2563EB"))
            btnMode2.setTextColor(Color.WHITE)
            btnMode4.setBackgroundColor(Color.TRANSPARENT)
            btnMode4.setTextColor(Color.parseColor("#9CA3AF"))

            // If slots 2 or 3 had active audio, switch to slot 0 or 1
            if (activeAudioSlotIndex >= 2) {
                setAudioActiveSlot(0)
            }
        }
        updateFeedsCounter()
    }

    private fun updateFeedsCounter() {
        val totalSlots = if (is4ScreensMode) 4 else 2
        val activeCount = slots.take(totalSlots).count { it.isActive }
        tvActiveFeedsCount.text = "$activeCount/$totalSlots FEEDS"
    }

    private fun clearAllSlots() {
        slots.forEach { it.clear() }
        updateFeedsCounter()
    }

    fun setAudioActiveSlot(index: Int) {
        if (index !in slots.indices) return
        activeAudioSlotIndex = index
        slots.forEachIndexed { i, slot ->
            slot.setAudioActive(i == index)
        }
    }

    private fun enterSlotFullscreen(slotIndex: Int) {
        if (slotIndex !in slots.indices) return
        val slot = slots[slotIndex]
        if (!slot.isActive) return

        fullscreenSlotIndex = slotIndex
        setAudioActiveSlot(slotIndex)

        // Move active stream view to fullscreen container
        slot.detachActiveStreamView()
        flFullscreenSlotContainer.removeAllViews()
        slot.flActiveStream?.let { streamView ->
            flFullscreenSlotContainer.addView(streamView)
        }
        flFullscreenSlotContainer.visibility = View.VISIBLE
    }

    private fun exitSlotFullscreen() {
        if (fullscreenSlotIndex < 0) return
        val slot = slots[fullscreenSlotIndex]
        flFullscreenSlotContainer.visibility = View.GONE
        flFullscreenSlotContainer.removeAllViews()
        slot.reattachActiveStreamView()
        fullscreenSlotIndex = -1
    }

    private fun autoPopulateTopLiveMatches() {
        val existing = repository.getLiveEvents()
        if (existing.isNotEmpty()) {
            populateSlotsWithEvents(existing)
        } else {
            lifecycleScope.launch {
                repository.fetchLiveMatches(lifecycleScope) { events ->
                    populateSlotsWithEvents(events)
                }
            }
        }
    }

    private fun calculateMatchScore(ev: LiveEvent, isRunning: Boolean, now: Long): Int {
        var score = 0
        if (isRunning) {
            score += 10000
        } else if (ev.startTimestamp > 0L && (ev.startTimestamp - now in 0..(60 * 60 * 1000L))) {
            score += 5000
        }

        if (ev.isHighlight) {
            score += 4000 - minOf(ev.highlightPriority * 20, 1000)
        }

        val n = ev.name.lowercase()
        val majorKeywords = listOf(
            "champions", "liga dos campeões", "benfica", "porto", "sporting", "braga",
            "derby", "dérbi", "clássico", "classico", "real madrid", "barcelona",
            "liverpool", "arsenal", "man city", "chelsea", "bayern", "psg",
            "juventus", "inter", "milan", "atletico", "premier league", "la liga", "serie a"
        )
        if (majorKeywords.any { n.contains(it) }) {
            score += 3000
        }

        if (SportsMatchHelper.isSoccerEvent(ev)) {
            score += 1500
        }

        if (ev.streams.isNotEmpty()) {
            score += 500
        }

        score += minOf(ev.viewers / 10, 500)
        return score
    }

    private fun populateSlotsWithEvents(events: List<LiveEvent>) {
        val now = System.currentTimeMillis()
        val ptChannels = repository.getAllCachedChannels().filter { it.isPortuguese }

        // Ranking live events:
        val rankedMatches = events.filter { ev ->
            if (ev.startTimestamp > 0L && (now - ev.startTimestamp > 180 * 60 * 1000L)) false
            else true
        }.sortedWith { a, b ->
            val aRunning = a.startTimestamp > 0L && (now in (a.startTimestamp - 15 * 60 * 1000L)..(a.startTimestamp + 150 * 60 * 1000L))
            val bRunning = b.startTimestamp > 0L && (now in (b.startTimestamp - 15 * 60 * 1000L)..(b.startTimestamp + 150 * 60 * 1000L))
            val aScore = calculateMatchScore(a, aRunning, now)
            val bScore = calculateMatchScore(b, bRunning, now)
            bScore.compareTo(aScore)
        }

        val topOptions = mutableListOf<SlotData>()

        for (ev in rankedMatches) {
            val title = SportsMatchHelper.translateToPt(ev.name)
            if (ev.streams.isNotEmpty()) {
                val st = ev.streams.first()
                topOptions.add(
                    SlotData(
                        title = "⚽ $title (${st.name})",
                        url = st.url,
                        isDirect = true
                    )
                )
            } else {
                val matched = ptChannels.firstOrNull { ch ->
                    ev.name.contains(ch.name, ignoreCase = true) || ch.name.contains(ev.name, ignoreCase = true)
                }
                if (matched != null) {
                    val streamUrl = matched.backupStreamUrl ?: "https://dlive.sx/stream/stream-${matched.id}.php"
                    topOptions.add(
                        SlotData(
                            title = "⚽ $title (${matched.name})",
                            url = streamUrl,
                            isDirect = !streamUrl.contains("stream-"),
                            channelId = matched.id
                        )
                    )
                }
            }
            if (topOptions.size >= 4) break
        }

        // Se houver menos de 4 jogos ao vivo no momento, preencher com os canais desportivos principais PT
        if (topOptions.size < 4) {
            val topSports = ptChannels.filter { ch ->
                val n = ch.name.lowercase()
                n.contains("sport tv 1") || n.contains("sport tv 2") || n.contains("sport tv 3") ||
                n.contains("dazn 1") || n.contains("dazn 2") || n.contains("eleven 1") || n.contains("eleven 2") ||
                n.contains("benfica") || n.contains("canal 11")
            }.sortedBy { repository.getSportTvOrder(it.name) }

            for (ch in topSports) {
                if (topOptions.any { it.channelId == ch.id }) continue
                val streamUrl = ch.backupStreamUrl ?: "https://dlive.sx/stream/stream-${ch.id}.php"
                topOptions.add(
                    SlotData(
                        title = "🇵🇹 ${ch.name}",
                        url = streamUrl,
                        isDirect = !streamUrl.contains("stream-"),
                        channelId = ch.id
                    )
                )
                if (topOptions.size >= 4) break
            }
        }

        // Carrega automaticamente nos ecrãs vazios
        val maxSlots = if (is4ScreensMode) 4 else 2
        for (i in 0 until minOf(maxSlots, topOptions.size)) {
            if (!slots[i].isActive) {
                slots[i].loadStream(topOptions[i])
            }
        }

        setAudioActiveSlot(0)
        updateFeedsCounter()
    }

    private fun openFeedPickerDialog(slotIndex: Int) {
        val now = System.currentTimeMillis()
        val liveEvents = repository.getLiveEvents()
        val ptChannels = repository.getAllCachedChannels().filter { it.isPortuguese }
        val intlChannels = repository.getAllCachedChannels().filter { !it.isPortuguese && (it.category.contains("Desporto", ignoreCase = true) || it.name.contains("sport", ignoreCase = true)) }

        // Ordenar eventos por relevância (jogos em direto, Champions League, clássicos, derbys no topo)
        val sortedEvents = liveEvents.sortedWith { a, b ->
            val aRunning = a.startTimestamp > 0L && (now in (a.startTimestamp - 15 * 60 * 1000L)..(a.startTimestamp + 150 * 60 * 1000L))
            val bRunning = b.startTimestamp > 0L && (now in (b.startTimestamp - 15 * 60 * 1000L)..(b.startTimestamp + 150 * 60 * 1000L))
            val aScore = calculateMatchScore(a, aRunning, now)
            val bScore = calculateMatchScore(b, bRunning, now)
            bScore.compareTo(aScore)
        }

        val options = mutableListOf<SlotData>()

        // 1. Jogos Hoje / Em Direto prioritários
        for (ev in sortedEvents) {
            val title = SportsMatchHelper.translateToPt(ev.name)
            val isRunning = ev.startTimestamp > 0L && (now in (ev.startTimestamp - 15 * 60 * 1000L)..(ev.startTimestamp + 150 * 60 * 1000L))
            val prefix = if (isRunning) "🔴 [EM DIRETO]" else "⚽"
            if (ev.streams.isNotEmpty()) {
                for (st in ev.streams) {
                    options.add(
                        SlotData(
                            title = "$prefix $title (${st.name})",
                            url = st.url,
                            isDirect = true
                        )
                    )
                }
            } else {
                val matched = ptChannels.firstOrNull { ch ->
                    ev.name.contains(ch.name, ignoreCase = true) || ch.name.contains(ev.name, ignoreCase = true)
                }
                if (matched != null) {
                    val streamUrl = matched.backupStreamUrl ?: "https://dlive.sx/stream/stream-${matched.id}.php"
                    options.add(
                        SlotData(
                            title = "$prefix $title (${matched.name})",
                            url = streamUrl,
                            isDirect = !streamUrl.contains("stream-"),
                            channelId = matched.id
                        )
                    )
                }
            }
        }

        // 2. Canais PT
        for (ch in ptChannels) {
            val streamUrl = ch.backupStreamUrl ?: "https://dlive.sx/stream/stream-${ch.id}.php"
            options.add(
                SlotData(
                    title = "🇵🇹 ${ch.name}",
                    url = streamUrl,
                    isDirect = !streamUrl.contains("stream-"),
                    channelId = ch.id
                )
            )
        }

        // 3. Canais Internacionais
        for (ch in intlChannels.take(50)) {
            val streamUrl = ch.backupStreamUrl ?: "https://dlive.sx/stream/stream-${ch.id}.php"
            options.add(
                SlotData(
                    title = "🌍 ${ch.name}",
                    url = streamUrl,
                    isDirect = !streamUrl.contains("stream-"),
                    channelId = ch.id
                )
            )
        }

        val titles = options.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Trocar Ecrã ${slotIndex + 1}: Escolher Jogo ou Canal")
            .setItems(titles) { _, which ->
                val selected = options[which]
                slots[slotIndex].loadStream(selected)
                setAudioActiveSlot(slotIndex)
                updateFeedsCounter()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        slots.forEach { it.destroy() }
    }

    inner class MultiViewSlot(
        val index: Int,
        val container: FrameLayout
    ) {
        var isActive = false
        var currentData: SlotData? = null
        var flActiveStream: FrameLayout? = null
        private var llEmptyView: View? = null
        private var vFocusBorder: View? = null
        private var webView: WebView? = null
        private var tvSlotTitle: TextView? = null
        private var tvAudioStatus: TextView? = null
        private var llSlotOverlay: View? = null
        private val handler = Handler(Looper.getMainLooper())

        init {
            val view = LayoutInflater.from(this@MultiViewActivity).inflate(R.layout.layout_multiview_slot, container, false)
            container.addView(view)

            llEmptyView = view.findViewById(R.id.llEmptyView)
            flActiveStream = view.findViewById(R.id.flActiveStream)
            vFocusBorder = view.findViewById(R.id.vFocusBorder)
            webView = view.findViewById(R.id.slotWebView)
            tvSlotTitle = view.findViewById(R.id.tvSlotTitle)
            tvAudioStatus = view.findViewById(R.id.tvAudioStatus)
            llSlotOverlay = view.findViewById(R.id.llSlotOverlay)

            view.findViewById<TextView>(R.id.tvEmptySlotNumber)?.text = "Ecrã ${index + 1}"

            llEmptyView?.setOnClickListener {
                openFeedPickerDialog(index)
            }

            view.findViewById<ImageButton>(R.id.btnSlotSwap)?.setOnClickListener {
                openFeedPickerDialog(index)
            }

            view.findViewById<ImageButton>(R.id.btnSlotClose)?.setOnClickListener {
                clear()
                updateFeedsCounter()
            }

            view.findViewById<ImageButton>(R.id.btnSlotAudio)?.setOnClickListener {
                setAudioActiveSlot(index)
            }

            view.findViewById<ImageButton>(R.id.btnSlotFullscreen)?.setOnClickListener {
                enterSlotFullscreen(index)
            }

            container.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    vFocusBorder?.visibility = View.VISIBLE
                    llSlotOverlay?.visibility = View.VISIBLE
                    if (isActive) {
                        setAudioActiveSlot(index)
                    }
                } else {
                    if (index != activeAudioSlotIndex) {
                        vFocusBorder?.visibility = View.GONE
                    }
                }
            }

            container.setOnClickListener {
                if (!isActive) {
                    openFeedPickerDialog(index)
                } else {
                    setAudioActiveSlot(index)
                    toggleOverlay()
                }
            }

            setupWebView()
        }

        private fun toggleOverlay() {
            llSlotOverlay?.let { overlay ->
                overlay.visibility = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (overlay.visibility == View.VISIBLE) {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ overlay.visibility = View.GONE }, 4000)
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun setupWebView() {
            val wv = webView ?: return
            val s = wv.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.mediaPlaybackRequiresUserGesture = false
            s.loadWithOverviewMode = true
            s.useWideViewPort = true
            s.javaScriptCanOpenWindowsAutomatically = false
            s.setSupportMultipleWindows(false)
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            s.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

            wv.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    return false // Suppress pop-ups
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectCleanPlayerStyle(view)
                    if (index == activeAudioSlotIndex) {
                        unmuteVideo(view)
                    } else {
                        muteVideo(view)
                    }
                }
            }
        }

        fun loadStream(data: SlotData) {
            currentData = data
            isActive = true
            llEmptyView?.visibility = View.GONE
            flActiveStream?.visibility = View.VISIBLE
            tvSlotTitle?.text = data.title

            val wv = webView ?: return
            var targetUrl = data.url
            val headers = mutableMapOf<String, String>()

            if (!data.isDirect && data.channelId != null) {
                val baseUrl = repository.getBaseUrl()
                val folder = repository.getDefaultServer()
                targetUrl = "$baseUrl/$folder/stream-${data.channelId}.php"
                headers["Referer"] = "$baseUrl/"
            } else if (targetUrl.contains("dlive.sx") || targetUrl.contains("thedaddy.pk")) {
                headers["Referer"] = "https://dlive.sx/"
            } else if (targetUrl.contains("timst") || targetUrl.contains("ehgembxe")) {
                headers["Referer"] = "https://timst.top/"
            }

            wv.loadUrl(targetUrl, headers)
            llSlotOverlay?.visibility = View.VISIBLE
            handler.postDelayed({ llSlotOverlay?.visibility = View.GONE }, 4000)
        }

        fun setAudioActive(active: Boolean) {
            val wv = webView ?: return
            if (active) {
                tvAudioStatus?.text = "🔊 ÁUDIO"
                tvAudioStatus?.setTextColor(Color.parseColor("#38BDF8"))
                vFocusBorder?.visibility = View.VISIBLE
                unmuteVideo(wv)
            } else {
                tvAudioStatus?.text = "🔇"
                tvAudioStatus?.setTextColor(Color.parseColor("#9CA3AF"))
                if (!container.isFocused) {
                    vFocusBorder?.visibility = View.GONE
                }
                muteVideo(wv)
            }
        }

        private fun unmuteVideo(view: WebView?) {
            view?.evaluateJavascript(
                "(function(){ var vids = document.querySelectorAll('video'); for(var i=0; i<vids.length; i++){ vids[i].muted = false; vids[i].volume = 1.0; if(vids[i].paused) vids[i].play(); } })();",
                null
            )
        }

        private fun muteVideo(view: WebView?) {
            view?.evaluateJavascript(
                "(function(){ var vids = document.querySelectorAll('video'); for(var i=0; i<vids.length; i++){ vids[i].muted = true; } })();",
                null
            )
        }

        private fun injectCleanPlayerStyle(view: WebView?) {
            val js = """
                (function() {
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.innerHTML = 'header, footer, nav, .site-header, .site-footer, .sidebar, .chat-panel, #shareCodeOverlay, iframe:not(#thatframe):not([id^="player"]):not(#streamPlayer) { display: none !important; } ' +
                                      'html, body { margin:0 !important; padding:0 !important; background-color:#000 !important; overflow:hidden !important; width:100% !important; height:100% !important; } ' +
                                      'iframe#thatframe, .preview-wrap, #player, #player video, .jwplayer, .jw-wrapper, .jw-media, iframe#streamPlayer, video { position:fixed !important; top:0 !important; left:0 !important; width:100vw !important; height:100vh !important; z-index:999999 !important; object-fit:contain !important; }';
                    document.head.appendChild(style);
                })();
            """.trimIndent()
            view?.evaluateJavascript(js, null)
        }

        fun detachActiveStreamView() {
            flActiveStream?.let { streamView ->
                (streamView.parent as? ViewGroup)?.removeView(streamView)
            }
        }

        fun reattachActiveStreamView() {
            flActiveStream?.let { streamView ->
                (streamView.parent as? ViewGroup)?.removeView(streamView)
                container.addView(streamView)
                streamView.visibility = View.VISIBLE
            }
        }

        fun clear() {
            isActive = false
            currentData = null
            handler.removeCallbacksAndMessages(null)
            webView?.loadUrl("about:blank")
            flActiveStream?.visibility = View.GONE
            llEmptyView?.visibility = View.VISIBLE
            vFocusBorder?.visibility = View.GONE
        }

        fun destroy() {
            clear()
            webView?.destroy()
        }
    }
}
