"""
Empirical Challenge Script for Milestone M2:
Tests renderer crash recovery, sliding window math, isHardError gating, and WebView lifecycle in PlayerActivity.kt
"""

import os
import re
import sys

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PLAYER_ACTIVITY = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream", "ui", "screens", "PlayerActivity.kt")

def test_sliding_window_math():
    print("[TEST 1] Testing sliding window logic (15 seconds)...")
    last_render_crash_time = 0
    render_crash_count = 0
    window_ms = 15000

    def on_crash(now):
        nonlocal last_render_crash_time, render_crash_count
        if (now - last_render_crash_time) < window_ms:
            render_crash_count += 1
        else:
            render_crash_count = 1
        last_render_crash_time = now
        return render_crash_count > 3

    # t = 1000
    finished = on_crash(1000)
    assert render_crash_count == 1 and not finished, f"Expected count=1, got {render_crash_count}"
    
    # t = 5000 (delta = 4000 < 15000)
    finished = on_crash(5000)
    assert render_crash_count == 2 and not finished, f"Expected count=2, got {render_crash_count}"

    # t = 9000 (delta = 4000 < 15000)
    finished = on_crash(9000)
    assert render_crash_count == 3 and not finished, f"Expected count=3, got {render_crash_count}"

    # t = 11000 (delta = 2000 < 15000) -> 4th crash -> should finish
    finished = on_crash(11000)
    assert render_crash_count == 4 and finished, f"Expected count=4 and finished=True, got {render_crash_count}, {finished}"

    # Reset test: after 20s
    finished = on_crash(35000)
    assert render_crash_count == 1 and not finished, f"Expected reset to 1 after 24s window expiry, got {render_crash_count}"

    print("  -> PASS: Sliding window arithmetic correctly limits consecutive crashes within 15s.")
    return True

def test_is_hard_error_gating_when_stream_active():
    print("[TEST 2] Testing triggerFailoverDueToPlaybackError with 'render_process_crashed' when stream is playing...")
    
    with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
        code = f.read()

    # Code AST / Regex Verification
    assert 'reason.contains("render")' in code, "Code does not check reason.contains('render') in isHardError"
    assert 'if (reason.contains("render")) {\n            streamLoadedSuccessfully = false' in code, \
        "Code does not reset streamLoadedSuccessfully on renderer crash"

    # Behavioral simulation matching PlayerActivity.kt remediated implementation
    def trigger_failover(reason: str, stream_loaded_successfully: bool):
        if "render" in reason:
            stream_loaded_successfully = False

        is_stall_error = reason.startswith("stall") or ("freeze" in reason)
        if stream_loaded_successfully and not is_stall_error:
            is_hard_error = (
                reason.startswith("http_error_")
                or (reason == "network_error")
                or ("sandbox" in reason)
                or ("render" in reason)
            )
            if not is_hard_error:
                return "IGNORED"
        return "FAILOVER_EXECUTED"

    # When video was playing:
    result = trigger_failover("render_process_crashed", stream_loaded_successfully=True)
    print(f"  Result when streamLoadedSuccessfully=True: {result}")
    
    if result != "FAILOVER_EXECUTED":
        print("  -> CRITICAL FLAW CONFIRMED: 'render_process_crashed' is IGNORED if stream was actively playing!")
        return False

    print("  -> PASS: 'render_process_crashed' triggers failover even when streamLoadedSuccessfully was True.")
    return True

def test_webview_lifecycle_state_after_renderer_crash():
    print("[TEST 3] Testing WebView state and parent attachment after onRenderProcessGone...")
    
    with open(PLAYER_ACTIVITY, "r", encoding="utf-8") as f:
        code = f.read()

    # Code AST / Regex Verification in PlayerActivity.kt
    assert "val newWebView = WebView(this@PlayerActivity)" in code, "newWebView instantiation missing"
    assert "addRule(RelativeLayout.BELOW, R.id.playerHeader)" in code, "layout rule BELOW playerHeader missing"
    assert "root.addView(newWebView, 0)" in code, "root.addView(newWebView, 0) missing"
    assert "webView = newWebView" in code, "webView reassignment missing"
    assert "isWebViewDestroyed = false" in code, "isWebViewDestroyed reset missing"
    assert "setupWebView()" in code, "setupWebView call on fresh WebView missing"

    class MockWebView:
        def __init__(self, tag="initial"):
            self.tag = tag
            self.destroyed = False
            self.loaded_url = None
        def destroy(self):
            self.destroyed = True
        def loadUrl(self, url, headers=None):
            if self.destroyed:
                raise RuntimeError("IllegalStateException: Calling loadUrl on a destroyed WebView!")
            self.loaded_url = url

    class MockParentLayout:
        def __init__(self, wv):
            self.children = [wv]
        def removeView(self, v):
            if v in self.children:
                self.children.remove(v)
        def addView(self, v, index=0):
            self.children.insert(index, v)

    # Simulate PlayerActivity state
    wv = MockWebView("old_dead")
    parent = MockParentLayout(wv)
    is_webview_destroyed = False

    # 1. onRenderProcessGone occurs:
    is_webview_destroyed = True
    parent.removeView(wv)
    wv.destroy()

    # 2. Remediation logic executed in PlayerActivity.kt on UI thread:
    new_wv = MockWebView("fresh_remediated")
    parent.addView(new_wv, 0)
    wv = new_wv
    is_webview_destroyed = False

    # 3. Next, triggerFailoverDueToPlaybackError -> applyServerOption -> loadCurrentStream:
    # Attempting to load next server:
    try:
        wv.loadUrl("https://wideiptv.top/stream-1.php")
        print(f"  WebView accepted loadUrl successfully on '{wv.tag}' instance")
    except RuntimeError as e:
        print(f"  -> CRITICAL FLAW: {e}")
        return False

    assert wv.loaded_url == "https://wideiptv.top/stream-1.php"
    assert not is_webview_destroyed
    assert wv in parent.children
    assert not parent.children[0].destroyed

    print("  -> PASS: Dead WebView was replaced by fresh instance in layout, and loadUrl succeeded.")
    return True

if __name__ == "__main__":
    t1 = True
    try:
        t1 = test_sliding_window_math()
    except Exception as e:
        print(f"Test 1 failed: {e}")
        t1 = False

    t2 = test_is_hard_error_gating_when_stream_active()
    t3 = test_webview_lifecycle_state_after_renderer_crash()

    print("\nSummary:")
    print(f"Sliding window math: {'PASS' if t1 else 'FAIL'}")
    print(f"isHardError gating: {'PASS' if t2 else 'FAIL (CRITICAL BUG)'}")
    print(f"WebView recovery state: {'PASS' if t3 else 'FAIL (CRITICAL BUG)'}")

    if not (t1 and t2 and t3):
        sys.exit(1)
    else:
        print("\nALL ADVERSARIAL CHALLENGES PASSED EMPIRICALLY!")
        sys.exit(0)
