#!/usr/bin/env python3
"""
tests/adversarial_challenge_m1.py - Challenger 1 Adversarial Harness for Milestone M1
Tests scripts/audit_channels_r1.py against hostile inputs, edge cases, mock network failures,
live network reality checks, and interface contracts.
"""

import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
import threading

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
AUDIT_SCRIPT = os.path.join(PROJECT_ROOT, "scripts", "audit_channels_r1.py")
CHANNELS_JSON = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "channels.json")


class ThreadedAdversarialServer(ThreadingMixIn, HTTPServer):
    """Multi-threaded HTTP server for stress-testing network edge cases."""
    daemon_threads = True


class AdversarialMockHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass # Suppress server console logs

    def do_GET(self):
        path = self.path
        if "/hang" in path:
            # Hang longer than client timeout
            time.sleep(2.5)
            try:
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"Delayed response")
            except Exception:
                pass
        elif "/redirect-302-valid" in path:
            self.send_response(302)
            self.send_header("Location", f"http://{self.server.server_address[0]}:{self.server.server_address[1]}/token-ok")
            self.end_headers()
        elif "/redirect-302-broken-token" in path:
            self.send_response(302)
            self.send_header("Location", f"http://{self.server.server_address[0]}:{self.server.server_address[1]}/token-404")
            self.end_headers()
        elif "/redirect-no-location" in path:
            self.send_response(302)
            self.end_headers()
        elif "/token-ok" in path:
            self.send_response(200)
            self.send_header("Content-Type", "application/x-mpegURL")
            self.end_headers()
            self.wfile.write(b"#EXTM3U\n#EXTINF:10,\nseg1.ts")
        elif "/token-404" in path:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Not Found")
        elif "/500-error" in path:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b"Internal Server Error")
        elif "/cors-test" in path:
            origin = self.headers.get("Origin", "")
            self.send_response(200)
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.end_headers()
            self.wfile.write(b"#EXTM3U\n#EXTINF:-1,\nstream.ts")
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")


class TestAdversarialM1(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.mock_server = ThreadedAdversarialServer(("127.0.0.1", 0), AdversarialMockHandler)
        cls.mock_port = cls.mock_server.server_address[1]
        cls.server_thread = threading.Thread(target=cls.mock_server.serve_forever, daemon=True)
        cls.server_thread.start()
        cls.base_mock_url = f"http://127.0.0.1:{cls.mock_port}"

    @classmethod
    def tearDownClass(cls):
        cls.mock_server.shutdown()
        cls.mock_server.server_close()

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="adv_m1_")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    # =========================================================================
    # Group 1: CLI and Argument Parsing Edge Cases
    # =========================================================================

    def test_cli_01_nonexistent_channels_file(self):
        """Passing non-existent channels.json must exit with code 1 and clean error."""
        nonexistent = os.path.join(self.temp_dir, "ghost.json")
        cmd = [sys.executable, AUDIT_SCRIPT, "--channels-file", nonexistent]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("channels.json not found", proc.stderr)

    def test_cli_02_invalid_flag(self):
        """Passing unrecognized flag must exit with argparse error code 2."""
        cmd = [sys.executable, AUDIT_SCRIPT, "--invalid-adversarial-flag"]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("unrecognized arguments", proc.stderr)

    def test_cli_03_nested_nonexistent_output_directory(self):
        """Outputting to deeply nested non-existent directory must create parents safely."""
        deep_json = os.path.join(self.temp_dir, "a", "b", "c", "audit.json")
        deep_md = os.path.join(self.temp_dir, "x", "y", "z", "audit.md")
        sample_catalog = os.path.join(self.temp_dir, "sample.json")
        with open(sample_catalog, "w", encoding="utf-8") as f:
            json.dump([{"id": "test1", "name": "Test", "backupStreamUrl": f"{self.base_mock_url}/token-ok"}], f)

        cmd = [
            sys.executable, AUDIT_SCRIPT,
            "--channels-file", sample_catalog,
            "--json-output", deep_json,
            "--md-output", deep_md,
            "--workers", "2",
            "--timeout", "2.0"
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f"Failed with: {proc.stderr}")
        self.assertTrue(os.path.exists(deep_json))
        self.assertTrue(os.path.exists(deep_md))

    def test_cli_04_limit_flag(self):
        """Testing --limit 2 limits execution to exactly 2 channels."""
        out_json = os.path.join(self.temp_dir, "limit.json")
        out_md = os.path.join(self.temp_dir, "limit.md")
        cmd = [
            sys.executable, AUDIT_SCRIPT,
            "--limit", "2",
            "--json-output", out_json,
            "--md-output", out_md,
            "--workers", "2",
            "--timeout", "2.0"
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f"Error: {proc.stderr}")
        with open(out_json, "r", encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["total_channels"], 2)
        self.assertEqual(len(data["channels"]), 2)

    # =========================================================================
    # Group 2: Malformed Catalog Entries & Boundary Cases
    # =========================================================================

    def test_catalog_01_empty_catalog_zero_division_behavior(self):
        """Adversarial probe: Catalog with 0 channels `[]` triggers ZeroDivisionError in unpatched script."""
        empty_cat = os.path.join(self.temp_dir, "empty.json")
        with open(empty_cat, "w", encoding="utf-8") as f:
            json.dump([], f)

        out_json = os.path.join(self.temp_dir, "empty_out.json")
        out_md = os.path.join(self.temp_dir, "empty_out.md")
        cmd = [
            sys.executable, AUDIT_SCRIPT,
            "--channels-file", empty_cat,
            "--json-output", out_json,
            "--md-output", out_md
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        # We document the empirical vulnerability found
        if proc.returncode != 0:
            self.assertIn("ZeroDivisionError", proc.stderr, "Empty catalog triggers ZeroDivisionError at line 785")

    def test_catalog_02_corrupted_json_syntax(self):
        """Corrupted JSON syntax must exit with non-zero exit code."""
        corrupt_file = os.path.join(self.temp_dir, "corrupt.json")
        with open(corrupt_file, "w", encoding="utf-8") as f:
            f.write("{this is not valid json!;;]")

        cmd = [sys.executable, AUDIT_SCRIPT, "--channels-file", corrupt_file]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertNotEqual(proc.returncode, 0)

    def test_catalog_03_malformed_channel_types(self):
        """Channel objects with missing fields, non-string URLs, null IDs, and unicode names."""
        adversarial_channels = [
            {"id": "adv_missing_all"}, # Bare minimal
            {"id": None, "name": "Null ID", "backupStreamUrl": f"{self.base_mock_url}/token-ok"}, # Null ID
            {"id": 9999, "name": "Int ID", "backupStreamUrl": f"{self.base_mock_url}/token-ok"}, # Integer ID
            {"id": "adv_unicode", "name": "Canal Português (çãéú) ⚽", "country": "PT", "backupStreamUrl": f"{self.base_mock_url}/token-ok"},
            {"id": "adv_special_chars", "name": "Table | Pipe | Breaker <script>", "backupStreamUrl": f"{self.base_mock_url}/token-ok"},
            {"id": "adv_relative_url", "name": "Relative URL", "backupStreamUrl": "/relative/path.m3u8"},
            {"id": "adv_nxdomain", "name": "NXDomain", "backupStreamUrl": "http://nxdomain-never-exists-123456789.test/stream.m3u8"}
        ]
        adv_file = os.path.join(self.temp_dir, "adversarial.json")
        with open(adv_file, "w", encoding="utf-8") as f:
            json.dump(adversarial_channels, f, ensure_ascii=False)

        out_json = os.path.join(self.temp_dir, "adv_out.json")
        out_md = os.path.join(self.temp_dir, "adv_out.md")

        cmd = [
            sys.executable, AUDIT_SCRIPT,
            "--channels-file", adv_file,
            "--json-output", out_json,
            "--md-output", out_md,
            "--workers", "4",
            "--timeout", "1.5"
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f"Adversarial channels crashed script: {proc.stderr}")

        with open(out_json, "r", encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["total_channels"], len(adversarial_channels))

        # Check that NXDomain was marked OFFLINE without crashing
        nx_ch = next(c for c in data["channels"] if c["id"] == "adv_nxdomain")
        self.assertEqual(nx_ch["overall_status"], "CRITICAL")

        # Check unicode and pipe characters in MD
        with open(out_md, "r", encoding="utf-8") as f:
            md_text = f.read()
        self.assertIn("Canal Português (çãéú) ⚽", md_text)

    # =========================================================================
    # Group 3: Network Mock Failure Modes (Timeouts, HTTP Errors, Redirects)
    # =========================================================================

    def test_network_01_timeout_handling(self):
        """Endpoints that hang past timeout must be aborted cleanly and recorded as OFFLINE."""
        timeout_channels = [
            {"id": "hang1", "name": "Hanging Channel 1", "backupStreamUrl": f"{self.base_mock_url}/hang"},
            {"id": "hang2", "name": "Hanging Channel 2", "backupStreamUrl": f"{self.base_mock_url}/hang"}
        ]
        hang_file = os.path.join(self.temp_dir, "hang.json")
        with open(hang_file, "w", encoding="utf-8") as f:
            json.dump(timeout_channels, f)

        out_json = os.path.join(self.temp_dir, "hang_out.json")
        out_md = os.path.join(self.temp_dir, "hang_out.md")
        t0 = time.time()
        cmd = [
            sys.executable, AUDIT_SCRIPT,
            "--channels-file", hang_file,
            "--json-output", out_json,
            "--md-output", out_md,
            "--workers", "2",
            "--timeout", "1.0"
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        elapsed = time.time() - t0

        self.assertEqual(proc.returncode, 0, f"Timeout test failed: {proc.stderr}")
        self.assertLess(elapsed, 4.0, f"Script hung beyond expected timeout: {elapsed}s")

        with open(out_json, "r", encoding="utf-8") as f:
            data = json.load(f)
        for ch in data["channels"]:
            self.assertEqual(ch["overall_status"], "CRITICAL")
            self.assertEqual(ch["primary_status"], "OFFLINE")

    def test_network_02_http_500_handling(self):
        """Endpoints returning HTTP 500 must be recorded as OFFLINE with error code."""
        error_channels = [
            {"id": "err500", "name": "500 Channel", "backupStreamUrl": f"{self.base_mock_url}/500-error"}
        ]
        err_file = os.path.join(self.temp_dir, "err.json")
        with open(err_file, "w", encoding="utf-8") as f:
            json.dump(error_channels, f)

        out_json = os.path.join(self.temp_dir, "err_out.json")
        out_md = os.path.join(self.temp_dir, "err_out.md")
        cmd = [
            sys.executable, AUDIT_SCRIPT,
            "--channels-file", err_file,
            "--json-output", out_json,
            "--md-output", out_md,
            "--timeout", "1.0"
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0)
        with open(out_json, "r", encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["channels"][0]["primary_status"], "OFFLINE")
        self.assertEqual(data["channels"][0]["endpoints"][0]["http_code"], 500)

    def test_network_03_timstreams_redirect_handling(self):
        """Test TimStreams redirect permutations: valid redirect, broken token, missing location."""
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "scripts"))
        import audit_channels_r1 as audit_mod

        # Valid 302
        res_valid = audit_mod.test_timstreams_endpoint(f"{self.base_mock_url}/redirect-302-valid", timeout=2.0)
        self.assertEqual(res_valid["status"], "ONLINE")
        self.assertEqual(res_valid["http_code"], 302)
        self.assertEqual(res_valid["token_status"], 200)

        # 302 with broken token destination (404)
        res_broken = audit_mod.test_timstreams_endpoint(f"{self.base_mock_url}/redirect-302-broken-token", timeout=2.0)
        self.assertEqual(res_broken["status"], "OFFLINE")

        # 302 without Location header
        res_no_loc = audit_mod.test_timstreams_endpoint(f"{self.base_mock_url}/redirect-no-location", timeout=2.0)
        self.assertEqual(res_no_loc["status"], "OFFLINE")

        # Mirror normalization
        norm1 = audit_mod.normalize_timst_url("https://grandemx.org/abc-123")
        self.assertEqual(norm1, "https://ehgembxe.cfd/abc-123")
        norm2 = audit_mod.normalize_timst_url("https://exmxbxe.cfd/xyz-456")
        self.assertEqual(norm2, "https://ehgembxe.cfd/xyz-456")

    # =========================================================================
    # Group 4: Live Host Reality Verification (Empirical Checks against Real World)
    # =========================================================================

    def test_live_01_timstreams_live_redirect_and_token(self):
        """Verify TimStreams live server ehgembxe.cfd returns HTTP 302 and valid JWT token."""
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "scripts"))
        import audit_channels_r1 as audit_mod

        # Real channel from channels.json: Sport TV 1 (id 49)
        real_url = "https://ehgembxe.cfd/oqq5c86g-7521"
        res = audit_mod.test_timstreams_endpoint(real_url, timeout=5.0)
        self.assertIn(res["status"], ["ONLINE", "OFFLINE"])
        self.assertEqual(res["http_code"], 302)
        self.assertIn("/play/", res.get("token_url", ""))
        self.assertEqual(res["status"], "ONLINE", f"TimStreams live token should be ONLINE, got: {res}")

    def test_live_02_daddylive_upstreams_live(self):
        """Verify DaddyLive upstream CDNs dembed.st and wideiptv.top are genuinely responsive."""
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "scripts"))
        import audit_channels_r1 as audit_mod

        # Channel 49 (Sport TV 1)
        res_p1 = audit_mod.test_daddylive_player1("49", timeout=5.0)
        res_p2 = audit_mod.test_daddylive_player2("49", timeout=5.0)

        # Upstream status must be 200 for at least one or both
        self.assertIsNotNone(res_p1.get("upstream_status"))
        self.assertEqual(res_p1["upstream_status"], 200, f"dembed.st upstream for ch 49 must return 200, got: {res_p1}")
        self.assertEqual(res_p2["upstream_status"], 200, f"wideiptv.top upstream for ch 49 must return 200, got: {res_p2}")

    def test_live_03_epicsports_live(self):
        """Verify EpicSports live endpoint responds HTTP 200."""
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "scripts"))
        import audit_channels_r1 as audit_mod

        res = audit_mod.test_epicsports_endpoint("https://epicsports-tv.com/eu.html?id=1", timeout=5.0)
        self.assertEqual(res["status"], "ONLINE")
        self.assertEqual(res["http_code"], 200)

    def test_live_04_official_portuguese_m3u8_live(self):
        """Verify official SIC & Canal 11 live endpoints return HTTP 200 with proper CORS."""
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "scripts"))
        import audit_channels_r1 as audit_mod

        sic_res = audit_mod.test_m3u8_endpoint("https://sic.live.impresa.pt/sic1080p.m3u8", channel_name="SIC HD", timeout=5.0)
        self.assertEqual(sic_res["status"], "ONLINE")
        self.assertEqual(sic_res["http_code"], 200)
        self.assertTrue(sic_res.get("cors") in ["https://sic.pt", "*"])

        c11_res = audit_mod.test_m3u8_endpoint("https://dy4isoelz3s0x.cloudfront.net/0_hd.m3u8", channel_name="Canal 11", timeout=5.0)
        self.assertEqual(c11_res["status"], "ONLINE")
        self.assertEqual(c11_res["http_code"], 200)

    def test_live_05_dead_mirror_grandemx_is_unresolvable(self):
        """Empirically confirm grandemx.org cannot be resolved (dead mirror)."""
        is_dead = False
        try:
            socket.gethostbyname("grandemx.org")
        except socket.gaierror:
            is_dead = True
        except Exception:
            is_dead = True
        self.assertTrue(is_dead, "grandemx.org must fail resolution")

    # =========================================================================
    # Group 5: Interface Contract & Schema Conformance
    # =========================================================================

    def test_schema_01_existing_report_matches_contract(self):
        """Verify build/reports/stream_audit_report.json conforms to PROJECT.md interface contract."""
        report_file = os.path.join(PROJECT_ROOT, "build", "reports", "stream_audit_report.json")
        self.assertTrue(os.path.exists(report_file), "build/reports/stream_audit_report.json must exist")

        with open(report_file, "r", encoding="utf-8") as f:
            data = json.load(f)

        self.assertEqual(data["total_channels"], 733)
        self.assertEqual(data["total_endpoints"], 1535)
        self.assertIn("status_summary", data)
        self.assertIn("ONLINE", data["status_summary"])
        self.assertIn("DEGRADED", data["status_summary"])
        self.assertIn("CRITICAL", data["status_summary"])

        summary = data["status_summary"]
        self.assertEqual(summary["ONLINE"] + summary["DEGRADED"] + summary["CRITICAL"], 733)

        # Check provider summary
        prov = data.get("provider_summary", {})
        self.assertIn("daddylive_p1", prov)
        self.assertIn("daddylive_p2", prov)
        self.assertIn("timstreams", prov)
        self.assertIn("epicsports", prov)
        self.assertIn("official_m3u8", prov)

        # Check channel list schema
        channels = data.get("channels", [])
        self.assertEqual(len(channels), 733)
        for ch in channels:
            self.assertIn("id", ch)
            self.assertIn("name", ch)
            self.assertIn("category", ch)
            self.assertIn("primary_status", ch)
            self.assertIn("fallback1_status", ch)
            self.assertIn("fallback2_status", ch)
            self.assertIn("overall_status", ch)
            self.assertIn(ch["overall_status"], ["ONLINE", "DEGRADED", "CRITICAL"])


if __name__ == "__main__":
    unittest.main()
