"""
Tier 4 Real-World Application Scenarios
Verifies end-to-end user journeys: Portuguese channel priority routing, DaddyLive failover,
TimStreams 302 token resolution, release packaging, and catalog contingency.
"""

import unittest
import os
import zipfile
from typing import List, Dict, Any, Optional
from tests.e2e.fixtures.helpers import (
    load_master_channels,
    probe_http_endpoint,
    PROJECT_ROOT
)

def build_channel_server_options_spec(channel: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Authoritative specification of PlayerActivity.buildFailoverList logic
    per PROJECT.md and R3 Priority Inversion fix.
    """
    options = []
    ch_id = str(channel.get("id", ""))
    backup1 = channel.get("backupStreamUrl")
    backup2 = channel.get("backupStreamUrl2")

    is_direct_broadcast = (
        (backup1 and (".m3u8" in backup1 or "rtp.pt" in backup1 or "impresa.pt" in backup1 or "iol.pt" in backup1 or "cloudfront.net" in backup1)) or
        (not ch_id.isdigit())
    )

    # 1. Official Direct M3U8 Stream (PRIORITIZED FOR NATIONAL CHANNELS)
    if is_direct_broadcast and backup1 and not ("ehgembxe" in backup1 or "timst" in backup1):
        options.append({"label": "Servidor Principal (Transmissão Oficial M3U8)", "url": backup1, "type": "direct_m3u8"})

    # 2. DaddyLive Player 1 & 2 for numeric channels (PRIORITIZED OVER TIMSTREAMS)
    if ch_id.isdigit() and int(ch_id) > 0:
        options.append({
            "label": "Servidor Principal (DaddyLive - Player 1)" if not options else f"Servidor Alternativo {len(options) + 1} (DaddyLive - Player 1)",
            "url": f"https://dlive.sx/stream/stream-{ch_id}.php",
            "type": "daddylive_p1"
        })
        options.append({
            "label": f"Servidor Alternativo {len(options) + 1} (DaddyLive - Player 2)",
            "url": f"https://dlive.sx/player/stream-{ch_id}.php",
            "type": "daddylive_p2"
        })

    # 3. TimStreams fallback if present
    if backup1 and ("ehgembxe" in backup1 or "timst" in backup1):
        options.append({"label": f"Servidor Alternativo {len(options) + 1} (TimStreams 1080p)", "url": backup1, "type": "timst"})
    # 4. EpicSports or other backups
    if backup2:
        options.append({"label": f"Servidor Alternativo {len(options) + 1} (EpicSports)", "url": backup2, "type": "epicsports"})
    elif backup1 and not is_direct_broadcast and not ("ehgembxe" in backup1 or "timst" in backup1):
        options.append({"label": f"Servidor Alternativo {len(options) + 1} (Direct)", "url": backup1, "type": "direct"})

    return options

class TestRealWorldScenarios(unittest.TestCase):

    def setUp(self):
        self.channels = load_master_channels()
        self.channel_map = {str(c["id"]): c for c in self.channels}

    def test_01_portuguese_national_channel_priority_routing(self):
        """Scenario 1: Portuguese national channels must route directly to official M3U8 as Server Option 0."""
        national_channels = ["719", "720", "722", "canal11-pt"] # RTP 1, RTP 2, SIC, Canal 11
        for cid in national_channels:
            ch = self.channel_map.get(cid)
            self.assertIsNotNone(ch, f"Channel {cid} must exist in catalog")
            options = build_channel_server_options_spec(ch)
            self.assertGreater(len(options), 0, f"Channel {cid} must have at least one server option")
            
            # Primary server (Option 0) must be the official direct M3U8 stream
            primary = options[0]
            self.assertEqual(
                primary["type"], "direct_m3u8",
                f"Channel {cid} ({ch['name']}) must prioritize direct_m3u8 as Option 0, got '{primary['type']}'"
            )
            self.assertTrue(
                ".m3u8" in primary["url"],
                f"Channel {cid} primary URL must be an M3U8 stream, got '{primary['url']}'"
            )

    def test_02_daddylive_player1_to_player2_failover_flow(self):
        """Scenario 2: DaddyLive Player 1 failover to Player 2 resolves valid upstream player."""
        sports_channel = self.channel_map.get("49") # Sky Sports Main Event
        self.assertIsNotNone(sports_channel)
        options = build_channel_server_options_spec(sports_channel)

        p1_opt = next((o for o in options if o["type"] == "daddylive_p1"), None)
        p2_opt = next((o for o in options if o["type"] == "daddylive_p2"), None)
        self.assertIsNotNone(p1_opt, "Must have DaddyLive Player 1 option")
        self.assertIsNotNone(p2_opt, "Must have DaddyLive Player 2 option")

        # Player 2 provides upstream failover via wideiptv.top
        wideiptv_url = f"https://wideiptv.top/daddy.php?stream={sports_channel['id']}"
        status, headers, body = probe_http_endpoint(wideiptv_url, headers={"Referer": "https://dlive.sx/"}, timeout=5.0)
        self.assertEqual(status, 200, f"Upstream Player 2 (wideiptv.top) must respond HTTP 200: {wideiptv_url}")
        self.assertTrue(len(body) > 0, "Player 2 upstream must return player container markup")

    def test_03_timstreams_302_token_resolution_live_flow(self):
        """Scenario 3: End-to-end TimStreams flow resolves HTTP 302 with JWT play session token."""
        spt1 = self.channel_map.get("49") # Sport TV 1 HD has TimStreams backup
        self.assertIsNotNone(spt1)
        timst_url = spt1.get("backupStreamUrl")
        self.assertIsNotNone(timst_url, "Channel 49 must have backupStreamUrl for TimStreams")
        self.assertIn("ehgembxe.cfd", timst_url)

        # Execute 302 probe with required Referer
        status, headers, body = probe_http_endpoint(
            timst_url,
            headers={"Referer": "https://timst.top/"},
            timeout=6.0,
            allow_redirects=False
        )
        self.assertEqual(status, 302, f"TimStreams endpoint must return 302 redirect, got {status}")
        location = headers.get("location", "")
        self.assertTrue("/play/" in location, f"Location header must contain '/play/' token path, got {location}")

    def test_04_full_assemblerelease_build_and_apk_integrity(self):
        """Scenario 4: Verify release APK artifact integrity and packaging completeness."""
        apk_path = os.path.join(PROJECT_ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
        self.assertTrue(os.path.exists(apk_path), "app-release.apk must exist from assembleRelease")
        
        # Verify APK is not corrupted and contains critical assets
        with zipfile.ZipFile(apk_path, "r") as apk:
            files = set(apk.namelist())
            self.assertIn("AndroidManifest.xml", files)
            self.assertIn("assets/channels.json", files)
            self.assertTrue(any(f.startswith("res/") for f in files), "APK must package Android resources")
            self.assertTrue(any(f.endswith(".dex") for f in files), "APK must package compiled DEX bytecode")

    def test_05_catalog_100_percent_contingency_coverage(self):
        """Scenario 5: 100% of channels must have at least one viable playback option defined."""
        zero_contingency_channels = []
        for ch in self.channels:
            cid = str(ch.get("id", ""))
            has_numeric_daddylive = cid.isdigit() and int(cid) > 0
            has_backup1 = bool(ch.get("backupStreamUrl") and str(ch["backupStreamUrl"]).strip())
            has_backup2 = bool(ch.get("backupStreamUrl2") and str(ch["backupStreamUrl2"]).strip())
            has_stream_url = bool(ch.get("streamUrl") and str(ch["streamUrl"]).strip())

            if not (has_numeric_daddylive or has_backup1 or has_backup2 or has_stream_url):
                zero_contingency_channels.append(ch)

        self.assertEqual(
            len(zero_contingency_channels), 0,
            f"All 733 channels must have viable streams or fallbacks. Found without contingency: {zero_contingency_channels}"
        )

if __name__ == "__main__":
    unittest.main()
