package com.dlive.ptstream.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dlive.ptstream.data.*
import com.dlive.ptstream.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: ChannelRepository,
    onChannelClick: (Channel) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val isTabletOrLandscape = configuration.screenWidthDp >= 600

    var selectedTab by remember { mutableStateOf(TabFilter.PORTUGAL) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("Todos") }
    var gamesSubFilter by remember { mutableStateOf("Todos") }
    var refreshKey by remember { mutableStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Live Matches State
    var liveEvents by remember { mutableStateOf<List<LiveEvent>>(emptyList()) }
    var isLoadingEvents by remember { mutableStateOf(false) }

    // OTA Update State
    val updateManager = remember { UpdateManager(context) }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (updateManager.isCheckOnStartEnabled()) {
            val result = updateManager.checkForUpdate()
            result.getOrNull()?.let { release ->
                availableRelease = release
                showUpdateDialog = true
            }
        }
    }

    LaunchedEffect(selectedTab, refreshKey) {
        if (selectedTab == TabFilter.LIVE_GAMES) {
            // Load existing cached events immediately if available
            val existing = repository.getLiveEvents()
            if (existing.isNotEmpty()) {
                liveEvents = existing
            }
            isLoadingEvents = true
            repository.fetchLiveMatches(scope) { events ->
                liveEvents = events
                isLoadingEvents = false
            }
        }
    }

    val footballChannels = remember(searchQuery, refreshKey) {
        repository.getTopFootballChannels(searchQuery)
    }

    val channels = remember(selectedTab, searchQuery, selectedCategory, refreshKey) {
        if (selectedTab == TabFilter.LIVE_GAMES) emptyList()
        else {
            if (selectedCategory == "⭐ Favoritos") {
                repository.getChannels(TabFilter.FAVORITES, searchQuery)
            } else {
                repository.getChannels(selectedTab, searchQuery, selectedCategory)
            }
        }
    }

    val categories = remember(selectedTab, refreshKey) {
        val list = repository.getAvailableCategories(selectedTab).filter { it != "Todos" }
        listOf("Todos", "⭐ Favoritos") + list
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundDark)
            ) {
                // Top App Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (!isSearchActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RedPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Daniel Streams",
                                    color = TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var searchFocused by remember { mutableStateOf(false) }
                            var settingsFocused by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { isSearchActive = true },
                                modifier = Modifier
                                    .onFocusChanged { searchFocused = it.isFocused }
                                    .focusable()
                                    .background(if (searchFocused) Color(0x33E50914) else Color.Transparent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Pesquisar",
                                    tint = if (searchFocused) RedPrimary else TextPrimary
                                )
                            }
                            IconButton(
                                onClick = { showSettingsDialog = true },
                                modifier = Modifier
                                    .onFocusChanged { settingsFocused = it.isFocused }
                                    .focusable()
                                    .background(if (settingsFocused) Color(0x33E50914) else Color.Transparent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Definições",
                                    tint = if (settingsFocused) RedPrimary else TextPrimary
                                )
                            }
                        }
                    } else {
                        // Search bar input
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Pesquisar canais ou jogos...", color = TextSecondary) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                            },
                            trailingIcon = {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    isSearchActive = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextPrimary)
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedIndicatorColor = RedPrimary,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // Tab Selector (3 Tabs: Portugal, Jogos em Direto, Todos)
                TabRow(
                    selectedTabIndex = when (selectedTab) {
                        TabFilter.PORTUGAL -> 0
                        TabFilter.LIVE_GAMES -> 1
                        TabFilter.ALL -> 2
                        else -> 0
                    },
                    containerColor = BackgroundDark,
                    contentColor = RedPrimary,
                    indicator = { tabPositions ->
                        val idx = when (selectedTab) {
                            TabFilter.PORTUGAL -> 0
                            TabFilter.LIVE_GAMES -> 1
                            TabFilter.ALL -> 2
                            else -> 0
                        }
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[idx]),
                            height = 3.dp,
                            color = RedPrimary
                        )
                    }
                ) {
                    var tab0Focused by remember { mutableStateOf(false) }
                    Tab(
                        selected = selectedTab == TabFilter.PORTUGAL,
                        onClick = {
                            selectedTab = TabFilter.PORTUGAL
                            selectedCategory = "Todos"
                        },
                        modifier = Modifier
                            .onFocusChanged { tab0Focused = it.isFocused }
                            .focusable()
                            .background(if (tab0Focused) Color(0x33E50914) else Color.Transparent),
                        text = {
                            Text(
                                "🇵🇹 Portugal",
                                fontWeight = if (selectedTab == TabFilter.PORTUGAL) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == TabFilter.PORTUGAL || tab0Focused) TextPrimary else TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    )
                    var tab1Focused by remember { mutableStateOf(false) }
                    Tab(
                        selected = selectedTab == TabFilter.LIVE_GAMES,
                        onClick = {
                            selectedTab = TabFilter.LIVE_GAMES
                            selectedCategory = "Todos"
                        },
                        modifier = Modifier
                            .onFocusChanged { tab1Focused = it.isFocused }
                            .focusable()
                            .background(if (tab1Focused) Color(0x33E50914) else Color.Transparent),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "⚽ Jogos em Direto",
                                    fontWeight = if (selectedTab == TabFilter.LIVE_GAMES) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == TabFilter.LIVE_GAMES || tab1Focused) TextPrimary else TextSecondary,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(AccentGreen)
                                )
                            }
                        }
                    )
                    var tab2Focused by remember { mutableStateOf(false) }
                    Tab(
                        selected = selectedTab == TabFilter.ALL,
                        onClick = {
                            selectedTab = TabFilter.ALL
                            selectedCategory = "Todos"
                        },
                        modifier = Modifier
                            .onFocusChanged { tab2Focused = it.isFocused }
                            .focusable()
                            .background(if (tab2Focused) Color(0x33E50914) else Color.Transparent),
                        text = {
                            Text(
                                "🌐 Todos",
                                fontWeight = if (selectedTab == TabFilter.ALL) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == TabFilter.ALL || tab2Focused) TextPrimary else TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    )
                }

                // Category Chips
                if (selectedTab != TabFilter.LIVE_GAMES && categories.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(categories) { cat ->
                            val isSelected = selectedCategory == cat
                            var chipFocused by remember { mutableStateOf(false) }
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedCategory = cat },
                                modifier = Modifier
                                    .onFocusChanged { chipFocused = it.isFocused }
                                    .focusable(),
                                label = { Text(cat, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedPrimary,
                                    selectedLabelColor = Color.White,
                                    containerColor = if (chipFocused) SurfaceVariantDark else SurfaceDark,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = if (chipFocused) RedPrimary else if (isSelected) RedPrimary else BorderDark,
                                    selectedBorderColor = RedPrimary,
                                    borderWidth = if (chipFocused) 2.dp else 1.dp
                                )
                            )
                        }
                    }
                }
            }
        },
        containerColor = BackgroundDark
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == TabFilter.LIVE_GAMES) {
                // Live Matches Tab View with Football Channels + Live Events
                val filteredEvents = remember(liveEvents, searchQuery) {
                    if (searchQuery.isBlank()) liveEvents
                    else liveEvents.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                                it.genreName.contains(searchQuery, ignoreCase = true)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Sub-filter row
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val subOptions = listOf(
                            "Todos" to "⚽ Todos (${filteredEvents.size + footballChannels.size})",
                            "Jogos" to "🔥 Jogos Hoje (${filteredEvents.size})",
                            "Canais" to "📺 Canais Futebol (${footballChannels.size})"
                        )
                        items(subOptions) { (key, label) ->
                            val isSelected = gamesSubFilter == key
                            FilterChip(
                                selected = isSelected,
                                onClick = { gamesSubFilter = key },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = RedPrimary,
                                    selectedLabelColor = Color.White,
                                    containerColor = SurfaceDark,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = if (isSelected) RedPrimary else BorderDark,
                                    selectedBorderColor = RedPrimary
                                )
                            )
                        }
                    }

                    if (isLoadingEvents && filteredEvents.isEmpty() && gamesSubFilter != "Canais") {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = RedPrimary)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("A procurar jogos em direto...", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Section 1: Live Matches
                            if (gamesSubFilter == "Todos" || gamesSubFilter == "Jogos") {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "🔥 Jogos e Eventos Hoje (TimStreams)",
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${filteredEvents.size} disponíveis",
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (filteredEvents.isEmpty()) {
                                    item {
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                                        ) {
                                            Text(
                                                "Sem transmissões ao vivo agendadas no momento. Veja os canais 24/7 abaixo.",
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(14.dp)
                                            )
                                        }
                                    }
                                } else {
                                    items(filteredEvents, key = { it.id }) { event ->
                                        LiveEventCard(
                                            event = event,
                                            onPlayClick = {
                                                PlayerActivity.closeActivePip()
                                                val streamUrl = event.streams.firstOrNull()?.url ?: ""
                                                val backupUrl = if (event.streams.size > 1) event.streams[1].url else null
                                                val intent = Intent(context, PlayerActivity::class.java).apply {
                                                    putExtra("EXTRA_CHANNEL_ID", "event_${event.id}")
                                                    putExtra("EXTRA_CHANNEL_NAME", event.name)
                                                    putExtra("EXTRA_DIRECT_STREAM_URL", streamUrl)
                                                    if (backupUrl != null) {
                                                        putExtra("EXTRA_BACKUP_STREAM_URL", backupUrl)
                                                    }
                                                }
                                                context.startActivity(intent)
                                            }
                                        )
                                    }
                                }
                            }

                            // Section 2: Top Football Channels (PT + English)
                            if (gamesSubFilter == "Todos" || gamesSubFilter == "Canais") {
                                item {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "🏆 Canais de Futebol 24/7 (PT & Mundiais)",
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${footballChannels.size} canais",
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                items(footballChannels, key = { "fb_${it.id}" }) { channel ->
                                    ChannelCard(
                                        channel = channel,
                                        onPlayClick = { onChannelClick(channel) },
                                        onToggleFavorite = {
                                            repository.toggleFavorite(channel.id)
                                            refreshKey++
                                        },
                                        onHideChannel = {
                                            repository.hideChannel(channel.id)
                                            refreshKey++
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Regular Channels List (Portugal & Todos)
                if (channels.isEmpty()) {
                    EmptyStateView(tab = selectedTab, query = searchQuery)
                } else if (isTabletOrLandscape) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 340.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(channels, key = { it.id }) { channel ->
                            ChannelCard(
                                channel = channel,
                                onPlayClick = { onChannelClick(channel) },
                                onToggleFavorite = {
                                    repository.toggleFavorite(channel.id)
                                    refreshKey++
                                },
                                onHideChannel = {
                                    repository.hideChannel(channel.id)
                                    refreshKey++
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(channels, key = { it.id }) { channel ->
                            ChannelCard(
                                channel = channel,
                                onPlayClick = { onChannelClick(channel) },
                                onToggleFavorite = {
                                    repository.toggleFavorite(channel.id)
                                    refreshKey++
                                },
                                onHideChannel = {
                                    repository.hideChannel(channel.id)
                                    refreshKey++
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            repository = repository,
            updateManager = updateManager,
            onDismiss = { showSettingsDialog = false },
            onChannelsSynced = { refreshKey++ },
            onUpdateFound = { release ->
                availableRelease = release
                showUpdateDialog = true
            }
        )
    }

    if (showUpdateDialog && availableRelease != null) {
        AppUpdateDialog(
            release = availableRelease!!,
            updateManager = updateManager,
            onDismiss = { showUpdateDialog = false }
        )
    }
}

@Composable
fun LiveEventCard(
    event: LiveEvent,
    onPlayClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.025f else 1.0f, label = "event_scale")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onPlayClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) SurfaceVariantDark else SurfaceDark
        ),
        border = if (isFocused) {
            BorderStroke(2.5.dp, RedPrimary)
        } else {
            CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderDark))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Team / Match Logo
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceVariantDark),
                    contentAlignment = Alignment.Center
                ) {
                    if (!event.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = event.logo,
                            contentDescription = event.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = if (event.isSoccer) "⚽" else "🏁",
                            fontSize = 22.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = event.name,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TagBadge(text = event.genreName, color = if (event.isSoccer) RedPrimary else AccentGreen)
                        if (event.time.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            TagBadge(text = event.time, color = TextSecondary)
                        }
                        if (event.viewers > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(AccentGreen)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    "${event.viewers}",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // Watch Button
            Button(
                onClick = onPlayClick,
                colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Assistir",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Assistir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ChannelCard(
    channel: Channel,
    onPlayClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHideChannel: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.025f else 1.0f, label = "channel_scale")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onPlayClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) SurfaceVariantDark else SurfaceDark
        ),
        border = if (isFocused) {
            BorderStroke(2.5.dp, RedPrimary)
        } else {
            CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(BorderDark))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Channel Logo or ID Badge
                val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
                val onlineLogo = channel.logoUrl

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (channel.isPortuguese) RedPrimary.copy(alpha = 0.15f) else SurfaceVariantDark),
                    contentAlignment = Alignment.Center
                ) {
                    if (onlineLogo != null) {
                        AsyncImage(
                            model = onlineLogo,
                            contentDescription = channel.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else if (localLogo != null) {
                        Image(
                            painter = painterResource(id = localLogo),
                            contentDescription = channel.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = channel.id,
                            color = if (channel.isPortuguese) RedPrimary else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = channel.name,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        // Online / Offline Status Dot
                        if (channel.safeStatus == ChannelStatus.ONLINE) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AccentGreen)
                            )
                        } else if (channel.safeStatus == ChannelStatus.OFFLINE) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(RedPrimary)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (channel.isPortuguese) {
                            TagBadge(text = "PT 🇵🇹", color = RedPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                        } else if (channel.country.isNotBlank() && channel.country != "Outro") {
                            TagBadge(text = channel.country, color = AccentGreen)
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        TagBadge(text = channel.category, color = TextSecondary)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Favorite Button
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (channel.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favorito",
                        tint = if (channel.isFavorite) AccentGold else TextSecondary
                    )
                }

                // Play Button
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RedPrimary)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Reproduzir",
                        tint = Color.White
                    )
                }

                // Options Menu (Hide channel)
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Mais opções",
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Ocultar da lista", color = TextPrimary, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = RedPrimary)
                            },
                            onClick = {
                                showMenu = false
                                onHideChannel()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TagBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EmptyStateView(tab: TabFilter, query: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = if (query.isNotBlank()) Icons.Default.SearchOff else if (tab == TabFilter.FAVORITES) Icons.Filled.Star else Icons.Default.TvOff,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    query.isNotBlank() -> "Nenhum canal encontrado para \"$query\""
                    tab == TabFilter.FAVORITES -> "Ainda não tem canais favoritos"
                    else -> "Nenhum canal disponível"
                },
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (tab == TabFilter.FAVORITES && query.isBlank())
                    "Toque na estrela para adicionar canais aos favoritos"
                else
                    "Tente pesquisar por outro termo ou categoria",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun SettingsDialog(
    repository: ChannelRepository,
    updateManager: UpdateManager,
    onDismiss: () -> Unit,
    onChannelsSynced: () -> Unit,
    onUpdateFound: (ReleaseInfo) -> Unit
) {
    val scope = rememberCoroutineScope()
    var autoPip by remember { mutableStateOf(repository.isAutoPipOnBack()) }
    var autoUnmute by remember { mutableStateOf(repository.isAutoUnmuteEnabled()) }
    var selectedServer by remember { mutableStateOf(repository.getDefaultServer()) }
    var baseUrl by remember { mutableStateOf(repository.getBaseUrl()) }
    var timstBaseUrl by remember { mutableStateOf(repository.getTimstBaseUrl()) }

    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    var isTestingChannels by remember { mutableStateOf(false) }
    var testStatusText by remember { mutableStateOf<String?>(null) }

    val serverOptions = listOf(
        "stream" to "Servidor 1 (Stream)",
        "cast" to "Servidor 2 (Cast)",
        "watch" to "Servidor 3 (Watch)",
        "player" to "Servidor 4 (Player)",
        "plus" to "Servidor 5 (Plus)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = RedPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Definições",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(RedPrimary.copy(alpha = 0.15f))
                        .border(1.dp, RedPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "v${updateManager.getCurrentVersionName()}",
                        color = RedPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Auto PiP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("📺 PiP Automático ao Voltar", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Entra em modo janela flutuante sem perguntar ao retroceder.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoPip,
                        onCheckedChange = {
                            autoPip = it
                            repository.setAutoPipOnBack(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RedPrimary
                        )
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 2. Auto Unmute
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("🔊 Ativar Som Automaticamente", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Desmuta a stream logo que carrega no leitor.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoUnmute,
                        onCheckedChange = {
                            autoUnmute = it
                            repository.setAutoUnmuteEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RedPrimary
                        )
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 3. Servidor Padrao
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("🌐 Servidor Padrão Inicial", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Escolha a fonte principal de carregamento das streams:", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    serverOptions.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedServer = key
                                    repository.setDefaultServer(key)
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedServer == key,
                                onClick = {
                                    selectedServer = key
                                    repository.setDefaultServer(key)
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = RedPrimary)
                            )
                            Text(label, color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 4. TimStreams Domain
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("🔗 Domínio TimStreams (Jogos & Backup)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Altere se o domínio mudar (.st, .top, .cfd, etc.):", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = timstBaseUrl,
                        onValueChange = {
                            timstBaseUrl = it
                            repository.setTimstBaseUrl(it)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedIndicatorColor = RedPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 5. DaddyLive Domain
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("🔗 Domínio DaddyLive (Base URL)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Caso o domínio mude ou esteja bloqueado pelo operador:", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            repository.setBaseUrl(it)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedIndicatorColor = RedPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 6. Testar Canais Portugueses (ON / OFF)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            isTestingChannels = true
                            testStatusText = "A iniciar teste aos canais portugueses..."
                            repository.testPortugueseChannels(
                                scope = scope,
                                onProgress = { current, total, name, isOnline ->
                                    testStatusText = "A testar [$current/$total]: $name (${if (isOnline) "🟢 ON" else "🔴 OFF"})"
                                },
                                onFinished = { onlineCount, totalCount ->
                                    isTestingChannels = false
                                    testStatusText = "✅ Teste concluído: $onlineCount de $totalCount canais operacionais!"
                                    onChannelsSynced()
                                }
                            )
                        },
                        enabled = !isTestingChannels,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isTestingChannels) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("A testar streams...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🧪 Testar Canais PT (ON / OFF)", fontSize = 13.sp)
                        }
                    }

                    testStatusText?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(it, color = AccentGreen, fontSize = 12.sp)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 7. Sincronizar Canais
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            isSyncing = true
                            syncMessage = null
                            repository.syncChannelsFromWeb(scope) { success ->
                                isSyncing = false
                                syncMessage = if (success) "✅ Lista de canais e backup TimStreams sincronizados!" else "⚠️ Falha ao contactar servidor. Usando catálogo local."
                                onChannelsSynced()
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("A sincronizar...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sincronizar Canais & Backup Online", fontSize = 13.sp)
                        }
                    }

                    syncMessage?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(it, color = if (it.startsWith("✅")) AccentGreen else RedPrimary, fontSize = 12.sp)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 8. Canais Ocultos
                var localHiddenRefresh by remember { mutableStateOf(0) }
                val hiddenChannels = remember(localHiddenRefresh) { repository.getHiddenChannels() }
                var showHiddenChannelsDialog by remember { mutableStateOf(false) }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("👁️ Canais Ocultos", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hiddenChannels.isEmpty()) "Nenhum canal oculto no momento."
                        else "${hiddenChannels.size} canais atualmente ocultos da lista.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    if (hiddenChannels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showHiddenChannelsDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                            ) {
                                Text("Ver Canais (${hiddenChannels.size})", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    repository.unhideAllChannels()
                                    localHiddenRefresh++
                                    onChannelsSynced()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RedPrimary)
                            ) {
                                Text("Restaurar Todos", fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (showHiddenChannelsDialog) {
                    HiddenChannelsDialog(
                        repository = repository,
                        onDismiss = { showHiddenChannelsDialog = false },
                        onRestored = {
                            localHiddenRefresh++
                            onChannelsSynced()
                        }
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 9. Atualizações da Aplicação (GitHub OTA)
                var githubRepoText by remember { mutableStateOf(updateManager.getGitHubRepo()) }
                var checkOnStart by remember { mutableStateOf(updateManager.isCheckOnStartEnabled()) }
                var isCheckingUpdate by remember { mutableStateOf(false) }
                var updateStatusMessage by remember { mutableStateOf<String?>(null) }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🚀 Atualizações da Aplicação", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Instalada: v${updateManager.getCurrentVersionName()}", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Atualizações diretas via GitHub Releases (OTA sem fios):", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    TextField(
                        value = githubRepoText,
                        onValueChange = {
                            githubRepoText = it
                            updateManager.setGitHubRepo(it)
                        },
                        label = { Text("Repositório GitHub", fontSize = 11.sp) },
                        placeholder = { Text("ex: dnogueira61/daniel-streams", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedIndicatorColor = RedPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    var githubTokenText by remember { mutableStateOf(updateManager.getGitHubToken()) }
                    TextField(
                        value = githubTokenText,
                        onValueChange = {
                            githubTokenText = it
                            updateManager.setGitHubToken(it)
                        },
                        label = { Text("Token GitHub (Opcional para repositórios privados)", fontSize = 11.sp) },
                        placeholder = { Text("ghp_... (vazio se repo for público)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedIndicatorColor = RedPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Verificar ao iniciar a app", color = TextPrimary, fontSize = 12.sp)
                        Switch(
                            checked = checkOnStart,
                            onCheckedChange = {
                                checkOnStart = it
                                updateManager.setCheckOnStartEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = RedPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            updateStatusMessage = null
                            scope.launch {
                                val result = updateManager.checkForUpdate()
                                isCheckingUpdate = false
                                result.fold(
                                    onSuccess = { release ->
                                        if (release != null) {
                                            updateStatusMessage = "Nova versão v${release.versionName} encontrada!"
                                            onUpdateFound(release)
                                        } else {
                                            updateStatusMessage = "✅ Estás na versão mais recente (v${updateManager.getCurrentVersionName()})!"
                                        }
                                    },
                                    onFailure = { err ->
                                        updateStatusMessage = "ℹ️ ${err.message ?: "Erro ao contactar GitHub"}"
                                    }
                                )
                            }
                        },
                        enabled = !isCheckingUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("A procurar atualizações...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Procurar Atualizações Agora", fontSize = 13.sp)
                        }
                    }

                    updateStatusMessage?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            color = if (it.startsWith("✅")) AccentGreen else if (it.startsWith("ℹ️")) AccentGold else RedPrimary,
                            fontSize = 12.sp
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // 10. Informações
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Daniel Streams v${updateManager.getCurrentVersionName()} (Build ${updateManager.getCurrentVersionCode()})", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Telemóvel & Google TV / Android TV", color = TextSecondary, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = RedPrimary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun HiddenChannelsDialog(
    repository: ChannelRepository,
    onDismiss: () -> Unit,
    onRestored: () -> Unit
) {
    var hiddenList by remember { mutableStateOf(repository.getHiddenChannels()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Canais Ocultos (${hiddenList.size})", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            if (hiddenList.isEmpty()) {
                Text("Nenhum canal oculto.", color = TextSecondary, fontSize = 13.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hiddenList, key = { it.id }) { ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BackgroundDark)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                ch.name,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Button(
                                onClick = {
                                    repository.unhideChannel(ch.id)
                                    hiddenList = repository.getHiddenChannels()
                                    onRestored()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Restaurar", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = RedPrimary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun AppUpdateDialog(
    release: ReleaseInfo,
    updateManager: UpdateManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = RedPrimary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Nova Versão Disponível!", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagBadge(text = "v${release.versionName}", color = RedPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    if (release.apkSize > 0) {
                        Text("Tamanho: ${release.apkSize / (1024 * 1024)} MB", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("Novidades / Registo de Alterações:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                    color = BackgroundDark,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = release.changelog.ifBlank { "Melhorias de desempenho e correções de bugs." },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState())
                    )
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "A descarregar: $downloadProgress% (${downloadedBytes / (1024 * 1024)} MB / ${if (totalBytes > 0) "${totalBytes / (1024 * 1024)} MB" else "?"})",
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = RedPrimary,
                        trackColor = BorderDark
                    )
                }

                if (downloadedFile != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("✅ Download concluído! Pronto para instalar.", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("⚠️ $it", color = RedPrimary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (downloadedFile != null) {
                Button(
                    onClick = {
                        if (!updateManager.canInstallPackages()) {
                            updateManager.openInstallPermissionSettings()
                        } else {
                            updateManager.installApk(downloadedFile!!)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Instalar Agora", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        if (!updateManager.canInstallPackages()) {
                            updateManager.openInstallPermissionSettings()
                        } else {
                            isDownloading = true
                            errorMessage = null
                            scope.launch {
                                val result = updateManager.downloadApk(release.downloadUrl, release.apkFileName) { pct, down, tot ->
                                    downloadProgress = pct
                                    downloadedBytes = down
                                    totalBytes = tot
                                }
                                isDownloading = false
                                result.fold(
                                    onSuccess = { file ->
                                        downloadedFile = file
                                        updateManager.installApk(file)
                                    },
                                    onFailure = { err ->
                                        errorMessage = err.message ?: "Falha ao descarregar atualização"
                                    }
                                )
                            }
                        }
                    },
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("A descarregar...")
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Atualizar Agora")
                    }
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("Mais tarde", color = TextSecondary)
                }
            }
        },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp)
    )
}
