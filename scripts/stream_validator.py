#!/usr/bin/env python3
"""
Daniel Streams - Automated Daily Stream & Domain Validator
Runs via GitHub Actions on a cron schedule (11:30 UTC / 12:30 Lisboa)
to detect domain changes, verify stream status, and keep channels.json up-to-date.
"""

import json
import os
import re
import sys
import urllib.request
import urllib.error
from urllib.parse import urlparse

CHANNELS_PATH = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "channels.json")

DADDYLIVE_MIRRORS = [
    "https://dlive.sx",
    "https://dlhd.pk",
    "https://dlhd.st",
    "https://dlstreams.st",
    "https://dlhd.dad"
]

TIMSTREAMS_MIRRORS = [
    "https://grandemx.org",
    "https://exmxbxe.cfd",
    "https://timst.top"
]

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
    "Referer": "https://timst.top/"
}

def check_redirect(url, timeout=5):
    """Follows HEAD/GET to detect if a domain or link was redirected (301/302)."""
    try:
        req = urllib.request.Request(url, headers=HEADERS, method="HEAD")
        opener = urllib.request.build_opener(urllib.request.HTTPRedirectHandler)
        with opener.open(req, timeout=timeout) as resp:
            final_url = resp.geturl()
            return final_url
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 307, 308):
            loc = e.headers.get("Location")
            if loc:
                return loc
        return url
    except Exception:
        return url

def check_url_alive(url, referer=None, timeout=5):
    """Checks if a URL returns HTTP 200/204 or is accessible."""
    h = dict(HEADERS)
    if referer:
        h["Referer"] = referer
    try:
        req = urllib.request.Request(url, headers=h, method="HEAD")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status in range(200, 400)
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
        except Exception as e:
            continue
    return "https://grandemx.org"

def fetch_m3upt_tokenized_url(m3u_file):
    """Fetches updated m3u8 token URL from official M3UPT repo."""
    url = f"https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/{m3u_file}"
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=6) as resp:
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

    # 2. Update TimStreams links if needed
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
                if ch.get("backupStreamUrl2") != tvi_url and "wmsAuthSign" in tvi_url:
                    print("Updated TVI HD tokenized stream URL")
                    ch["backupStreamUrl2"] = tvi_url
                    changes_count += 1

    cnn_url = fetch_m3upt_tokenized_url("CNN_Portugal.m3u8")
    if cnn_url:
        for ch in channels:
            if ch.get("id") == "cnn-pt" or ch.get("name") == "CNN Portugal":
                if ch.get("backupStreamUrl2") != cnn_url and "wmsAuthSign" in cnn_url:
                    print("Updated CNN Portugal tokenized stream URL")
                    ch["backupStreamUrl2"] = cnn_url
                    changes_count += 1

    # 4. Save channels.json if changes occurred
    if changes_count > 0:
        print(f"Applying {changes_count} changes to channels.json...")
        with open(norm_path, "w", encoding="utf-8") as f:
            json.dump(channels, f, indent=2, ensure_ascii=False)
        print("channels.json successfully updated!")
    else:
        print("All channels and domains are already up-to-date. No changes needed.")

    print("=== Validation Finished Successfully ===")

if __name__ == "__main__":
    main()
