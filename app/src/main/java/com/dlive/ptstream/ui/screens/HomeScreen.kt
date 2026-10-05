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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
    onChannelClick: (Channel, String?) -> Unit,
    onThemeChange: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val isTabletOrLandscape = configuration.screenWidthDp >= 600 || repository.isTv()

    val defaultTabPref = remember { repository.getDefaultTab() }
    val initialTab = remember {
        when (defaultTabPref.uppercase()) {
            "LIVE_GAMES" -> TabFilter.LIVE_GAMES
            "GAMING" -> TabFilter.GAMING
            "FAVORITES" -> TabFilter.FAVORITES
            "ALL" -> TabFilter.ALL
            else -> TabFilter.PORTUGAL
        }
    }
    var selectedTab by remember { mutableStateOf(initialTab) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            kotlinx.coroutines.delay(150)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    var selectedCategory by remember { mutableStateOf("Todos") }
    var gamesSubFilter by remember { mutableStateOf("Canais") }
    var selectedLeagueFilter by remember { mutableStateOf("Todos") }
    var selectedFootballLeague by remember { mutableStateOf("Todas") }
    var refreshKey by remember { mutableStateOf(0) }
    val prefViewMode = remember(refreshKey) { repository.getChannelViewMode() }
    val showClock = remember(refreshKey) { repository.isShowClockEnabled() }
    val isSplitLayout = if (prefViewMode == "SPLIT_LIST") true else if (prefViewMode == "GRID") false else isTabletOrLandscape
    val isGridLayout = prefViewMode == "GRID"
    var showSettingsDialog by remember { mutableStateOf(false) }

    var focusedOrSelectedChannel by remember { mutableStateOf<Channel?>(null) }
    var selectedChannelForSheet by remember { mutableStateOf<Channel?>(null) }

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

    val channelsVersion by repository.channelsVersion

    val sportsChannels = remember(searchQuery, refreshKey, channelsVersion) {
        repository.getChannels(TabFilter.ALL, searchQuery, "Desporto")
    }

    val favChannels = remember(refreshKey, channelsVersion) {
        repository.getChannels(TabFilter.FAVORITES)
    }

    val channels = remember(selectedTab, searchQuery, selectedCategory, refreshKey, channelsVersion) {
        if (selectedTab == TabFilter.LIVE_GAMES) emptyList()
        else {
            if (selectedCategory == "⭐ Favoritos") {
                repository.getChannels(TabFilter.FAVORITES, searchQuery)
            } else {
                repository.getChannels(selectedTab, searchQuery, selectedCategory)
            }
        }
    }

    val epgVersion by repository.epgRepository.epgVersion

    val categories = remember(selectedTab, refreshKey, channelsVersion) {
        val list = repository.getAvailableCategories(selectedTab).filter { it != "Todos" }
        listOf("Todos", "⭐ Favoritos") + list
    }

    var categoryDropdownOpen by remember { mutableStateOf(false) }

    val theme = LocalCustomColors.current

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.surface)
            ) {
                // Top App Bar (NOS TV style)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (!isSearchActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = com.dlive.ptstream.R.drawable.ic_app_logo),
                                contentDescription = "Logo",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = when (selectedTab) {
                                    TabFilter.LIVE_GAMES -> "Desporto"
                                    TabFilter.GAMING -> "Gaming & Esports 🎮"
                                    TabFilter.ALL -> "Mundo"
                                    else -> "Canais"
                                },
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showClock) {
                                DigitalClock()
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            var searchFocused by remember { mutableStateOf(false) }
                            var settingsFocused by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { isSearchActive = true },
                                modifier = Modifier
                                    .onFocusChanged { searchFocused = it.isFocused }
                                    .focusable()
                                    .background(if (searchFocused) Color(0x3338BDF8) else Color.Transparent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Pesquisar",
                                    tint = if (searchFocused) Color(0xFF38BDF8) else TextPrimary
                                )
                            }
                            IconButton(
                                onClick = { showSettingsDialog = true },
                                modifier = Modifier
                                    .onFocusChanged { settingsFocused = it.isFocused }
                                    .focusable()
                                    .background(if (settingsFocused) Color(0x3338BDF8) else Color.Transparent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Definições",
                                    tint = if (settingsFocused) Color(0xFF38BDF8) else TextPrimary
                                )
                            }
                        }
                    } else {
                        // Search bar input
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Pesquisar canais...", color = TextSecondary) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                }
                            ),
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                            },
                            trailingIcon = {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    isSearchActive = false
                                    keyboardController?.hide()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextPrimary)
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = SurfaceDark,
                                unfocusedContainerColor = SurfaceDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedIndicatorColor = Color(0xFF38BDF8),
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // Filter pills row (Filtrar por nome & Categoria Todos)
                if (selectedTab != TabFilter.LIVE_GAMES && !isSearchActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pill 1: Filtrar por nome
                        Surface(
                            onClick = { isSearchActive = true },
                            shape = RoundedCornerShape(20.dp),
                            color = if (searchQuery.isNotBlank()) Color(0x3338BDF8) else Color(0xFF1E2230),
                            border = BorderStroke(1.dp, if (searchQuery.isNotBlank()) Color(0xFF38BDF8) else Color(0xFF374151)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = null,
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank()) searchQuery else "Filtrar por nome",
                                    color = if (searchQuery.isNotBlank()) Color.White else Color(0xFF9CA3AF),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Pill 2: Categorias (Todos ▾)
                        Box {
                            Surface(
                                onClick = { categoryDropdownOpen = true },
                                shape = RoundedCornerShape(20.dp),
                                color = if (selectedCategory != "Todos") Color(0x3338BDF8) else Color.White,
                                border = BorderStroke(1.dp, if (selectedCategory != "Todos") Color(0xFF38BDF8) else Color.White)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.FilterList,
                                        contentDescription = null,
                                        tint = if (selectedCategory != "Todos") Color.White else Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = selectedCategory,
                                        color = if (selectedCategory != "Todos") Color.White else Color.Black,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = if (selectedCategory != "Todos") Color.White else Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = categoryDropdownOpen,
                                onDismissRequest = { categoryDropdownOpen = false },
                                modifier = Modifier.background(SurfaceDark)
                            ) {
                                categories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = cat,
                                                color = if (selectedCategory == cat) Color(0xFF38BDF8) else TextPrimary,
                                                fontWeight = if (selectedCategory == cat) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            selectedCategory = cat
                                            categoryDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = theme.surface,
                contentColor = Color.White,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == TabFilter.PORTUGAL && selectedCategory != "⭐ Favoritos",
                    onClick = {
                        selectedTab = TabFilter.PORTUGAL
                        selectedCategory = "Todos"
                    },
                    icon = { Icon(Icons.Default.Menu, contentDescription = "Canais") },
                    label = {
                        Text(
                            "Canais",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == TabFilter.PORTUGAL && selectedCategory != "⭐ Favoritos") FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = theme.primary,
                        selectedTextColor = theme.primary,
                        indicatorColor = theme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == TabFilter.LIVE_GAMES,
                    onClick = {
                        selectedTab = TabFilter.LIVE_GAMES
                        gamesSubFilter = "Canais"
                    },
                    icon = { Icon(Icons.Default.SportsSoccer, contentDescription = "Desporto") },
                    label = {
                        Text(
                            "Desporto",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == TabFilter.LIVE_GAMES) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = theme.primary,
                        selectedTextColor = theme.primary,
                        indicatorColor = theme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == TabFilter.ALL && selectedCategory != "⭐ Favoritos",
                    onClick = {
                        selectedTab = TabFilter.ALL
                        selectedCategory = "Todos"
                    },
                    icon = { Icon(Icons.Default.Public, contentDescription = "Mundo") },
                    label = {
                        Text(
                            "Mundo",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == TabFilter.ALL && selectedCategory != "⭐ Favoritos") FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = theme.primary,
                        selectedTextColor = theme.primary,
                        indicatorColor = theme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
                NavigationBarItem(
                    selected = selectedCategory == "⭐ Favoritos",
                    onClick = {
                        selectedTab = TabFilter.PORTUGAL
                        selectedCategory = "⭐ Favoritos"
                    },
                    icon = { Icon(Icons.Filled.Star, contentDescription = "Favoritos") },
                    label = {
                        Text(
                            "Favoritos",
                            fontSize = 12.sp,
                            fontWeight = if (selectedCategory == "⭐ Favoritos") FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = theme.primary,
                        selectedTextColor = theme.primary,
                        indicatorColor = theme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
                NavigationBarItem(
                    selected = showSettingsDialog,
                    onClick = { showSettingsDialog = true },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Definições") },
                    label = { Text("Definições", fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = theme.primary,
                        selectedTextColor = theme.primary,
                        indicatorColor = theme.primary.copy(alpha = 0.2f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        },
        containerColor = theme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == TabFilter.LIVE_GAMES) {
                // Desporto Tab View: Sub-tab 1 Canais de Desporto, Sub-tab 2 Jogos Hoje
                val filteredEvents = remember(liveEvents, searchQuery) {
                    if (searchQuery.isBlank()) liveEvents
                    else liveEvents.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                                it.genreName.contains(searchQuery, ignoreCase = true)
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Sub-filter row: #1 Canais de Desporto, #2 Jogos Hoje
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val subOptions = listOf(
                            "Canais" to "📺 Canais de Desporto (${sportsChannels.size})",
                            "Jogos" to "🔥 Jogos Hoje (${filteredEvents.size})"
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

                    if (gamesSubFilter == "Canais") {
                        // Sub-tab 1: All top sports TV channels
                        if (sportsChannels.isEmpty()) {
                            EmptyStateView(tab = selectedTab, query = searchQuery)
                        } else if (isGridLayout) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = if (isTabletOrLandscape) 200.dp else 160.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(sportsChannels, key = { index, ch -> "sp_grid_${ch.id}_$index" }) { _, channel ->
                                    ChannelGridCard(
                                        channel = channel,
                                        epgProgram = repository.epgRepository.getCurrentProgram(channel.name),
                                        onPlayClick = { onChannelClick(channel, null) },
                                        onToggleFavorite = {
                                            repository.toggleFavorite(channel.id)
                                            refreshKey++
                                        },
                                        onShowDetails = { selectedChannelForSheet = channel }
                                    )
                                }
                            }
                        } else if (isSplitLayout) {
                            val activeChannel = focusedOrSelectedChannel ?: sportsChannels.firstOrNull()
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(modifier = Modifier.weight(0.40f).fillMaxHeight()) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        itemsIndexed(sportsChannels, key = { index, ch -> "sp_split_${ch.id}_$index" }) { _, channel ->
                                            val isSelected = activeChannel?.id == channel.id
                                            SplitChannelRow(
                                                channel = channel,
                                                isSelected = isSelected,
                                                epgProgram = repository.epgRepository.getCurrentProgram(channel.name),
                                                onFocus = { focusedOrSelectedChannel = channel },
                                                onClick = {
                                                    focusedOrSelectedChannel = channel
                                                    onChannelClick(channel, null)
                                                },
                                                onPlayDirect = {
                                                    onChannelClick(channel, null)
                                                }
                                            )
                                        }
                                    }
                                }
                                Box(modifier = Modifier.weight(0.60f).fillMaxHeight()) {
                                    if (activeChannel != null) {
                                        EpgDetailPanel(
                                            channel = activeChannel,
                                            epgRepository = repository.epgRepository,
                                            onPlayClick = { directUrl -> onChannelClick(activeChannel, directUrl) },
                                            onToggleFavorite = {
                                                repository.toggleFavorite(activeChannel.id)
                                                refreshKey++
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(sportsChannels, key = { index, ch -> "sp_${ch.id}_$index" }) { _, channel ->
                                    ChannelCard(
                                        channel = channel,
                                        epgProgram = repository.epgRepository.getCurrentProgram(channel.name),
                                        onPlayClick = { onChannelClick(channel, null) },
                                        onShowDetails = { selectedChannelForSheet = channel },
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
                    } else {
                        // Sub-tab 2: Live matches for today (StreamFC style: Matchday by Leagues + Game-to-Channel Mapping)
                        val allCachedChannels = remember(refreshKey, channelsVersion) { repository.getAllCachedChannels() }

                        val soccerEvents = remember(filteredEvents) {
                            filteredEvents.filter { SportsMatchHelper.isSoccerEvent(it) }
                        }
                        val nonSoccerEvents = remember(filteredEvents) {
                            filteredEvents.filter { !SportsMatchHelper.isSoccerEvent(it) }
                        }
                        val sportsGroups = remember(nonSoccerEvents) {
                            nonSoccerEvents.groupBy { SportsMatchHelper.getSportCategory(it) }
                        }
                        val footballLeagueGroups = remember(soccerEvents) {
                            soccerEvents.groupBy { SportsMatchHelper.getSoccerLeague(it) }
                        }

                        // Subseparadores principais: Todos, Futebol e outras modalidades desportivas
                        val availableSubFilters = remember(filteredEvents.size, soccerEvents.size, sportsGroups) {
                            val list = mutableListOf<Pair<String, Int>>()
                            list.add("Todos" to filteredEvents.size)
                            if (soccerEvents.isNotEmpty()) {
                                list.add("Futebol" to soccerEvents.size)
                            }
                            // Todas as modalidades específicas primeiro
                            val specificSports = sportsGroups.filterKeys { !it.contains("Outros", ignoreCase = true) }
                            specificSports.forEach { (cat, evs) ->
                                list.add(cat to evs.size)
                            }
                            // "Outros desportos" SEMPRE EM ÚLTIMO
                            val otherSports = sportsGroups.filterKeys { it.contains("Outros", ignoreCase = true) }
                            otherSports.forEach { (cat, evs) ->
                                list.add(cat to evs.size)
                            }
                            list
                        }

                        val currentMainFilter = if (availableSubFilters.any { it.first == selectedLeagueFilter }) selectedLeagueFilter else "Todos"
                        var leagueDropdownExpanded by remember { mutableStateOf(false) }

                        if (isLoadingEvents && filteredEvents.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = RedPrimary)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("A procurar jogos em direto...", color = TextSecondary, fontSize = 13.sp)
                                }
                            }
                        } else if (filteredEvents.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                                ) {
                                    Text(
                                        "Sem transmissões ao vivo agendadas no momento. Veja os canais de desporto 24/7 no separador ao lado.",
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(18.dp)
                                    )
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Barra de Subseparadores: Todos, Futebol, Motores, ..., Outros Desportos (último)
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(availableSubFilters, key = { idx, item -> "sub_${item.first}_$idx" }) { _, (filterName, count) ->
                                        val isSelected = currentMainFilter == filterName
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                selectedLeagueFilter = filterName
                                                selectedFootballLeague = "Todas"
                                                leagueDropdownExpanded = false
                                            },
                                            label = {
                                                val labelText = when (filterName) {
                                                    "Todos" -> "🔥 Todos ($count)"
                                                    "Futebol" -> "⚽ Futebol ($count)"
                                                    else -> "$filterName ($count)"
                                                }
                                                Text(
                                                    text = labelText,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color(0xFF38BDF8),
                                                selectedLabelColor = Color.Black,
                                                containerColor = SurfaceDark,
                                                labelColor = TextSecondary
                                            ),
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = isSelected,
                                                borderColor = if (isSelected) Color(0xFF38BDF8) else BorderDark,
                                                selectedBorderColor = Color(0xFF38BDF8)
                                            )
                                        )
                                    }
                                }

                                // Dropdown de seleção de Ligas dentro do Futebol (predefinido fechado)
                                if (currentMainFilter == "Futebol" && footballLeagueGroups.isNotEmpty()) {
                                    val availableFootballLeagues = remember(footballLeagueGroups, soccerEvents.size) {
                                        listOf("Todas" to soccerEvents.size) + footballLeagueGroups.map { it.key to it.value.size }
                                    }
                                    val currentLeagueLabel = if (selectedFootballLeague == "Todas") {
                                        "🏆 Todas as Ligas (${soccerEvents.size} jogos)"
                                    } else {
                                        val count = footballLeagueGroups[selectedFootballLeague]?.size ?: 0
                                        "$selectedFootballLeague ($count jogos)"
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        var isLeagueCardFocused by remember { mutableStateOf(false) }
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { isLeagueCardFocused = it.isFocused }
                                                .focusable()
                                                .clickable { leagueDropdownExpanded = !leagueDropdownExpanded },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = if (isLeagueCardFocused) SurfaceVariantDark else SurfaceDark),
                                            border = BorderStroke(if (isLeagueCardFocused) 2.dp else 1.dp, if (isLeagueCardFocused || leagueDropdownExpanded) Color(0xFF38BDF8) else BorderDark)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    modifier = Modifier.weight(1f),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Menu,
                                                        contentDescription = "Ligas",
                                                        tint = Color(0xFF38BDF8),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = currentLeagueLabel,
                                                        color = TextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Icon(
                                                    imageVector = if (leagueDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = "Expandir Ligas",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = leagueDropdownExpanded,
                                            onDismissRequest = { leagueDropdownExpanded = false },
                                            modifier = Modifier
                                                .background(SurfaceDark)
                                                .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                                        ) {
                                            availableFootballLeagues.forEach { (lgName, lgCount) ->
                                                val isCurrent = selectedFootballLeague == lgName
                                                var isItemFocused by remember { mutableStateOf(false) }
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = if (lgName == "Todas") "Todas as Ligas" else lgName,
                                                                color = if (isItemFocused) Color.White else if (isCurrent) Color(0xFF38BDF8) else TextPrimary,
                                                                fontSize = 13.sp,
                                                                fontWeight = if (isCurrent || isItemFocused) FontWeight.Bold else FontWeight.Normal
                                                            )
                                                            Spacer(modifier = Modifier.width(16.dp))
                                                            Text(
                                                                text = "$lgCount jogos",
                                                                color = if (isItemFocused) Color.White.copy(alpha = 0.8f) else TextSecondary,
                                                                fontSize = 11.sp
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        selectedFootballLeague = lgName
                                                        leagueDropdownExpanded = false
                                                    },
                                                    modifier = Modifier
                                                        .onFocusChanged { isItemFocused = it.isFocused }
                                                        .focusable()
                                                        .background(if (isItemFocused) Color(0xFF1E293B) else if (isCurrent) SurfaceVariantDark else Color.Transparent)
                                                )
                                            }
                                        }
                                    }
                                }

                                val onPlayEvent: (LiveEvent, List<Channel>) -> Unit = { event, matchedChannels ->
                                    // 1. Procurar stream TimStreams direta do próprio jogo (feed direto 1080p do evento)
                                    val timstStream = event.streams.firstOrNull {
                                        it.url.contains("exmxbxe") || it.url.contains("timst") || it.url.contains("grandemx") ||
                                        (!it.url.contains("ntv.st") && !it.url.contains("embed.st") && !it.url.contains("strmfree") && !it.url.contains("ppv.st"))
                                    }

                                    // 2. Procurar canal PT correspondente (preferência Sport TV 1..6, depois restantes)
                                    val sportTvChannel = matchedChannels.firstOrNull { it.isPortuguese && it.name.contains("Sport TV", ignoreCase = true) }
                                    val anyPtChannel = sportTvChannel ?: matchedChannels.firstOrNull { it.isPortuguese }

                                    // Link TimStreams do canal PT caso exista
                                    val channelTimstUrl = anyPtChannel?.let { ch ->
                                        when {
                                            ch.backupStreamUrl?.let { it.contains("exmxbxe") || it.contains("timst") || it.contains("grandemx") } == true -> ch.backupStreamUrl
                                            ch.backupStreamUrl2?.let { it.contains("exmxbxe") || it.contains("timst") || it.contains("grandemx") } == true -> ch.backupStreamUrl2
                                            else -> null
                                        }
                                    }

                                    if (timstStream != null) {
                                        // Prioridade 1: Stream TimStreams do próprio jogo
                                        PlayerActivity.closeActivePip()
                                        val streamUrl = timstStream.url
                                        val otherStreams = event.streams.filter { it.url != streamUrl }
                                        val backupUrl = otherStreams.firstOrNull()?.url ?: channelTimstUrl ?: anyPtChannel?.backupStreamUrl
                                        val backupUrl2 = if (otherStreams.size > 1) otherStreams[1].url else anyPtChannel?.backupStreamUrl ?: anyPtChannel?.backupStreamUrl2
                                        val intent = Intent(context, PlayerActivity::class.java).apply {
                                            putExtra("EXTRA_CHANNEL_ID", "event_${event.id}")
                                            putExtra("EXTRA_CHANNEL_NAME", SportsMatchHelper.translateToPt(event.name))
                                            putExtra("EXTRA_DIRECT_STREAM_URL", streamUrl)
                                            if (backupUrl != null) putExtra("EXTRA_BACKUP_STREAM_URL", backupUrl)
                                            if (backupUrl2 != null) putExtra("EXTRA_BACKUP_STREAM_URL2", backupUrl2)
                                        }
                                        context.startActivity(intent)
                                    } else if (channelTimstUrl != null) {
                                        // Prioridade 2: Canal PT com transmissão TimStreams
                                        onChannelClick(anyPtChannel, channelTimstUrl)
                                    } else if (anyPtChannel != null) {
                                        // Prioridade 3: Canal PT oficial (DaddyLive / outros)
                                        onChannelClick(anyPtChannel, null)
                                    } else {
                                        // Prioridade 4: Outras streams do evento (NTV, Streamed, PPV)
                                        val ptStream = event.streams.firstOrNull {
                                            it.name.contains("Sport TV", ignoreCase = true) ||
                                            it.name.contains("DAZN PT", ignoreCase = true) ||
                                            it.name.contains("PT", ignoreCase = true) ||
                                            it.name.contains("Português", ignoreCase = true) ||
                                            it.name.contains("Portuguese", ignoreCase = true)
                                        }
                                        val streamUrl = ptStream?.url ?: event.streams.firstOrNull()?.url ?: ""
                                        val otherStreams = event.streams.filter { it.url != streamUrl }
                                        val backupUrl = otherStreams.firstOrNull()?.url
                                        val backupUrl2 = if (otherStreams.size > 1) otherStreams[1].url else null
                                        PlayerActivity.closeActivePip()
                                        val intent = Intent(context, PlayerActivity::class.java).apply {
                                            putExtra("EXTRA_CHANNEL_ID", "event_${event.id}")
                                            putExtra("EXTRA_CHANNEL_NAME", SportsMatchHelper.translateToPt(event.name))
                                            putExtra("EXTRA_DIRECT_STREAM_URL", streamUrl)
                                            if (backupUrl != null) putExtra("EXTRA_BACKUP_STREAM_URL", backupUrl)
                                            if (backupUrl2 != null) putExtra("EXTRA_BACKUP_STREAM_URL2", backupUrl2)
                                        }
                                        context.startActivity(intent)
                                    }
                                }

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (currentMainFilter == "Futebol") {
                                        // Dentro de Futebol: Sub-secções para cada Liga
                                        if (selectedFootballLeague == "Todas") {
                                            footballLeagueGroups.forEach { (leagueName, eventsInLeague) ->
                                                item(key = "hdr_fb_${leagueName.hashCode()}") {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = leagueName,
                                                            color = TextPrimary,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = "${eventsInLeague.size} jogos",
                                                            color = TextSecondary,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }

                                                itemsIndexed(eventsInLeague, key = { index, event -> "ev_fb_${leagueName.hashCode()}_${event.id}_$index" }) { _, event ->
                                                    val matchedChannels = remember(event.id) {
                                                        SportsMatchHelper.findBroadcastingChannels(event, allCachedChannels, repository.epgRepository)
                                                    }
                                                    LiveEventCard(
                                                        event = event,
                                                        broadcastingChannels = matchedChannels,
                                                        onPlayClick = { onPlayEvent(event, matchedChannels) },
                                                        onChannelClick = { ch -> onChannelClick(ch, null) }
                                                    )
                                                }
                                            }
                                        } else {
                                            val eventsInLeague = footballLeagueGroups[selectedFootballLeague] ?: emptyList()
                                            item(key = "hdr_fb_single_${selectedFootballLeague.hashCode()}") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = selectedFootballLeague,
                                                        color = TextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${eventsInLeague.size} jogos",
                                                        color = TextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            itemsIndexed(eventsInLeague, key = { index, event -> "ev_fb_s_${selectedFootballLeague.hashCode()}_${event.id}_$index" }) { _, event ->
                                                val matchedChannels = remember(event.id) {
                                                    SportsMatchHelper.findBroadcastingChannels(event, allCachedChannels, repository.epgRepository)
                                                }
                                                LiveEventCard(
                                                    event = event,
                                                    broadcastingChannels = matchedChannels,
                                                    onPlayClick = { onPlayEvent(event, matchedChannels) },
                                                    onChannelClick = { ch -> onChannelClick(ch, null) }
                                                )
                                            }
                                        }
                                    } else if (currentMainFilter == "Todos") {
                                        // Todos: Ligas de Futebol primeiro em sub-secções, seguidas de outras modalidades (Outros Desportos por último)
                                        footballLeagueGroups.forEach { (leagueName, eventsInLeague) ->
                                            item(key = "hdr_all_fb_${leagueName.hashCode()}") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = leagueName,
                                                        color = TextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${eventsInLeague.size} jogos",
                                                        color = TextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            itemsIndexed(eventsInLeague, key = { index, event -> "ev_all_fb_${leagueName.hashCode()}_${event.id}_$index" }) { _, event ->
                                                val matchedChannels = remember(event.id) {
                                                    SportsMatchHelper.findBroadcastingChannels(event, allCachedChannels, repository.epgRepository)
                                                }
                                                LiveEventCard(
                                                    event = event,
                                                    broadcastingChannels = matchedChannels,
                                                    onPlayClick = { onPlayEvent(event, matchedChannels) },
                                                    onChannelClick = { ch -> onChannelClick(ch, null) }
                                                )
                                            }
                                        }

                                        // Modalidades específicas (Motores, Basquete, Ténis, Combate)
                                        val specificSports = sportsGroups.filterKeys { !it.contains("Outros", ignoreCase = true) }
                                        specificSports.forEach { (sportName, eventsInSport) ->
                                            item(key = "hdr_all_sp_${sportName.hashCode()}") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = sportName,
                                                        color = TextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${eventsInSport.size} eventos",
                                                        color = TextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            itemsIndexed(eventsInSport, key = { index, event -> "ev_all_sp_${sportName.hashCode()}_${event.id}_$index" }) { _, event ->
                                                val matchedChannels = remember(event.id) {
                                                    SportsMatchHelper.findBroadcastingChannels(event, allCachedChannels, repository.epgRepository)
                                                }
                                                LiveEventCard(
                                                    event = event,
                                                    broadcastingChannels = matchedChannels,
                                                    onPlayClick = { onPlayEvent(event, matchedChannels) },
                                                    onChannelClick = { ch -> onChannelClick(ch, null) }
                                                )
                                            }
                                        }

                                        // Outros Desportos garantidamente no fim
                                        val otherSports = sportsGroups.filterKeys { it.contains("Outros", ignoreCase = true) }
                                        otherSports.forEach { (sportName, eventsInSport) ->
                                            item(key = "hdr_all_sp_other_${sportName.hashCode()}") {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = sportName,
                                                        color = TextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${eventsInSport.size} eventos",
                                                        color = TextSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }

                                            itemsIndexed(eventsInSport, key = { index, event -> "ev_all_sp_other_${sportName.hashCode()}_${event.id}_$index" }) { _, event ->
                                                val matchedChannels = remember(event.id) {
                                                    SportsMatchHelper.findBroadcastingChannels(event, allCachedChannels, repository.epgRepository)
                                                }
                                                LiveEventCard(
                                                    event = event,
                                                    broadcastingChannels = matchedChannels,
                                                    onPlayClick = { onPlayEvent(event, matchedChannels) },
                                                    onChannelClick = { ch -> onChannelClick(ch, null) }
                                                )
                                            }
                                        }
                                    } else {
                                        // Modalidade desportiva específica selecionada
                                        val eventsInSport = sportsGroups[currentMainFilter] ?: emptyList()
                                        item(key = "hdr_sp_single_${currentMainFilter.hashCode()}") {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = currentMainFilter,
                                                    color = TextPrimary,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "${eventsInSport.size} eventos",
                                                    color = TextSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        itemsIndexed(eventsInSport, key = { index, event -> "ev_sp_s_${currentMainFilter.hashCode()}_${event.id}_$index" }) { _, event ->
                                            val matchedChannels = remember(event.id) {
                                                SportsMatchHelper.findBroadcastingChannels(event, allCachedChannels, repository.epgRepository)
                                            }
                                            LiveEventCard(
                                                event = event,
                                                broadcastingChannels = matchedChannels,
                                                onPlayClick = { onPlayEvent(event, matchedChannels) },
                                                onChannelClick = { ch -> onChannelClick(ch, null) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Regular Channels List (Portugal & Todos)
                if (channels.isEmpty()) {
                    EmptyStateView(tab = selectedTab, query = searchQuery)
                } else if (isGridLayout) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (isTabletOrLandscape) 200.dp else 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (searchQuery.isBlank() && selectedCategory == "Todos" && selectedTab == TabFilter.PORTUGAL) {
                            if (favChannels.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    FavoritesQuickBar(
                                        favorites = favChannels,
                                        onChannelClick = { onChannelClick(it, null) }
                                    )
                                }
                            }
                        }
                        itemsIndexed(channels, key = { index, channel -> "ch_grid_${channel.id}_$index" }) { _, channel ->
                            ChannelGridCard(
                                channel = channel,
                                epgProgram = repository.epgRepository.getCurrentProgram(channel.name),
                                onPlayClick = {
                                    onChannelClick(channel, null)
                                },
                                onShowDetails = {
                                    selectedChannelForSheet = channel
                                },
                                onToggleFavorite = {
                                    repository.toggleFavorite(channel.id)
                                    refreshKey++
                                }
                            )
                        }
                    }
                } else if (isSplitLayout) {
                    val activeChannel = focusedOrSelectedChannel ?: channels.firstOrNull()
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Coluna da Esquerda: Lista de Canais (40% da largura)
                        Box(modifier = Modifier.weight(0.40f).fillMaxHeight()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (searchQuery.isBlank() && selectedCategory == "Todos" && selectedTab == TabFilter.PORTUGAL) {
                                    if (favChannels.isNotEmpty()) {
                                        item {
                                            FavoritesQuickBar(
                                                favorites = favChannels,
                                                onChannelClick = { onChannelClick(it, null) }
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                    }
                                }
                                itemsIndexed(channels, key = { index, channel -> "ch_split_${channel.id}_$index" }) { _, channel ->
                                    val isSelected = activeChannel?.id == channel.id
                                    SplitChannelRow(
                                        channel = channel,
                                        isSelected = isSelected,
                                        epgProgram = repository.epgRepository.getCurrentProgram(channel.name),
                                        onFocus = { focusedOrSelectedChannel = channel },
                                        onClick = {
                                            focusedOrSelectedChannel = channel
                                            onChannelClick(channel, null)
                                        },
                                        onPlayDirect = {
                                            onChannelClick(channel, null)
                                        }
                                    )
                                }
                            }
                        }

                        // Coluna da Direita: Painel EPG Detalhado (60% da largura)
                        Box(modifier = Modifier.weight(0.60f).fillMaxHeight()) {
                            if (activeChannel != null) {
                                EpgDetailPanel(
                                    channel = activeChannel,
                                    epgRepository = repository.epgRepository,
                                    onPlayClick = { directUrl -> onChannelClick(activeChannel, directUrl) },
                                    onToggleFavorite = {
                                        repository.toggleFavorite(activeChannel.id)
                                        refreshKey++
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (searchQuery.isBlank() && selectedCategory == "Todos" && selectedTab == TabFilter.PORTUGAL) {
                            if (favChannels.isNotEmpty()) {
                                item {
                                    FavoritesQuickBar(
                                        favorites = favChannels,
                                        onChannelClick = { onChannelClick(it, null) }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                        itemsIndexed(channels, key = { index, channel -> "ch_${channel.id}_$index" }) { _, channel ->
                            ChannelCard(
                                channel = channel,
                                epgProgram = repository.epgRepository.getCurrentProgram(channel.name),
                                onPlayClick = {
                                    onChannelClick(channel, null)
                                },
                                onShowDetails = {
                                    selectedChannelForSheet = channel
                                },
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

    if (selectedChannelForSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedChannelForSheet = null },
            containerColor = LocalCustomColors.current.surface,
            scrimColor = Color.Black.copy(alpha = 0.65f),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF384055))
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                EpgDetailPanel(
                    channel = selectedChannelForSheet!!,
                    epgRepository = repository.epgRepository,
                    onPlayClick = { directUrl ->
                        val ch = selectedChannelForSheet!!
                        selectedChannelForSheet = null
                        onChannelClick(ch, directUrl)
                    },
                    onToggleFavorite = {
                        repository.toggleFavorite(selectedChannelForSheet!!.id)
                        refreshKey++
                    }
                )
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
            },
            onThemeChange = onThemeChange
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
    broadcastingChannels: List<Channel> = emptyList(),
    onPlayClick: () -> Unit,
    onChannelClick: ((Channel) -> Unit)? = null
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        val displayName = remember(event.name) { SportsMatchHelper.translateToPt(event.name) }
                        Text(
                            text = displayName,
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
                                TagBadge(text = event.time, color = if (event.time.startsWith("🔴")) RedPrimary else TextSecondary)
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

                Spacer(modifier = Modifier.width(8.dp))

                // Watch Button
                Button(
                    onClick = onPlayClick,
                    modifier = Modifier.focusable(false),
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

            // StreamFC: Mapeamento de Canais de TV Oficiais
            if (broadcastingChannels.isNotEmpty() && onChannelClick != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(BorderDark.copy(alpha = 0.6f))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "📺 Na TV:",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    broadcastingChannels.forEachIndexed { bIdx, channel ->
                        TvChannelPill(
                            channel = channel,
                            onClick = { onChannelClick(channel) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TvChannelPill(
    channel: Channel,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) Color(0xFF38BDF8) else if (channel.isPortuguese) Color(0x33E50914) else SurfaceVariantDark)
            .border(1.dp, if (isFocused) Color.White else if (channel.isPortuguese) RedPrimary.copy(alpha = 0.6f) else BorderDark, RoundedCornerShape(6.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val logo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = channel.name,
                    modifier = Modifier.size(16.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = if (channel.isPortuguese) "🇵🇹" else "📺",
                    fontSize = 11.sp
                )
            }
            Text(
                text = channel.name,
                color = if (isFocused) Color.Black else TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun FavoritesQuickBar(
    favorites: List<Channel>,
    onChannelClick: (Channel) -> Unit,
    modifier: Modifier = Modifier
) {
    if (favorites.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = AccentGold,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Os Meus Favoritos",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "${favorites.size} canais",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
        ) {
            itemsIndexed(favorites, key = { index, channel -> "fav_bar_${channel.id}_$index" }) { _, channel ->
                FavoriteQuickCard(
                    channel = channel,
                    onClick = { onChannelClick(channel) }
                )
            }
        }
    }
}

@Composable
fun FavoriteQuickCard(
    channel: Channel,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
    val onlineLogo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)

    Card(
        modifier = Modifier
            .width(112.dp)
            .height(76.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) Color(0xFF232838) else Color(0xFF161A26)
        ),
        border = if (isFocused) BorderStroke(2.dp, Color(0xFF38BDF8)) else BorderStroke(1.dp, Color(0xFF262C3D))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F121C)),
                contentAlignment = Alignment.Center
            ) {
                if (onlineLogo != null) {
                    AsyncImage(
                        model = onlineLogo,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        contentScale = ContentScale.Fit
                    )
                } else if (localLogo != null) {
                    Image(
                        painter = painterResource(id = localLogo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = channel.name.take(3).uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = channel.name,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FeaturedLiveCard(
    channel: Channel,
    epgProgram: EpgProgram?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) Color(0xFF232838) else Color(0xFF161A26)
        ),
        border = if (isFocused) BorderStroke(2.dp, Color(0xFF38BDF8)) else BorderStroke(1.dp, Color(0xFF262C3D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
            val onlineLogo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)

            Box(
                modifier = Modifier
                    .size(width = 74.dp, height = 54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F121C)),
                contentAlignment = Alignment.Center
            ) {
                if (onlineLogo != null) {
                    AsyncImage(
                        model = onlineLogo,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                } else if (localLogo != null) {
                    Image(
                        painter = painterResource(id = localLogo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = channel.name.take(3).uppercase(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFDC2626))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "DIRETO",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = channel.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                val programTitle = epgProgram?.title ?: "Emissão em Direto"
                Text(
                    text = programTitle,
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                val progress = epgProgram?.getProgress() ?: 0.40f
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF2D3243))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Color(0xFF84CC16))
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                val timeStr = epgProgram?.timeRange ?: "Emissão em Direto"
                Text(
                    text = timeStr,
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp
                )
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Assistir",
                tint = Color(0xFF38BDF8),
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x2238BDF8))
                    .padding(8.dp)
            )
        }
    }
}

@Composable
fun SplitChannelRow(
    channel: Channel,
    isSelected: Boolean,
    epgProgram: EpgProgram?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit
) {
    val theme = LocalCustomColors.current
    var isFocused by remember { mutableStateOf(false) }
    val isHighlighted = isFocused || isSelected

    val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
    val onlineLogo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) theme.surfaceVariant else theme.surface
        ),
        border = if (isFocused) {
            BorderStroke(2.dp, theme.primary)
        } else if (isSelected) {
            BorderStroke(1.5.dp, theme.primary.copy(alpha = 0.7f))
        } else {
            BorderStroke(1.dp, theme.border)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Channel Logo
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (onlineLogo != null) {
                    AsyncImage(
                        model = onlineLogo,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit
                    )
                } else if (localLogo != null) {
                    Image(
                        painter = painterResource(id = localLogo),
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = channel.name.take(3).uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    color = if (isHighlighted) Color.White else theme.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val progText = epgProgram?.title ?: channel.category
                Text(
                    text = progText,
                    color = if (epgProgram != null) theme.primary else theme.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (epgProgram != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    val progress = epgProgram.getProgress()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF2D3243))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(Color(0xFF84CC16))
                        )
                    }
                }
            }

            if (channel.isFavorite) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onPlayDirect,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Assistir",
                    tint = theme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ChannelCard(
    channel: Channel,
    epgProgram: EpgProgram? = null,
    onPlayClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHideChannel: () -> Unit,
    onShowDetails: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1.0f, label = "channel_scale")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onPlayClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) Color(0xFF222634) else Color(0xFF151824)
        ),
        border = if (isFocused) {
            BorderStroke(2.dp, Color(0xFF38BDF8))
        } else {
            BorderStroke(1.dp, Color(0xFF222636))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Channel Logo
                val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
                val onlineLogo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)

                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E2230)),
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
                                .padding(4.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        val initials = channel.name.replace("(", "").replace(")", "").split(" ")
                            .filter { it.isNotBlank() }.take(2).map { it.first().uppercase() }.joinToString("")
                        Text(
                            text = initials.ifBlank { channel.id.take(3) },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 1. Channel Name in highlight (EM DESTAQUE)
                    Text(
                        text = channel.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // 2. Program Title from EPG
                    val programTitle = epgProgram?.title ?: "Emissão em Direto"
                    Text(
                        text = programTitle,
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 3. Progress bar
                    val progress = epgProgram?.getProgress() ?: 0.35f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF2D3243))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(Color(0xFF84CC16))
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // 4. Time range
                    val timeRange = epgProgram?.timeRange ?: "Em Direto"
                    Text(
                        text = timeRange,
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Favorite Button
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (channel.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favorito",
                        tint = if (channel.isFavorite) AccentGold else Color(0xFF6B7280),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Options Menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Mais opções",
                            tint = Color(0xFF6B7280),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        if (onShowDetails != null) {
                            DropdownMenuItem(
                                text = { Text("Guia TV / Detalhes (EPG)", color = TextPrimary, fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF38BDF8))
                                },
                                onClick = {
                                    showMenu = false
                                    onShowDetails()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Ocultar canal", color = TextPrimary, fontSize = 13.sp) },
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
fun DigitalClock(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Europe/Lisbon")
        }
        while (true) {
            currentTime = sdf.format(Date())
            kotlinx.coroutines.delay(1000L)
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0x331E2230),
        border = BorderStroke(1.dp, Color(0xFF2D3748))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🕒 $currentTime",
                color = Color(0xFF38BDF8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ChannelGridCard(
    channel: Channel,
    epgProgram: EpgProgram? = null,
    onPlayClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowDetails: (() -> Unit)? = null
) {
    val theme = LocalCustomColors.current
    var isFocused by remember { mutableStateOf(false) }
    val localLogo = ChannelLogoHelper.getLocalLogoRes(channel.name)
    val onlineLogo = channel.logoUrl ?: ChannelLogoHelper.getLogoUrl(channel.name)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onPlayClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) theme.surfaceVariant else theme.surface
        ),
        border = if (isFocused) {
            BorderStroke(2.dp, theme.primary)
        } else {
            BorderStroke(1.dp, theme.border)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (onlineLogo != null) {
                        AsyncImage(
                            model = onlineLogo,
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else if (localLogo != null) {
                        Image(
                            painter = painterResource(id = localLogo),
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = channel.name.take(3).uppercase(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channel.isFavorite) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorito",
                            tint = AccentGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (onShowDetails != null) {
                        IconButton(
                            onClick = onShowDetails,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Detalhes",
                                tint = theme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = channel.name,
                color = if (isFocused) Color.White else theme.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            val progTitle = epgProgram?.title ?: channel.category
            Text(
                text = progTitle,
                color = if (epgProgram != null) theme.primary else theme.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (epgProgram != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val progress = epgProgram.getProgress()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF2D3243))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Color(0xFF84CC16))
                    )
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
                    tab == TabFilter.GAMING -> "Nenhum canal gaming disponível"
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
    onUpdateFound: (ReleaseInfo) -> Unit,
    onThemeChange: (String, String) -> Unit = { _, _ -> }
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var autoUnmute by remember { mutableStateOf(repository.isAutoUnmuteEnabled()) }
    var autoResume by remember { mutableStateOf(repository.isAutoResumeEnabled()) }
    var baseUrl by remember { mutableStateOf(repository.getBaseUrl()) }
    var timstBaseUrl by remember { mutableStateOf(repository.getTimstBaseUrl()) }
    var themeMode by remember { mutableStateOf(repository.getThemeMode()) }
    var accentColor by remember { mutableStateOf(repository.getAccentColor()) }
    var defaultTab by remember { mutableStateOf(repository.getDefaultTab()) }
    var channelViewMode by remember { mutableStateOf(repository.getChannelViewMode()) }
    var showClockSetting by remember { mutableStateOf(repository.isShowClockEnabled()) }
    var autoPip by remember { mutableStateOf(repository.isAutoPipOnBack()) }

    var isSyncingChannels by remember { mutableStateOf(false) }
    var syncChannelsMsg by remember { mutableStateOf<String?>(null) }

    var isSyncingEpg by remember { mutableStateOf(false) }
    var syncEpgMsg by remember { mutableStateOf<String?>(null) }

    var checkOnStart by remember { mutableStateOf(updateManager.isCheckOnStartEnabled()) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }

    val epgProgramsCount = repository.epgRepository.getProgramsCount()
    val hiddenCount = repository.getHiddenChannelIds().size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF38BDF8))
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
                        .background(Color(0x3338BDF8))
                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "v${updateManager.getCurrentVersionName()}",
                        color = Color(0xFF38BDF8),
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
                // SEÇÃO 0: TEMA & PERSONALIZAÇÃO
                Text("🎨 TEMA & APARÊNCIA", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Preto Puro OLED (#000000)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Fundo 100% preto para poupança de bateria e contraste na TV.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = themeMode == "OLED",
                        onCheckedChange = { isOled ->
                            themeMode = if (isOled) "OLED" else "DARK"
                            repository.setThemeMode(themeMode)
                            onThemeChange(themeMode, accentColor)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF38BDF8))
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Cor de Destaque:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val colorChoices = listOf(
                            Triple("RED", "Vermelho", Color(0xFFE50914)),
                            Triple("CYAN", "Ciano", Color(0xFF00B4D8)),
                            Triple("PURPLE", "Roxo", Color(0xFFA855F7)),
                            Triple("GREEN", "Verde", Color(0xFF10B981))
                        )
                        colorChoices.forEach { (code, label, cVal) ->
                            val isSelected = accentColor.equals(code, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) cVal else cVal.copy(alpha = 0.2f))
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color.White else cVal.copy(alpha = 0.5f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        accentColor = code
                                        repository.setAccentColor(code)
                                        onThemeChange(themeMode, accentColor)
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Modo de Visualização dos Canais
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Modo de Visualização dos Canais:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    val viewModes = listOf(
                        Pair("AUTO", "Automático"),
                        Pair("SPLIT_LIST", "Lista com EPG"),
                        Pair("GRID", "Grelha")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        viewModes.forEach { (code, label) ->
                            val isSelected = channelViewMode.equals(code, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF2563EB) else SurfaceVariantDark)
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color(0xFF38BDF8) else BorderDark,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        channelViewMode = code
                                        repository.setChannelViewMode(code)
                                        onChannelsSynced()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Relógio Digital no Ecrã
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Relógio no Ecrã (Hora Lisboa)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Exibe a hora atual de Lisboa/Londres na barra superior.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = showClockSetting,
                        onCheckedChange = { enabled ->
                            showClockSetting = enabled
                            repository.setShowClockEnabled(enabled)
                            onChannelsSynced()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF38BDF8))
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // SEÇÃO: INICIALIZAÇÃO
                Text("🚀 INICIALIZAÇÃO DA APLICAÇÃO", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Separador Inicial ao Abrir:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    val tabs = listOf(
                        Pair("PORTUGAL", "Portugal"),
                        Pair("LIVE_GAMES", "Jogos"),
                        Pair("FAVORITES", "Favoritos"),
                        Pair("ALL", "Todos")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tabs.forEach { (code, label) ->
                            val isSelected = defaultTab.equals(code, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF2563EB) else SurfaceVariantDark)
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color(0xFF38BDF8) else BorderDark,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        defaultTab = code
                                        repository.setDefaultTab(code)
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // SEÇÃO 1: REPRODUÇÃO & PLAYER
                Text("📺 REPRODUÇÃO & PLAYER", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Ativar Som Automaticamente", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Desmuta a transmissão assim que o vídeo inicia.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoUnmute,
                        onCheckedChange = {
                            autoUnmute = it
                            repository.setAutoUnmuteEnabled(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF38BDF8))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Abrir Último Canal ao Iniciar", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Inicia diretamente no último canal reproduzido.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoResume,
                        onCheckedChange = {
                            autoResume = it
                            repository.setAutoResumeEnabled(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF38BDF8))
                    )
                }

                if (!repository.isTv()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Modo PiP ao Premir Voltar", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Minimiza para janela flutuante em vez de sair.", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoPip,
                            onCheckedChange = { enabled ->
                                autoPip = enabled
                                repository.setAutoPipOnBack(enabled)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF38BDF8))
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📺 Comando TV: O botão Voltar sai do reprodutor e regressa aos canais.",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // SEÇÃO 2: GUIA DE PROGRAMAÇÃO (EPG)
                Text("📅 GUIA DE PROGRAMAÇÃO (EPG)", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Fonte: JohnPulse iptv-epg Strong8K\nProgramas em cache: $epgProgramsCount",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        isSyncingEpg = true
                        syncEpgMsg = null
                        repository.epgRepository.syncEpgFromWeb(scope) { success, msg ->
                            isSyncingEpg = false
                            syncEpgMsg = msg
                        }
                    },
                    enabled = !isSyncingEpg,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isSyncingEpg) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("A atualizar Guia TV...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔄 Atualizar Guia TV (EPG) Agora", fontSize = 13.sp)
                    }
                }
                syncEpgMsg?.let {
                    Text(it, color = AccentGreen, fontSize = 12.sp)
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // SEÇÃO 3: SERVIDORES & REDE
                Text("📡 SERVIDORES & REDE", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Domínio DaddyLive:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = {
                            baseUrl = "https://dlive.sx"
                            repository.setBaseUrl(baseUrl)
                        }) {
                            Text("Repor dlive.sx", fontSize = 11.sp, color = Color(0xFF38BDF8))
                        }
                    }
                    TextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            repository.setBaseUrl(it)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.DirectionDown) {
                                        focusManager.moveFocus(FocusDirection.Down)
                                        true
                                    } else if (event.key == Key.DirectionUp) {
                                        focusManager.moveFocus(FocusDirection.Up)
                                        true
                                    } else false
                                } else false
                            },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Domínio TimStreams:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = {
                            timstBaseUrl = "https://grandemx.org"
                            repository.setTimstBaseUrl(timstBaseUrl)
                        }) {
                            Text("Repor grandemx.org", fontSize = 11.sp, color = Color(0xFF38BDF8))
                        }
                    }
                    TextField(
                        value = timstBaseUrl,
                        onValueChange = {
                            timstBaseUrl = it
                            repository.setTimstBaseUrl(it)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    if (event.key == Key.DirectionDown) {
                                        focusManager.moveFocus(FocusDirection.Down)
                                        true
                                    } else if (event.key == Key.DirectionUp) {
                                        focusManager.moveFocus(FocusDirection.Up)
                                        true
                                    } else false
                                } else false
                            },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Button(
                    onClick = {
                        isSyncingChannels = true
                        syncChannelsMsg = null
                        repository.syncChannelsFromWeb(scope) { success ->
                            isSyncingChannels = false
                            syncChannelsMsg = if (success) "✅ Lista de canais atualizada!" else "⚠️ Falha ao contactar servidor."
                            onChannelsSynced()
                        }
                    },
                    enabled = !isSyncingChannels,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isSyncingChannels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("A sincronizar...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sincronizar Canais Online", fontSize = 13.sp)
                    }
                }
                syncChannelsMsg?.let {
                    Text(it, color = AccentGreen, fontSize = 12.sp)
                }

                // SEÇÃO 4: GESTÃO DE CANAIS
                if (hiddenCount > 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))
                    Text("📋 GESTÃO DE CANAIS", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            repository.unhideAllChannels()
                            onChannelsSynced()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4B5563)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restaurar Canais Ocultos ($hiddenCount)", fontSize = 13.sp)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                // SEÇÃO 5: ATUALIZAÇÕES (OTA)
                Text("ℹ️ SOBRE & ATUALIZAÇÕES", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Verificar atualizações ao iniciar", color = TextPrimary, fontSize = 13.sp)
                    Switch(
                        checked = checkOnStart,
                        onCheckedChange = {
                            checkOnStart = it
                            updateManager.setCheckOnStartEnabled(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF38BDF8))
                    )
                }
                Button(
                    onClick = {
                        isCheckingUpdate = true
                        updateStatusMessage = null
                        scope.launch {
                            val res = updateManager.checkForUpdate()
                            isCheckingUpdate = false
                            val rel = res.getOrNull()
                            if (rel != null) {
                                onUpdateFound(rel)
                            } else {
                                updateStatusMessage = "A aplicação já está na versão mais recente!"
                            }
                        }
                    },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("A procurar atualizações...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Procurar Atualizações Agora", fontSize = 13.sp)
                    }
                }
                updateStatusMessage?.let {
                    Text(it, color = Color(0xFF34D399), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
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
                    itemsIndexed(hiddenList, key = { index, ch -> "hidden_${ch.id}_$index" }) { _, ch ->
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
