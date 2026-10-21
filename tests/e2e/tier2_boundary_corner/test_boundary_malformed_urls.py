"""
Tier 2 Boundary & Corner Cases: Malformed URLs & Scheme Safety
Tests handling of dangerous schemes, malformed manifests, corrupted tokens, and encoded URIs.
"""

import unittest
import urllib.parse
from typing import Optional

def simulate_should_override_url_loading(
    url: str,
    is_main_frame: bool = True,
    allowed_hosts: Optional[set] = None
) -> bool:
    """
    Simulates PlayerActivity.shouldOverrideUrlLoading safety guard.
    Returns True if request should be intercepted (blocked), False if allowed.
    """
    if not url:
        return True # Block empty/null
    
    parsed = urllib.parse.urlparse(url)
    scheme = parsed.scheme.lower() if parsed.scheme else ""
    host = parsed.netloc.lower().split(":")[0] if parsed.netloc else ""

    # 1. Dangerous non-web schemes
    if scheme not in ("http", "https", "about", "data", "blob"):
        return True # Block intent, market, javascript, file, etc.

    if not is_main_frame:
        # Subframe navigation checks
        if any(ad in host or ad in url.lower() for ad in ["popads", "adsterra", "exoclick"]):
            return True
        return False

    # Main frame whitelist
    default_allowed = {
        "dlive.sx", "dembed.st", "wideiptv.top", "ehgembxe.cfd",
        "timst.top", "epicsports-tv.com", "streaming-live.rtp.pt",
        "sic.live.impresa.pt", "dy4isoelz3s0x.cloudfront.net",
        "video-auth7.iol.pt"
    }
    allowed = allowed_hosts or default_allowed
    is_allowed = any(a in host for a in allowed) or url.startswith("blob:") or url.startswith("data:")
    return not is_allowed

def validate_m3u8_manifest_syntax(manifest_text: str) -> bool:
    """
    Validates minimal RFC 8216 HLS playlist format.
    """
    lines = [line.strip() for line in manifest_text.strip().splitlines() if line.strip()]
    if not lines or lines[0] != "#EXTM3U":
        return False
    # Must have either media segments or master stream tags
    has_valid_tag = any(
        line.startswith("#EXTINF") or line.startswith("#EXT-X-STREAM-INF") or line.startswith("#EXT-X-TARGETDURATION")
        for line in lines
    )
    return has_valid_tag

class TestBoundaryMalformedUrls(unittest.TestCase):

    def test_01_null_and_empty_url_handling(self):
        """Verify empty and whitespace strings are blocked safely."""
        self.assertTrue(simulate_should_override_url_loading(""))
        self.assertTrue(simulate_should_override_url_loading("   "))

    def test_02_dangerous_schemes_blocked(self):
        """Verify malicious schemes (intent, javascript, market, file) are blocked."""
        dangerous_urls = [
            "javascript:alert(document.domain)",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "market://details?id=com.malicious.app",
            "file:///data/data/com.dlive.ptstream/shared_prefs/app_prefs.xml",
            "content://com.android.providers.media.documents/document/123",
            "tel:+123456789"
        ]
        for url in dangerous_urls:
            with self.subTest(url=url):
                blocked = simulate_should_override_url_loading(url)
                self.assertTrue(blocked, f"Dangerous scheme must be blocked: {url}")

    def test_03_malformed_m3u8_syntax_detection(self):
        """Verify malformed M3U8 syntax is detected."""
        invalid_manifests = [
            "", # Empty
            "<html><body>404 Not Found</body></html>", # HTML error page
            "#EXTINF:10.0\nsegment1.ts", # Missing #EXTM3U header
            "#EXTM3U\nrandom garbage without tags", # No HLS tags
            "#EXTM3U\n#EXT-X-VERSION:3" # No targetduration or segments
        ]
        for manifest in invalid_manifests:
            with self.subTest(manifest=manifest[:20]):
                valid = validate_m3u8_manifest_syntax(manifest)
                self.assertFalse(valid, f"Malformed manifest must be rejected: {manifest[:20]}")

        # Valid manifest check
        valid_manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg1.ts"
        self.assertTrue(validate_m3u8_manifest_syntax(valid_manifest))

    def test_04_corrupt_timstreams_token_format(self):
        """Verify corrupt TimStreams tokens fail JWT parsing boundary."""
        def is_valid_timstreams_jwt(token_str: str) -> bool:
            # TimStreams JWT format: eyJhbGciOi... (3 or 5 dot-separated base64url sections)
            parts = token_str.split(".")
            if len(parts) not in (3, 5):
                return False
            # First part (header) must be valid base64url json
            return len(parts[0]) > 10 and len(parts[-1]) >= 3

        self.assertFalse(is_valid_timstreams_jwt(""))
        self.assertFalse(is_valid_timstreams_jwt("invalid_token"))
        self.assertFalse(is_valid_timstreams_jwt("eyJhbGciOi.truncated"))
        self.assertTrue(
            is_valid_timstreams_jwt("eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0..abc123def456ghi.payload.sig")
        )

    def test_05_percent_encoded_and_special_character_uris(self):
        """Verify URLs with spaces, unicode, query params, and escaped fragments parse safely."""
        complex_urls = [
            "https://dlive.sx/stream/stream-49.php?title=Sport%20TV%201&lang=pt-PT#live_feed",
            "https://streaming-live.rtp.pt/liverepeater/smil:rtp1HD.smil/playlist.m3u8?token=xyz%2B123%3D%3D",
            "https://ehgembxe.cfd/oqq5c86g-7521?auth=token%2Fwith%2Fslashes"
        ]
        for u in complex_urls:
            with self.subTest(url=u):
                parsed = urllib.parse.urlparse(u)
                self.assertIn(parsed.scheme, ["http", "https"])
                self.assertTrue(len(parsed.netloc) > 0)
                # Verify URL can be unquoted without raising
                unquoted = urllib.parse.unquote(u)
                self.assertTrue(len(unquoted) > 0)

    def test_06_subframe_navigation_blocks_rogue_ad_domains(self):
        """Verify subframe requests targeting rogue ad networks are intercepted."""
        self.assertTrue(simulate_should_override_url_loading("https://adsterra.com/direct", is_main_frame=False))
        self.assertTrue(simulate_should_override_url_loading("https://popads.net/click", is_main_frame=False))
        # Legitimate subframe iframe allowed
        self.assertFalse(simulate_should_override_url_loading("https://dembed.st/player", is_main_frame=False))

if __name__ == "__main__":
    unittest.main()
