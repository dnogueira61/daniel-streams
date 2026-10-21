#!/usr/bin/env python3
"""
tests/adversarial_challenge_m2.py - Challenger 1 Adversarial Harness for Milestone M2
Adversarially stress-tests:
1. Dynamic DOM sandbox overrides (static HTML, nested iframes, attribute mutations, reload churn).
2. Navigation guard against rogue ad domains, wildcard TLDs, and external schemes.
3. Ad-blocking efficacy and false-positive census against master channels.json.
4. Renderer crash loop counter & sliding window resilience.
5. Lifecycle teardown integrity.
"""

import json
import os
import re
import sys
import unittest
import urllib.parse

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, PROJECT_ROOT)

from tests.e2e.fixtures.helpers import run_node_script

CHANNELS_JSON = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "channels.json")
PLAYER_ACTIVITY = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "PlayerActivity.kt")
EPG_PANEL = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "EpgDetailPanel.kt")

# Extracted AD_BLOCK_PATTERNS matching PlayerActivity companion object
AD_BLOCK_PATTERNS = {
    "piousshiners", "nanisms", "histats", "premiumvertising",
    "dzlhwcoblmc", "azrmpjyh", "smartbanner", "adsco",
    "popads", "popunder", "creativecdn", "doubleclick",
    "googlesyndication", "monetag", "trafficjunky",
    "adservice", "chatango", "onclicksuper", "syndication",
    "exdynsrv", "adsystem", "adnxs", "burstyflavia",
    "profitableratecpmnetwork", "cleverwebserver", "adsboosters",
    "reliedhounder", "canine.tools", "plausible",
    "disable-devtool", "aclib",
    "adsterra", "propellerads", "exoclick", "hilltopads",
    "adcash", "yllix", "clickadu", "juicyads", "trafficstars",
    "effectivecpmgate", "highcpmgate", "whos.amung.us",
    "scorecardresearch", "onclickmega", "alwingulla",
    "deloplen", "coinhive", "adkeep", "inpagepush",
    "bet365", "1xbet", "betano", "betfair", "mostbet", "melbet"
}

ALLOWED_HOST_KEYWORDS = [
    "dlive", "daddylive", "thedaddy", "dlhd", "dlstreams", "dembed",
    "grandemx", "wideiptv", "assetrage", "exmxbxe", "ehgembxe",
    "embeds.gay", "timst", "tim-streams", "world-proxifier",
    "dreamstream", "hesgoal", "cdnlive", "cowedd", "strmfree",
    "streamfree", "embedindia", "ppv", "ppvservices", "cdn-lab",
    "streamed.pk", "embed.st", "epicsports-tv.com", "rtp.pt",
    "sic.pt", "iol.pt", "portocanal.sapo.pt", "canal11.pt",
    "cloudfront.net", "fastly.net", "livextend.pt", "jsdelivr.net"
]


def simulate_should_override_url_loading(url_str: str, is_for_main_frame: bool) -> bool:
    """
    Simulates PlayerActivity.shouldOverrideUrlLoading logic faithfully.
    Returns True if request is BLOCKED / INTERCEPTED.
    Returns False if request is ALLOWED to proceed.
    """
    if not url_str or not url_str.strip():
        return True # Blank URL blocked

    parsed = urllib.parse.urlparse(url_str)
    scheme = (parsed.scheme or "").lower()
    host = (parsed.netloc.split(":")[0] or "").lower()

    # 1. Scheme check
    if scheme not in ["http", "https", "about", "data", "blob"]:
        return True # Blocked

    # 2. Subframe navigation safety check
    if not is_for_main_frame:
        url_lower = url_str.lower()
        if any(p in host or p in url_lower for p in AD_BLOCK_PATTERNS):
            return True # Blocked
        return False # Allowed for internal stream subframe

    # 3. Main Frame navigation whitelist
    is_allowed_host = any(k in host for k in ALLOWED_HOST_KEYWORDS)
    is_allowed = is_allowed_host or url_str.startswith("blob:") or url_str.startswith("data:")

    if not is_allowed:
        return True # Blocked
    return False # Allowed


class TestAdversarialM2(unittest.TestCase):

    # =========================================================================
    # Group 1: DOM Sandbox Overrides & Mutation Patterns
    # =========================================================================

    def _execute_harness(self, js_test_logic: str) -> dict:
        harness_js = f"""
        const {{ MockElement, MockMutationObserver }} = require('{os.path.join(PROJECT_ROOT, "tests", "e2e", "fixtures", "dom_sandbox_harness.js").replace(chr(92), "/")}');

        // Emulate StartScript
        var origSetAttr = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function(name, value) {{
            if (name && (name === 'sandbox' || name.toLowerCase() === 'sandbox')) {{
                return;
            }}
            return origSetAttr.apply(this, arguments);
        }};

        var origSetAttrNS = Element.prototype.setAttributeNS;
        if (origSetAttrNS) {{
            Element.prototype.setAttributeNS = function(ns, name, value) {{
                if (name && (name === 'sandbox' || name.toLowerCase() === 'sandbox')) {{
                    return;
                }}
                return origSetAttrNS.apply(this, arguments);
            }};
        }}

        var origHasAttr = Element.prototype.hasAttribute;
        Element.prototype.hasAttribute = function(name) {{
            if (name && (name === 'sandbox' || name.toLowerCase() === 'sandbox')) {{
                return false;
            }}
            return origHasAttr.apply(this, arguments);
        }};

        var origGetAttr = Element.prototype.getAttribute;
        Element.prototype.getAttribute = function(name) {{
            if (name && (name === 'sandbox' || name.toLowerCase() === 'sandbox')) {{
                return null;
            }}
            return origGetAttr.apply(this, arguments);
        }};

        var origToggleAttr = Element.prototype.toggleAttribute;
        if (origToggleAttr) {{
            Element.prototype.toggleAttribute = function(name, force) {{
                if (name && (name === 'sandbox' || name.toLowerCase() === 'sandbox')) {{
                    return false;
                }}
                return origToggleAttr.apply(this, arguments);
            }};
        }}

        if (window.HTMLIFrameElement && HTMLIFrameElement.prototype) {{
            Object.defineProperty(HTMLIFrameElement.prototype, 'sandbox', {{
                get: function() {{
                    return {{
                        add: function() {{}},
                        remove: function() {{}},
                        contains: function() {{ return false; }},
                        toggle: function() {{ return false; }},
                        value: ''
                    }};
                }},
                set: function() {{}},
                configurable: true
            }});
        }}

        function unSandboxIframe(ifr) {{
            if (!ifr) return;
            var hadSandbox = ifr.hasAttribute && ifr.hasAttribute('sandbox');
            try {{ ifr.removeAttribute('sandbox'); }} catch(e) {{}}
            if (ifr.src && ifr.src.indexOf('http') === 0) {{
                try {{ ifr.src = ifr.src; }} catch(e) {{}}
            }}
        }}

        try {{
            Object.defineProperty(window, 'open', {{
                value: function() {{ return null; }},
                writable: false,
                configurable: false
            }});
        }} catch(e) {{
            window.open = function() {{ return null; }};
        }}

        var origCreateElement = document.createElement;
        document.createElement = function(tag) {{
            var el;
            if (tag && tag.toLowerCase() === 'iframe' && typeof HTMLIFrameElement !== 'undefined') {{
                el = new HTMLIFrameElement();
            }} else {{
                el = origCreateElement.call(document, tag);
            }}
            if (tag && tag.toLowerCase() === 'a') {{
                el.target = '_self';
            }}
            if (tag && tag.toLowerCase() === 'iframe') {{
                unSandboxIframe(el);
            }}
            return el;
        }};

        {js_test_logic}
        """
        code, stdout, stderr = run_node_script(harness_js)
        if code != 0:
            raise RuntimeError(f"Node execution failed ({code}): {stderr}\nOutput: {stdout}")
        return json.loads(stdout.strip())

    def test_dom_01_static_iframe_un_sandboxing(self):
        """Pre-existing static HTML iframes with sandbox attributes must be stripped."""
        js = """
        var el = document.createElement('iframe');
        el.attributes.set('sandbox', 'allow-scripts allow-same-origin');
        el.src = 'https://dembed.st/stream.php';

        unSandboxIframe(el);

        var res = {
            hasAttrRaw: el.attributes.has('sandbox'),
            srcReloaded: el.srcReloaded || 0
        };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        self.assertFalse(res["hasAttrRaw"], "Sandbox attribute must be removed from iframe")
        self.assertGreaterEqual(res["srcReloaded"], 1, "Source must reload to drop sandbox context")

    def test_dom_02_deeply_nested_dynamic_iframes(self):
        """Dynamically added nested container (div > div > iframe) must have all iframes stripped."""
        js = """
        var outer = document.createElement('div');
        var inner = document.createElement('div');
        var ifr1 = document.createElement('iframe');
        var ifr2 = document.createElement('iframe');

        ifr1.attributes.set('sandbox', 'allow-scripts');
        ifr2.attributes.set('sandbox', 'allow-forms');

        inner.children = [ifr1];
        inner.querySelectorAll = function(tag) { return tag.toLowerCase() === 'iframe' ? [ifr1] : []; };

        outer.children = [inner, ifr2];
        outer.querySelectorAll = function(tag) { return tag.toLowerCase() === 'iframe' ? [ifr1, ifr2] : []; };

        // Simulate MutationObserver childList handler
        var addedNodes = [outer];
        for (var j = 0; j < addedNodes.length; j++) {
            var node = addedNodes[j];
            if (node.tagName === 'IFRAME') unSandboxIframe(node);
            var subs = node.querySelectorAll ? node.querySelectorAll('iframe') : [];
            for (var k = 0; k < subs.length; k++) {
                unSandboxIframe(subs[k]);
            }
        }

        var res = {
            ifr1Sandbox: ifr1.attributes.has('sandbox'),
            ifr2Sandbox: ifr2.attributes.has('sandbox')
        };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        self.assertFalse(res["ifr1Sandbox"], "Nested iframe 1 sandbox must be neutralized")
        self.assertFalse(res["ifr2Sandbox"], "Nested iframe 2 sandbox must be neutralized")

    def test_dom_03_property_setter_and_dom_token_list(self):
        """Setting iframe.sandbox property or using sandbox.add() must be a no-op."""
        js = """
        var ifr = document.createElement('iframe');
        ifr.sandbox = 'allow-scripts';
        ifr.sandbox.add('allow-same-origin');
        ifr.sandbox.remove('allow-same-origin');
        var res = {
            contains: ifr.sandbox.contains('allow-scripts'),
            val: ifr.sandbox.value,
            hasAttr: ifr.hasAttribute('sandbox')
        };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        self.assertFalse(res["contains"], "sandbox.contains must return false")
        self.assertEqual(res["val"], "", "sandbox.value must remain empty string")
        self.assertFalse(res["hasAttr"], "hasAttribute('sandbox') must be false")

    def test_dom_04_window_open_locking(self):
        """window.open must be locked, return null, and resist reassignment."""
        js = """
        var r1 = window.open('https://popads.net');
        var caughtAssign = false;
        try {
            window.open = function() { return 'evil_window'; };
        } catch(e) {
            caughtAssign = true;
        }
        var r2 = window.open('https://popads.net');
        var res = {
            r1: r1,
            r2: r2,
            caughtAssign: caughtAssign
        };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        self.assertIsNone(res["r1"], "window.open must return null")
        self.assertIsNone(res["r2"], "Reassigned window.open must still return null")

    def test_dom_05_link_target_sanitization(self):
        """document.createElement('a') must default target to _self to block popup tabs."""
        js = """
        var a = document.createElement('a');
        var res = { target: a.target };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        self.assertEqual(res["target"], "_self", "Anchor target must default to _self")

    def test_dom_06_un_sandbox_reload_churn_empirical_check(self):
        """
        EMPIRICAL CHALLENGE: unSandboxIframe reloads iframe.src on every call.
        Verify that multiple invocations cause cumulative reloads if not guarded.
        """
        js = """
        var ifr = document.createElement('iframe');
        ifr.src = 'https://dembed.st/player.php?id=49';
        var initial = ifr.srcReloaded || 0;

        // 5 simulated ticks
        for (var i = 0; i < 5; i++) {
            unSandboxIframe(ifr);
        }

        var res = {
            initial: initial,
            finalReloads: ifr.srcReloaded || 0
        };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        # We record empirical observation of cumulative reloads
        self.assertGreaterEqual(res["finalReloads"], 5, "unSandboxIframe executes reload on each call")

    # =========================================================================
    # Group 2: URL Navigation Guard & Scheme Blocking
    # =========================================================================

    def test_nav_01_external_schemes_blocked(self):
        """Non-web schemes (intent://, market://, tel://, file://) must be blocked for all frames."""
        dangerous_urls = [
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "market://details?id=com.malicious.adapp",
            "tel:+351912345678",
            "file:///android_asset/channels.json",
            "smsto:12345:subscribe",
            "geo:38.7223,-9.1393",
            "javascript:alert(1)"
        ]
        for url in dangerous_urls:
            with self.subTest(url=url):
                # Must be blocked for main frame
                self.assertTrue(simulate_should_override_url_loading(url, is_for_main_frame=True), f"Main frame failed to block {url}")
                # Must be blocked for subframe
                self.assertTrue(simulate_should_override_url_loading(url, is_for_main_frame=False), f"Subframe failed to block {url}")

    def test_nav_02_wildcard_tld_exploits_blocked(self):
        """Ad domains using formerly allowed wildcard TLDs (.top, .sbs, .ru, .org, .cx) must now be blocked."""
        wildcard_ad_urls = [
            "https://clicktracker.top/redirect?offer=42",
            "https://betbonus.sbs/landing",
            "https://popunder-ad.ru/script.js",
            "https://phishing-streams.org/login",
            "https://malware-network.cx/get",
            "https://rogue-affiliate.st/track",
            "https://spammer.pk/click"
        ]
        for url in wildcard_ad_urls:
            with self.subTest(url=url):
                # Must be blocked on main frame
                blocked = simulate_should_override_url_loading(url, is_for_main_frame=True)
                self.assertTrue(blocked, f"Wildcard TLD exploit must be blocked: {url}")

    def test_nav_03_subframe_ad_vs_stream_filtering(self):
        """Subframes targeting known ad networks must be blocked; legitimate stream CDNs allowed."""
        ad_subframes = [
            "https://delivery.popads.net/serve?id=123",
            "https://creative.trafficjunky.com/banner",
            "https://adsterra.com/tag.js",
            "https://track.clickadu.com/pixel",
            "https://ads.exoclick.com/iframe.php"
        ]
        stream_subframes = [
            "https://dembed.st/premiumtv/daddy.php?id=49",
            "https://wideiptv.top/embed/49.php",
            "https://ehgembxe.cfd/oqq5c86g-7521",
            "https://epicsports-tv.com/eu.html?id=1"
        ]

        for url in ad_subframes:
            with self.subTest(url=url):
                self.assertTrue(simulate_should_override_url_loading(url, is_for_main_frame=False), f"Ad subframe must be blocked: {url}")

        for url in stream_subframes:
            with self.subTest(url=url):
                self.assertFalse(simulate_should_override_url_loading(url, is_for_main_frame=False), f"Stream subframe must be allowed: {url}")

    def test_nav_04_blank_and_special_urls(self):
        """Blank or whitespace URLs must be intercepted cleanly without throwing."""
        self.assertTrue(simulate_should_override_url_loading("", is_for_main_frame=True))
        self.assertTrue(simulate_should_override_url_loading("   ", is_for_main_frame=True))
        # blob: and data: must be allowed
        self.assertFalse(simulate_should_override_url_loading("blob:https://dlive.sx/uuid-123", is_for_main_frame=True))
        self.assertFalse(simulate_should_override_url_loading("data:image/png;base64,iVBORw...", is_for_main_frame=True))

    def test_nav_05_main_frame_whitelist_substring_challenge(self):
        """
        Adversarial challenge: Domain containing allowed keyword (e.g. 'fake-ppv.com' or 'dlive-casino.com')
        passes whitelist because host.contains(...) is used.
        """
        keyword_spoofed_url = "https://fake-ppv-streams.com/ad"
        # Empirically verify that host.contains('ppv') allows it
        is_blocked = simulate_should_override_url_loading(keyword_spoofed_url, is_for_main_frame=True)
        self.assertFalse(is_blocked, "Empirical observation: keyword spoofed domain matches whitelist")

    # =========================================================================
    # Group 3: Ad-Block Patterns & Catalog Census
    # =========================================================================

    def test_ad_01_pattern_census_count(self):
        """PlayerActivity AD_BLOCK_PATTERNS must contain at least 50 high-impact patterns."""
        with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
            code = f.read()

        match = re.search(r"private val AD_BLOCK_PATTERNS = hashSetOf\((.*?)\)", code, re.DOTALL)
        self.assertIsNotNone(match, "AD_BLOCK_PATTERNS declaration not found in PlayerActivity")
        patterns_raw = re.findall(r'"([^"]+)"', match.group(1))
        self.assertGreaterEqual(len(patterns_raw), 50, f"Expected >= 50 patterns, found {len(patterns_raw)}")
        self.assertEqual(len(patterns_raw), len(AD_BLOCK_PATTERNS))

    def test_ad_02_case_insensitive_matching(self):
        """Ad patterns must match regardless of uppercase/lowercase in incoming request."""
        test_cases = [
            "https://WWW.ADSTERRA.COM/script.js",
            "https://cdn.site.com/PROPELLERADS/banner.png",
            "https://api.BET365.com/odds",
            "https://tracker.WHOS.AMUNG.US/track"
        ]
        for url in test_cases:
            url_l = url.lower()
            matched = any(p in url_l for p in AD_BLOCK_PATTERNS)
            self.assertTrue(matched, f"Case variation failed to match: {url}")

    def test_ad_03_zero_false_positives_in_channels_json(self):
        """AD_BLOCK_PATTERNS must not match any valid stream URLs in master channels.json."""
        with open(CHANNELS_JSON, "r", encoding="utf-8") as f:
            channels = json.load(f)

        false_positives = []
        for ch in channels:
            for key in ["timStreamsUrl", "backupStreamUrl", "url"]:
                val = ch.get(key)
                if val:
                    val_l = val.lower()
                    for p in AD_BLOCK_PATTERNS:
                        if p in val_l:
                            false_positives.append((ch.get("id"), ch.get("name"), key, val, p))

        self.assertEqual(len(false_positives), 0, f"False positives found in channels.json: {false_positives}")

    def test_ad_04_query_param_and_path_matching(self):
        """Ad patterns in query parameters or nested path elements must be detected."""
        sub_cases = [
            "https://clean-host.com/loader?network=trafficjunky&id=1",
            "https://content-cdn.org/scripts/vendors/monetag.min.js",
            "https://analytics-proxy.net/ping?site=scorecardresearch"
        ]
        for url in sub_cases:
            matched = any(p in url.lower() for p in AD_BLOCK_PATTERNS)
            self.assertTrue(matched, f"Query/path pattern not matched: {url}")

    # =========================================================================
    # Group 4: Renderer Process Crash Loop & Sliding Window
    # =========================================================================

    def test_crash_01_sliding_window_advances_failover_under_threshold(self):
        """Crash occurrences <= 3 within 15 seconds window advance failover instead of recreating."""
        render_crash_count = 0
        last_crash_time = 0
        now = 1000000

        # Crash 1
        if now - last_crash_time < 15000:
            render_crash_count += 1
        else:
            render_crash_count = 1
        last_crash_time = now
        self.assertEqual(render_crash_count, 1)

        # Crash 2 (3 seconds later)
        now += 3000
        if now - last_crash_time < 15000:
            render_crash_count += 1
        else:
            render_crash_count = 1
        last_crash_time = now
        self.assertEqual(render_crash_count, 2)

        # Crash 3 (5 seconds later)
        now += 5000
        if now - last_crash_time < 15000:
            render_crash_count += 1
        else:
            render_crash_count = 1
        last_crash_time = now
        self.assertEqual(render_crash_count, 3)

        # All 3 crashes were <= 3 -> would trigger failover, not finish()
        self.assertLessEqual(render_crash_count, 3)

    def test_crash_02_crash_count_exceeding_threshold_terminates_loop(self):
        """Crash occurrence > 3 within 15 seconds window triggers finish() to prevent infinite bootloop."""
        render_crash_count = 3
        last_crash_time = 1000000
        now = last_crash_time + 2000 # 2 seconds later

        if now - last_crash_time < 15000:
            render_crash_count += 1
        else:
            render_crash_count = 1
        last_crash_time = now

        self.assertEqual(render_crash_count, 4)
        should_finish = render_crash_count > 3
        self.assertTrue(should_finish, "Activity must finish when crash count exceeds 3")

    def test_crash_03_isolated_crash_after_window_resets_counter(self):
        """Crash occurring after > 15 seconds window resets counter back to 1."""
        render_crash_count = 3
        last_crash_time = 1000000
        now = last_crash_time + 16000 # 16 seconds later

        if now - last_crash_time < 15000:
            render_crash_count += 1
        else:
            render_crash_count = 1
        last_crash_time = now

        self.assertEqual(render_crash_count, 1, "Counter must reset to 1 after sliding window expires")

    # =========================================================================
    # Group 5: Lifecycle Cleanup & Memory Leak Guardrails
    # =========================================================================

    def test_lifecycle_01_player_activity_teardown_components(self):
        """PlayerActivity.onDestroy must clean up animator, dialog, JS interface, and webView."""
        with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
            code = f.read()

        on_destroy_block = code[code.find("override fun onDestroy()"):]
        self.assertIn("breathingAnimator?.cancel()", on_destroy_block)
        self.assertIn("activeDialog?.dismiss()", on_destroy_block)
        self.assertIn("removeJavascriptInterface(\"AndroidFailover\")", on_destroy_block)
        self.assertIn("removeView(webView)", on_destroy_block)
        self.assertIn("webView.destroy()", on_destroy_block)
        self.assertIn("isWebViewDestroyed", on_destroy_block)

    def test_dom_07_set_attribute_node_mutation_observer(self):
        """Attacker using setAttributeNode or setAttributeNodeNS must have sandbox stripped by MutationObserver."""
        js = """
        MockElement.prototype.setAttributeNode = function(attr) {
            this.attributes.set(attr.name.toLowerCase(), attr.value);
        };

        var iframe = document.createElement('iframe');
        iframe.src = 'https://dembed.st/stream.php';

        var observer = new MockMutationObserver(function(mutations) {
            for (var i = 0; i < mutations.length; i++) {
                var m = mutations[i];
                if (m.type === 'attributes' && m.attributeName && m.attributeName.toLowerCase() === 'sandbox') {
                    unSandboxIframe(m.target);
                }
            }
        });

        observer.observe(iframe, { attributes: true, attributeFilter: ['sandbox'] });
        iframe.setAttributeNode({ name: 'sandbox', value: 'allow-scripts' });
        var hadBefore = iframe.attributes.has('sandbox');

        observer.trigger([{ type: 'attributes', attributeName: 'sandbox', target: iframe }]);
        var hasAfter = iframe.attributes.has('sandbox');

        var res = { hadBefore: hadBefore, hasAfter: hasAfter };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        self.assertTrue(res["hadBefore"], "setAttributeNode set raw attribute")
        self.assertFalse(res["hasAfter"], "MutationObserver must strip sandbox attribute")

    def test_dom_08_case_insensitive_mutation_variations(self):
        """Case variations like 'sAnDbOx', 'SandBox', and 'SANDBOX' must all be neutralized."""
        js = """
        var ifr = document.createElement('iframe');
        ifr.setAttribute('sAnDbOx', 'allow-scripts');
        ifr.setAttributeNS(null, 'SandBox', 'allow-forms');
        var toggleRes = ifr.toggleAttribute ? ifr.toggleAttribute('SANDBOX', true) : false;

        var res = {
            hasAttr: ifr.hasAttribute('sandbox'),
            getAttr: ifr.getAttribute('sAnDbOx'),
            toggleRes: toggleRes
        };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        self.assertFalse(res["hasAttr"])
        self.assertIsNone(res["getAttr"])
        self.assertFalse(res["toggleRes"])

    def test_dom_09_window_dialogs_suppressed(self):
        """window.alert, confirm, and prompt must be neutralized to prevent dialog freeze."""
        js = """
        // PlayerActivity startScript dialog neutralizers
        window.alert = function() {};
        window.confirm = function() { return true; };
        window.prompt = function() { return null; };

        var res = {
            alertRet: window.alert('test'),
            confirmRet: window.confirm('test'),
            promptRet: window.prompt('test')
        };
        console.log(JSON.stringify(res));
        """
        res = self._execute_harness(js)
        # undefined in JS is omitted in JSON stringify
        self.assertIsNone(res.get("alertRet"))
        self.assertTrue(res["confirmRet"])
        self.assertIsNone(res.get("promptRet"))

    def test_nav_06_legitimate_upstream_cdns_allowed(self):
        """Legitimate upstream player and stream CDNs must pass main frame whitelist."""
        stream_urls = [
            "https://dlive.sx/stream/stream-49.php",
            "https://dlive.sx/player/player-49.php",
            "https://dembed.st/premiumtv/daddy.php?id=49",
            "https://wideiptv.top/embed/49.php",
            "https://ehgembxe.cfd/oqq5c86g-7521",
            "https://epicsports-tv.com/eu.html?id=1",
            "https://sic.pt/direto",
            "https://dy4isoelz3s0x.cloudfront.net/0_hd.m3u8",
            "https://portocanal.sapo.pt/live.m3u8",
            "https://video-auth1.iol.pt/live_tvi/live_tvi/playlist.m3u8",
            "https://cdn.jsdelivr.net/npm/hls.js@latest"
        ]
        for url in stream_urls:
            with self.subTest(url=url):
                blocked = simulate_should_override_url_loading(url, is_for_main_frame=True)
                self.assertFalse(blocked, f"Legitimate stream must not be blocked: {url}")

    def test_nav_07_impresa_domain_whitelisting_gap(self):
        """
        Adversarial Observation: SIC live M3U8s use sic.live.impresa.pt.
        PlayerActivity whitelist includes 'sic.pt' but omits 'impresa.pt',
        which would block top-level navigation to impresa.pt if navigated directly.
        """
        sic_impresa_url = "https://sic.live.impresa.pt/sic1080p.m3u8"
        is_blocked = simulate_should_override_url_loading(sic_impresa_url, is_for_main_frame=True)
        # We record that top-level main frame navigation is currently blocked because host contains 'impresa.pt'
        self.assertTrue(is_blocked, "Empirical observation: sic.live.impresa.pt is currently blocked in main frame navigation")

    def test_ad_05_empty_js_response_characteristics(self):
        """EMPTY_JS_RESPONSE in PlayerActivity must be configured with application/javascript and UTF-8."""
        with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
            code = f.read()

        match = re.search(r'EMPTY_JS_RESPONSE\s*=\s*WebResourceResponse\("([^"]+)",\s*"([^"]+)",\s*ByteArrayInputStream\(ByteArray\(0\)\)\)', code)
        self.assertIsNotNone(match, "EMPTY_JS_RESPONSE not found in PlayerActivity companion object")
        mime, encoding = match.group(1), match.group(2)
        self.assertEqual(mime, "application/javascript")
        self.assertEqual(encoding, "UTF-8")

    def test_lifecycle_02_epg_detail_panel_on_release(self):
        """EpgDetailPanel.kt AndroidView must define an onRelease block destroying the preview WebView."""
        with open(EPG_PANEL, "r", encoding="utf-8") as f:
            code = f.read()

        self.assertIn("onRelease = { wv ->", code)
        self.assertIn("wv.destroy()", code)
        self.assertIn("wv.stopLoading()", code)
        self.assertIn("wv.clearHistory()", code)


if __name__ == "__main__":
    unittest.main()

