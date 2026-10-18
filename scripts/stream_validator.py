#!/usr/bin/env python3
"""
Daniel Streams - Automated Daily Stream & Domain Validator
Runs via GitHub Actions on a cron schedule (11:30 UTC / 12:30 Lisboa and 05:00 UTC / 06:00 Lisboa)
to detect domain changes, verify stream status, auto-categorize offline channels into
'Em Manutenção', restore them when online, and keep channels.json up-to-date.
"""

import json
import os
import re
import sys
import urllib.request
import urllib.error
import ssl
import time
from urllib.parse import urlparse

CHANNELS_PATH = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "channels.json")

DADDYLIVE_MIRRORS = [
    "https://dlive.sx",
    "https://dlhd.pk",
    "https://dlhd.st",
    "https://dlstreams.st"
]

TIMSTREAMS_MIRRORS = [
    "https://ehgembxe.cfd",
    "https://grandemx.org",
    "https://exmxbxe.cfd",
    "https://timst.top"
]

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
    "Referer": "https://grandemx.org/"
}

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def check_hls_sequence_progress(url, headers_dict, timeout=5):
    """Verifies that an HLS live stream sequence is actively advancing and not stalled."""
    try:
        req = urllib.request.Request(url, headers=headers_dict)
        with urllib.request.urlopen(req, context=ctx, timeout=timeout) as resp:
            content1 = resp.read().decode('utf-8', errors='ignore')
            m1 = re.search(r'#EXT-X-MEDIA-SEQUENCE:(\d+)', content1)
            if not m1:
                return True  # Master playlist or VOD, assume OK if reachable
            seq1 = int(m1.group(1))

        time.sleep(3.5)

        req = urllib.request.Request(url, headers=headers_dict)
        with urllib.request.urlopen(req, context=ctx, timeout=timeout) as resp:
            content2 = resp.read().decode('utf-8', errors='ignore')
            m2 = re.search(r'#EXT-X-MEDIA-SEQUENCE:(\d+)', content2)
            if not m2:
                return True
            seq2 = int(m2.group(1))

        # True if sequence advanced; False if stalled/frozen at the same number
        return seq2 > seq1
    except Exception:
        return False

def check_url_alive(url, referer=None, timeout=6, verify_hls_progress=False):
    """Checks if a URL returns HTTP 200/206 or is accessible, with optional HLS stall detection."""
    if not url:
        return False
    h = dict(HEADERS)
    if referer:
        h["Referer"] = referer
    elif "rtp.pt" in url:
        h["Referer"] = "https://www.rtp.pt/"
    elif "impresa" in url:
        h["Referer"] = "https://sic.pt/"
    elif "iol.pt" in url:
        h["Referer"] = "https://tviplayer.iol.pt/"
    elif "epicsports" in url:
        h["Referer"] = "https://epicsports-tv.com/"
    elif "portocanal" in url or "fastly" in url:
        h["Referer"] = "https://portocanal.sapo.pt/"
    elif "canal11" in url or "cloudfront" in url:
        h["Referer"] = "https://canal11.pt/"
    elif "grandemx.org" in url or "ehgembxe" in url or "exmxbxe" in url:
        h["Referer"] = "https://grandemx.org/"
    elif "dlive" in url:
        h["Referer"] = "https://dlive.sx/"

    try:
        req = urllib.request.Request(url, headers=h)
        with urllib.request.urlopen(req, context=ctx, timeout=timeout) as resp:
            if resp.status not in range(200, 400):
                return False

        if verify_hls_progress and ".m3u8" in url:
            return check_hls_sequence_progress(url, h, timeout=timeout)

        return True
    except urllib.error.HTTPError as e:
        return e.code in range(200, 400)
    except Exception:
        return False


def detect_active_timstreams_domain():
    """Finds current working domain for TimStreams by following redirects."""
    for mirror in TIMSTREAMS_MIRRORS:
        test_url = f"{mirror}/oqq5c86g-7521"
        try:
            req = urllib.request.Request(test_url, headers=HEADERS, method="GET")
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, req, fp, code, msg, headers, newurl):
                    return None
            opener = urllib.request.build_opener(NoRedirect)
            try:
                with opener.open(req, timeout=5) as resp:
                    p = urlparse(resp.geturl())
                    return f"{p.scheme}://{p.netloc}"
            except urllib.error.HTTPError as e:
                if e.code in (301, 302, 307, 308):
                    loc = e.headers.get("Location", "")
                    if loc.startswith("http"):
                        p = urlparse(loc)
                        return f"{p.scheme}://{p.netloc}"
        except Exception:
            continue
    return "https://grandemx.org"

def fetch_m3upt_tokenized_url(m3u_file):
    """Fetches updated m3u8 token URL from official M3UPT repo."""
    url = f"https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/{m3u_file}"
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, context=ctx, timeout=6) as resp:
            content = resp.read().decode('utf-8')
            for line in content.splitlines():
                line = line.strip()
                if line.startswith("http") and ".m3u8" in line:
                    return line
    except Exception:
        pass
    return None

def main():
    print("=== Daniel Streams Daily Stream Validator ===")
    norm_path = os.path.normpath(CHANNELS_PATH)
    if not os.path.exists(norm_path):
        print(f"Error: channels.json not found at {norm_path}")
        sys.exit(1)

    with open(norm_path, "r", encoding="utf-8") as f:
        channels = json.load(f)

    print(f"Loaded {len(channels)} channels from channels.json")

    # 1. Detect TimStreams active domain
    active_timst = detect_active_timstreams_domain()
    print(f"Active TimStreams Domain: {active_timst}")

    changes_count = 0

    # 2. Update TimStreams links if domain changed
    for ch in channels:
        b1 = ch.get("backupStreamUrl")
        b2 = ch.get("backupStreamUrl2")

        if b1 and ("exmxbxe.cfd" in b1 or "grandemx.org" in b1):
            path = urlparse(b1).path
            new_b1 = f"{active_timst}{path}"
            if new_b1 != b1:
                ch["backupStreamUrl"] = new_b1
                changes_count += 1

        if b2 and ("exmxbxe.cfd" in b2 or "grandemx.org" in b2):
            path = urlparse(b2).path
            new_b2 = f"{active_timst}{path}"
            if new_b2 != b2:
                ch["backupStreamUrl2"] = new_b2
                changes_count += 1

    # 3. Check and refresh M3UPT live tokens (TVI and CNN Portugal)
    tvi_url = fetch_m3upt_tokenized_url("TVI.m3u8")
    if tvi_url:
        for ch in channels:
            if ch.get("id") in ("723", "tvi-pt") or ch.get("name") == "TVI HD":
                field = "backupStreamUrl" if ch.get("backupStreamUrl") and "iol.pt" in ch.get("backupStreamUrl") else "backupStreamUrl2"
                if ch.get(field) != tvi_url and "wmsAuthSign" in tvi_url:
                    print("Updated TVI HD tokenized stream URL")
                    ch[field] = tvi_url
                    changes_count += 1

    cnn_url = fetch_m3upt_tokenized_url("CNN_Portugal.m3u8")
    if cnn_url:
        for ch in channels:
            if ch.get("id") == "cnn-pt" or ch.get("name") == "CNN Portugal":
                field = "backupStreamUrl" if ch.get("backupStreamUrl") and "iol.pt" in ch.get("backupStreamUrl") else "backupStreamUrl2"
                if ch.get(field) != cnn_url and "wmsAuthSign" in cnn_url:
                    print("Updated CNN Portugal tokenized stream URL")
                    ch[field] = cnn_url
                    changes_count += 1

    # 4. Stream Health & Auto Maintenance Categorization for Portuguese channels
    pt_channels = [c for c in channels if c.get("isPt") or c.get("country") == "PT"]
    print(f"Analyzing stream health for {len(pt_channels)} Portuguese channels...")

    for ch in pt_channels:
        cid = str(ch.get("id", ""))
        name = ch.get("name", "Unknown")
        current_cat = ch.get("category", "")
        orig_cat = ch.get("originalCategory") or (current_cat if current_cat != "Em Manutenção" else "Desporto")
        
        has_working_source = False

        # Check Primary (DaddyLive)
        if cid.isdigit():
            dl_url = f"https://dlive.sx/stream/stream-{cid}.php"
            if check_url_alive(dl_url, "https://dlive.sx/"):
                has_working_source = True

        # Check Backup 1
        b1 = ch.get("backupStreamUrl")
        if not has_working_source and b1:
            if check_url_alive(b1, verify_hls_progress=True):
                has_working_source = True

        # Check Backup 2
        b2 = ch.get("backupStreamUrl2")
        if not has_working_source and b2:
            if check_url_alive(b2, verify_hls_progress=True):
                has_working_source = True

        # State Transition Logic
        if not has_working_source:
            # Channel is OFFLINE -> Move to "Em Manutenção"
            if current_cat != "Em Manutenção":
                print(f"[OFFLINE] {name} -> Moving to 'Em Manutenção' (saved original: {orig_cat})")
                ch["originalCategory"] = orig_cat
                ch["category"] = "Em Manutenção"
                changes_count += 1
        else:
            # Channel is ONLINE -> Restore from "Em Manutenção" if needed
            if current_cat == "Em Manutenção":
                restored_cat = ch.get("originalCategory", "Desporto")
                print(f"[ONLINE] {name} -> Restored to '{restored_cat}'!")
                ch["category"] = restored_cat
                changes_count += 1

    # 5. Sort Portuguese channels so that "Em Manutenção" stays at the bottom
    pt_active = [c for c in channels if (c.get("isPt") or c.get("country") == "PT") and c.get("category") != "Em Manutenção"]
    pt_maintenance = [c for c in channels if (c.get("isPt") or c.get("country") == "PT") and c.get("category") == "Em Manutenção"]
    other_channels = [c for c in channels if not (c.get("isPt") or c.get("country") == "PT")]

    sorted_channels = pt_active + pt_maintenance + other_channels

    # 6. Save channels.json if changes occurred
    if changes_count > 0:
        print(f"Applying {changes_count} status/domain changes to channels.json...")
        with open(norm_path, "w", encoding="utf-8") as f:
            json.dump(sorted_channels, f, indent=2, ensure_ascii=False)
        print("channels.json successfully updated!")
    else:
        print("All channels and domains are already up-to-date. No changes needed.")

    print(f"Summary: {len(pt_active)} active PT channels, {len(pt_maintenance)} in maintenance.")
    print("=== Validation Finished Successfully ===")

if __name__ == "__main__":
    main()
