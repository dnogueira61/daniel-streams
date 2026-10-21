#!/usr/bin/env python3
"""
Independent Empirical Stream Verification Harness - Challenger M1_2
Directly samples and verifies live streams from:
1. TimStreams 1080p (302 redirect + JWT location check)
2. DaddyLive Player 1 (outer dlive.sx wrapper and upstream dembed.st)
3. DaddyLive Player 2 (outer dlive.sx wrapper and upstream wideiptv.top)
4. EpicSports (anti-leech Referer enforcement and HTML response)
5. Official Portuguese M3U8s (SIC, Canal 11, RTP 1/2, Porto Canal, ARTV, TVI/CNN dynamic tokens)
"""

import os
import sys
import json
import time
import ssl
import urllib.request
import urllib.error
import urllib.parse
from typing import Dict, Any, List

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, hdrs, newurl):
        return None

NO_REDIR_OPENER = urllib.request.build_opener(urllib.request.HTTPSHandler(context=SSL_CTX), NoRedirect)
STANDARD_OPENER = urllib.request.build_opener(urllib.request.HTTPSHandler(context=SSL_CTX))

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

def probe_url(url: str, headers: Dict[str, str] = None, allow_redirects: bool = True, timeout: float = 6.0):
    t0 = time.perf_counter()
    h = {"User-Agent": USER_AGENT}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    opener = STANDARD_OPENER if allow_redirects else NO_REDIR_OPENER
    try:
        with opener.open(req, timeout=timeout) as resp:
            elapsed = round((time.perf_counter() - t0) * 1000, 1)
            body = resp.read(65536)
            resp_headers = {k.lower(): v for k, v in resp.headers.items()}
            return resp.status, resp_headers, body, elapsed, None
    except urllib.error.HTTPError as e:
        elapsed = round((time.perf_counter() - t0) * 1000, 1)
        resp_headers = {k.lower(): v for k, v in e.headers.items()} if e.headers else {}
        body = e.read(65536) if hasattr(e, "read") else b""
        return e.code, resp_headers, body, elapsed, f"HTTPError {e.code}"
    except Exception as e:
        elapsed = round((time.perf_counter() - t0) * 1000, 1)
        return 0, {}, b"", elapsed, str(e)


def test_timstreams_sample():
    print("\n--- [1] TIMSTREAMS 1080P SAMPLE ---")
    samples = [
        ("Sport TV 1 HD", "https://ehgembxe.cfd/oqq5c86g-7521"),
        ("Sport TV 2 HD", "https://ehgembxe.cfd/6dw9r22a-7192"),
        ("Sport TV 3 HD", "https://ehgembxe.cfd/dbo09jwh-5404"),
        ("RTP 1 HD", "https://ehgembxe.cfd/tfe74o77-7501"),
        ("SIC HD", "https://ehgembxe.cfd/b8q8v7q4-7503"),
        ("CMTV", "https://ehgembxe.cfd/m6t3f4s8-7517"),
        ("DAZN 1 PT", "https://ehgembxe.cfd/v5q2z7x9-7527")
    ]
    results = []
    for name, url in samples:
        # Test 1: with Referer
        status, headers, body, elapsed, err = probe_url(url, headers={"Referer": "https://timst.top/"}, allow_redirects=False)
        loc = headers.get("location", "")
        # Follow location token
        token_status = None
        if loc:
            full_loc = urllib.parse.urljoin(url, loc)
            t_status, _, _, _, _ = probe_url(full_loc, headers={"Referer": "https://timst.top/"}, allow_redirects=True, timeout=5.0)
            token_status = t_status
        
        # Test 2: without Referer (stress test anti-leech)
        no_ref_status, _, _, _, _ = probe_url(url, headers={}, allow_redirects=False)

        res = {
            "name": name,
            "url": url,
            "redirect_code": status,
            "has_jwt": "/play/" in loc,
            "token_http_status": token_status,
            "no_ref_code": no_ref_status,
            "latency_ms": elapsed,
            "error": err
        }
        results.append(res)
        print(f"  {name:15}: code={status} jwt={res['has_jwt']} token_resp={token_status} (no_ref={no_ref_status}) {elapsed}ms")
    return results


def test_daddylive_sample():
    print("\n--- [2] DADDYLIVE PLAYER 1 & 2 SAMPLE ---")
    sample_ids = ["49", "74", "454", "719", "722", "1", "10", "100"]
    results = []
    for cid in sample_ids:
        # P1 outer
        p1_outer = f"https://dlive.sx/stream/stream-{cid}.php"
        p1_outer_st, _, p1_body, p1_lat, p1_err = probe_url(p1_outer, headers={"Referer": "https://dlive.sx/"}, timeout=4.0)
        
        # P1 upstream (dembed.st)
        p1_up = f"https://dembed.st/premiumtv/daddy.php?id={cid}"
        p1_up_st, _, p1_up_body, p1_up_lat, p1_up_err = probe_url(p1_up, headers={"Referer": "https://dlive.sx/"}, timeout=5.0)

        # P2 outer
        p2_outer = f"https://dlive.sx/player/stream-{cid}.php"
        p2_outer_st, _, p2_body, p2_lat, p2_err = probe_url(p2_outer, headers={"Referer": "https://dlive.sx/"}, timeout=4.0)

        # P2 upstream (wideiptv.top)
        p2_up = f"https://wideiptv.top/daddy.php?stream={cid}"
        p2_up_st, _, p2_up_body, p2_up_lat, p2_up_err = probe_url(p2_up, headers={"Referer": "https://dlive.sx/"}, timeout=5.0)

        res = {
            "channel_id": cid,
            "p1_outer_status": p1_outer_st,
            "p1_upstream_status": p1_up_st,
            "p2_outer_status": p2_outer_st,
            "p2_upstream_status": p2_up_st,
            "p1_upstream_latency": p1_up_lat,
            "p2_upstream_latency": p2_up_lat,
            "p1_outer_err": p1_err,
            "p2_outer_err": p2_err
        }
        results.append(res)
        print(f"  Channel {cid:4}: P1 outer={p1_outer_st} up={p1_up_st} ({p1_up_lat}ms) | P2 outer={p2_outer_st} up={p2_up_st} ({p2_up_lat}ms)")
        time.sleep(0.2)
    return results


def test_epicsports_sample():
    print("\n--- [3] EPICSPORTS SAMPLE ---")
    samples = [
        ("Sport TV 1", "https://epicsports-tv.com/eu.html?id=43633"),
        ("Sport TV 2", "https://epicsports-tv.com/eu.html?id=43634"),
        ("Sport TV 3", "https://epicsports-tv.com/eu.html?id=43635"),
        ("Sport TV 4", "https://epicsports-tv.com/eu.html?id=43636"),
        ("Sport TV 5", "https://epicsports-tv.com/eu.html?id=43631"),
        ("Sport TV 6", "https://epicsports-tv.com/eu.html?id=43632"),
        ("DAZN 1 PT",  "https://epicsports-tv.com/eu.html?id=43637")
    ]
    results = []
    for name, url in samples:
        st, headers, body, elapsed, err = probe_url(url, headers={"Referer": "https://epicsports-tv.com/"}, timeout=5.0)
        st_noref, _, _, _, _ = probe_url(url, headers={}, timeout=5.0)

        res = {
            "name": name,
            "url": url,
            "status": st,
            "status_no_referer": st_noref,
            "latency_ms": elapsed,
            "body_len": len(body),
            "error": err
        }
        results.append(res)
        print(f"  {name:15}: code={st} (no_ref={st_noref}) len={len(body)}b {elapsed}ms")
    return results


def test_official_m3u8_sample():
    print("\n--- [4] OFFICIAL PORTUGUESE M3U8s SAMPLE ---")
    samples = [
        ("SIC HD", "https://sic.live.impresa.pt/sic1080p.m3u8", {"Origin": "https://sic.pt", "Referer": "https://sic.pt/"}),
        ("SIC Notícias", "https://sicnot.live.impresa.pt/sicnot.m3u8", {"Origin": "https://sicnoticias.pt", "Referer": "https://sicnoticias.pt/"}),
        ("Canal 11", "https://dy4isoelz3s0x.cloudfront.net/0_hd.m3u8", {"Origin": "https://canal11.pt", "Referer": "https://canal11.pt/"}),
        ("RTP 1 HD", "https://streaming-live.rtp.pt/liverepeater/smil:rtp1HD.smil/playlist.m3u8", {"Referer": "https://www.rtp.pt/"}),
        ("RTP 2 HD", "https://streaming-live.rtp.pt/liverepeater/rtp2HD.smil/playlist.m3u8", {"Referer": "https://www.rtp.pt/"}),
        ("Porto Canal", "https://pull-live-156-1.global.ssl.fastly.net/pc5865dc25400thmb-ea6bf03b14fa318f7133/pc1-jhrgyuoqe5865db-68tkgb14fa318f7133f03.smil/playlist.m3u8", {}),
        ("ARTV", "https://playout175.livextend.cloud/livenlin4/_definst_/2liveartvpub2/playlist.m3u8", {})
    ]
    results = []
    for name, url, headers in samples:
        st, res_h, body, elapsed, err = probe_url(url, headers=headers, timeout=6.0)
        is_m3u8 = b"#EXTM3U" in body
        cors = res_h.get("access-control-allow-origin", "NONE")
        res = {
            "name": name,
            "url": url,
            "status": st,
            "is_m3u8": is_m3u8,
            "cors": cors,
            "latency_ms": elapsed,
            "error": err
        }
        results.append(res)
        print(f"  {name:15}: code={st} is_m3u8={is_m3u8} cors={cors} {elapsed}ms")

    # Test IOL TVI/CNN static token vs dynamic token
    print("\n  [IOL Dynamic Token Verification]:")
    tvi_static = "https://video-auth7.iol.pt/live_tvi/live_tvi/edge_servers/tvi-720p/chunks.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMTkvMjAyNiA0OjExOjQyIFBNJmhhc2hfdmFsdWU9aDB6QUtvTzJCN0ZLZzI3UU5OWVNBQT09JnZhbGlkbWludXRlcz0xNDQwJmlkPTgzNDQ0MjIyLWEwMTEtNGNkZi1iYjg1LTgzZDcxNzJiZTRiNg=="
    st_stat, _, _, _, err_stat = probe_url(tvi_static, timeout=5.0)
    print(f"    TVI Static Token Status: {st_stat} ({err_stat})")
    
    # Probe upstream M3UPT master for fresh TVI feed
    upstream_m3u = "https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/TVI.m3u8"
    st_m3u, _, body_m3u, _, _ = probe_url(upstream_m3u, timeout=5.0)
    print(f"    M3UPT Upstream Generator TVI.m3u8: status={st_m3u} len={len(body_m3u)}b")
    
    return results

if __name__ == "__main__":
    t_start = time.perf_counter()
    timst = test_timstreams_sample()
    daddy = test_daddylive_sample()
    epic = test_epicsports_sample()
    m3u8 = test_official_m3u8_sample()
    total_time = round(time.perf_counter() - t_start, 2)
    print(f"\n[SUMMARY] Independent stream validation completed in {total_time}s")
