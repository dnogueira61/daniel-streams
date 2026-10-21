"""
Tier 1 Feature Coverage: Stream Audit & Catalog Census (Requirement R1)
Validates channel census, upstream stream servers, redirect contracts, and CORS headers.
"""

import unittest
import json
import socket
from tests.e2e.fixtures.helpers import (
    load_master_channels,
    probe_http_endpoint,
    CHANNELS_JSON_PATH
)

class TestFeatureStreamAudit(unittest.TestCase):

    def setUp(self):
        self.channels = load_master_channels()

    def test_01_catalog_census_completeness_and_schema(self):
        """Verify channels.json has 733 channels, correct schema, and PT channel count."""
        self.assertEqual(len(self.channels), 733, "Catalog must contain exactly 733 channels")
        
        numeric_count = 0
        non_numeric_count = 0
        pt_count = 0
        
        for ch in self.channels:
            self.assertIn("id", ch, "Every channel must have an 'id'")
            self.assertIn("name", ch, "Every channel must have a 'name'")
            self.assertIn("category", ch, "Every channel must have a 'category'")
            self.assertTrue(len(str(ch["name"]).strip()) > 0, f"Channel {ch['id']} has empty name")
            
            ch_id_str = str(ch["id"])
            if ch_id_str.isdigit():
                numeric_count += 1
            else:
                non_numeric_count += 1
                
            if ch.get("isPt") is True or ch.get("country") == "PT":
                pt_count += 1

        self.assertEqual(numeric_count, 700, "Should have exactly 700 numeric ID channels (DaddyLive)")
        self.assertEqual(non_numeric_count, 33, "Should have exactly 33 non-numeric slug channels")
        self.assertEqual(pt_count, 40, "Should have exactly 40 Portuguese channels")

    def test_02_daddylive_player1_endpoint_generation_and_reachability(self):
        """Verify DaddyLive Player 1 endpoint synthesis and upstream dembed.st reachability."""
        channel_id = "49"
        player1_url = f"https://dlive.sx/stream/stream-{channel_id}.php"
        upstream_url = f"https://dembed.st/premiumtv/daddy.php?id={channel_id}"
        headers = {"Referer": "https://dlive.sx/"}
        
        status, resp_headers, body = probe_http_endpoint(player1_url, headers=headers, timeout=3.0)
        if status != 200:
            # Fallback probe to upstream embedded CDN engine
            status, resp_headers, body = probe_http_endpoint(upstream_url, headers=headers, timeout=5.0)

        self.assertEqual(status, 200, f"DaddyLive Player 1 or upstream dembed.st must return HTTP 200 for channel {channel_id}")
        self.assertTrue(len(body) > 0, "Response body must contain player container markup")

    def test_03_daddylive_player2_endpoint_generation_and_reachability(self):
        """Verify DaddyLive Player 2 endpoint synthesis and upstream wideiptv.top reachability."""
        channel_id = "49"
        player2_url = f"https://dlive.sx/player/stream-{channel_id}.php"
        upstream_url = f"https://wideiptv.top/daddy.php?stream={channel_id}"
        headers = {"Referer": "https://dlive.sx/"}
        
        status, resp_headers, body = probe_http_endpoint(player2_url, headers=headers, timeout=3.0)
        if status != 200:
            # Fallback probe to upstream embedded CDN engine
            status, resp_headers, body = probe_http_endpoint(upstream_url, headers=headers, timeout=5.0)

        self.assertEqual(status, 200, f"DaddyLive Player 2 or upstream wideiptv.top must return HTTP 200 for channel {channel_id}")
        self.assertTrue(len(body) > 0, "Response body must contain player container markup")

    def test_04_timstreams_domain_and_302_redirect_contract(self):
        """Verify TimStreams 1080p active domain (ehgembxe.cfd) returns HTTP 302 with JWT Location."""
        target_url = "https://ehgembxe.cfd/oqq5c86g-7521"
        headers = {"Referer": "https://timst.top/"}
        
        status, resp_headers, body = probe_http_endpoint(
            target_url, headers=headers, timeout=6.0, allow_redirects=False
        )
        self.assertEqual(status, 302, f"TimStreams endpoint must return HTTP 302 redirect, got {status}")
        location = resp_headers.get("location", "")
        self.assertTrue(
            "/play/" in location and len(location) > 50,
            f"TimStreams 302 Location header must contain JWT play token, got '{location}'"
        )

    def test_05_official_portuguese_m3u8_cors_and_referer(self):
        """Verify official Portuguese M3U8 streams (SIC, Canal 11) enforce CORS and return valid playlists."""
        # Test SIC
        sic_url = "https://sic.live.impresa.pt/sic1080p.m3u8"
        sic_headers = {"Origin": "https://sic.pt", "Referer": "https://sic.pt/"}
        sic_status, sic_res_headers, sic_body = probe_http_endpoint(sic_url, headers=sic_headers, timeout=6.0)
        self.assertEqual(sic_status, 200, f"SIC official M3U8 must return HTTP 200 with proper headers, got {sic_status}")
        cors_origin = sic_res_headers.get("access-control-allow-origin", "")
        self.assertTrue(
            cors_origin in ["https://sic.pt", "*"],
            f"SIC M3U8 must have Access-Control-Allow-Origin matching sic.pt, got '{cors_origin}'"
        )
        self.assertTrue(b"#EXTM3U" in sic_body, "SIC response body must start with #EXTM3U playlist tag")

        # Test Canal 11
        c11_url = "https://dy4isoelz3s0x.cloudfront.net/0_hd.m3u8"
        c11_headers = {"Origin": "https://canal11.pt", "Referer": "https://canal11.pt/"}
        c11_status, c11_res_headers, c11_body = probe_http_endpoint(c11_url, headers=c11_headers, timeout=6.0)
        self.assertEqual(c11_status, 200, f"Canal 11 official M3U8 must return HTTP 200, got {c11_status}")
        self.assertTrue(b"#EXTM3U" in c11_body, "Canal 11 response body must start with #EXTM3U tag")

    def test_06_dead_mirror_grandemx_detection(self):
        """Verify grandemx.org is dead/unresolvable, confirming requirement to purge it."""
        is_dead = False
        try:
            socket.gethostbyname("grandemx.org")
        except socket.gaierror:
            is_dead = True
        except Exception:
            is_dead = True
        self.assertTrue(is_dead, "grandemx.org must fail DNS resolution (dead mirror to be purged)")

if __name__ == "__main__":
    unittest.main()
