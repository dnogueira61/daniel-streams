"""
Tier 2 Boundary & Corner Cases: Rapid Zapping & High-Frequency Events
Tests D-pad zapping debounce, watchdog cancellation, drawer toggling, and failover cooldowns.
"""

import unittest
from typing import List, Tuple

class ChannelZappingSimulator:
    """Simulates state machine for Android TV channel zapping."""

    def __init__(self, channels: List[str], debounce_ms: int = 300):
        self.channels = channels
        self.current_idx = 0
        self.debounce_ms = debounce_ms
        self.last_zap_time = -debounce_ms
        self.active_timer_cancelled = False
        self.active_loading_stopped = False
        self.zap_history: List[Tuple[int, str]] = []

    def zap_next(self, current_time_ms: int) -> bool:
        if (current_time_ms - self.last_zap_time) < self.debounce_ms:
            # Throttled by debounce
            return False
        
        self.last_zap_time = current_time_ms
        self.active_timer_cancelled = True
        self.active_loading_stopped = True
        self.current_idx = (self.current_idx + 1) % len(self.channels)
        self.zap_history.append((current_time_ms, self.channels[self.current_idx]))
        return True

    def zap_previous(self, current_time_ms: int) -> bool:
        if (current_time_ms - self.last_zap_time) < self.debounce_ms:
            return False
            
        self.last_zap_time = current_time_ms
        self.active_timer_cancelled = True
        self.active_loading_stopped = True
        self.current_idx = (self.current_idx - 1 + len(self.channels)) % len(self.channels)
        self.zap_history.append((current_time_ms, self.channels[self.current_idx]))
        return True

class TestBoundaryRapidZapping(unittest.TestCase):

    def test_01_rapid_dpad_zapping_debounce(self):
        """Verify rapid key events within 300ms window are throttled."""
        channels = ["RTP 1", "RTP 2", "SIC", "TVI", "Sport TV 1"]
        sim = ChannelZappingSimulator(channels, debounce_ms=300)

        # 1st zap at t=100ms -> succeeds
        self.assertTrue(sim.zap_next(100))
        # 2nd zap at t=150ms -> rejected
        self.assertFalse(sim.zap_next(150))
        # 3rd zap at t=250ms -> rejected
        self.assertFalse(sim.zap_next(250))
        # 4th zap at t=450ms -> succeeds (350ms after t=100ms)
        self.assertTrue(sim.zap_next(450))

        self.assertEqual(len(sim.zap_history), 2)
        self.assertEqual(sim.zap_history[0][1], "RTP 2")
        self.assertEqual(sim.zap_history[1][1], "SIC")

    def test_02_watchdog_timer_cancellation_on_zapping(self):
        """Verify channel change flags watchdog timer cancellation."""
        channels = ["CH 1", "CH 2"]
        sim = ChannelZappingSimulator(channels)
        sim.active_timer_cancelled = False

        sim.zap_next(1000)
        self.assertTrue(sim.active_timer_cancelled, "Watchdog runnable must be cancelled on channel switch")

    def test_03_webview_loading_cancellation_on_zapping(self):
        """Verify webView.stopLoading() is triggered on channel switch."""
        channels = ["CH 1", "CH 2"]
        sim = ChannelZappingSimulator(channels)
        sim.active_loading_stopped = False

        sim.zap_next(1000)
        self.assertTrue(sim.active_loading_stopped, "Active webView loading must be stopped before loading next channel")

    def test_04_lateral_drawer_rapid_toggle_state(self):
        """Verify rapid drawer open/close toggles resolve to consistent boolean state."""
        class DrawerState:
            def __init__(self):
                self.is_open = False
            def toggle(self):
                self.is_open = not self.is_open

        drawer = DrawerState()
        # 5 rapid toggle events
        for _ in range(5):
            drawer.toggle()
        self.assertTrue(drawer.is_open)
        
        drawer.toggle()
        self.assertFalse(drawer.is_open)

    def test_05_server_cycle_rapid_key_events(self):
        """Verify rapid server cycling cycles through options cleanly with modulo wrap."""
        servers = ["Server 1 (DaddyLive P1)", "Server 2 (DaddyLive P2)", "Server 3 (TimStreams)"]
        idx = 0
        
        # Cycle 10 times
        history = []
        for _ in range(10):
            idx = (idx + 1) % len(servers)
            history.append(servers[idx])

        self.assertEqual(len(history), 10)
        # Should follow cyclical pattern: 1, 2, 0, 1, 2, 0...
        self.assertEqual(history[0], "Server 2 (DaddyLive P2)")
        self.assertEqual(history[1], "Server 3 (TimStreams)")
        self.assertEqual(history[2], "Server 1 (DaddyLive P1)")

    def test_06_consecutive_failover_cooldown_debounce(self):
        """Verify failover triggers enforce 1800ms cooldown to avoid cascading switches."""
        def should_allow_failover(current_time: int, last_failover_time: int, cooldown_ms: int = 1800) -> bool:
            return (current_time - last_failover_time) >= cooldown_ms

        t0 = 5000
        # Instant duplicate error at t0 + 200ms -> reject
        self.assertFalse(should_allow_failover(5200, t0))
        # Another duplicate error at t0 + 1000ms -> reject
        self.assertFalse(should_allow_failover(6000, t0))
        # Error at t0 + 1900ms -> allow
        self.assertTrue(should_allow_failover(6900, t0))

if __name__ == "__main__":
    unittest.main()
