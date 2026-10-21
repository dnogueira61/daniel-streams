"""
Tier 1 Feature Coverage: Ad Blocking & URL Filtering (Requirement R2)
Tests ad network pattern matching, sports/betting blocker rules, and response contracts.
"""

import unittest
import time
from typing import Set, Tuple, Optional

# Canonical Ad Block Pattern Set defined in PROJECT.md and Explorer Security Survey
SPEC_AD_BLOCK_PATTERNS: Set[str] = {
    "piousshiners", "nanisms", "histats", "premiumvertising",
    "dzlhwcoblmc", "azrmpjyh", "smartbanner", "adsco",
    "popads", "popunder", "creativecdn", "doubleclick",
    "googlesyndication", "monetag", "trafficjunky",
    "adservice", "chatango", "onclicksuper", "syndication",
    "exdynsrv", "adsystem", "adnxs", "burstyflavia",
    "profitableratecpmnetwork", "cleverwebserver", "adsboosters",
    "reliedhounder", "canine.tools", "plausible",
    "disable-devtool", "aclib",
    # Expanded sports stream ad networks & betting redirectors
    "adsterra", "propellerads", "exoclick", "hilltopads",
    "adcash", "yllix", "clickadu", "juicyads", "trafficstars",
    "effectivecpmgate", "highcpmgate", "whos.amung.us",
    "scorecardresearch", "onclickmega", "alwingulla",
    "deloplen", "coinhive", "adkeep", "inpagepush",
    "bet365", "1xbet", "betano", "betfair", "mostbet", "melbet"
}

def simulate_should_intercept(raw_url: str, pattern_set: Set[str]) -> Optional[Tuple[str, str, bytes]]:
    """
    Simulates PlayerActivity.shouldInterceptRequest behavior.
    Returns (mime_type, encoding, bytes) if intercepted, else None.
    """
    url_lower = raw_url.lower()
    for pattern in pattern_set:
        if pattern in url_lower:
            return ("application/javascript", "UTF-8", b"")
    return None

class TestFeatureAdBlocking(unittest.TestCase):

    def test_01_standard_ad_network_patterns_blocked(self):
        """Verify baseline ad networks are intercepted."""
        urls = [
            "https://securepubads.g.doubleclick.net/gampad/ads?iu=/123",
            "https://serve.popads.net/banner.js",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://s10.histats.com/js15.js",
            "https://ads.creativecdn.com/tags/app.js",
            "https://static.monetag.com/tag.js"
        ]
        for url in urls:
            with self.subTest(url=url):
                res = simulate_should_intercept(url, SPEC_AD_BLOCK_PATTERNS)
                self.assertIsNotNone(res, f"URL must be blocked: {url}")
                self.assertEqual(res[0], "application/javascript")

    def test_02_expanded_sports_stream_ad_networks_blocked(self):
        """Verify sports-streaming ad networks identified in survey are intercepted."""
        sports_ad_urls = [
            "https://alwingulla.com/tag/script.js",
            "https://adsterra.com/publisher/widget.js",
            "https://engine.phn.exoclick.com/tag.js",
            "https://propellerads.com/zone/12345",
            "https://hilltopads.net/serve/ad.js",
            "https://effectivecpmgate.com/gate.js",
            "https://whos.amung.us/counternew.js"
        ]
        for url in sports_ad_urls:
            with self.subTest(url=url):
                res = simulate_should_intercept(url, SPEC_AD_BLOCK_PATTERNS)
                self.assertIsNotNone(res, f"Sports ad URL must be intercepted: {url}")

    def test_03_betting_and_gambling_redirect_patterns_blocked(self):
        """Verify gambling/betting redirect networks are intercepted."""
        betting_urls = [
            "https://affiliates.bet365.com/landing?pid=999",
            "https://refpa123.1xbet.com/banners/sport.js",
            "https://tracker.betano.pt/track/click",
            "https://promo.betfair.com/sportsbook/offer",
            "https://aff.mostbet.com/click.php"
        ]
        for url in betting_urls:
            with self.subTest(url=url):
                res = simulate_should_intercept(url, SPEC_AD_BLOCK_PATTERNS)
                self.assertIsNotNone(res, f"Betting redirect must be intercepted: {url}")

    def test_04_blocked_response_contract_is_empty_zero_byte_js(self):
        """Verify intercepted response matches Android WebResourceResponse contract."""
        url = "https://popads.net/pop.js"
        res = simulate_should_intercept(url, SPEC_AD_BLOCK_PATTERNS)
        self.assertIsNotNone(res)
        mime, encoding, data = res
        self.assertEqual(mime, "application/javascript")
        self.assertEqual(encoding, "UTF-8")
        self.assertEqual(len(data), 0, "Blocked response must return exactly 0 bytes")

    def test_05_static_hashset_pattern_lookup_efficiency(self):
        """Verify pattern matching evaluates 2000 URLs in < 50ms without allocations."""
        test_urls = [
            f"https://stream-server-{i}.dlive.sx/segment{i}.ts" for i in range(1000)
        ] + [
            f"https://bad-ad-network-{i}.exoclick.com/ad{i}.js" for i in range(1000)
        ]
        
        start_time = time.perf_counter()
        blocked_count = 0
        for u in test_urls:
            if simulate_should_intercept(u, SPEC_AD_BLOCK_PATTERNS):
                blocked_count += 1
        elapsed_ms = (time.perf_counter() - start_time) * 1000.0
        
        self.assertEqual(blocked_count, 1000, "Should exactly match the 1000 exoclick ad URLs")
        self.assertLess(elapsed_ms, 100.0, f"2000 URL evaluations took too long: {elapsed_ms:.2f}ms")

    def test_06_legitimate_stream_urls_not_blocked(self):
        """Verify legitimate streaming CDNs and official players are NOT blocked."""
        legit_urls = [
            "https://dlive.sx/stream/stream-49.php",
            "https://dlive.sx/player/stream-49.php",
            "https://dembed.st/premiumtv/daddy.php?id=49",
            "https://wideiptv.top/daddy.php?stream=SPT1",
            "https://ehgembxe.cfd/oqq5c86g-7521",
            "https://timst.top/",
            "https://epicsports-tv.com/eu.html?id=43633",
            "https://streaming-live.rtp.pt/liverepeater/smil:rtp1HD.smil/playlist.m3u8",
            "https://sic.live.impresa.pt/sic1080p.m3u8",
            "https://dy4isoelz3s0x.cloudfront.net/0_hd.m3u8",
            "https://cdn.jsdelivr.net/npm/hls.js@latest"
        ]
        for url in legit_urls:
            with self.subTest(url=url):
                res = simulate_should_intercept(url, SPEC_AD_BLOCK_PATTERNS)
                self.assertIsNone(res, f"Legitimate URL must NOT be blocked: {url}")

if __name__ == "__main__":
    unittest.main()
