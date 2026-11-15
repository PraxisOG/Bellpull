import asyncio
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from plugins.bellpull.artifacts import ArtifactError
from plugins.bellpull.live_files_store import LiveFiles, tasks
from plugins.bellpull import live_files


class LiveFilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        self.path = self.home / "groceries"
        self.path.write_bytes(b"# Groceries\r\n\r\n- [ ] Milk\r\n- [x] Bread\r\n")
        self.store = LiveFiles(self.home, "a" * 64)
        self.id = self.store.reference(str(self.path), "fixture")["file"]["id"]
    def tearDown(self): self.tmp.cleanup()
    def edit(self, **fields):
        return self.store.edit(self.id, dict(operation_id="1" * 32, base_etag=self.store.snapshot(self.id)["etag"], **fields))
    def grant(self): self.store.reference(str(self.path), "fixture", access="checklist")
    def test_explicit_reference_is_live_readonly_scoped_and_repeatable(self):
        self.assertEqual("read", self.store.snapshot(self.id)["access"])
        with self.assertRaises(ArtifactError) as caught: self.edit(action="check", line=2, checked=True)
        self.assertEqual(403, caught.exception.status)
        with self.assertRaises(ArtifactError): LiveFiles(self.home, "b" * 64).snapshot(self.id)
        self.path.write_text("updated in place")
        self.assertEqual("updated in place", self.store.snapshot(self.id)["text"])
        self.assertEqual(self.id, self.store.reference(str(self.path), "fixture")["file"]["id"])
        self.assertEqual(1, len(self.store.list()["files"]))
    def test_grant_pin_refresh_and_revoke(self):
        self.grant(); self.store.pin(self.id, True)
        row = self.store.reference(str(self.path), "fixture")["file"]
        self.assertTrue(row["pinned"]); self.assertEqual("checklist", row["access"])
        self.store.reference(str(self.path), "fixture", access="read")
        with self.assertRaises(ArtifactError): self.edit(action="check", line=2, checked=True)
    def test_check_preserves_crlf_mode_and_other_content(self):
        self.grant(); self.path.chmod(0o640)
        value = self.edit(action="check", line=2, checked=True)
        self.assertIn(b"- [x] Milk\r\n- [x] Bread\r\n", self.path.read_bytes())
        self.assertTrue(value["tasks"][0]["checked"])
        self.assertEqual(0o640, self.path.stat().st_mode & 0o777)
    def test_add_escapes_markdown_and_retry_cannot_duplicate(self):
        self.grant()
        body = dict(operation_id="2" * 32, base_etag=self.store.snapshot(self.id)["etag"], action="add", text="*apples* [green]", after_line=2)
        self.store.edit(self.id, body); self.store.edit(self.id, body)
        self.assertEqual(1, self.path.read_text().count(r"- [ ] \*apples\* \[green\]"))
        with self.assertRaises(ArtifactError): self.store.edit(self.id, body | {"text": "different"})
    def test_external_change_conflicts_without_touching_file(self):
        self.grant(); old = self.store.snapshot(self.id)["etag"]
        self.path.write_text("external content")
        with self.assertRaises(ArtifactError) as caught:
            self.store.edit(self.id, dict(operation_id="3" * 32, base_etag=old, action="add", text="lost edit"))
        self.assertEqual(409, caught.exception.status); self.assertEqual("external content", self.path.read_text())
    def test_recovery_after_replace_before_receipt_commit(self):
        self.grant()
        body = dict(operation_id="4" * 32, base_etag=self.store.snapshot(self.id)["etag"], action="add", text="Eggs")
        self.store.edit(self.id, body)
        with self.store.db() as db: db.execute("UPDATE edits SET state='prepared'")
        self.store.edit(self.id, body)
        self.assertEqual(1, self.path.read_text().count("Eggs"))
    def test_rejects_symlink_hardlink_binary_and_oversize(self):
        link = self.home / "link"; link.symlink_to(self.path)
        with self.assertRaises(ArtifactError): self.store.reference(str(link), "fixture")
        link.unlink(); os.link(self.path, link)
        with self.assertRaises(ArtifactError): self.store.snapshot(self.id)
        link.unlink()
        self.path.write_bytes(b"binary\0data")
        with self.assertRaises(ArtifactError): self.store.snapshot(self.id)
        self.path.write_bytes(b"x" * (2 * 1024 * 1024 + 1))
        with self.assertRaises(ArtifactError): self.store.snapshot(self.id)
    def test_code_examples_are_not_editable_tasks(self):
        value = "```md\n- [ ] Example\n```\n    - [ ] Code\n\t- [ ] Code\n- [ ] Real\n~~~\n- [ ] More code\n~~~"
        self.assertEqual([{"line": 5, "text": "Real", "checked": False}], tasks(value))


class FileHttpTest(unittest.IsolatedAsyncioTestCase):
    async def test_native_auth_guards_all_file_routes_before_storage(self):
        from gateway.config import PlatformConfig
        from gateway.platforms.api_server import APIServerAdapter
        from plugins.bellpull.http import wire
        adapter = APIServerAdapter(PlatformConfig(enabled=True, extra={"key": "synthetic-file-profile"}))
        app = web.Application()
        stop = wire(app, adapter)
        try:
            with patch.object(live_files, "store") as storage:
                async with TestClient(TestServer(app)) as client:
                    for method, path, _ in live_files.routes(adapter):
                        path = path.replace("{file_id}", "a" * 32)
                        for headers in ({}, {"Authorization": "Bearer wrong"}):
                            self.assertEqual(401, (await client.request(method, path, headers=headers)).status)
                    storage.assert_not_called()
                    caps = await (await client.get("/api/bellpull/capabilities", headers={"Authorization": "Bearer synthetic-file-profile"})).json()
                    self.assertTrue(caps["features"]["bellpull_live_files"])
        finally:
            stop(); adapter._run_idempotency_store.close()

    async def test_snapshot_etag_pin_edit_and_bounded_request(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "list.md"; path.write_text("- [ ] Milk\n")
            registry = LiveFiles(folder, "a" * 64)
            identity = registry.reference(str(path), "fixture", access="checklist")["file"]["id"]
            @web.middleware
            async def errors(request, handler):
                try: return await handler(request)
                except ArtifactError as exc: return web.json_response({"error": str(exc)}, status=exc.status)
            app = web.Application(middlewares=[errors])
            for method, route, handler in live_files.routes(None): app.router.add_route(method, route, handler)
            with patch.object(live_files, "store", return_value=registry):
                async with TestClient(TestServer(app)) as client:
                    url = "/api/bellpull/files/" + identity
                    response = await client.get(url); snapshot = await response.json()
                    self.assertEqual(304, (await client.get(url, headers={"If-None-Match": snapshot["etag"]})).status)
                    self.assertTrue((await (await client.post(url + "/pin", json={"pinned": True})).json())["pinned"])
                    response = await client.post(url + "/edit", json=dict(operation_id="5" * 32, base_etag=snapshot["etag"], action="check", line=0, checked=True))
                    self.assertTrue((await response.json())["tasks"][0]["checked"])
                    async def chunks():
                        for chunk in [b'{"pinned":', b'true}']:
                            yield chunk; await asyncio.sleep(.01)
                    self.assertEqual(200, (await client.post(url + "/pin", data=chunks())).status)
                    self.assertEqual(413, (await client.post(url + "/edit", data=b"x" * 16385)).status)
                    self.assertEqual(404, (await client.get("/api/bellpull/files/" + "b" * 32)).status)
