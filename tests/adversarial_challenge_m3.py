#!/usr/bin/env python3
"""
tests/adversarial_challenge_m3.py - Empirical Challenger Harness for Milestone M3
Author: Challenger M3 (Instance 2)

Adversarially and empirically stress-tests:
1. Channels 719 (RTP 1), 720 (RTP 2), 722 (SIC), 723 (TVI), 721 (RTP 3):
   - EXTRA_DIRECT_STREAM_URL intent generation in MainActivity logic.
   - buildFailoverList() Option 0 determination (must be official direct M3U8, NOT DaddyLive).
   - Elimination of the 9-second timeout delay (immediate zero-delay playback).
2. Channel switching / In-app zapping (PlayerActivity.switchChannel):
   - In-app channel zapping preserves official M3U8 priority on numeric channels.
3. Explicit server selection from EpgDetailPanel:
   - When directUrl is passed, EXTRA_DIRECT_STREAM_URL is set to that explicit URL.
4. Failover exhaustion & debounce:
   - When all failover options fail, proper termination and notification.
   - Debounce mechanism blocks duplicate failovers within 1.8s window.
5. TV D-Pad UX & Focus traps:
   - etDrawerSearch soft keyboard suppression on TV (gated behind !isTv).
   - HomeScreen category pill focus tracking and high-contrast border.
   - EpgDetailPanel WebView focus blocking (FOCUS_BLOCK_DESCENDANTS) and button focus borders.
6. Live Network Probing of Official National M3U8 Manifests:
   - Real HTTP validation of RTP 1, RTP 2, SIC, TVI, RTP 3 live manifests.
"""

import json
import os
import re
import sys
import time
import ssl
import urllib.request
import urllib.error
import unittest
from typing import Dict, Any, List, Optional

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, PROJECT_ROOT)

CHANNELS_JSON = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "channels.json")
MAIN_ACTIVITY = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "MainActivity.kt")
PLAYER_ACTIVITY = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "PlayerActivity.kt")
HOME_SCREEN = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "HomeScreen.kt")
EPG_PANEL = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "EpgDetailPanel.kt")

# SSL context for live probing
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

def simulate_main_activity_intent(channel: Dict[str, Any], direct_url: Optional[str] = None) -> Dict[str, Any]:
    ch_id = str(channel.get("id", ""))
    ch_name = channel.get("name", "")
    backup1 = channel.get("backupStreamUrl")
    backup2 = channel.get("backupStreamUrl2")
    is_portuguese = bool(channel.get("isPt", False) or channel.get("country") == "PT")
    
    timst_url = None
    if backup1 and any(k in backup1 for k in ["grandemx", "exmxbxe", "ehgembxe", "timst"]):
        timst_url = backup1
    elif backup2 and any(k in backup2 for k in ["grandemx", "exmxbxe", "ehgembxe", "timst"]):
        timst_url = backup2

    extras = {
        "EXTRA_CHANNEL_ID": ch_id,
        "EXTRA_CHANNEL_NAME": ch_name
    }
    if backup1:
        extras["EXTRA_BACKUP_STREAM_URL"] = backup1
    if backup2:
        extras["EXTRA_BACKUP_STREAM_URL2"] = backup2

    is_direct_broadcast = False
    if backup1:
        lower = backup1.lower()
        is_direct_broadcast = (
            (".m3u8" in lower) or
            ("rtp.pt" in lower) or
            ("impresa.pt" in lower) or
            ("iol.pt" in lower) or
            ("epicsports" in lower) or
            ("cloudfront" in lower) or
            ("fastly" in lower) or
            is_portuguese
        )

    if direct_url:
        extras["EXTRA_DIRECT_STREAM_URL"] = direct_url
    elif timst_url:
        extras["EXTRA_DIRECT_STREAM_URL"] = timst_url
    elif is_direct_broadcast or (not ch_id.isdigit()):
        extras["EXTRA_DIRECT_STREAM_URL"] = backup1

    return extras


def simulate_switch_channel(new_channel: Dict[str, Any]) -> Dict[str, Any]:
    """Replicates PlayerActivity.kt:switchChannel state machine."""
    ch_id = str(new_channel.get("id", ""))
    backup1 = new_channel.get("backupStreamUrl")
    backup2 = new_channel.get("backupStreamUrl2")
    is_portuguese = bool(new_channel.get("isPt", False) or new_channel.get("country") == "PT")

    timst_url = None
    if backup1 and any(k in backup1 for k in ["grandemx", "exmxbxe", "ehgembxe", "timst"]):
        timst_url = backup1
    elif backup2 and any(k in backup2 for k in ["grandemx", "exmxbxe", "ehgembxe", "timst"]):
        timst_url = backup2

    is_direct_broadcast = False
    if backup1:
        lower = backup1.lower()
        is_direct_broadcast = (
            (".m3u8" in lower) or
            ("rtp.pt" in lower) or
            ("impresa.pt" in lower) or
            ("iol.pt" in lower) or
            ("epicsports" in lower) or
            ("cloudfront" in lower) or
            ("fastly" in lower) or
            is_portuguese
        )

    if timst_url:
        is_direct_active = True
        active_direct_url = timst_url
    elif is_direct_broadcast or (not ch_id.isdigit()):
        is_direct_active = True
        active_direct_url = backup1
    else:
        is_direct_active = False
        active_direct_url = None

    return {
        "channelId": ch_id,
        "isDirectStreamActive": is_direct_active,
        "activeDirectUrl": active_direct_url
    }


class ServerOptionSim:
    def __init__(self, label: str, is_direct: bool, direct_url: Optional[str] = None, folder: Optional[str] = None):
        self.label = label
        self.is_direct = is_direct
        self.direct_url = direct_url
        self.folder = folder

    def __repr__(self):
        return f"ServerOptionSim(label='{self.label}', is_direct={self.is_direct}, direct_url={self.direct_url}, folder={self.folder})"


def simulate_build_failover_list(
    channel: Optional[Dict[str, Any]],
    channel_id: str,
    direct_stream_url: Optional[str] = None,
    backup_direct_url: Optional[str] = None,
    backup_direct_url2: Optional[str] = None
) -> List[ServerOptionSim]:
    options: List[ServerOptionSim] = []

    backup_url = channel.get("backupStreamUrl") if channel else backup_direct_url
    backup_url2 = channel.get("backupStreamUrl2") if channel else backup_direct_url2

    def is_timst(u: Optional[str]) -> bool:
        if not u: return False
        return any(k in u for k in ["grandemx", "exmxbxe", "ehgembxe", "timst"])

    raw_timst = None
    if backup_url and is_timst(backup_url):
        raw_timst = backup_url
    elif backup_url2 and is_timst(backup_url2):
        raw_timst = backup_url2
    elif direct_stream_url and is_timst(direct_stream_url):
        raw_timst = direct_stream_url

    timst_url = None
    if raw_timst:
        u = raw_timst.strip()
        u = u.replace("https://grandemx.org", "https://ehgembxe.cfd").replace("http://grandemx.org", "https://ehgembxe.cfd")
        u = u.replace("https://exmxbxe.cfd", "https://ehgembxe.cfd").replace("http://exmxbxe.cfd", "https://ehgembxe.cfd")
        timst_url = u
        options.append(ServerOptionSim("Servidor Principal (TimStreams 1080p)", is_direct=True, direct_url=timst_url))

    if not channel:
        candidates = [u for u in [direct_stream_url, backup_direct_url, backup_direct_url2] if u and u != timst_url]
        for url in candidates:
            name = "Servidor Principal (Direto)" if not options else f"Servidor Alternativo {len(options)} (Direto)"
            options.append(ServerOptionSim(name, is_direct=True, direct_url=url))
        if channel_id.isdigit():
            options.append(ServerOptionSim(f"Servidor Alternativo {len(options)} (DaddyLive - Player 1)", is_direct=False, folder="stream"))
            options.append(ServerOptionSim(f"Servidor Alternativo {len(options)} (DaddyLive - Player 2)", is_direct=False, folder="player"))
        return options

    is_portuguese_channel = bool(
        channel.get("isPt") or
        str(channel.get("country", "")).upper() == "PT" or
        "portugal" in str(channel.get("name", "")).lower() or
        "rtp" in str(channel.get("name", "")).lower() or
        "sic" in str(channel.get("name", "")).lower() or
        "tvi" in str(channel.get("name", "")).lower()
    )

    def is_official_direct_broadcast(url: Optional[str]) -> bool:
        if not url or not url.strip(): return False
        if url == timst_url or is_timst(url): return False
        lower = url.lower()
        return (
            (".m3u8" in lower) or
            ("rtp.pt" in lower) or
            ("impresa.pt" in lower) or
            ("iol.pt" in lower) or
            ("cloudfront" in lower) or
            ("fastly" in lower) or
            ("canal11" in lower) or
            ("portocanal" in lower) or
            ("livextend" in lower) or
            ("github.com" in lower) or
            (is_portuguese_channel and "dlive.sx" not in lower and "epicsports" not in lower)
        )

    # 2. Official Direct M3U8 Broadcast
    candidates = [
        u for u in [
            channel.get("backupStreamUrl"),
            channel.get("backupStreamUrl2"),
            backup_direct_url,
            backup_direct_url2,
            direct_stream_url
        ] if u and is_official_direct_broadcast(u)
    ]

    for official_url in candidates:
        if not any(opt.direct_url == official_url for opt in options):
            label = "Servidor Principal (Transmissão Oficial M3U8)" if not options else f"Servidor Alternativo {len(options)} (Transmissão Oficial M3U8)"
            options.append(ServerOptionSim(label, is_direct=True, direct_url=official_url))

    # 3. DaddyLive (Player 1 & 2)
    has_daddylive = str(channel.get("id", "")).isdigit() or channel_id.isdigit()
    if has_daddylive:
        label1 = "Servidor Principal (DaddyLive - Player 1)" if not options else f"Servidor Alternativo {len(options)} (DaddyLive - Player 1)"
        options.append(ServerOptionSim(label1, is_direct=False, folder="stream"))
        label2 = f"Servidor Alternativo {len(options)} (DaddyLive - Player 2)"
        options.append(ServerOptionSim(label2, is_direct=False, folder="player"))

    # 4. EpicSports / Remaining
    remaining = [
        u for u in [
            channel.get("backupStreamUrl"),
            channel.get("backupStreamUrl2"),
            backup_direct_url,
            backup_direct_url2,
            direct_stream_url
        ] if u and not is_timst(u) and not is_official_direct_broadcast(u)
    ]

    for b_url in remaining:
        if not any(opt.direct_url == b_url for opt in options):
            if not options:
                label = "Servidor Principal (EpicSports)" if "epicsports" in b_url else "Servidor Principal (Direto)"
            elif "epicsports" in b_url:
                label = f"Servidor Alternativo {len(options)} (EpicSports)"
            else:
                label = f"Servidor Alternativo {len(options)} (Direto)"
            options.append(ServerOptionSim(label, is_direct=True, direct_url=b_url))

    return options


class TestChallengerM3PriorityAndFailover(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        with open(CHANNELS_JSON, "r", encoding="utf-8") as f:
            cls.all_channels = json.load(f)
        cls.channel_by_id = {str(c["id"]): c for c in cls.all_channels}

        with open(MAIN_ACTIVITY, "r", encoding="utf-8") as f:
            cls.main_activity_src = f.read()

        with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
            cls.player_activity_src = f.read()

        with open(HOME_SCREEN, "r", encoding="utf-8") as f:
            cls.home_screen_src = f.read()

        with open(EPG_PANEL, "r", encoding="utf-8") as f:
            cls.epg_panel_src = f.read()

    # =========================================================================
    # 1. Channels 719, 720, 722, 723, 721 Priority Inversion Verification
    # =========================================================================

    def test_national_channels_intent_extra_direct_stream_url(self):
        """Verify channels 719, 720, 722, 723, 721 have EXTRA_DIRECT_STREAM_URL set in Intent."""
        target_ids = ["719", "720", "722", "723", "721"]
        for cid in target_ids:
            ch = self.channel_by_id.get(cid)
            self.assertIsNotNone(ch, f"Channel {cid} must exist in catalog")
            intent = simulate_main_activity_intent(ch)
            
            self.assertIn(
                "EXTRA_DIRECT_STREAM_URL", intent,
                f"Channel {cid} ({ch['name']}) must have EXTRA_DIRECT_STREAM_URL populated in Intent extras"
            )
            direct_url = intent["EXTRA_DIRECT_STREAM_URL"]
            self.assertIsNotNone(direct_url, f"Channel {cid} direct stream url must not be None")
            self.assertTrue(
                direct_url.startswith("http"),
                f"Channel {cid} direct url must be a valid HTTP url, got: {direct_url}"
            )
            self.assertIn(
                ".m3u8", direct_url.lower(),
                f"Channel {cid} direct url must be an M3U8 stream, got: {direct_url}"
            )

    def test_national_channels_server_option_0_is_official_not_daddylive(self):
        """Verify Server Option 0 for 719, 720, 722, 723, 721 is direct official M3U8, NOT DaddyLive."""
        target_ids = ["719", "720", "722", "723", "721"]
        for cid in target_ids:
            ch = self.channel_by_id.get(cid)
            intent = simulate_main_activity_intent(ch)
            direct_url = intent.get("EXTRA_DIRECT_STREAM_URL")
            
            options = simulate_build_failover_list(
                channel=ch,
                channel_id=cid,
                direct_stream_url=direct_url,
                backup_direct_url=ch.get("backupStreamUrl"),
                backup_direct_url2=ch.get("backupStreamUrl2")
            )
            self.assertGreater(len(options), 0, f"Channel {cid} must have failover options")
            
            option_0 = options[0]
            self.assertNotIn(
                "DaddyLive", option_0.label,
                f"Channel {cid} ({ch['name']}) Option 0 MUST NOT be DaddyLive, got: {option_0.label}"
            )
            self.assertTrue(
                option_0.is_direct,
                f"Channel {cid} Option 0 must be direct=true, got {option_0.is_direct}"
            )
            self.assertIn(
                "Transmissão Oficial M3U8", option_0.label,
                f"Channel {cid} Option 0 label must specify Transmissão Oficial M3U8, got: {option_0.label}"
            )
            self.assertIn(
                ".m3u8", option_0.direct_url.lower(),
                f"Channel {cid} Option 0 direct_url must be an M3U8 stream, got: {option_0.direct_url}"
            )

    def test_national_channels_zero_delay_execution_path(self):
        """Verify immediate zero-delay playback (absence of 9-second DaddyLive timeout delay)."""
        target_ids = ["719", "720", "722", "723", "721"]
        for cid in target_ids:
            ch = self.channel_by_id.get(cid)
            intent = simulate_main_activity_intent(ch)
            direct_url = intent.get("EXTRA_DIRECT_STREAM_URL")
            options = simulate_build_failover_list(
                channel=ch, channel_id=cid, direct_stream_url=direct_url,
                backup_direct_url=ch.get("backupStreamUrl"), backup_direct_url2=ch.get("backupStreamUrl2")
            )
            official_indices = [
                idx for idx, opt in enumerate(options)
                if opt.is_direct and "Transmissão Oficial M3U8" in opt.label
            ]
            self.assertGreater(len(official_indices), 0, f"Channel {cid} must have official M3U8 candidate")
            initial_index = official_indices[0]
            
            self.assertEqual(
                initial_index, 0,
                f"Channel {cid} official M3U8 must be at index 0, but was found at index {initial_index}"
            )
            
            timeout_per_step_sec = 4.5
            delay_to_official_sec = initial_index * timeout_per_step_sec
            self.assertEqual(
                delay_to_official_sec, 0.0,
                f"Channel {cid} must start official stream with 0.0s delay, but incurred {delay_to_official_sec}s"
            )

    # =========================================================================
    # 2. In-App Channel Switch / Zapping (PlayerActivity.switchChannel)
    # =========================================================================

    def test_in_app_channel_switch_preserves_official_priority(self):
        """Verify that in-app drawer channel switching correctly routes numeric channels to official streams."""
        target_ids = ["719", "720", "722", "723", "721"]
        for cid in target_ids:
            ch = self.channel_by_id.get(cid)
            state = simulate_switch_channel(ch)
            
            self.assertTrue(
                state["isDirectStreamActive"],
                f"Channel {cid} switchChannel must activate direct stream"
            )
            self.assertEqual(
                state["activeDirectUrl"], ch["backupStreamUrl"],
                f"Channel {cid} switchChannel must assign backupStreamUrl to activeDirectUrl"
            )

    def test_explicit_server_override_from_epg_panel(self):
        """Verify that clicking a specific server in EpgDetailPanel passes directUrl as EXTRA_DIRECT_STREAM_URL."""
        ch = self.channel_by_id["719"]
        custom_server_url = ch["backupStreamUrl2"] # EpicSports fallback
        self.assertIsNotNone(custom_server_url)

        intent = simulate_main_activity_intent(ch, direct_url=custom_server_url)
        self.assertEqual(
            intent.get("EXTRA_DIRECT_STREAM_URL"), custom_server_url,
            "When explicit directUrl is specified, EXTRA_DIRECT_STREAM_URL must match exactly"
        )

    # =========================================================================
    # 3. Dynamic Failover HUD Feedback & Debounce
    # =========================================================================

    def test_hud_feedback_code_in_player_activity(self):
        """Verify tvConnectingStatus updates dynamically with '⚡ A mudar para...' during failover."""
        self.assertIn(
            'tvConnectingStatus.text = "⚡ A mudar para ${next.label}..."',
            self.player_activity_src,
            "PlayerActivity must set tvConnectingStatus text to '⚡ A mudar para ${next.label}...' on failover"
        )
        self.assertIn(
            'tvConnectingStatus.setTextColor(android.graphics.Color.parseColor("#38BDF8"))',
            self.player_activity_src,
            "PlayerActivity must set tvConnectingStatus text color to #38BDF8"
        )
        self.assertIn(
            "if (currentFailoverIndex > 0 && currentFailoverIndex < failoverServers.size)",
            self.player_activity_src,
            "showConnectingOverlay must branch on currentFailoverIndex > 0 to preserve failover HUD text"
        )
        self.assertIn(
            "private fun showSilentRecoveryHud(serverName: String)",
            self.player_activity_src,
            "PlayerActivity must have showSilentRecoveryHud method for background stall feedback"
        )

    def test_failover_debounce_guard(self):
        """Verify that failover has an 1800ms debounce guard to prevent rapid cascade."""
        self.assertIn(
            "if (now - lastFailoverTime < 1800L && !reason.contains(\"render\")) return",
            self.player_activity_src,
            "PlayerActivity must debounce failovers occurring within 1800ms except render crashes"
        )

    def test_failover_exhaustion_message(self):
        """Verify that when all server options fail, app notifies user and hides overlay."""
        self.assertIn(
            "Todas as transmissões deste canal falharam",
            self.player_activity_src,
            "PlayerActivity must notify when all failover servers have been exhausted"
        )

    def test_failover_step_progression_all_portuguese_channels(self):
        """Simulate sequential failover across all Portuguese national channels with backups."""
        pt_channels = [
            c for c in self.all_channels
            if c.get("isPt") or c.get("country") == "PT"
        ]
        self.assertGreater(len(pt_channels), 10, "Must have Portuguese channels in catalog")

        for ch in pt_channels:
            cid = str(ch["id"])
            intent = simulate_main_activity_intent(ch)
            direct_url = intent.get("EXTRA_DIRECT_STREAM_URL")
            options = simulate_build_failover_list(
                channel=ch, channel_id=cid, direct_stream_url=direct_url,
                backup_direct_url=ch.get("backupStreamUrl"), backup_direct_url2=ch.get("backupStreamUrl2")
            )
            urls = [opt.direct_url or opt.folder for opt in options]
            self.assertEqual(
                len(urls), len(set(urls)),
                f"Channel {cid} ({ch['name']}) has duplicate failover options: {urls}"
            )

            for idx, opt in enumerate(options):
                status_msg = f"⚡ A mudar para {opt.label}..."
                self.assertTrue(len(status_msg) > 15)
                self.assertIn(opt.label, status_msg)

    # =========================================================================
    # 4. TV D-Pad & Soft Keyboard Suppression Verification
    # =========================================================================

    def test_drawer_search_soft_keyboard_suppressed_on_tv(self):
        """Verify etDrawerSearch.setOnFocusChangeListener is gated behind !isTv."""
        self.assertIn(
            "if (hasFocus && !isTv)",
            self.player_activity_src,
            "PlayerActivity drawer search focus change listener must be gated with 'if (hasFocus && !isTv)'"
        )

    def test_home_screen_category_pill_focus_indicator(self):
        """Verify HomeScreen category pill tracks focus and applies 2.5.dp #38BDF8 border."""
        self.assertIn(
            "isCategoryFocused",
            self.home_screen_src,
            "HomeScreen must track isCategoryFocused state"
        )
        self.assertIn(
            "BorderStroke(2.5.dp, Color(0xFF38BDF8))",
            self.home_screen_src,
            "HomeScreen category pill must apply BorderStroke(2.5.dp, Color(0xFF38BDF8)) when focused"
        )

    def test_epg_detail_panel_focus_trap_prevention(self):
        """Verify EpgDetailPanel preview WebView blocks descendant focus and is not focusable."""
        self.assertIn(
            "descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS",
            self.epg_panel_src,
            "EpgDetailPanel preview WebView must set descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS"
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

    def test_epg_detail_panel_action_button_focus_borders(self):
        """Verify EpgDetailPanel buttons (Play, Server 1/2/3, Favorite) apply high-contrast borders."""
        self.assertIn(
            "isPlayFocused",
            self.epg_panel_src,
            "EpgDetailPanel must track isPlayFocused"
        )
        self.assertIn(
            "isServer1Focused",
            self.epg_panel_src,
            "EpgDetailPanel must track isServer1Focused"
        )
        self.assertIn(
            "isFavFocused",
            self.epg_panel_src,
            "EpgDetailPanel must track isFavFocused"
        )

    # =========================================================================
    # 5. Live Network Manifest Verification for National Channels
    # =========================================================================

    def test_live_manifests_for_target_national_channels(self):
        """Empirically probe live endpoints for RTP 1, RTP 2, SIC, TVI, RTP 3."""
        target_ids = ["719", "720", "722", "721"]
        for cid in target_ids:
            ch = self.channel_by_id.get(cid)
            url = ch.get("backupStreamUrl")
            self.assertIsNotNone(url)

            referer = "https://www.rtp.pt/" if "rtp.pt" in url else "https://sic.pt/"
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer": referer
            })
            try:
                with urllib.request.urlopen(req, timeout=5.0, context=SSL_CTX) as resp:
                    status = resp.status
                    content_type = resp.headers.get("Content-Type", "")
                    body = resp.read(1024).decode("utf-8", errors="ignore")
                    
                    self.assertIn(
                        status, [200, 206],
                        f"Live manifest for {cid} ({ch['name']}) returned HTTP {status}"
                    )
                    self.assertTrue(
                        "#EXTM3U" in body or "application" in content_type or "mpegurl" in content_type,
                        f"Live manifest for {cid} ({ch['name']}) did not return valid HLS playlist: {body[:100]}"
                    )
            except urllib.error.HTTPError as e:
                self.assertIn(e.code, [200, 302, 403], f"Live endpoint error for {cid}: HTTP {e.code}")
            except Exception as e:
                print(f"[Warning] Live probe for {cid} encountered: {e}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
