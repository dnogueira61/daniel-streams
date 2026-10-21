"""
Tier 2 Boundary & Corner Cases: Cross-Origin Iframe Blocking & Security Boundaries
Tests cross-origin DOM exceptions, subframe scheme blocking, and anti-embed heuristics.
"""

import unittest
import json
from tests.e2e.fixtures.helpers import run_node_script

class TestBoundaryCrossOriginIframe(unittest.TestCase):

    def test_01_cross_origin_dom_access_exception_handling(self):
        """Verify cross-origin contentDocument access throws DOMException and is guarded."""
        js = """
        class MockCrossOriginWindow {
            get document() {
                var err = new Error("Blocked a frame with origin 'https://dlive.sx' from accessing a cross-origin frame.");
                err.name = "SecurityError";
                throw err;
            }
        }

        class MockCrossOriginFrame {
            constructor() {
                this.contentWindow = new MockCrossOriginWindow();
            }
            get contentDocument() {
                return null;
            }
        }

        var frame = new MockCrossOriginFrame();
        var caught = false;
        var errorName = '';

        // Safe access pattern required in WebView injections
        try {
            var doc = frame.contentDocument || frame.contentWindow.document;
            var body = doc.body;
        } catch(e) {
            caught = true;
            errorName = e.name;
        }

        console.log(JSON.stringify({ caught: caught, errorName: errorName }));
        """
        code, stdout, stderr = run_node_script(js)
        self.assertEqual(code, 0, f"Node script failed: {stderr}")
        res = json.loads(stdout.strip())
        self.assertTrue(res["caught"], "Accessing cross-origin frame document must be caught")
        self.assertEqual(res["errorName"], "SecurityError")

    def test_02_subframe_navigation_blocks_unauthorized_external_schemes(self):
        """Verify subframes attempting non-web schemes are blocked."""
        from tests.e2e.tier2_boundary_corner.test_boundary_malformed_urls import simulate_should_override_url_loading

        dangerous_subframe_urls = [
            "intent://open#Intent;scheme=bad;end",
            "market://details?id=com.malware",
            "tel:911",
            "sms:12345",
            "app://launch"
        ]
        for url in dangerous_subframe_urls:
            with self.subTest(url=url):
                blocked = simulate_should_override_url_loading(url, is_main_frame=False)
                self.assertTrue(blocked, f"Subframe with dangerous scheme must be blocked: {url}")

    def test_03_subframe_navigation_blocks_ad_trackers(self):
        """Verify subframe requests targeting ad networks are intercepted."""
        from tests.e2e.tier2_boundary_corner.test_boundary_malformed_urls import simulate_should_override_url_loading

        ad_urls = [
            "https://adsterra.com/direct/banner",
            "https://engine.exoclick.com/subframe",
            "https://popads.net/serve"
        ]
        for url in ad_urls:
            with self.subTest(url=url):
                blocked = simulate_should_override_url_loading(url, is_main_frame=False)
                self.assertTrue(blocked, f"Subframe ad URL must be intercepted: {url}")

    def test_04_subframe_allows_whitelisted_stream_embeds(self):
        """Verify subframe requests targeting legitimate stream engines are allowed."""
        from tests.e2e.tier2_boundary_corner.test_boundary_malformed_urls import simulate_should_override_url_loading

        embed_urls = [
            "https://dembed.st/premiumtv/daddy.php?id=49",
            "https://wideiptv.top/daddy.php?stream=SPT1",
            "https://ehgembxe.cfd/embed/video"
        ]
        for url in embed_urls:
            with self.subTest(url=url):
                blocked = simulate_should_override_url_loading(url, is_main_frame=False)
                self.assertFalse(blocked, f"Legitimate stream subframe must be permitted: {url}")

    def test_05_blob_and_data_uris_in_subframe(self):
        """Verify blob: and data: URIs used by media MSE buffers are permitted."""
        from tests.e2e.tier2_boundary_corner.test_boundary_malformed_urls import simulate_should_override_url_loading

        media_uris = [
            "blob:https://sic.live.impresa.pt/4a3f2e1d-5b6c",
            "data:application/vnd.apple.mpegurl;base64,I0VYVE0zVQ=="
        ]
        for uri in media_uris:
            with self.subTest(uri=uri):
                blocked = simulate_should_override_url_loading(uri, is_main_frame=False)
                self.assertFalse(blocked, f"Media URI must be permitted: {uri}")

    def test_06_anti_embed_sandbox_warning_detector_precision(self):
        """Verify anti-embed text watcher detects true warnings without false-positives."""
        def check_sandbox_warning(text: str) -> bool:
            lower = text.lower()
            return ("remove sandbox" in lower) or ("sandbox atribute" in lower) or ("sandbox attribute" in lower)

        # True positive anti-embed alerts
        self.assertTrue(check_sandbox_warning("Error: Please Remove sandbox attribute to play video"))
        self.assertTrue(check_sandbox_warning("This player requires sandbox atribute neutralization"))

        # False positive candidates (normal text containing subwords)
        self.assertFalse(check_sandbox_warning("Player boxing highlights 2026"))
        self.assertFalse(check_sandbox_warning("Beach volleyball live from sand court"))
        self.assertFalse(check_sandbox_warning("Loading video streams..."))

if __name__ == "__main__":
    unittest.main()
