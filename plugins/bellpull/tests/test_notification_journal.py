import asyncio
import json
import tempfile
import unittest

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from plugins.bellpull.notification_journal import NotificationJournal
from plugins.bellpull.notifications import Relay, topic


class NotificationJournalTest(unittest.TestCase):
    def test_scoped_replay_and_expired_lease_preserve_one_receipt(self):
        with tempfile.TemporaryDirectory() as home:
            first, second = NotificationJournal(home), NotificationJournal(home)
            args = ("owner-topic", "run_one", "session_one", "waiting_for_approval", "request_one", 90)
            event_id = first.record(*args, now=100)
            cursor = first.history("owner-topic", 0, 1)["next_cursor"]
            self.assertEqual(event_id, second.record(*args, now=101))
            self.assertEqual(
                event_id,
                second.record(
                    "owner-topic",
                    "run_one",
                    "new_session_label",
                    "waiting_for_approval",
                    "request_one",
                    91,
                    now=102,
                ),
            )
            self.assertEqual("session_one", first.lookup("owner-topic", event_id)["session_id"])
            self.assertFalse(first.history("owner-topic", cursor, 1)["events"])
            self.assertIsNone(first.lookup("another-topic", event_id))
            claim = first.claim(now=101)
            self.assertIsNone(second.claim(now=102))
            retry = second.claim(now=132)
            self.assertEqual(event_id, retry["event_id"])
            self.assertEqual(2, retry["attempts"])
            first.finish(claim, 503, now=133)
            second.finish(retry, 201, now=134)
            first.finish(claim, None, now=135)
            self.assertIsNone(first.claim(now=2000))
            (event,) = second.history("owner-topic", cursor, 1)["events"]
            self.assertEqual(
                ("transport_accepted", 134, 201),
                (event["dispatch"], event["accepted_at"], event["last_http_status"]),
            )
            self.assertEqual("attention", event["severity"])
            self.assertEqual(90, event["occurred_at"])

    def test_scoped_cursor_survives_restart_and_recovers_after_replacement(self):
        with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as restored:
            journal = NotificationJournal(home)
            journal.record("owner", "run", "session", "completed", "", 10)
            original = journal.history("owner", 0, 50)
            cursor, journal_id = original["next_cursor"], original["journal_id"]
            resumed = NotificationJournal(home).history("owner", cursor, 50, journal_id)
            self.assertEqual((cursor, [], journal_id), (resumed["after"], resumed["events"], resumed["journal_id"]))
            rolled_back = journal.history("owner", cursor + 100, 50, journal_id)
            self.assertTrue(rolled_back["reset"])
            self.assertEqual(original["events"], rolled_back["events"])
            journal.record("other", "separate", "elsewhere", "failed", "", 11)
            other = journal.history("other", cursor, 50, journal_id)
            self.assertEqual(0, other["after"])
            self.assertNotEqual(journal_id, other["journal_id"])
            self.assertEqual(["separate"], [event["run_id"] for event in other["events"]])
            replacement = NotificationJournal(restored)
            replacement.record("owner", "new", "session", "interrupted", "", 12)
            reset = replacement.history("owner", cursor, 50, journal_id)
            self.assertNotEqual(journal_id, reset["journal_id"])
            self.assertEqual(["new"], [event["run_id"] for event in reset["events"]])

    def test_backlog_counts_every_scoped_receipt_without_claiming_it(self):
        with tempfile.TemporaryDirectory() as home:
            journal = NotificationJournal(home)
            journal.record("other", "run", "chat", "completed", "", 40, now=40)
            journal.finish(journal.claim(now=50), 200, now=60)
            for n in range(3):
                journal.record("owner", str(n), "chat", "completed", "", 1, now=100+n)
            self.assertTrue(journal.history("owner", 0, 1)["has_more"])
            before = journal.history("owner", 0, 100)
            self.assertEqual({"pending": 3, "oldest_pending_at": 100, "last_accepted_at": None}, journal.backlog("owner"))
            self.assertEqual(before, journal.history("owner", 0, 100))
            journal.finish(journal.claim(now=110), 200, now=111)
            self.assertEqual({"pending": 2, "oldest_pending_at": 101, "last_accepted_at": 111},
                             NotificationJournal(home).backlog("owner"))
            self.assertEqual({"pending": 0, "oldest_pending_at": None, "last_accepted_at": 60}, journal.backlog("other"))

    def test_cursor_pages_approvals_and_excludes_other_scopes(self):
        with tempfile.TemporaryDirectory() as home:
            journal = NotificationJournal(home)
            ids = [
                journal.record("owner", "run", "chat", "waiting_for_approval", str(i), 7) for i in range(3)
            ]
            journal.record("other", "run", "chat", "completed", "", 8)
            page = journal.history("owner", 0, 2)
            self.assertTrue(page["has_more"])
            self.assertEqual(ids[:2], [e["event_id"] for e in page["events"]])
            rest = journal.history("owner", page["next_cursor"], 2)
            self.assertEqual([ids[2]], [e["event_id"] for e in rest["events"]])
            self.assertFalse(rest["has_more"])


class RelayRecoveryTest(unittest.IsolatedAsyncioTestCase):
    async def test_relay_health_is_a_read_only_probe_and_bounds_unrecognized_responses(self):
        response = web.json_response({"healthy": True})
        seen = []
        async def health(request):
            seen.append((request.method, request.path, request.headers.get("Authorization")))
            return response
        broker = web.Application()
        broker.router.add_get("/v1/health", health)
        async with TestServer(broker) as server:
            relay = Relay(str(server.make_url("")).rstrip("/"), None)
            self.assertEqual({"state":"healthy", "http_status":200}, await relay._health())
            response = web.json_response({"healthy": False})
            self.assertEqual("unavailable", (await relay._health())["state"])
            response = web.Response(status=503)
            self.assertEqual({"state":"unavailable", "http_status":503}, await relay._health())
            response = web.Response(status=302, headers={"Location": "/should-never-request"})
            self.assertEqual(302, (await relay._health())["http_status"])
            for body in ("bad", " " * 4096 + '{"healthy":true}', '{"healthy":1}'):
                response = web.Response(text=body)
                self.assertEqual("unknown", (await relay._health())["state"])
            self.assertEqual([("GET", "/v1/health", None)] * 7, seen)
        self.assertEqual("unreachable", (await relay._health())["state"])
        off = Relay("", None)
        body = json.loads((await off.dispatch_status("owner", False)).body)
        self.assertEqual({"state":"unconfigured"}, body["transport"])
        self.assertEqual({"state":"unsupported"}, body["backlog"])
        self.assertEqual({"state":"unavailable"}, json.loads((await off.dispatch_status("owner", True)).body)["backlog"])

    async def test_restart_retries_stored_receipt_and_enriches_only_the_matching_scope(self):
        attempted = []
        accepted = asyncio.Event()
        healthy = False

        async def publish(request):
            self.assertEqual(topic("owner"), request.match_info["topic"])
            attempted.append(await request.json())
            if healthy:
                accepted.set()
            return web.Response(status=200 if healthy else 503)

        async def messages(_):
            return web.Response(
                text=json.dumps({"event": "message", "message": json.dumps(attempted[0])}) + "\n"
            )

        broker = web.Application()
        broker.router.add_post("/{topic}", publish)
        broker.router.add_get("/{topic}/json", messages)
        async with TestServer(broker) as server:
            with tempfile.TemporaryDirectory() as home:
                url = str(server.make_url("")).rstrip("/")
                event = dict(
                    scope="owner",
                    run_id="run_fixture",
                    session_id="chat_fixture",
                    status="completed",
                    updated_at=10,
                    private_output="private fixture",
                )
                first = Relay(url, NotificationJournal(home))
                try:
                    first.status(event)
                    (before,) = first.journal.history(topic("owner"), 0, 1)["events"]
                    self.assertFalse(attempted)
                    first.start()
                    for _ in range(100):
                        if (
                            first.journal.lookup(topic("owner"), before["event_id"])["last_http_status"]
                            == 503
                        ):
                            break
                        await asyncio.sleep(0.02)
                    self.assertEqual(
                        503, first.journal.lookup(topic("owner"), before["event_id"])["last_http_status"]
                    )
                finally:
                    await first.close()
                healthy = True
                second = Relay(url, NotificationJournal(home))
                try:
                    second.start()
                    await asyncio.wait_for(accepted.wait(), 5)
                    for _ in range(100):
                        if (
                            second.journal.lookup(topic("owner"), before["event_id"])["accepted_at"]
                            is not None
                        ):
                            break
                        await asyncio.sleep(0.02)
                    second.status(event)
                    (after,) = second.journal.history(topic("owner"), before["revision"], 1)["events"]
                    self.assertEqual("transport_accepted", after["dispatch"])
                    self.assertEqual(2, after["attempts"])
                    self.assertEqual([{"event_id": before["event_id"]}] * 2, attempted)
                    proxy = web.Application()
                    proxy.router.add_get(
                        "/{scope}/events", lambda req: second.events(req.match_info["scope"], req)
                    )
                    async with TestClient(TestServer(proxy)) as client:
                        response = await client.get("/owner/events")
                        data = await response.text()
                        self.assertIn("run_fixture", data)
                        self.assertIn(before["event_id"], data)
                        self.assertNotIn("private fixture", data)
                        other = await client.get("/other/events")
                        self.assertNotIn("bellpull.wake", await other.text())
                finally:
                    await second.close()
