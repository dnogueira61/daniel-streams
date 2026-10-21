#!/usr/bin/env python3
"""
scripts/audit_channels_r1.py - Daniel Streams High-Speed Stream Audit Tool
Requirement R1 Implementation for Milestone M1

Performs a 100% census and connectivity validation of all channels and endpoints
in app/src/main/assets/channels.json using multi-threaded execution (25 workers).

Audits:
1. DaddyLive Player 1 (dlive.sx/stream/) & upstream iframe (dembed.st)
2. DaddyLive Player 2 (dlive.sx/player/) & upstream iframe (wideiptv.top)
3. TimStreams 1080p (ehgembxe.cfd) HTTP 302 redirect & JWT token validation
4. EpicSports (epicsports-tv.com) with Referer validation
5. Official Portuguese M3U8s (RTP, SIC, Canal 11, Porto Canal, ARTV, TVI/CNN) with CORS headers

Outputs:
- build/reports/stream_audit_report.json
- build/reports/stream_audit_report.md
"""

import argparse
import concurrent.futures
import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

# Permissive SSL context for stream endpoint testing
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Custom handler that prevents automatic redirect following to inspect 301/302 headers."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


NO_REDIRECT_OPENER = urllib.request.build_opener(NoRedirectHandler)


def normalize_timst_url(url: str) -> str:
    """Sanitizes dead/obsolete TimStreams mirrors (grandemx.org, exmxbxe.cfd) to active ehgembxe.cfd."""
    if not url:
        return ""
    u = url.strip()
    u = u.replace("https://grandemx.org", "https://ehgembxe.cfd").replace("http://grandemx.org", "https://ehgembxe.cfd")
    u = u.replace("https://exmxbxe.cfd", "https://ehgembxe.cfd").replace("http://exmxbxe.cfd", "https://ehgembxe.cfd")
    return u


def test_daddylive_player1(channel_id: str, timeout: float = 5.0) -> Dict[str, Any]:
    """
    Validates DaddyLive Player 1:
    1. Outer player: https://dlive.sx/stream/stream-{id}.php
    2. Upstream iframe: https://dembed.st/premiumtv/daddy.php?id={id}
    """
    outer_url = f"https://dlive.sx/stream/stream-{channel_id}.php"
    start_t = time.perf_counter()
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": "https://dlive.sx/",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }

    upstream_url = f"https://dembed.st/premiumtv/daddy.php?id={channel_id}"
    outer_code = None
    outer_error = None

    try:
        req = urllib.request.Request(outer_url, headers=headers)
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as resp:
            outer_code = resp.status
            body = resp.read().decode("utf-8", errors="ignore")
            iframe_match = re.search(r'<iframe[^>]*src=["\']([^"\']+)["\']', body, re.IGNORECASE)
            if iframe_match:
                cand = iframe_match.group(1).strip()
                if cand.startswith("//"):
                    cand = "https:" + cand
                if cand.startswith("http"):
                    upstream_url = cand
    except urllib.error.HTTPError as e:
        outer_code = e.code
        outer_error = f"Outer HTTPError {e.code}"
    except Exception as e:
        outer_error = f"Outer error: {e}"

    # Always validate upstream CDN player
    upstream_status = None
    upstream_error = None
    try:
        req_up = urllib.request.Request(upstream_url, headers=headers)
        with urllib.request.urlopen(req_up, context=SSL_CTX, timeout=timeout) as resp_up:
            upstream_status = resp_up.status
    except urllib.error.HTTPError as e:
        upstream_status = e.code
        upstream_error = f"Upstream HTTPError {e.code}"
    except Exception as e:
        upstream_error = f"Upstream error: {e}"

    latency = round((time.perf_counter() - start_t) * 1000, 1)
    is_ok = (outer_code == 200 and upstream_status == 200)

    res = {
        "type": "daddylive_p1",
        "url": outer_url,
        "upstream_url": upstream_url,
        "status": "ONLINE" if is_ok else "OFFLINE",
        "http_code": outer_code,
        "upstream_status": upstream_status,
        "latency_ms": latency
    }
    if not is_ok:
        res["error"] = outer_error or upstream_error or f"outer={outer_code}, upstream={upstream_status}"
    return res


def test_daddylive_player2(channel_id: str, timeout: float = 5.0) -> Dict[str, Any]:
    """
    Validates DaddyLive Player 2:
    1. Outer player: https://dlive.sx/player/stream-{id}.php
    2. Upstream iframe: typically https://wideiptv.top/daddy.php?stream={streamKey}
    """
    outer_url = f"https://dlive.sx/player/stream-{channel_id}.php"
    start_t = time.perf_counter()
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": "https://dlive.sx/",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }

    upstream_url = f"https://wideiptv.top/daddy.php?stream={channel_id}"
    outer_code = None
    outer_error = None

    try:
        req = urllib.request.Request(outer_url, headers=headers)
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as resp:
            outer_code = resp.status
            body = resp.read().decode("utf-8", errors="ignore")
            iframe_match = re.search(r'<iframe[^>]*src=["\']([^"\']+)["\']', body, re.IGNORECASE)
            if iframe_match:
                cand = iframe_match.group(1).strip()
                if cand.startswith("//"):
                    cand = "https:" + cand
                if cand.startswith("http"):
                    upstream_url = cand
    except urllib.error.HTTPError as e:
        outer_code = e.code
        outer_error = f"Outer HTTPError {e.code}"
    except Exception as e:
        outer_error = f"Outer error: {e}"

    # Always validate upstream CDN player
    upstream_status = None
    upstream_error = None
    try:
        req_up = urllib.request.Request(upstream_url, headers=headers)
        with urllib.request.urlopen(req_up, context=SSL_CTX, timeout=timeout) as resp_up:
            upstream_status = resp_up.status
    except urllib.error.HTTPError as e:
        upstream_status = e.code
        upstream_error = f"Upstream HTTPError {e.code}"
    except Exception as e:
        upstream_error = f"Upstream error: {e}"

    latency = round((time.perf_counter() - start_t) * 1000, 1)
    is_ok = (outer_code == 200 and upstream_status == 200)

    res = {
        "type": "daddylive_p2",
        "url": outer_url,
        "upstream_url": upstream_url,
        "status": "ONLINE" if is_ok else "OFFLINE",
        "http_code": outer_code,
        "upstream_status": upstream_status,
        "latency_ms": latency
    }
    if not is_ok:
        res["error"] = outer_error or upstream_error or f"outer={outer_code}, upstream={upstream_status}"
    return res


def test_timstreams_endpoint(raw_url: str, timeout: float = 5.0) -> Dict[str, Any]:
    """
    Validates TimStreams 1080p:
    1. Checks HTTP 302 redirect from https://ehgembxe.cfd/{hash}-{id} with Referer: https://timst.top/
    2. Validates generated JWT token location URL returns HTTP 200
    """
    url = normalize_timst_url(raw_url)
    start_t = time.perf_counter()
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": "https://timst.top/"
    }

    try:
        req = urllib.request.Request(url, headers=headers, method="GET")
        location = None
        code = None

        try:
            with NO_REDIRECT_OPENER.open(req, timeout=timeout) as resp:
                code = resp.status
                location = resp.headers.get("Location")
        except urllib.error.HTTPError as e:
            code = e.code
            location = e.headers.get("Location")

        if code not in (301, 302, 307, 308) or not location:
            return {
                "type": "timstreams",
                "url": url,
                "status": "OFFLINE",
                "http_code": code,
                "error": f"Expected 302 redirect, received {code} (Location: {location})",
                "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
            }

        # Resolve location token URL
        token_url = urllib.parse.urljoin(url, location)
        req_token = urllib.request.Request(token_url, headers=headers)
        with urllib.request.urlopen(req_token, context=SSL_CTX, timeout=timeout) as token_resp:
            token_status = token_resp.status

        latency = round((time.perf_counter() - start_t) * 1000, 1)
        is_ok = (token_status == 200)

        return {
            "type": "timstreams",
            "url": url,
            "token_url": token_url[:100] + "...",
            "status": "ONLINE" if is_ok else "OFFLINE",
            "http_code": code,
            "token_status": token_status,
            "latency_ms": latency
        }
    except Exception as e:
        return {
            "type": "timstreams",
            "url": url,
            "status": "OFFLINE",
            "error": str(e),
            "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
        }


def test_epicsports_endpoint(url: str, timeout: float = 5.0) -> Dict[str, Any]:
    """Validates EpicSports stream endpoint with mandatory Referer header."""
    start_t = time.perf_counter()
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": "https://epicsports-tv.com/",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }

    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as resp:
            code = resp.status

        latency = round((time.perf_counter() - start_t) * 1000, 1)
        return {
            "type": "epicsports",
            "url": url,
            "status": "ONLINE" if code == 200 else "OFFLINE",
            "http_code": code,
            "latency_ms": latency
        }
    except urllib.error.HTTPError as e:
        return {
            "type": "epicsports",
            "url": url,
            "status": "OFFLINE",
            "http_code": e.code,
            "error": f"HTTPError {e.code}",
            "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
        }
    except Exception as e:
        return {
            "type": "epicsports",
            "url": url,
            "status": "OFFLINE",
            "error": str(e),
            "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
        }


def test_m3u8_endpoint(url: str, channel_name: str = "", timeout: float = 5.0) -> Dict[str, Any]:
    """
    Validates Official Portuguese and generic M3U8 direct streams:
    - RTP: Referer https://www.rtp.pt/
    - SIC: Origin & Referer https://sic.pt/
    - Canal 11: Origin & Referer https://canal11.pt/
    - Porto Canal: Referer https://portocanal.sapo.pt/
    - TVI / CNN: Referer https://tviplayer.iol.pt/
    - ARTV / Cloudfront / Fastly
    """
    start_t = time.perf_counter()
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*"
    }

    # Domain / Channel specific CORS and Referer headers
    u_lower = url.lower()
    if "rtp.pt" in u_lower:
        headers["Referer"] = "https://www.rtp.pt/"
    elif "sic" in u_lower or "impresa" in u_lower:
        headers["Referer"] = "https://sic.pt/"
        headers["Origin"] = "https://sic.pt"
    elif "canal11" in u_lower or "cloudfront.net" in u_lower:
        headers["Referer"] = "https://canal11.pt/"
        headers["Origin"] = "https://canal11.pt"
    elif "portocanal" in u_lower or "fastly.net" in u_lower:
        headers["Referer"] = "https://portocanal.sapo.pt/"
    elif "iol.pt" in u_lower:
        headers["Referer"] = "https://tviplayer.iol.pt/"

    cors_header = None
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as resp:
            code = resp.status
            cors_header = resp.headers.get("Access-Control-Allow-Origin")
            content_type = resp.headers.get("Content-Type", "")

        latency = round((time.perf_counter() - start_t) * 1000, 1)
        is_ok = code in (200, 204, 206)

        return {
            "type": "official_m3u8",
            "url": url,
            "status": "ONLINE" if is_ok else "OFFLINE",
            "http_code": code,
            "cors": cors_header,
            "content_type": content_type,
            "latency_ms": latency
        }
    except urllib.error.HTTPError as e:
        # If TVI/CNN static token expired (HTTP 403/404/410), attempt fallback test against fresh upstream generator
        if "iol.pt" in u_lower and e.code in (401, 403, 404, 410):
            try:
                m3u_file = "TVI.m3u8" if "tvi" in channel_name.lower() else "CNN_PORTUGAL.m3u8"
                m3u_repo_url = f"https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/{m3u_file}"
                req_repo = urllib.request.Request(m3u_repo_url, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req_repo, context=SSL_CTX, timeout=timeout) as repo_resp:
                    lines = [l.strip() for l in repo_resp.read().decode("utf-8").splitlines() if l.strip().startswith("http")]
                    if lines:
                        fresh_url = lines[0]
                        req_fresh = urllib.request.Request(fresh_url, headers=headers)
                        with urllib.request.urlopen(req_fresh, context=SSL_CTX, timeout=timeout) as fresh_resp:
                            if fresh_resp.status in (200, 204, 206):
                                return {
                                    "type": "official_m3u8",
                                    "url": url,
                                    "status": "ONLINE",
                                    "http_code": fresh_resp.status,
                                    "note": "Dynamic token refreshed from M3UPT generator",
                                    "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
                                }
            except Exception:
                pass

        return {
            "type": "official_m3u8",
            "url": url,
            "status": "OFFLINE",
            "http_code": e.code,
            "error": f"HTTPError {e.code}",
            "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
        }
    except Exception as e:
        return {
            "type": "official_m3u8",
            "url": url,
            "status": "OFFLINE",
            "error": str(e),
            "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
        }


def test_generic_endpoint(url: str, channel_name: str = "", timeout: float = 5.0) -> Dict[str, Any]:
    """Routes an endpoint URL to the appropriate specialized tester."""
    u_lower = url.lower()
    if "ehgembxe" in u_lower or "timst" in u_lower or "grandemx" in u_lower or "exmxbxe" in u_lower:
        return test_timstreams_endpoint(url, timeout=timeout)
    elif "epicsports" in u_lower:
        return test_epicsports_endpoint(url, timeout=timeout)
    elif ".m3u8" in u_lower or "rtp.pt" in u_lower or "impresa" in u_lower or "canal11" in u_lower or "portocanal" in u_lower or "iol.pt" in u_lower:
        return test_m3u8_endpoint(url, channel_name=channel_name, timeout=timeout)
    else:
        # Fallback HTTP GET verification
        start_t = time.perf_counter()
        headers = {"User-Agent": USER_AGENT}
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as resp:
                code = resp.status
            return {
                "type": "generic_direct",
                "url": url,
                "status": "ONLINE" if code in (200, 204, 206) else "OFFLINE",
                "http_code": code,
                "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
            }
        except urllib.error.HTTPError as e:
            return {
                "type": "generic_direct",
                "url": url,
                "status": "OFFLINE",
                "http_code": e.code,
                "error": f"HTTPError {e.code}",
                "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
            }
        except Exception as e:
            return {
                "type": "generic_direct",
                "url": url,
                "status": "OFFLINE",
                "error": str(e),
                "latency_ms": round((time.perf_counter() - start_t) * 1000, 1)
            }


def audit_single_channel(ch: Dict[str, Any], timeout: float = 5.0) -> Dict[str, Any]:
    """Audits all configured streams and fallbacks for a single channel."""
    ch_id = str(ch.get("id", "")).strip()
    name = ch.get("name", "Unknown")
    country = ch.get("country", "")
    category = ch.get("category", "")
    is_pt = bool(ch.get("isPt", False) or country == "PT")
    backup1 = ch.get("backupStreamUrl")
    backup2 = ch.get("backupStreamUrl2")

    endpoints_tested: List[Dict[str, Any]] = []

    primary_status = "OFFLINE"
    fallback1_status = "N/A"
    fallback2_status = "N/A"
    fallback3_status = "N/A"

    if ch_id.isdigit():
        # Numeric channel: Primary is DaddyLive Player 1, Fallback 1 is DaddyLive Player 2
        import threading
        res_p1: List[Optional[Dict[str, Any]]] = [None]
        res_p2: List[Optional[Dict[str, Any]]] = [None]
        res_b1: List[Optional[Dict[str, Any]]] = [None]
        res_b2: List[Optional[Dict[str, Any]]] = [None]

        threads: List[threading.Thread] = []
        t1 = threading.Thread(target=lambda: res_p1.__setitem__(0, test_daddylive_player1(ch_id, timeout=timeout)))
        t2 = threading.Thread(target=lambda: res_p2.__setitem__(0, test_daddylive_player2(ch_id, timeout=timeout)))
        threads.extend([t1, t2])
        t1.start()
        t2.start()

        if backup1:
            tb1 = threading.Thread(target=lambda: res_b1.__setitem__(0, test_generic_endpoint(backup1, channel_name=name, timeout=timeout)))
            threads.append(tb1)
            tb1.start()

        if backup2:
            tb2 = threading.Thread(target=lambda: res_b2.__setitem__(0, test_generic_endpoint(backup2, channel_name=name, timeout=timeout)))
            threads.append(tb2)
            tb2.start()

        for t in threads:
            t.join()

        if res_p1[0]:
            endpoints_tested.append(res_p1[0])
            primary_status = res_p1[0]["status"]

        if res_p2[0]:
            endpoints_tested.append(res_p2[0])
            fallback1_status = res_p2[0]["status"]

        if backup1 and res_b1[0]:
            endpoints_tested.append(res_b1[0])
            fallback2_status = res_b1[0]["status"]

        if backup2 and res_b2[0]:
            endpoints_tested.append(res_b2[0])
            fallback3_status = res_b2[0]["status"]

    else:
        # Non-numeric channel: Primary is backupStreamUrl
        if backup1 and backup2:
            import threading
            res_b1_non: List[Optional[Dict[str, Any]]] = [None]
            res_b2_non: List[Optional[Dict[str, Any]]] = [None]
            tb1 = threading.Thread(target=lambda: res_b1_non.__setitem__(0, test_generic_endpoint(backup1, channel_name=name, timeout=timeout)))
            tb2 = threading.Thread(target=lambda: res_b2_non.__setitem__(0, test_generic_endpoint(backup2, channel_name=name, timeout=timeout)))
            tb1.start()
            tb2.start()
            tb1.join()
            tb2.join()
            if res_b1_non[0]:
                endpoints_tested.append(res_b1_non[0])
                primary_status = res_b1_non[0]["status"]
            if res_b2_non[0]:
                endpoints_tested.append(res_b2_non[0])
                fallback1_status = res_b2_non[0]["status"]
        elif backup1:
            res_b1 = test_generic_endpoint(backup1, channel_name=name, timeout=timeout)
            endpoints_tested.append(res_b1)
            primary_status = res_b1["status"]

    # Health classification
    # ONLINE: Primary stream ok
    # DEGRADED: Primary failed, but at least one backup/fallback is working
    # CRITICAL: All configured streams failed
    all_fallbacks = [
        s for s in [fallback1_status, fallback2_status, fallback3_status] if s != "N/A"
    ]

    if primary_status == "ONLINE":
        overall_status = "ONLINE"
    elif any(s == "ONLINE" for s in all_fallbacks):
        overall_status = "DEGRADED"
    else:
        overall_status = "CRITICAL"

    return {
        "id": ch_id,
        "name": name,
        "country": country,
        "category": category,
        "isPt": is_pt,
        "primary_status": primary_status,
        "fallback1_status": fallback1_status,
        "fallback2_status": fallback2_status,
        "fallback3_status": fallback3_status,
        "overall_status": overall_status,
        "endpoints": endpoints_tested
    }


def generate_markdown_report(report_data: Dict[str, Any]) -> str:
    """Formats consolidated audit findings into a clean Markdown table and summary."""
    meta = report_data["audit_metadata"]
    summary = report_data["status_summary"]
    providers = report_data["provider_summary"]
    channels = report_data["channels"]

    total = report_data["total_channels"]
    online = summary.get("ONLINE", 0)
    degraded = summary.get("DEGRADED", 0)
    critical = summary.get("CRITICAL", 0)

    online_pct = round((online / total * 100) if total else 0, 1)
    degraded_pct = round((degraded / total * 100) if total else 0, 1)
    critical_pct = round((critical / total * 100) if total else 0, 1)

    lines = []
    lines.append("# DLivePTApp (Daniel Streams) - Stream Audit Report (Requirement R1)")
    lines.append("")
    lines.append(f"**Execution Timestamp**: `{meta['timestamp']}`  ")
    lines.append(f"**Audit Duration**: `{meta['duration_seconds']}s`  ")
    lines.append(f"**Concurrency**: `{meta['workers']} workers` | **Timeout**: `{meta['timeout_per_request']}s`  ")
    lines.append(f"**Catalog Path**: `{meta['catalog_path']}`  ")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 1. Executive Summary")
    lines.append("")
    lines.append("| Metric | Value | Percentage |")
    lines.append("|---|---|---|")
    lines.append(f"| **Total Channels Audited** | **{total}** | 100.0% |")
    lines.append(f"| **Total Endpoints Tested** | **{report_data['total_endpoints']}** | - |")
    lines.append(f"| 🟢 **ONLINE Channels** (Primary Functional) | **{online}** | **{online_pct}%** |")
    lines.append(f"| 🟡 **DEGRADED Channels** (Primary Fail, Backup OK) | **{degraded}** | **{degraded_pct}%** |")
    lines.append(f"| 🔴 **CRITICAL Channels** (All Options Failed) | **{critical}** | **{critical_pct}%** |")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 2. Stream Provider Infrastructure Health")
    lines.append("")
    lines.append("| Provider / Endpoint Group | Tested | Passed | Failed | Success Rate |")
    lines.append("|---|---|---|---|---|")

    provider_labels = {
        "daddylive_p1": "DaddyLive Player 1",
        "daddylive_p2": "DaddyLive Player 2",
        "timstreams": "TimStreams 1080p",
        "epicsports": "EpicSports",
        "official_m3u8": "Official Portuguese M3U8",
        "generic_direct": "Generic Direct Streams"
    }

    for k, v in providers.items():
        if v["tested"] == 0:
            continue
        label = provider_labels.get(k, k)
        pass_pct = round((v["passed"] / v["tested"] * 100) if v["tested"] else 0, 1)
        lines.append(f"| **{label}** | {v['tested']} | {v['passed']} | {v['failed']} | **{pass_pct}%** |")

    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 3. Portuguese Channels (40) Census & Contingency Matrix")
    lines.append("")
    lines.append("| ID | Channel Name | Category | Primary Status | Fallback 1 | Fallback 2 | Overall Health |")
    lines.append("|---|---|---|---|---|---|---|")

    pt_channels = [c for c in channels if c.get("isPt") or c.get("country") == "PT"]
    for c in sorted(pt_channels, key=lambda x: (x["overall_status"] != "ONLINE", x["name"])):
        status_icon = "🟢 ONLINE" if c["overall_status"] == "ONLINE" else ("🟡 DEGRADED" if c["overall_status"] == "DEGRADED" else "🔴 CRITICAL")
        lines.append(
            f"| `{c['id']}` | {c['name']} | {c['category']} | {c['primary_status']} | {c['fallback1_status']} | {c['fallback2_status']} | **{status_icon}** |"
        )

    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 4. Channels Requiring Attention (DEGRADED & CRITICAL)")
    lines.append("")

    problematic = [c for c in channels if c["overall_status"] in ("DEGRADED", "CRITICAL")]
    if not problematic:
        lines.append("✅ No channels are degraded or critical! All 100% operational.")
    else:
        lines.append(f"Found **{len(problematic)}** channels with degraded or critical status:")
        lines.append("")
        lines.append("| ID | Name | Country | Category | Primary | Fallback 1 | Fallback 2 | Health | Error / Reason |")
        lines.append("|---|---|---|---|---|---|---|---|---|")
        for c in problematic:
            err_msg = ""
            for ep in c.get("endpoints", []):
                if ep.get("status") == "OFFLINE" and ep.get("error"):
                    err_msg += f"[{ep.get('type')}: {ep.get('error')}] "
            if not err_msg:
                err_msg = "No response"
            lines.append(
                f"| `{c['id']}` | {c['name']} | {c['country']} | {c['category']} | {c['primary_status']} | {c['fallback1_status']} | {c['fallback2_status']} | **{c['overall_status']}** | {err_msg[:60]} |"
            )

    lines.append("")
    lines.append("---")
    lines.append("*Report generated automatically by `scripts/audit_channels_r1.py`.*")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Daniel Streams High-Speed Stream Audit Tool (Requirement R1)")
    parser.add_argument("--channels-file", default=None, help="Path to channels.json")
    parser.add_argument("--json-output", default=None, help="Output path for JSON report")
    parser.add_argument("--md-output", default=None, help="Output path for Markdown report")
    parser.add_argument("--workers", type=int, default=25, help="Number of concurrent worker threads")
    parser.add_argument("--timeout", type=float, default=5.0, help="Per-request HTTP timeout in seconds")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of channels to audit (for smoke tests)")

    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    channels_path = args.channels_file or os.path.join(project_root, "app", "src", "main", "assets", "channels.json")
    json_out_path = args.json_output or os.path.join(project_root, "build", "reports", "stream_audit_report.json")
    md_out_path = args.md_output or os.path.join(project_root, "build", "reports", "stream_audit_report.md")

    if not os.path.exists(channels_path):
        print(f"[ERROR] channels.json not found at {channels_path}", file=sys.stderr)
        sys.exit(1)

    print(f"=== Daniel Streams High-Speed Stream Audit (Requirement R1) ===")
    print(f"Catalog File: {channels_path}")
    print(f"Concurrency:  {args.workers} worker threads | Timeout: {args.timeout}s")

    with open(channels_path, "r", encoding="utf-8") as f:
        channels = json.load(f)

    if args.limit:
        print(f"[INFO] Limiting audit to first {args.limit} channels for smoke test")
        channels = channels[:args.limit]

    total_channels = len(channels)
    print(f"Total Channels to Audit: {total_channels}")

    start_audit_time = time.perf_counter()
    timestamp_iso = datetime.now(timezone.utc).isoformat()

    results: List[Dict[str, Any]] = []
    completed_count = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        future_to_channel = {
            executor.submit(audit_single_channel, ch, timeout=args.timeout): ch
            for ch in channels
        }

        for future in concurrent.futures.as_completed(future_to_channel):
            res = future.result()
            results.append(res)
            completed_count += 1
            if completed_count % 50 == 0 or completed_count == total_channels:
                pct = round(completed_count / total_channels * 100, 1)
                elapsed = round(time.perf_counter() - start_audit_time, 1)
                print(f"  -> Audited {completed_count}/{total_channels} channels ({pct}%) in {elapsed}s...")

    # Sort results to match original channel order
    id_to_order = {str(ch.get("id")): idx for idx, ch in enumerate(channels)}
    results.sort(key=lambda r: id_to_order.get(r["id"], 99999))

    duration_seconds = round(time.perf_counter() - start_audit_time, 2)
    print(f"\n[DONE] Completed audit of {total_channels} channels in {duration_seconds}s!")

    # Calculate statistics
    total_endpoints = sum(len(r.get("endpoints", [])) for r in results)

    status_counts = {"ONLINE": 0, "DEGRADED": 0, "CRITICAL": 0}
    for r in results:
        status_counts[r["overall_status"]] = status_counts.get(r["overall_status"], 0) + 1

    provider_stats: Dict[str, Dict[str, int]] = {
        "daddylive_p1": {"tested": 0, "passed": 0, "failed": 0},
        "daddylive_p2": {"tested": 0, "passed": 0, "failed": 0},
        "timstreams": {"tested": 0, "passed": 0, "failed": 0},
        "epicsports": {"tested": 0, "passed": 0, "failed": 0},
        "official_m3u8": {"tested": 0, "passed": 0, "failed": 0},
        "generic_direct": {"tested": 0, "passed": 0, "failed": 0}
    }

    for r in results:
        for ep in r.get("endpoints", []):
            ep_type = ep.get("type", "generic_direct")
            if ep_type not in provider_stats:
                provider_stats[ep_type] = {"tested": 0, "passed": 0, "failed": 0}
            provider_stats[ep_type]["tested"] += 1
            if ep.get("status") == "ONLINE":
                provider_stats[ep_type]["passed"] += 1
            else:
                provider_stats[ep_type]["failed"] += 1

    # Build report object conforming to PROJECT.md interface contract
    report_dict = {
        "total_channels": total_channels,
        "total_endpoints": total_endpoints,
        "status_summary": {
            "ONLINE": status_counts["ONLINE"],
            "DEGRADED": status_counts["DEGRADED"],
            "CRITICAL": status_counts["CRITICAL"]
        },
        "provider_summary": provider_stats,
        "audit_metadata": {
            "timestamp": timestamp_iso,
            "duration_seconds": duration_seconds,
            "workers": args.workers,
            "timeout_per_request": args.timeout,
            "catalog_path": channels_path
        },
        "channels": results
    }

    # Write JSON report
    os.makedirs(os.path.dirname(json_out_path), exist_ok=True)
    with open(json_out_path, "w", encoding="utf-8") as f:
        json.dump(report_dict, f, indent=2, ensure_ascii=False)
    print(f"JSON Report written to: {json_out_path}")

    # Write Markdown report
    os.makedirs(os.path.dirname(md_out_path), exist_ok=True)
    md_content = generate_markdown_report(report_dict)
    with open(md_out_path, "w", encoding="utf-8") as f:
        f.write(md_content)
    print(f"Markdown Report written to: {md_out_path}")

    print("\n=== Audit Summary ===")
    print(f"Total Channels:    {total_channels}")
    print(f"Total Endpoints:   {total_endpoints}")
    print(f"[ONLINE]           {status_counts['ONLINE']} ({round(status_counts['ONLINE']/total_channels*100, 1)}%)")
    print(f"[DEGRADED]         {status_counts['DEGRADED']} ({round(status_counts['DEGRADED']/total_channels*100, 1)}%)")
    print(f"[CRITICAL]         {status_counts['CRITICAL']} ({round(status_counts['CRITICAL']/total_channels*100, 1)}%)")
    print("=====================\n")

    sys.exit(0)


if __name__ == "__main__":
    main()
