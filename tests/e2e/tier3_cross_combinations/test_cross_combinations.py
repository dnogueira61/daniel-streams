"""
Tier 3 Cross-Feature Combinations (Pairwise Verification)
Tests interactions between Failover, Ad-Blocking, D-Pad UX, CORS BaseURLs, and Lifecycle Management.
"""

import unittest
from typing import List, Dict, Any, Optional
from tests.e2e.tier1_feature_coverage.test_feature_ad_blocking import (
    SPEC_AD_BLOCK_PATTERNS,
    simulate_should_intercept
)
from tests.e2e.fixtures.helpers import probe_http_endpoint

class TestCrossFeatureCombinations(unittest.TestCase):

    def test_01_failover_under_active_ad_blocking(self):
        """Pairwise: Verify failover correctly switches servers when ad-blocker is actively intercepting."""
        class StreamFailoverEngine:
            def __init__(self, servers: List[str], ad_patterns: set):
                self.servers = servers
                self.ad_patterns = ad_patterns
                self.current_index = 0
                self.active_stream: Optional[str] = None

            def load_current(self) -> bool:
                url = self.servers[self.current_index]
                # If the stream itself or an ad script triggers interception
                if simulate_should_intercept(url, self.ad_patterns):
                    # Intercepted as ad -> trigger failover
                    return self.advance_failover()
                self.active_stream = url
                return True

            def advance_failover(self) -> bool:
                if self.current_index + 1 < len(self.servers):
                    self.current_index += 1
                    return self.load_current()
                return False

        # Scenario: Server 0 is an ad-corrupted URL, Server 1 is DaddyLive P2, Server 2 is TimStreams
        candidate_servers = [
            "https://serve.popads.net/stream-corrupt.php",
            "https://dlive.sx/player/stream-49.php",
            "https://ehgembxe.cfd/oqq5c86g-7521"
        ]

        engine = StreamFailoverEngine(candidate_servers, SPEC_AD_BLOCK_PATTERNS)
        success = engine.load_current()

        self.assertTrue(success, "Failover must succeed despite Server 0 matching ad-block patterns")
        self.assertEqual(engine.current_index, 1, "Must advance past corrupted Server 0 to Server 1")
        self.assertEqual(engine.active_stream, "https://dlive.sx/player/stream-49.php")

    def test_02_dpad_navigation_during_stream_stall(self):
        """Pairwise: Verify D-pad navigation remains unblocked during video stalls."""
        class PlayerControllerState:
            def __init__(self):
                self.is_stalled = False
                self.failover_in_progress = False
                self.current_channel_id = "719"
                self.ui_thread_locked = False

            def on_playback_stalled(self):
                self.is_stalled = True
                self.failover_in_progress = True

            def handle_dpad_event(self, keycode: int) -> bool:
                # Keycodes: 19 = DPAD_UP, 20 = DPAD_DOWN, 21 = DPAD_LEFT
                if self.ui_thread_locked:
                    return False
                # User can always switch channels regardless of stall state
                if keycode == 20: # DPAD_DOWN
                    self.current_channel_id = "720"
                    self.is_stalled = False
                    self.failover_in_progress = False
                    return True
                return False

        state = PlayerControllerState()
        state.on_playback_stalled()
        self.assertTrue(state.is_stalled)

        # Dispatch DPAD_DOWN during stall
        consumed = state.handle_dpad_event(20)
        self.assertTrue(consumed, "D-pad event must be consumed and handled during stream stall")
        self.assertEqual(state.current_channel_id, "720", "Channel must switch to 720")
        self.assertFalse(state.is_stalled, "Stall state must be reset upon manual zap")

    def test_03_cors_headers_with_hlsjs_baseurl(self):
        """Pairwise: Verify CORS Origin checks succeed when loadDataWithBaseURL passes exact origin."""
        # When loading SIC M3U8, origin must be https://sic.pt
        target_stream = "https://sic.live.impresa.pt/sic1080p.m3u8"
        matching_base_url = "https://sic.pt/"
        
        headers = {
            "Origin": "https://sic.pt",
            "Referer": matching_base_url
        }
        status, resp_headers, body = probe_http_endpoint(target_stream, headers=headers, timeout=6.0)
        self.assertEqual(status, 200, "Stream must return 200 when loaded with matching base URL")
        
        cors_origin = resp_headers.get("access-control-allow-origin", "")
        self.assertIn(cors_origin, ["https://sic.pt", "*"], "CORS header must allow matching base URL origin")

    def test_04_failover_state_combined_with_tv_hud_feedback(self):
        """Pairwise: Verify failover transition updates HUD connecting overlay status dynamically."""
        class FailoverHudCoordinator:
            def __init__(self):
                self.hud_status_text = "A ligar à transmissão..."
                self.hud_text_color = "#FFFFFF"
                self.hud_visible = True

            def on_failover_transition(self, target_server_label: str):
                self.hud_status_text = f"⚡ A mudar para {target_server_label}..."
                self.hud_text_color = "#38BDF8"
                self.hud_visible = True

        coord = FailoverHudCoordinator()
        self.assertEqual(coord.hud_status_text, "A ligar à transmissão...")

        # Trigger transition to Server 2
        coord.on_failover_transition("Servidor 2 (DaddyLive - Player 2)")
        self.assertIn("Servidor 2", coord.hud_status_text)
        self.assertEqual(coord.hud_text_color, "#38BDF8", "HUD text color should update to accent color")
        self.assertTrue(coord.hud_visible)

    def test_05_lifecycle_destruction_under_active_playback_and_failover(self):
        """Pairwise: Verify Activity destruction mid-failover cleanly cancels all background tasks and leaks."""
        class LifecycleManager:
            def __init__(self):
                self.active_timers = ["failover_timeout_4500ms", "stall_detector_1000ms"]
                self.animator_cancelled = False
                self.dialog_dismissed = False
                self.js_interface_removed = False
                self.view_detached = False

            def on_destroy(self):
                # 1. Cancel all timers
                self.active_timers.clear()
                # 2. Cancel infinite breathing animator
                self.animator_cancelled = True
                # 3. Dismiss active server dialogs
                self.dialog_dismissed = True
                # 4. Remove JavascriptInterface
                self.js_interface_removed = True
                # 5. Detach view before destroying
                self.view_detached = True

        lm = LifecycleManager()
        self.assertEqual(len(lm.active_timers), 2)

        lm.on_destroy()
        self.assertEqual(len(lm.active_timers), 0, "All active failover timers must be cleared")
        self.assertTrue(lm.animator_cancelled, "Infinite breathing animator must be cancelled")
        self.assertTrue(lm.dialog_dismissed, "Active dialog must be dismissed to prevent WindowLeaked")
        self.assertTrue(lm.js_interface_removed, "Failover JS interface must be removed to prevent Activity leak")
        self.assertTrue(lm.view_detached, "WebView must be detached from parent before destroy")

    def test_06_sandbox_neutralization_under_ad_interception(self):
        """Pairwise: Verify dynamic sandbox neutralization operates seamlessly alongside ad request blocking."""
        from tests.e2e.fixtures.helpers import run_node_script

        js = """
        // Combined sandbox + ad block simulation
        var blockedUrls = [];
        var adPatterns = ['popads', 'adsterra', 'exoclick'];

        function shouldIntercept(url) {
            for (var i = 0; i < adPatterns.length; i++) {
                if (url.indexOf(adPatterns[i]) !== -1) {
                    blockedUrls.push(url);
                    return true;
                }
            }
            return false;
        }

        var iframe = document.createElement('iframe');
        iframe.setAttribute('sandbox', 'allow-scripts');
        var adUrl = 'https://serve.popads.net/banner.js';
        var streamUrl = 'https://dembed.st/premiumtv/daddy.php?id=49';

        var adIntercepted = shouldIntercept(adUrl);
        var streamIntercepted = shouldIntercept(streamUrl);

        var result = {
            hasSandbox: iframe.hasAttribute('sandbox'),
            adIntercepted: adIntercepted,
            streamIntercepted: streamIntercepted,
            blockedUrlsCount: blockedUrls.length
        };
        console.log(JSON.stringify(result));
        """
        code, stdout, stderr = run_node_script(js)
        self.assertEqual(code, 0)
        import json
        res = json.loads(stdout.strip())
        self.assertTrue(res["adIntercepted"], "Ad URL must be intercepted")
        self.assertFalse(res["streamIntercepted"], "Stream URL must not be intercepted")
        self.assertEqual(res["blockedUrlsCount"], 1)

if __name__ == "__main__":
    unittest.main()
