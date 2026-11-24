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
