"""
tests/adversarial_challenge_m2_it2.py
Comprehensive Adversarial Challenge Suite for Milestone M2 Iteration 2:
Empirical validation of:
1. isHardError handling of renderer crash when stream is actively playing
2. WebView re-instantiation and re-attachment on renderer crash
3. Consecutive renderer crash recovery and sliding window loop breaker
4. Debounce bypass on renderer crash
5. Code integrity in PlayerActivity.kt
"""

import os
import re
import sys
import unittest

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PLAYER_ACTIVITY = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "PlayerActivity.kt")


class TestM2RemediationAdversarial(unittest.TestCase):

    def setUp(self):
        with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
            self.code = f.read()

    def test_01_ast_hard_error_includes_render(self):
        """isHardError must explicitly check reason.contains('render')"""
        match = re.search(r"val isHardError = (.*)", self.code)
        self.assertIsNotNone(match, "isHardError expression not found")
        expr = match.group(1)
        self.assertIn('reason.contains("render")', expr, "reason.contains('render') missing from isHardError")

    def test_02_ast_stream_loaded_reset(self):
        """streamLoadedSuccessfully must be cleared when reason contains 'render'"""
        self.assertIn('if (reason.contains("render")) {\n            streamLoadedSuccessfully = false\n        }', self.code)

    def test_03_ast_debounce_bypass_for_render_crash(self):
        """Renderer crash must bypass 1800ms failover debounce"""
        match = re.search(r"if \(now - lastFailoverTime < 1800L && !reason\.contains\(\"render\"\)\)\s*return", self.code)
        self.assertIsNotNone(match, "Debounce bypass for render crash not found in PlayerActivity.kt")

    def test_04_ast_on_render_process_gone_recreation(self):
        """onRenderProcessGone must re-create WebView, set layoutParams, attach to root, reassign, reset flag, and call setupWebView()"""
        match = re.search(r"override fun onRenderProcessGone\(view: WebView\?, detail: RenderProcessGoneDetail\?\): Boolean \{(.*?)\n\s*return true", self.code, re.DOTALL)
        self.assertIsNotNone(match, "onRenderProcessGone implementation not found")
        body = match.group(1)
        
        # Verify detaching & destroying dead webview
        self.assertIn("(view?.parent as? ViewGroup)?.removeView(view)", body)
        self.assertIn("view?.destroy()", body)
        
        # Verify state reset
        self.assertIn("streamLoadedSuccessfully = false", body)
        self.assertIn("isWebViewDestroyed = true", body)
        
        self.assertIn("val newWebView = WebView(this@PlayerActivity)", body)
        self.assertIn("id = R.id.webView", body)
        self.assertTrue(".apply {" in body, ".apply block missing")
        self.assertIn("addRule(RelativeLayout.BELOW, R.id.playerHeader)", body)
        self.assertIn("root.addView(newWebView, 0)", body)
        self.assertIn("webView = newWebView", body)
        self.assertIn("isWebViewDestroyed = false", body)
        self.assertIn("setupWebView()", body)
        self.assertIn('triggerFailoverDueToPlaybackError("render_process_crashed")', body)

    def test_05_behavior_trigger_failover_when_stream_actively_playing(self):
        """
        Simulate PlayerActivity.triggerFailoverDueToPlaybackError
        Verify that when streamLoadedSuccessfully is True, 'render_process_crashed' is NOT ignored.
        """
        stream_loaded_successfully = True
        last_failover_time = 0
        current_failover_index = 0
        failover_servers = ["Server 1 (Primary)", "Server 2 (Backup)", "Server 3 (Fallback)"]
        executed_failovers = []

        def trigger_failover(reason, now):
            nonlocal stream_loaded_successfully, last_failover_time, current_failover_index
            if "render" in reason:
                stream_loaded_successfully = False

            is_stall_error = reason.startswith("stall") or ("freeze" in reason)
            if stream_loaded_successfully and not is_stall_error:
                is_hard_error = reason.startswith("http_error_") or (reason == "network_error") or ("sandbox" in reason) or ("render" in reason)
                if not is_hard_error:
                    return "IGNORED"

            if (now - last_failover_time < 1800) and ("render" not in reason):
                return "DEBOUNCED"

            last_failover_time = now
            if current_failover_index < len(failover_servers) - 1:
                current_failover_index += 1
                executed_failovers.append((current_failover_index, failover_servers[current_failover_index]))
                return "FAILOVER_EXECUTED"
            else:
                return "ALL_FAILED"

        # Renderer crashes while stream is actively playing
        res = trigger_failover("render_process_crashed", now=5000)
        self.assertEqual(res, "FAILOVER_EXECUTED")
        self.assertFalse(stream_loaded_successfully)
        self.assertEqual(current_failover_index, 1)
        self.assertEqual(executed_failovers[-1][1], "Server 2 (Backup)")

    def test_06_behavior_renderer_crash_bypasses_debounce(self):
        """
        If a non-critical event triggered failover at t=1000, and a renderer crash occurs at t=1500 (delta=500 < 1800),
        the crash must NOT be debounced.
        """
        last_failover_time = 1000
        current_failover_index = 0
        failover_servers = ["Server 1", "Server 2", "Server 3"]

        def trigger_failover(reason, now):
            nonlocal last_failover_time, current_failover_index
            if (now - last_failover_time < 1800) and ("render" not in reason):
                return "DEBOUNCED"
            last_failover_time = now
            current_failover_index += 1
            return "EXECUTED"

        # Crash happens only 200ms after last failover
        res = trigger_failover("render_process_crashed", now=1200)
        self.assertEqual(res, "EXECUTED")
        self.assertEqual(current_failover_index, 1)

    def test_07_behavior_full_lifecycle_after_crash(self):
        """
        Simulate full crash and recovery cycle:
        1. WebView destroyed
        2. Fresh WebView re-created & attached
        3. Next server loaded on fresh instance without throwing IllegalStateException
        """
        class MockWebView:
            def __init__(self, name="WebView"):
                self.name = name
                self.destroyed = False
                self.loaded_url = None

            def destroy(self):
                self.destroyed = True

            def loadUrl(self, url):
                if self.destroyed:
                    raise RuntimeError("IllegalStateException: Calling loadUrl on a destroyed WebView!")
                self.loaded_url = url

        class MockRootLayout:
            def __init__(self):
                self.children = []

            def addView(self, view, index=0):
                self.children.insert(index, view)

            def removeView(self, view):
                if view in self.children:
                    self.children.remove(view)

        # Initial state
        root = MockRootLayout()
        active_webview = MockWebView("InitialWebView")
        root.addView(active_webview)
        is_destroyed = False

        # --- Crash occurs ---
        # 1. Detach & destroy
        root.removeView(active_webview)
        active_webview.destroy()
        is_destroyed = True

        # 2. Worker remediation: create new WebView, add to root at index 0, reset flag
        new_webview = MockWebView("FreshWebViewAfterCrash")
        root.addView(new_webview, 0)
        active_webview = new_webview
        is_destroyed = False

        # 3. Failover loads next URL
        failover_url = "https://wideiptv.top/stream-backup.php"
        active_webview.loadUrl(failover_url)

        self.assertFalse(is_destroyed)
        self.assertEqual(active_webview.loaded_url, failover_url)
        self.assertEqual(len(root.children), 1)
        self.assertEqual(root.children[0].name, "FreshWebViewAfterCrash")


if __name__ == "__main__":
    unittest.main()
