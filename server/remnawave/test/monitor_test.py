#!/usr/bin/env python3
"""Unit tests of klaus-monitor's counting, without Docker or a panel.

  python3 server/remnawave/test/monitor_test.py

The panel, Telegram and the sending thread are stubbed: each test loads a
fresh copy of the monitor and feeds it block reports as the app sends them.
run-local.sh runs this first, then the same service for real.
"""

import importlib.util
import os
import time
import unittest

MONITOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "klaus-monitor.py")
NOTE = "Klaus VPN: мобильный интернет в режиме белых списков"
ALERT = "Klaus VPN: сервер «Германия» не отвечает"


class Now:
    """Runs the sending thread's work at once, so a test sees what was sent."""

    def __init__(self, target, args, daemon):
        self.target, self.args = target, args

    def start(self):
        self.target(*self.args)


class MonitorTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("klaus_monitor", MONITOR)
        m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(m)
        m.log = lambda msg: None
        m.TG_TOKEN, m.TG_CHAT = "token", "42"
        self.sent = []
        self.telegram_ok = True
        m.telegram_send = lambda text: self.telegram_ok and (self.sent.append(text) or True)
        m.subscription_ok = lambda s: True
        host = {"address": "de.example.com", "port": 443, "remark": "Германия", "nodes": ["n1"]}
        m.panel_hosts = lambda: [host]

        def panel_response(path):
            if path == "/api/nodes":
                return [{"uuid": "n1", "name": "de-1", "isConnected": True}]
            raise m.PanelError("unexpected " + path)

        m.panel_response = panel_response
        m.threading.Thread = Now
        self.m = m

    def report(self, s, network="mobile", operator="МТС", **extra):
        query = {"s": [s], "h": ["de.example.com"], "p": ["443"], "k": ["vless"],
                 "n": [network], "o": [operator], "v": ["1.0.99"]}
        query.update({k: [v] for k, v in extra.items()})
        status, _ = self.m.handle_report(query)
        self.assertEqual(200, status)

    def test_whitelist_reports_make_one_note_that_never_says_disable(self):
        self.report("friend01", w="1")
        self.assertEqual([], self.sent)
        self.report("friend02", operator="Билайн", w="1")
        self.report("friend03", w="1")
        self.assertEqual(1, len(self.sent))
        note = self.sent[0]
        self.assertTrue(note.startswith(NOTE + " у 2 человек"), note)
        for want in ("«Германия»", "МТС ×1", "Билайн ×1", "менять этот не нужно", "обычное сообщение о блокировке"):
            self.assertIn(want, note)
        self.assertNotIn("disable-node", note)

    def test_other_reports_still_raise_the_alert_with_whitelist_ones_marked(self):
        self.report("friend01", w="1")
        self.report("friend02", w="1")
        self.report("friend03", network="wifi", operator="")
        self.assertEqual(1, len(self.sent))
        # a=1 (no server answered at all) counts like any other report.
        self.report("friend04", network="wifi", operator="", a="1")
        self.assertEqual(2, len(self.sent))
        alert = self.sent[1]
        self.assertTrue(alert.startswith(ALERT + " у 4 человек"), alert)
        self.assertIn("МТС (белые списки) ×2", alert)
        self.assertIn("Wi-Fi ×2", alert)
        self.assertIn("klaus-panel disable-node de-1", alert)
        # The alert's quiet time covers the note too.
        self.report("friend05", w="1")
        self.report("friend06", network="wifi", operator="")
        self.assertEqual(2, len(self.sent))

    def test_one_of_each_is_neither(self):
        self.report("friend01", w="1")
        self.report("friend02", network="wifi", operator="")
        self.assertEqual([], self.sent)

    def test_a_note_that_did_not_go_out_is_tried_again(self):
        self.telegram_ok = False
        self.report("friend01", w="1")
        self.report("friend02", w="1")
        self.assertEqual([], self.sent)
        self.telegram_ok = True
        self.report("friend03", w="1")
        self.assertEqual(1, len(self.sent))
        self.assertIn("у 3 человек", self.sent[0])

    def test_old_entries_go(self):
        self.report("friend01", w="1")
        self.report("friend02", w="1")
        self.m.STATE.purge(time.monotonic() + 7 * 24 * 3600)
        self.assertEqual({}, self.m.STATE.reports)
        self.assertEqual({}, self.m.STATE.cooldown)


if __name__ == "__main__":
    unittest.main()
