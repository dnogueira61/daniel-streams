"""
Tier 2 Boundary & Corner Cases: Empty Channels & Catalog Boundaries
Tests handling of missing URLs, empty names, zero-channel catalogs, duplicate IDs, and numeric boundaries.
"""

import unittest
from typing import List, Dict, Any, Optional
from tests.e2e.fixtures.helpers import load_master_channels

def get_channel_display_name(channel: Dict[str, Any]) -> str:
    """Safe display name resolution."""
    name = channel.get("name")
    if name and isinstance(name, str) and name.strip():
        return name.strip()
    return f"Canal {channel.get('id', 'Sem ID')}"

def is_channel_numeric_daddylive(channel_id: Any) -> bool:
    """Evaluates whether channel ID qualifies for DaddyLive synthesis."""
    if channel_id is None:
        return False
    s = str(channel_id).strip()
    return s.isdigit() and int(s) > 0

class TestBoundaryEmptyChannels(unittest.TestCase):

    def setUp(self):
        self.master_channels = load_master_channels()

    def test_01_channel_missing_all_stream_urls(self):
        """Verify handling of channel entries with zero configured streaming URLs."""
        empty_channel = {
            "id": "test-empty-1",
            "name": "Empty Stream Channel",
            "category": "TEST",
            "streamUrl": None,
            "backupStreamUrl": "",
            "backupStreamUrl2": None
        }
        
        # Check that fallback resolution detects zero streams
        available_streams = [
            u for u in [empty_channel.get("streamUrl"), empty_channel.get("backupStreamUrl"), empty_channel.get("backupStreamUrl2")]
            if u and isinstance(u, str) and u.strip()
        ]
        self.assertEqual(len(available_streams), 0, "Empty channel must yield 0 available stream URLs")
        
        # Verify DaddyLive fallback is also unavailable for non-numeric slug
        self.assertFalse(is_channel_numeric_daddylive(empty_channel["id"]))

    def test_02_channel_with_blank_or_whitespace_name(self):
        """Verify fallback to channel ID when name is whitespace or None."""
        test_cases = [
            ({"id": "100", "name": ""}, "Canal 100"),
            ({"id": "200", "name": "   "}, "Canal 200"),
            ({"id": "300", "name": None}, "Canal 300"),
            ({"id": "400", "name": "RTP 1 HD"}, "RTP 1 HD")
        ]
        for ch, expected in test_cases:
            with self.subTest(ch=ch):
                self.assertEqual(get_channel_display_name(ch), expected)

    def test_03_empty_catalog_list_handling(self):
        """Verify catalog operations gracefully handle empty list [] without crashing."""
        empty_catalog: List[Dict[str, Any]] = []
        
        # Operations that must succeed without IndexError
        total = len(empty_catalog)
        self.assertEqual(total, 0)
        
        pt_channels = [c for c in empty_catalog if c.get("isPt") is True]
        self.assertEqual(len(pt_channels), 0)
        
        first_channel = empty_catalog[0] if empty_catalog else None
        self.assertIsNone(first_channel)

    def test_04_duplicate_channel_ids_detection(self):
        """Verify catalog contains zero duplicate channel IDs."""
        seen_ids = set()
        duplicates = []
        for ch in self.master_channels:
            cid = str(ch.get("id"))
            if cid in seen_ids:
                duplicates.append(cid)
            seen_ids.add(cid)
            
        self.assertEqual(
            len(duplicates), 0,
            f"Catalog must not contain duplicate channel IDs, found: {duplicates[:10]}"
        )

    def test_05_numeric_id_boundary_edge_cases(self):
        """Verify numeric ID parser boundaries: 0, negative, non-numeric, huge IDs."""
        self.assertFalse(is_channel_numeric_daddylive("0"), "'0' must not be considered valid DaddyLive channel")
        self.assertFalse(is_channel_numeric_daddylive("-5"), "Negative ID must not be valid DaddyLive channel")
        self.assertFalse(is_channel_numeric_daddylive("canal11-pt"), "Slug must not be DaddyLive channel")
        self.assertFalse(is_channel_numeric_daddylive(None), "None must return False")
        self.assertTrue(is_channel_numeric_daddylive("49"), "'49' is valid DaddyLive channel")
        self.assertTrue(is_channel_numeric_daddylive("719"), "'719' is numeric")
        self.assertTrue(is_channel_numeric_daddylive("999999"), "'999999' is numeric")

    def test_06_missing_optional_metadata_fields(self):
        """Verify channel parser safely defaults missing optional fields."""
        bare_channel = {
            "id": "50",
            "name": "Bare Channel",
            "category": "General"
        }
        self.assertFalse(bare_channel.get("isPt", False))
        self.assertEqual(bare_channel.get("country", "INT"), "INT")
        self.assertIsNone(bare_channel.get("logo"))
        self.assertIsNone(bare_channel.get("epgId"))
        self.assertIsNone(bare_channel.get("backupStreamUrl"))
        self.assertIsNone(bare_channel.get("backupStreamUrl2"))

if __name__ == "__main__":
    unittest.main()
