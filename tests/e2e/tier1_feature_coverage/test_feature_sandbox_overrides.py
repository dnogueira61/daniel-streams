"""
Tier 1 Feature Coverage: Sandbox Overrides & DOM Neutralization (Requirement R2)
Tests JavaScript DOM overrides for dynamic iframe sandbox neutralization.
"""

import unittest
import json
from tests.e2e.fixtures.helpers import run_node_script

class TestFeatureSandboxOverrides(unittest.TestCase):

    def _execute_harness_script(self, js_test_logic: str) -> dict:
        """
        Executes JavaScript DOM test logic inside the Node.js harness and returns JSON result.
        """
        script = f"""
        // Injected Dynamic Sandbox Neutralization Logic
        try {{
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

            function unSandboxIframe(ifr) {{
                if (!ifr) return;
                var hadSandbox = ifr.hasAttribute && ifr.hasAttribute('sandbox');
                try {{ ifr.removeAttribute('sandbox'); }} catch(e) {{}}
                if (ifr.src && ifr.src.indexOf('http') === 0) {{
                    try {{ ifr.src = ifr.src; }} catch(e) {{}}
                }}
            }}
        }} catch(e) {{}}

        // Run user test assertion logic
        {js_test_logic}
        """
        code, stdout, stderr = run_node_script(script)
        if code != 0:
            raise RuntimeError(f"Node execution failed with code {code}: {stderr}\nOutput: {stdout}")
        try:
            return json.loads(stdout.strip())
        except json.JSONDecodeError as e:
            raise ValueError(f"Failed to parse JSON output: '{stdout}' (Error: {e})")

    def test_01_set_attribute_sandbox_neutralization(self):
        """Verify calling setAttribute('sandbox', ...) is blocked."""
        js = """
        var el = document.createElement('iframe');
        el.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        var result = {
            hasAttr: el.hasAttribute('sandbox'),
            getAttr: el.getAttribute('sandbox')
        };
        console.log(JSON.stringify(result));
        """
        res = self._execute_harness_script(js)
        self.assertFalse(res["hasAttr"], "setAttribute('sandbox') must be neutralized")
        self.assertIsNone(res["getAttr"], "getAttribute('sandbox') must return null")

    def test_02_set_attribute_ns_sandbox_neutralization(self):
        """Verify calling setAttributeNS(null, 'sandbox', ...) is blocked."""
        js = """
        var el = document.createElement('iframe');
        el.setAttributeNS(null, 'sandbox', 'allow-scripts');
        var result = {
            hasAttr: el.hasAttribute('sandbox'),
            getAttr: el.getAttribute('sandbox')
        };
        console.log(JSON.stringify(result));
        """
        res = self._execute_harness_script(js)
        self.assertFalse(res["hasAttr"], "setAttributeNS('sandbox') must be neutralized")
        self.assertIsNone(res["getAttr"], "getAttribute('sandbox') must return null")

    def test_03_toggle_attribute_sandbox_neutralization(self):
        """Verify calling toggleAttribute('sandbox', true) returns false and does not set attribute."""
        js = """
        var el = document.createElement('iframe');
        var toggleRes = el.toggleAttribute('sandbox', true);
        var result = {
            toggleRes: toggleRes,
            hasAttr: el.hasAttribute('sandbox')
        };
        console.log(JSON.stringify(result));
        """
        res = self._execute_harness_script(js)
        self.assertFalse(res["toggleRes"], "toggleAttribute('sandbox') must return false")
        self.assertFalse(res["hasAttr"], "toggleAttribute must not set sandbox attribute")

    def test_04_has_and_get_attribute_sandbox_masking(self):
        """Verify hasAttribute and getAttribute mask existing sandbox attributes."""
        js = """
        var el = document.createElement('iframe');
        // Simulate pre-existing attribute from static parser
        el.attributes.set('sandbox', 'allow-scripts');
        var result = {
            hasAttr: el.hasAttribute('sandbox'),
            getAttr: el.getAttribute('sandbox')
        };
        console.log(JSON.stringify(result));
        """
        res = self._execute_harness_script(js)
        self.assertFalse(res["hasAttr"], "hasAttribute('sandbox') must be masked to false")
        self.assertIsNone(res["getAttr"], "getAttribute('sandbox') must be masked to null")

    def test_05_mutation_observer_removes_sandbox_and_refreshes_src(self):
        """Verify dynamic MutationObserver strips sandbox and re-navigates iframe.src."""
        js = """
        var iframe = document.createElement('iframe');
        iframe.attributes.set('sandbox', 'allow-scripts');
        iframe.src = 'https://dembed.st/premiumtv/daddy.php?id=49';
        var initialReloadCount = iframe.srcReloaded || 0;

        // MutationObserver handler logic
        var observer = new MutationObserver(function(mutations) {
            for (var i = 0; i < mutations.length; i++) {
                var m = mutations[i];
                if (m.type === 'attributes' && m.attributeName && m.attributeName.toLowerCase() === 'sandbox') {
                    unSandboxIframe(m.target);
                }
            }
        });

        observer.observe(iframe, { attributes: true, attributeFilter: ['sandbox'] });
        observer.trigger([{ type: 'attributes', attributeName: 'sandbox', target: iframe }]);

        var result = {
            hasAttrRaw: iframe.attributes.has('sandbox'),
            reloadCount: iframe.srcReloaded || 0
        };
        console.log(JSON.stringify(result));
        """
        res = self._execute_harness_script(js)
        self.assertFalse(res["hasAttrRaw"], "MutationObserver must strip sandbox attribute from DOM Map")
        self.assertGreaterEqual(res["reloadCount"], 1, "iframe.src must be refreshed to drop Chromium sandbox context")

    def test_06_case_insensitive_sandbox_neutralization(self):
        """Verify case-insensitive matching for SandBox and SANDBOX."""
        js = """
        var el = document.createElement('iframe');
        el.setAttribute('SandBox', 'allow-forms');
        el.setAttribute('SANDBOX', 'allow-modals');
        var result = {
            hasAttrLower: el.hasAttribute('sandbox'),
            hasAttrUpper: el.hasAttribute('SANDBOX'),
            getAttr: el.getAttribute('SandBox')
        };
        console.log(JSON.stringify(result));
        """
        res = self._execute_harness_script(js)
        self.assertFalse(res["hasAttrLower"], "Case variations like SandBox must be neutralized")
        self.assertFalse(res["hasAttrUpper"], "Uppercase SANDBOX must be neutralized")
        self.assertIsNone(res["getAttr"], "getAttribute on case variation must return null")

if __name__ == "__main__":
    unittest.main()
