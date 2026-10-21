#!/usr/bin/env python3
"""
tests/adversarial_challenge_m3_c1.py - Challenger 1 Adversarial Harness for Milestone M3
Author: Challenger M3 (Instance 1)

Adversarial Stress Scenarios:
1. HomeScreen D-Pad Navigation & Focus Stability:
   - Category filter pill focus border high contrast (#38BDF8)
   - Pill clickability and DropdownMenu item focus traversal
   - Verification that TV box suppresses search bar when repository.isSearchEnabledOnTv() is false
2. TV IME Software Keyboard Suppression:
   - etDrawerSearch focus change listener: strict gate behind `!isTv`
   - etDrawerSearch click listener: explicit activation preserved
   - etDrawerSearch editor action: IME dismissed and focus restored to rvDrawerChannels
   - TextChangedListener: typing filter does not trigger IME loop
3. EpgDetailPanel Preview WebView Isolation & Focus Trap Prevention:
   - WebView descendantFocusability set to FOCUS_BLOCK_DESCENDANTS
   - WebView isFocusable and isFocusableInTouchMode set to false
   - Action buttons (Play, Server 1/2/3, Favorite) apply high-contrast cyan borders (#38BDF8)
   - Preview WebView lifecycle onRelease memory leak prevention
4. Failover Priority Inversion Comprehensive Matrix:
   - All 733 channels in master catalog evaluated
   - National Portuguese channels (RTP 1, RTP 2, SIC, TVI, RTP 3, Canal 11, Porto Canal, CNN PT)
   - Hybrid channels with TimStreams + Official M3U8 + DaddyLive
   - DaddyLive-only channels
   - Renderer crash recovery failover bypass
5. Dynamic Failover HUD Feedback:
   - Status text dynamic updating with '⚡ A mudar para ${next.label}...'
   - Status color set to cyan #38BDF8
   - Silent recovery HUD invocation when connecting overlay is hidden
"""

import json
import os
import re
import sys
import unittest
from typing import Dict, Any, List, Optional

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, PROJECT_ROOT)

CHANNELS_JSON = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "channels.json")
MAIN_ACTIVITY = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "MainActivity.kt")
PLAYER_ACTIVITY = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "PlayerActivity.kt")
HOME_SCREEN = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "HomeScreen.kt")
EPG_PANEL = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "EpgDetailPanel.kt")
CHANNEL_REPO = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "data", "ChannelRepository.kt")


class ServerOption:
    def __init__(self, label: str, is_direct: bool, url: Optional[str] = None, folder: Optional[str] = None):
        self.label = label
        self.is_direct = is_direct
        self.url = url
        self.folder = folder

    def __repr__(self):
        return f"ServerOption(label='{self.label}', is_direct={self.is_direct}, url='{self.url}', folder='{self.folder}')"


def simulate_is_direct_broadcast(channel: Dict[str, Any]) -> bool:
    """Exact emulation of MainActivity.kt / PlayerActivity.kt isDirectBroadcast."""
    backup_url = channel.get("backupStreamUrl")
    if not backup_url:
        return False
    lower = backup_url.lower()
    is_pt = bool(channel.get("isPt", False) or channel.get("isPortuguese", False) or channel.get("country") == "PT")
    return (
        ".m3u8" in lower or
        "rtp.pt" in lower or
        "impresa.pt" in lower or
        "iol.pt" in lower or
        "epicsports" in lower or
        "cloudfront" in lower or
        "fastly" in lower or
        is_pt
    )


def simulate_build_failover_list(
    channel: Dict[str, Any],
    direct_stream_url: Optional[str] = None,
    backup_direct_url: Optional[str] = None,
    backup_direct_url2: Optional[str] = None
) -> List[ServerOption]:
    """Exact emulation of PlayerActivity.kt:buildFailoverList logic."""
    options: List[ServerOption] = []
    ch_id = str(channel.get("id", ""))
    ch_name = channel.get("name", "")
    is_pt_channel = bool(
        channel.get("isPt", False) or
        channel.get("isPortuguese", False) or
        channel.get("country") == "PT" or
        "rtp" in ch_name.lower() or
        "sic" in ch_name.lower() or
        "tvi" in ch_name.lower()
    )

    backup_url = channel.get("backupStreamUrl") or backup_direct_url
    raw_timst = None
    for cand in [backup_url, channel.get("backupStreamUrl2"), backup_direct_url2, direct_stream_url]:
        if cand and any(k in cand for k in ["grandemx", "exmxbxe", "ehgembxe", "timst"]):
            raw_timst = cand
            break

    timst_url = None
    if raw_timst:
        u = raw_timst.strip()
        u = u.replace("https://grandemx.org", "https://ehgembxe.cfd").replace("http://grandemx.org", "https://ehgembxe.cfd")
        u = u.replace("https://exmxbxe.cfd", "https://ehgembxe.cfd").replace("http://exmxbxe.cfd", "https://ehgembxe.cfd")
        timst_url = u

    # 1. TimStreams
    if timst_url:
        options.append(ServerOption("Servidor Principal (TimStreams 1080p)", is_direct=True, url=timst_url))

    def is_official_direct_broadcast(url: Optional[str]) -> bool:
        if not url or url.strip() == "":
            return False
        if url == timst_url or any(k in url for k in ["ehgembxe", "exmxbxe", "timst", "grandemx"]):
            return False
        lower = url.lower()
        return (
            ".m3u8" in lower or
            "rtp.pt" in lower or
            "impresa.pt" in lower or
            "iol.pt" in lower or
            "cloudfront" in lower or
            "fastly" in lower or
            "canal11" in lower or
            "portocanal" in lower or
            "livextend" in lower or
            "github.com" in lower or
            (is_pt_channel and not ("dlive.sx" in lower or "epicsports" in lower))
        )

    # 2. Official Direct M3U8
    candidates = [
        channel.get("backupStreamUrl"),
        channel.get("backupStreamUrl2"),
        backup_direct_url,
        backup_direct_url2,
        direct_stream_url
    ]
    official_candidates = [u for u in candidates if is_official_direct_broadcast(u)]
    for off_url in official_candidates:
        if not any(o.url == off_url for o in options):
            label = "Servidor Principal (Transmissão Oficial M3U8)" if len(options) == 0 else f"Servidor Alternativo {len(options)} (Transmissão Oficial M3U8)"
            options.append(ServerOption(label, is_direct=True, url=off_url))

    # 3. DaddyLive Player 1 & 2
    has_daddylive = ch_id.isdigit() and int(ch_id) > 0
    if has_daddylive:
        label1 = "Servidor Principal (DaddyLive - Player 1)" if len(options) == 0 else f"Servidor Alternativo {len(options)} (DaddyLive - Player 1)"
        options.append(ServerOption(label1, is_direct=False, folder="stream"))
        label2 = f"Servidor Alternativo {len(options)} (DaddyLive - Player 2)"
        options.append(ServerOption(label2, is_direct=False, folder="player"))

    # 4. Remaining direct backups
    remaining = [
        u for u in candidates
        if u and not any(k in u for k in ["ehgembxe", "exmxbxe", "timst", "grandemx"]) and not is_official_direct_broadcast(u)
    ]
    for b_url in remaining:
        if not any(o.url == b_url for o in options):
            label = "Servidor Principal (EpicSports)" if ("epicsports" in b_url and len(options) == 0) else \
                    "Servidor Principal (Direto)" if len(options) == 0 else \
                    f"Servidor Alternativo {len(options)} (EpicSports)" if "epicsports" in b_url else \
                    f"Servidor Alternativo {len(options)} (Direto)"
            options.append(ServerOption(label, is_direct=True, url=b_url))

    return options


class TestChallengerM3Empirical(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        with open(CHANNELS_JSON, "r", encoding="utf-8") as f:
            cls.channels = json.load(f)
        cls.ch_by_id = {str(c["id"]): c for c in cls.channels}

        with open(HOME_SCREEN, "r", encoding="utf-8") as f:
            cls.home_screen_src = f.read()

        with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
            cls.player_activity_src = f.read()

        with open(EPG_PANEL, "r", encoding="utf-8") as f:
            cls.epg_panel_src = f.read()

        with open(MAIN_ACTIVITY, "r", encoding="utf-8") as f:
            cls.main_activity_src = f.read()

    # =========================================================================
    # Mission Requirement 1: HomeScreen D-Pad Navigation & Focus Stability
    # =========================================================================

    def test_c1_01_home_screen_category_pill_has_focus_tracking(self):
        """Verify HomeScreen Category pill tracks focus state and never loses focus cursor."""
        # Check variable declaration
        self.assertIn(
            "var isCategoryFocused by remember { mutableStateOf(false) }",
            self.home_screen_src,
            "HomeScreen must define isCategoryFocused mutable state"
        )
        # Check onFocusChanged modifier
        self.assertIn(
            "modifier = Modifier.onFocusChanged { isCategoryFocused = it.isFocused }",
            self.home_screen_src,
            "Category filter pill must attach onFocusChanged listener"
        )
        # Check border style when focused
        self.assertIn(
            "border = if (isCategoryFocused) {",
            self.home_screen_src,
            "Category filter pill must switch border when focused"
        )
        self.assertIn(
            "BorderStroke(2.5.dp, Color(0xFF38BDF8))",
            self.home_screen_src,
            "Category filter pill must apply 2.5dp cyan border #38BDF8 when focused"
        )

    def test_c1_02_home_screen_dropdown_menu_items_have_focus_and_contrast(self):
        """Verify DropdownMenuItems inside Category dropdown have distinct text color for active category."""
        self.assertIn(
            "color = if (selectedCategory == cat) Color(0xFF38BDF8) else TextPrimary",
            self.home_screen_src,
            "Category dropdown menu items must highlight active category with #38BDF8"
        )
        self.assertIn(
            "categoryDropdownOpen = false",
            self.home_screen_src,
            "Selecting a category must dismiss the dropdown to restore navigation"
        )

    def test_c1_03_home_screen_tv_box_suppresses_top_search_bar_by_default(self):
        """Verify on TV boxes (!isTv || isSearchEnabledOnTv), search pill is suppressed to avoid keyboard traps."""
        self.assertIn(
            "val showSearch = !isTv || isSearchEnabledOnTv",
            self.home_screen_src,
            "showSearch must be false on TV unless isSearchEnabledOnTv is explicitly toggled"
        )
        self.assertIn(
            "if (showSearch) {",
            self.home_screen_src,
            "Search pill (Filtrar por nome) must be conditionally rendered only when showSearch == true"
        )

    # =========================================================================
    # Mission Requirement 2: TV Virtual Keyboard Suppression on etDrawerSearch
    # =========================================================================

    def test_c1_04_player_activity_drawer_search_focus_gates_soft_keyboard_behind_not_is_tv(self):
        """Verify etDrawerSearch.setOnFocusChangeListener strictly blocks showSoftInput when isTv == true."""
        focus_listener_match = re.search(
            r"etDrawerSearch\.setOnFocusChangeListener\s*\{\s*[^,]+,\s*hasFocus\s*->\s*(.*?)\n\s*\}",
            self.player_activity_src,
            re.DOTALL
        )
        self.assertIsNotNone(focus_listener_match, "etDrawerSearch must have setOnFocusChangeListener")
        body = focus_listener_match.group(1)

        self.assertIn(
            "if (hasFocus && !isTv)",
            body,
            "Focus listener must enforce `if (hasFocus && !isTv)` before calling showSoftInput"
        )
        self.assertIn(
            "imm?.showSoftInput(etDrawerSearch, InputMethodManager.SHOW_IMPLICIT)",
            body
        )

    def test_c1_05_player_activity_drawer_search_click_preserves_explicit_input(self):
        """Verify etDrawerSearch.setOnClickListener exists so TV users clicking OK can still open keyboard."""
        click_listener_match = re.search(
            r"etDrawerSearch\.setOnClickListener\s*\{(.*?)\n\s*\}",
            self.player_activity_src,
            re.DOTALL
        )
        self.assertIsNotNone(click_listener_match, "etDrawerSearch must have setOnClickListener")
        body = click_listener_match.group(1)

        self.assertIn("etDrawerSearch.requestFocus()", body)
        self.assertIn("imm?.showSoftInput(etDrawerSearch, InputMethodManager.SHOW_IMPLICIT)", body)

    def test_c1_06_player_activity_drawer_search_action_dismisses_keyboard(self):
        """Verify etDrawerSearch onEditorAction dismisses keyboard and returns focus to rvDrawerChannels."""
        editor_match = re.search(
            r"etDrawerSearch\.setOnEditorActionListener\s*\{\s*[^,]+,\s*actionId,\s*[^,]+\s*->\s*(.*?)\n\s*\}",
            self.player_activity_src,
            re.DOTALL
        )
        self.assertIsNotNone(editor_match)
        body = editor_match.group(1)

        self.assertIn("imm?.hideSoftInputFromWindow(etDrawerSearch.windowToken, 0)", body)
        self.assertIn("etDrawerSearch.clearFocus()", body)
        self.assertIn("rvDrawerChannels.requestFocus()", body)

    # =========================================================================
    # Mission Requirement 3: EpgDetailPanel Preview WebView Isolation
    # =========================================================================

    def test_c1_07_epg_preview_webview_blocks_focus_descendants(self):
        """Verify preview WebView strictly sets descendantFocusability = FOCUS_BLOCK_DESCENDANTS."""
        self.assertIn(
            "descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS",
            self.epg_panel_src,
            "EpgDetailPanel preview WebView must set descendantFocusability = FOCUS_BLOCK_DESCENDANTS"
        )
        self.assertIn(
            "isFocusable = false",
            self.epg_panel_src,
            "EpgDetailPanel preview WebView must set isFocusable = false"
        )
        self.assertIn(
            "isFocusableInTouchMode = false",
            self.epg_panel_src,
            "EpgDetailPanel preview WebView must set isFocusableInTouchMode = false"
        )

    def test_c1_08_epg_preview_webview_onrelease_cleanup(self):
        """Verify preview WebView onRelease callback clears history, loads about:blank, and destroys."""
        match = re.search(r"onRelease\s*=\s*\{\s*wv\s*->\s*(.*?)\n\s*\}", self.epg_panel_src, re.DOTALL)
        self.assertIsNotNone(match, "AndroidView for preview WebView must have onRelease callback")
        body = match.group(1)

        self.assertIn("wv.stopLoading()", body)
        self.assertIn('wv.loadUrl("about:blank")', body)
        self.assertIn("wv.clearHistory()", body)
        self.assertIn("wv.destroy()", body)

    def test_c1_09_epg_action_buttons_all_have_focus_borders(self):
        """Verify all interactive buttons in EpgDetailPanel have onFocusChanged and cyan borders."""
        # 1. Main play Button
        self.assertIn("var isPlayFocused by remember { mutableStateOf(false) }", self.epg_panel_src)
        self.assertIn("border = if (isPlayFocused) BorderStroke(2.5.dp, Color(0xFF38BDF8)) else null", self.epg_panel_src)

        # 2. Server 1 OutlinedButton
        self.assertIn("var isServer1Focused by remember { mutableStateOf(false) }", self.epg_panel_src)
        self.assertIn("border = if (isServer1Focused) {", self.epg_panel_src)

        # 3. Server 2 OutlinedButton
        self.assertIn("var isServer2Focused by remember { mutableStateOf(false) }", self.epg_panel_src)

        # 4. Server 3 OutlinedButton
        self.assertIn("var isServer3Focused by remember { mutableStateOf(false) }", self.epg_panel_src)

        # 5. Favorite IconButton
        self.assertIn("var isFavFocused by remember { mutableStateOf(false) }", self.epg_panel_src)
        self.assertIn("if (isFavFocused) Modifier.border(2.dp, Color(0xFF38BDF8), CircleShape)", self.epg_panel_src)

    # =========================================================================
    # Mission Requirement 4: Priority Inversion & Failover Stress Verification
    # =========================================================================

    def test_c1_10_portuguese_national_channels_priority_census(self):
        """Adversarial challenge: verify all national Portuguese channels start at Option 0 with official M3U8."""
        key_portuguese = [
            ("719", "RTP 1"),
            ("720", "RTP 2"),
            ("722", "SIC"),
            ("723", "TVI"),
            ("canal11-pt", "Canal 11"),
            ("porto-canal-pt", "Porto Canal"),
            ("cnn-pt", "CNN Portugal")
        ]

        for cid, expected_name in key_portuguese:
            ch = self.ch_by_id.get(cid)
            self.assertIsNotNone(ch, f"Channel {cid} ({expected_name}) must exist in channels.json")

            is_direct = simulate_is_direct_broadcast(ch)
            self.assertTrue(
                is_direct,
                f"Channel {cid} ({expected_name}) must be classified as isDirectBroadcast=true"
            )

            options = simulate_build_failover_list(ch)
            self.assertGreater(len(options), 0, f"Channel {cid} must have at least 1 option")

            opt0 = options[0]
            self.assertTrue(
                opt0.is_direct,
                f"Channel {cid} ({expected_name}) Option 0 MUST be a direct stream, got: {opt0}"
            )
            self.assertIn(
                "Transmissão Oficial M3U8", opt0.label,
                f"Channel {cid} ({expected_name}) Option 0 label must specify Transmissão Oficial M3U8, got: {opt0.label}"
            )
            self.assertNotIn("DaddyLive", opt0.label, f"Channel {cid} Option 0 must never be DaddyLive")

    def test_c1_11_catalog_broad_audit_no_official_stream_inverted(self):
        """Verify across ALL 733 channels: any channel with an official M3U8 stream has direct stream as Option 0."""
        violations = []
        for ch in self.channels:
            cid = str(ch.get("id", ""))
            backup = ch.get("backupStreamUrl", "")
            if not backup:
                continue

            lower = backup.lower()
            has_official_m3u8 = (
                ".m3u8" in lower or
                "rtp.pt" in lower or
                "impresa.pt" in lower or
                "iol.pt" in lower or
                "canal11" in lower or
                "portocanal" in lower
            )
            if not has_official_m3u8:
                continue

            options = simulate_build_failover_list(ch)
            if not options:
                continue

            opt0 = options[0]
            if not opt0.is_direct or "DaddyLive" in opt0.label:
                violations.append((cid, ch.get("name"), opt0.label))

        self.assertEqual(
            len(violations), 0,
            f"Found channels with official M3U8 inverted behind DaddyLive: {violations}"
        )

    def test_c1_12_hybrid_timstreams_channel_option_ordering(self):
        """Verify channels that have both TimStreams and DaddyLive: Option 0 is TimStreams, followed by DaddyLive."""
        sport_tv_1 = self.ch_by_id.get("49") # Sport TV 1 HD
        self.assertIsNotNone(sport_tv_1)
        self.assertIn("ehgembxe.cfd", sport_tv_1.get("backupStreamUrl", ""))

        options = simulate_build_failover_list(sport_tv_1)
        self.assertGreaterEqual(len(options), 3)

        # Option 0: TimStreams
        self.assertEqual(options[0].label, "Servidor Principal (TimStreams 1080p)")
        self.assertTrue(options[0].is_direct)
        self.assertIn("ehgembxe.cfd", options[0].url)

        # Option 1: DaddyLive Player 1
        self.assertEqual(options[1].label, "Servidor Alternativo 1 (DaddyLive - Player 1)")
        self.assertFalse(options[1].is_direct)
        self.assertEqual(options[1].folder, "stream")

        # Option 2: DaddyLive Player 2
        self.assertEqual(options[2].label, "Servidor Alternativo 2 (DaddyLive - Player 2)")
        self.assertFalse(options[2].is_direct)
        self.assertEqual(options[2].folder, "player")

    def test_c1_13_daddylive_only_channel_ordering(self):
        """Verify channels without any backup stream (DaddyLive only) start with DaddyLive Player 1."""
        dl_channels = [c for c in self.channels if str(c.get("id", "")).isdigit() and not c.get("backupStreamUrl")]
        self.assertGreater(len(dl_channels), 0)
        sample = dl_channels[0]

        options = simulate_build_failover_list(sample)
        self.assertEqual(len(options), 2)
        self.assertEqual(options[0].label, "Servidor Principal (DaddyLive - Player 1)")
        self.assertEqual(options[1].label, "Servidor Alternativo 1 (DaddyLive - Player 2)")

    # =========================================================================
    # Mission Requirement 5: Failover HUD Feedback
    # =========================================================================

    def test_c1_14_failover_hud_updates_status_dynamically(self):
        """Verify triggerFailoverDueToPlaybackError updates tvConnectingStatus.text with next.label and cyan color."""
        self.assertIn(
            'tvConnectingStatus.text = "⚡ A mudar para ${next.label}..."',
            self.player_activity_src,
            "tvConnectingStatus.text must be updated with dynamic next.label"
        )
        self.assertIn(
            'tvConnectingStatus.setTextColor(android.graphics.Color.parseColor("#38BDF8"))',
            self.player_activity_src,
            "tvConnectingStatus must receive high-contrast cyan text color (#38BDF8)"
        )
        self.assertIn(
            "showSilentRecoveryHud(next.label)",
            self.player_activity_src,
            "Must call showSilentRecoveryHud when connectingOverlay is not visible"
        )

    def test_c1_15_show_connecting_overlay_preserves_failover_text(self):
        """Verify showConnectingOverlay preserves dynamic next.label status when currentFailoverIndex > 0."""
        self.assertIn(
            "if (currentFailoverIndex > 0 && currentFailoverIndex < failoverServers.size) {",
            self.player_activity_src,
            "showConnectingOverlay must check currentFailoverIndex > 0 to preserve failover text"
        )
        self.assertIn(
            'tvConnectingStatus.text = "A ligar à transmissão..."',
            self.player_activity_src,
            "Initial load (currentFailoverIndex == 0) must display initial text"
        )


if __name__ == "__main__":
    unittest.main()
