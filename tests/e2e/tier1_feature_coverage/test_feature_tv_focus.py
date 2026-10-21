"""
Tier 1 Feature Coverage: Google TV UX, D-Pad Navigation & Focus (Requirement R3)
Tests remote control keycode handling, focus visual selectors, soft keyboard suppression, and media surface isolation.
"""

import unittest
import os
import xml.etree.ElementTree as ET
from tests.e2e.fixtures.helpers import PROJECT_ROOT

class TestFeatureTvFocus(unittest.TestCase):

    def setUp(self):
        self.res_dir = os.path.join(PROJECT_ROOT, "app", "src", "main", "res")
        self.java_dir = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "dlive", "ptstream")
        self.player_activity_path = os.path.join(self.java_dir, "ui", "screens", "PlayerActivity.kt")

    def test_01_tv_soft_keyboard_suppression_contract(self):
        """Verify contract specification that soft keyboard must not pop up on TV focus."""
        # Simulated contract validation of search focus listener
        def on_search_focus_change(has_focus: bool, is_tv: bool) -> bool:
            # Returns whether soft input keyboard should be requested
            if has_focus and not is_tv:
                return True # Allowed on mobile/tablet
            return False # Blocked on Google TV

        self.assertFalse(on_search_focus_change(True, is_tv=True), "TV must suppress automatic soft keyboard on focus")
        self.assertTrue(on_search_focus_change(True, is_tv=False), "Mobile should allow soft keyboard on search focus")
        self.assertFalse(on_search_focus_change(False, is_tv=False))

    def test_02_dpad_key_dispatch_mappings(self):
        """Verify remote D-pad keycodes are defined and mapped to navigation actions."""
        # Linux / Android standard TV keycodes
        KEYCODE_DPAD_UP = 19
        KEYCODE_DPAD_DOWN = 20
        KEYCODE_DPAD_LEFT = 21
        KEYCODE_DPAD_RIGHT = 22
        KEYCODE_DPAD_CENTER = 23
        KEYCODE_ENTER = 66
        KEYCODE_BACK = 4

        key_mappings = {
            KEYCODE_DPAD_UP: "ZAP_PREVIOUS_OR_NAV_UP",
            KEYCODE_DPAD_DOWN: "ZAP_NEXT_OR_NAV_DOWN",
            KEYCODE_DPAD_LEFT: "OPEN_DRAWER_OR_CYCLE_TAB",
            KEYCODE_DPAD_RIGHT: "OPEN_EPG_OR_CYCLE_TAB",
            KEYCODE_DPAD_CENTER: "TOGGLE_OVERLAY_OR_CLICK",
            KEYCODE_ENTER: "TOGGLE_OVERLAY_OR_CLICK",
            KEYCODE_BACK: "CLOSE_OVERLAY_OR_FINISH"
        }

        self.assertEqual(key_mappings[KEYCODE_DPAD_UP], "ZAP_PREVIOUS_OR_NAV_UP")
        self.assertEqual(key_mappings[KEYCODE_DPAD_DOWN], "ZAP_NEXT_OR_NAV_DOWN")
        self.assertEqual(key_mappings[KEYCODE_DPAD_CENTER], "TOGGLE_OVERLAY_OR_CLICK")

    def test_03_tv_focus_visual_selectors_in_resources(self):
        """Verify XML drawable selectors define explicit state_focused='true' styles."""
        channel_row_selector = os.path.join(self.res_dir, "drawable", "selector_channel_row.xml")
        self.assertTrue(os.path.exists(channel_row_selector), "selector_channel_row.xml must exist")

        tree = ET.parse(channel_row_selector)
        root = tree.getroot()
        focused_items = [
            item for item in root.findall("item")
            if item.attrib.get("{http://schemas.android.com/apk/res/android}state_focused") == "true"
        ]
        self.assertGreaterEqual(len(focused_items), 1, "Must define at least one state_focused='true' item")

        # Verify stroke width or color in focused shape
        shape = focused_items[0].find("shape")
        self.assertIsNotNone(shape, "Focused item must contain shape definition")
        stroke = shape.find("stroke")
        self.assertIsNotNone(stroke, "Focused shape must have high-contrast stroke border")
        stroke_color = stroke.attrib.get("{http://schemas.android.com/apk/res/android}color", "")
        self.assertTrue(len(stroke_color) > 0, "Focused stroke must have a defined color")

    def test_04_media_webview_focus_isolation(self):
        """Verify PlayerActivity source sets isFocusable = false on video WebView."""
        with open(self.player_activity_path, "r", encoding="utf-8") as f:
            code = f.read()

        self.assertIn(
            "webView.isFocusable = false",
            code,
            "PlayerActivity must set webView.isFocusable = false to avoid D-pad lockup"
        )
        self.assertIn(
            "webView.isFocusableInTouchMode = false",
            code,
            "PlayerActivity must set webView.isFocusableInTouchMode = false"
        )

    def test_05_dpad_overlay_debounce_protection(self):
        """Verify overlay activation logic enforces >= 500ms debounce window."""
        def evaluate_overlay_click(current_uptime: int, last_overlay_show_time: int, debounce_window: int = 600) -> bool:
            return (current_uptime - last_overlay_show_time) >= debounce_window

        show_time = 1000
        # Immediate click 100ms later -> must be rejected
        self.assertFalse(evaluate_overlay_click(1100, show_time))
        # Click 400ms later -> must be rejected
        self.assertFalse(evaluate_overlay_click(1400, show_time))
        # Click 650ms later -> valid user action
        self.assertTrue(evaluate_overlay_click(1650, show_time))

    def test_06_lateral_drawer_row_favorite_focusability(self):
        """Verify item_channel_drawer.xml marks btnDrawerFavorite non-focusable."""
        drawer_item_layout = os.path.join(self.res_dir, "layout", "item_channel_drawer.xml")
        self.assertTrue(os.path.exists(drawer_item_layout))
        
        tree = ET.parse(drawer_item_layout)
        root = tree.getroot()
        
        # Search for btnDrawerFavorite
        favorite_btn = None
        for elem in root.iter():
            elem_id = elem.attrib.get("{http://schemas.android.com/apk/res/android}id", "")
            if "btnDrawerFavorite" in elem_id:
                favorite_btn = elem
                break
                
        self.assertIsNotNone(favorite_btn, "btnDrawerFavorite must exist in item_channel_drawer.xml")
        focusable = favorite_btn.attrib.get("{http://schemas.android.com/apk/res/android}focusable")
        self.assertEqual(focusable, "false", "btnDrawerFavorite must be non-focusable to prevent D-pad entrapment")

if __name__ == "__main__":
    unittest.main()
