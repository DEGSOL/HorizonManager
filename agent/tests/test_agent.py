"""Run with the pinned Horizon environment. The OpenAI-compatible server is a local fixture."""
import base64
import json
import os
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import uuid4
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from horizon_manager.api import REVISION, create_app
from horizon_manager.article_context import key_for
from horizon_manager.backup import pack, unpack
from horizon_manager.configuration import DIGEST_LANGUAGES, only_source
from horizon_manager.jobs import consume
from horizon_manager.push import enqueue_digest, signed_message, valid_endpoint
from horizon_manager.scheduler import Schedule, next_run
from horizon_manager.store import Store

TOKEN = "1" * 64
AUTH = {"Authorization": "Bearer " + TOKEN}
SECRET = "test-key-this-string-must-not-leak"


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path not in {"/feed", "/alternate-feed"}:
            self.send_error(404)
            return
        date = format_datetime(datetime.now(timezone.utc), usegmt=True)
        title, story, item = (
            ("Chipmaker starts selling its PocketStream handheld", "alternate-news", "alternate-id")
            if self.path == "/alternate-feed" else
            ("Fixture engineering article", "fixture-news", "fixture-id")
        )
        data = f'''<?xml version="1.0"?>
        <rss version="2.0"><channel><title>Fixture</title><link>https://example.org/</link>
        <description>Local integration test feed</description><item><title>{title}</title>
        <link>https://example.org/{story}</link><description>Fixture article content</description>
        <guid>{item}</guid><pubDate>{date}</pubDate></item></channel></rss>'''.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/rss+xml")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.headers.get("Authorization") != "Bearer " + SECRET:
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"error": {"message": "invalid api key " + SECRET}}).encode())
            return
        if request.get("model") == "slow":
            time.sleep(3)
        instructions = request["messages"][0]["content"]
        if "Artifact" in instructions or "blocks" in instructions:
            self.server.enrichment_requests.append(request["messages"])
            if request.get("model") == "slow-context":
                time.sleep(3)
        if "You route content" in instructions and request.get("model") == "classify-with-retry":
            self.server.classification_requests.append(instructions)
            content = '{"profile":"tech-news"}' if "containing only" in instructions else '{"profile":"unknown",'
        elif "Return OK as plain text." in instructions:
            content = "OK"
        elif "Artifact" in instructions or "blocks" in instructions:
            title, article = "Fixture engineering article", "A fixture summary."
            if "language `ru`" in instructions:
                title, article = "Тестовая инженерная статья", "Текст новости на русском языке."
            elif "Simplified Chinese" in instructions:
                title, article = "工程测试文章", "简体中文新闻正文。"
            content = json.dumps({"title": title, "blocks": [
                {"id": "summary", "title": "Summary", "content": article, "source_refs": []}
            ]})
        elif "Identify groups of news items" in instructions:
            self.server.duplicate_requests.append(instructions)
            content = '{"duplicates": [[0, 1]]}'
        elif "brief summaries into" in instructions:
            self.server.translation_requests.append(instructions)
            texts = {
                "Russian": ("Тестовая инженерная статья", "Краткое описание новости на русском."),
                "Simplified Chinese": ("工程测试文章", "简体中文新闻摘要。"),
                "English": ("Fixture engineering article", "Fixture AI summary"),
                "French": ("Article technique de test", "Résumé français de l’article."),
            }
            title, article = next(value for language, value in texts.items() if language in instructions)
            entries = json.loads(request["messages"][1]["content"])
            content = json.dumps({"items": [{"id": row["id"], "title": title, "text": article} for row in entries]})
        else:
            content = json.dumps({"score": 9, "summary": "Fixture AI summary", "reason": "Fixture rubric", "tags": ["test"]})
        body = json.dumps({"id": "fixture", "object": "chat.completion", "created": 0, "model": "fixture",
                           "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                                        "finish_reason": "stop"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass


class AgentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        cls.thread = threading.Thread(target=cls.fixture.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = "http://127.0.0.1:" + str(cls.fixture.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.fixture.shutdown()
        cls.fixture.server_close()
        cls.thread.join()

    def setUp(self):
        self.fixture.classification_requests = []
        self.fixture.enrichment_requests = []
        self.fixture.duplicate_requests = []
        self.fixture.translation_requests = []
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = TestClient(create_app(self.root, TOKEN), headers=AUTH)
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def config(self, model="fixture-model", secret=SECRET, with_rss=True):
        incoming = self.client.get("/v1/config").json()
        incoming["config"]["ai"].update({"base_url": self.url + "/v1", "model": model})
        if with_rss:
            incoming["config"]["sources"]["rss"] = [
                {"name": "Working fixture", "url": self.url + "/feed", "profile": "tech-news"},
                {"name": "Broken fixture", "url": self.url + "/broken", "profile": "tech-news"},
            ]
        item = next(p for p in incoming["profiles"] if p["definition"]["id"] == "tech-news")
        item["definition"]["enrichment"]["blocks"] = [{"id": "summary", "primary": True}]
        incoming["secrets"] = {"OPENAI_API_KEY": secret}
        return incoming

    def wait(self, id, final=True):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            jobs = self.client.get("/v1/jobs").json()
            job = next((job for job in jobs if job["id"] == id), None)
            if job and ((job["state"] not in ("queued", "running")) if final else job["state"] == "running"):
                return job
            time.sleep(0.1)
        raise AssertionError(jobs)

    def test_auth_health_and_body_limit(self):
        response = self.client.get("/v1/health")
        self.assertEqual(response.json()["horizon"], REVISION)
        self.assertEqual(self.client.get("/v1/health", headers={"Authorization": "Bearer wrong"}).status_code, 401)
        self.assertEqual(self.client.post("/v1/jobs", content=b"x",
                                         headers={"Content-Length": "12000000"}).status_code, 413)

    def test_notification_plan_tracks_schedule_and_unfinished_digest_jobs(self):
        path = "/v1/digest-notification-plan"
        self.assertEqual(self.client.get(path).json(), {"id": "", "next": None, "preparing_since": None})
        scheduled = self.client.put("/v1/schedule", json={"mode": "interval", "hours": 6}).json()["next"]
        run = uuid4().hex
        with Store(self.root).db() as db:
            Store.queue(db, run, "run", {})
            db.execute("UPDATE jobs SET state='running' WHERE id=?", (run,))
            started = db.execute("SELECT created FROM jobs WHERE id=?", (run,)).fetchone()[0]
        self.assertEqual(self.client.get(path).json(), {"id": "", "next": scheduled, "preparing_since": started})
        with Store(self.root).db() as db:
            db.execute("UPDATE jobs SET result=? WHERE id=?", (json.dumps({"languages": ["ru"]}), run))
        self.assertEqual(self.client.get(path).json(), {"id": run, "next": scheduled, "preparing_since": None})
        self.assertEqual(self.client.get(path, headers={"Authorization": ""}).status_code, 401)

    def test_live_digest_wakes_without_a_push_registration(self):
        path = "/v1/digest-notifications"
        self.assertTrue(self.client.get("/v1/health").json()["digest_notifications"])
        first = self.client.get(path)
        self.assertEqual(first.json(), {"id": ""})
        self.assertEqual(first.headers["Cache-Control"], "no-store")
        self.assertEqual(self.client.get(path, params={"after": "bad"}).status_code, 422)
        self.assertEqual(self.client.get(path, headers={"Authorization": ""}).status_code, 401)
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        run = uuid4()
        with ThreadPoolExecutor(max_workers=1) as pool:
            waiting = pool.submit(self.client.get, path, params={"after": "", "wait": 18})
            time.sleep(.15)
            self.assertFalse(waiting.done())
            self.assertLess(self.client.post("/v1/jobs", json={"id": str(run)}).status_code, 300)
            self.assertEqual(waiting.result(15).json(), {"id": run.hex})
        self.assertEqual(self.client.get(path, params={"after": "", "wait": 0}).json(), {"id": run.hex})
        self.assertEqual(self.client.get(path, params={"after": run.hex, "wait": 1}).json(), {"id": run.hex})
        with Store(self.root).db() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM push_devices").fetchone()[0], 0)

    def test_invalid_classifier_response_retries_only_the_profile_selection(self):
        config = self.config(model="classify-with-retry")
        config["config"]["sources"]["rss"][0]["profile"] = ["tech-news", "tech-blog"]
        self.assertEqual(self.client.put("/v1/config", json=config).status_code, 200)
        run = uuid4()
        self.client.post("/v1/jobs", json={"id": str(run)})
        self.assertEqual(self.wait(run.hex)["state"], "success")
        article = self.client.get(f"/v1/digests/{run.hex}/en").json()["items"][0]
        self.assertEqual(article["profile"], "tech-news")
        self.assertEqual(len(self.fixture.classification_requests), 2)
        self.assertFalse(any(event["code"] == "INVALID_FORMAT" for event in self.client.get("/v1/events").json()))

    def test_rejected_profile_references_return_specific_codes_without_overwriting_settings(self):
        before = self.client.get("/v1/config").json()
        invalid = self.config()
        invalid["config"]["sources"]["rss"][0]["profile"] = []
        self.assertEqual(self.client.put("/v1/config", json=invalid).json()["detail"]["code"], "PROFILE_SOURCE_INVALID")
        invalid["profiles"].append(invalid["profiles"][0])
        self.assertEqual(self.client.put("/v1/config", json=invalid).json()["detail"]["code"], "PROFILE_DUPLICATE_ID")
        self.assertEqual(self.client.get("/v1/config").json(), before)

    def test_media_and_language_settings_survive_backup_without_changing_horizon_config(self):
        before = self.client.get("/v1/config").json()
        self.assertFalse(self.client.get("/v1/status").json()["media_search"])
        self.assertTrue(self.client.put("/v1/media-search", json={"enabled": True}).json()["enabled"])
        self.assertEqual(self.client.put("/v1/digest-language", json={"language": "ru"}).json(), {"language": "ru"})
        self.assertEqual(self.client.get("/v1/status").json()["headline_max_words"], 10)
        self.assertEqual(self.client.get("/v1/status").json()["headline_range"], {"minimum": 3, "maximum": 60})
        self.assertEqual(self.client.put("/v1/headline-length", json={"words": 31}).status_code, 422)
        self.assertEqual(self.client.put("/v1/headline-length", json={"words": 16}).json(), {"words": 16})
        self.assertEqual(self.client.get("/v1/status").json()["headline_range"], {"minimum": 3, "maximum": 96})
        self.assertEqual(self.client.put("/v1/headline-range", json={"minimum": 8, "maximum": 3}).status_code, 422)
        self.assertEqual(self.client.put("/v1/headline-range", json={"minimum": 0, "maximum": 8}).status_code, 422)
        self.assertEqual(self.client.put("/v1/headline-range", json={"minimum": 3, "maximum": 241}).status_code, 422)
        limits = {"minimum": 3, "maximum": 8}
        self.assertEqual(self.client.put("/v1/headline-range", json=limits).json(), limits)
        self.assertEqual(before, self.client.get("/v1/config").json())
        backup = self.client.post("/v1/backup", json={"password": "long-password-for-tests"}).json()["archive"]
        self.client.put("/v1/media-search", json={"enabled": False})
        self.client.put("/v1/digest-language", json={"language": "en"})
        self.client.put("/v1/headline-length", json={"words": 10})
        self.assertEqual(self.client.post("/v1/restore", json={"password": "long-password-for-tests",
                         "archive": backup, "revision": before["revision"]}).status_code, 200)
        status = self.client.get("/v1/status").json()
        self.assertTrue(status["media_search"])
        self.assertEqual(status["digest_language"], "ru")
        self.assertEqual(status["headline_max_words"], 16)
        self.assertEqual(status["headline_range"], limits)

    def test_digest_language_upgrade_keeps_old_choice_and_accepts_all_new_codes(self):
        import sqlite3

        with TemporaryDirectory() as folder:
            root = Path(folder)
            with sqlite3.connect(root / "manager.sqlite3") as db:
                db.execute("CREATE TABLE digest_options (id INTEGER PRIMARY KEY CHECK(id=1), "
                           "language TEXT NOT NULL CHECK(language IN ('en', 'ru', 'zh')))")
                db.execute("INSERT INTO digest_options VALUES (1, 'zh')")
            store = Store(root)
            with store.db() as db:
                self.assertEqual(store.digest_language(db), ("zh", True))
                store.set_digest_language(db, "tt")
            with Store(root).db() as db:
                self.assertEqual(store.digest_language(db), ("tt", True))

        self.assertEqual(set(self.client.get("/v1/status").json()["digest_languages"]), set(DIGEST_LANGUAGES))
        for code in DIGEST_LANGUAGES:
            self.assertEqual(self.client.put("/v1/digest-language", json={"language": code}).json(), {"language": code})
        self.assertEqual(self.client.put("/v1/digest-language", json={"language": "nl"}).status_code, 422)

    def test_generation_uses_one_new_language(self):
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        self.assertEqual(self.client.put("/v1/digest-language", json={"language": "fr"}).status_code, 200)
        self.assertEqual(self.client.put("/v1/headline-range", json={"minimum": 12, "maximum": 30}).status_code, 200)
        run = uuid4()
        self.assertLess(self.client.post("/v1/jobs", json={"id": str(run)}).status_code, 300)
        job = self.wait(run.hex)
        self.assertEqual(job["state"], "success", job)
        self.assertEqual(job["result"]["languages"], ["fr"])
        self.assertEqual(len(self.fixture.translation_requests), 1)
        self.assertIn("into French", self.fixture.translation_requests[0])
        self.assertIn("12 to 30 characters", self.fixture.translation_requests[0])
        digest = self.client.get(f"/v1/digests/{run.hex}/fr").json()
        self.assertIn("Revue du jour Horizon", digest["markdown"])
        self.assertEqual(digest["items"][0]["title"], "Article technique de test")
        self.assertEqual(digest["items"][0]["publisher"], "Working fixture")

    def test_config_atomic_and_backup_integrity(self):
        expected = self.config()
        saved = self.client.put("/v1/config", json=expected)
        self.assertEqual(saved.status_code, 200, saved.text)
        self.assertNotIn(SECRET, saved.text)
        self.assertEqual(self.client.put("/v1/config", json=expected).status_code, 409)
        invalid = self.config("updated")
        invalid["config"]["processing"]["profiles_dir"] = "../../elsewhere"
        self.assertEqual(self.client.put("/v1/config", json=invalid).status_code, 422)
        self.assertEqual(self.client.get("/v1/config").json()["config"]["ai"]["model"], "fixture-model")
        archive = self.client.post("/v1/backup", json={"password": "backup-password"}).json()["archive"]
        self.assertNotIn(SECRET.encode(), base64.b64decode(archive))
        self.assertEqual(self.client.post("/v1/restore", json={"password": "incorrect-password", "archive": archive,
                                                             "revision": 2}).status_code, 422)
        reverted = self.client.post("/v1/config/revert", json={"revision": 2})
        self.assertEqual(reverted.status_code, 200, reverted.text)
        self.assertEqual(reverted.json()["config"]["ai"]["model"], "")
        response = self.client.post("/v1/restore", json={"password": "backup-password", "archive": archive, "revision": 3})
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["config"]["ai"]["model"], "fixture-model")

    def test_pipeline_partial_rss_failure_digest_and_logs(self):
        self.client.put("/v1/digest-language", json={"language": "ru"})
        self.assertEqual(self.client.put("/v1/enrichment-mode", json={"mode": "automatic"}).status_code, 200)
        response = self.client.put("/v1/config", json=self.config())
        self.assertEqual(response.status_code, 200, response.text)
        id = uuid4()
        submitted = self.client.post("/v1/jobs", json={"id": str(id)})
        self.assertEqual(submitted.json(), {"id": id.hex})
        self.assertEqual(self.client.post("/v1/jobs", json={"id": str(id)}).json(), submitted.json())
        self.assertEqual(self.client.post("/v1/jobs", json={"id": str(uuid4())}).status_code, 409)
        job = self.wait(id.hex)
        events = self.client.get("/v1/events").json()
        self.assertEqual(job["state"], "success", events)
        self.assertEqual(job["result"]["fetch_status"], "partial_failure")
        self.assertEqual(job["result"]["raw"], 1)
        self.assertEqual(job["result"]["kept"], 1)
        self.assertEqual(job["result"]["enriched"], 1)
        self.assertEqual(len(self.fixture.enrichment_requests), 1)
        self.assertEqual(self.fixture.translation_requests, [])
        digests = self.client.get("/v1/digests").json()
        self.assertEqual([d["id"] for d in digests], [id.hex])
        self.assertEqual(digests[0]["ready_at"], job["result"]["ready_at"])
        self.assertEqual(self.client.get(f"/v1/digest-info/{id.hex}").json(), digests[0])
        self.assertEqual(digests[0]["languages"], ["ru"])
        self.assertEqual(digests[0]["output_language"], "ru")
        self.assertEqual(self.client.get("/v1/digest-marker").json(), {"id": id.hex})
        self.assertEqual(self.client.get(f"/v1/digests/{id.hex}/en").status_code, 404)
        self.assertEqual(self.client.get(f"/v1/digests/{id.hex}/zh").status_code, 404)
        ru = self.client.get(f"/v1/digests/{id.hex}/ru").json()
        self.assertIn("Ежедневный дайджест Horizon", ru["markdown"])
        self.assertEqual(ru["items"][0]["body"], "Текст новости на русском языке.")
        self.assertEqual(ru["items"][0]["score"], 9)
        self.assertIn("media", ru["items"][0])
        self.assertEqual(ru["items"][0]["reason"], "")
        article_key = key_for(ru["items"][0]["id"])
        path = f"/v1/article-context/{id.hex}/{article_key}/ru"
        self.assertEqual(self.client.post(path, json={}).json()["state"], "ready")
        self.assertEqual(len(self.fixture.enrichment_requests), 1)
        self.assertEqual(self.client.post("/v1/jobs", json={"id": str(uuid4()), "kind": "localize",
                                                          "run": id.hex}).status_code, 409)
        self.assertTrue(any("Broken fixture" in str(event) and "HTTP_NOT_FOUND" in str(event) for event in events))
        self.assertNotIn(SECRET, json.dumps(events))
        with Store(self.root).db() as db:
            self.assertEqual(db.execute("SELECT state FROM jobs WHERE id=?", (id.hex,)).fetchone()[0], "success")

    def test_manually_enriched_digest_merges_same_event_across_profiles_before_publication(self):
        config = self.config()
        config["config"]["sources"]["rss"][1] = {
            "name": "Alternate fixture", "url": self.url + "/alternate-feed", "profile": "tech-blog",
        }
        self.assertEqual(self.client.put("/v1/config", json=config).status_code, 200)
        self.assertEqual(self.client.put("/v1/digest-language", json={"language": "en"}).status_code, 200)
        run = uuid4()
        self.client.post("/v1/jobs", json={"id": str(run)})
        job = self.wait(run.hex)
        self.assertEqual(job["state"], "success", job)
        self.assertEqual(job["result"]["kept"], 1)
        self.assertEqual(job["result"]["enrichment_mode"], "on_demand")
        self.assertEqual(len(self.client.get(f"/v1/digests/{run.hex}/en").json()["items"]), 1)
        self.assertEqual(self.fixture.enrichment_requests, [])
        self.assertEqual(len(self.fixture.duplicate_requests), 1)

    def test_optional_detail_does_not_abort_processing_worker_warning(self):
        run = uuid4().hex
        ready = consume(Store(self.root), run, {"kind": "log", "code": "DEDUPE_PARTIAL", "level": "WARNING"})
        self.assertFalse(ready)
        journal = [event for event in self.client.get("/v1/events").json() if event.get("job") == run]
        self.assertEqual([event["code"] for event in journal], ["DEDUPE_PARTIAL"])

    def test_on_demand_selected_article_is_deduplicated_and_does_not_rebuild_digest(self):
        self.assertEqual(self.client.get("/v1/status").json()["enrichment_mode"], "on_demand")
        self.assertFalse(self.client.get("/v1/status").json()["digest_language_chosen"])
        self.assertEqual(self.client.put("/v1/digest-language", json={"language": "ru", "initialize": True}).json(),
                         {"language": "ru"})
        self.assertEqual(self.client.put("/v1/digest-language", json={"language": "en", "initialize": True}).json(),
                         {"language": "ru"})
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        run = uuid4()
        self.client.post("/v1/jobs", json={"id": str(run)})
        digest_job = self.wait(run.hex)
        self.assertEqual(digest_job["state"], "success")
        self.assertEqual(digest_job["result"]["stage"], "filtered")
        self.assertEqual(digest_job["result"]["enriched"], 0)
        self.assertEqual(self.fixture.enrichment_requests, [])
        self.assertEqual(len(self.fixture.translation_requests), 1)
        self.assertIn("into Russian", self.fixture.translation_requests[0])
        self.assertEqual(digest_job["result"]["languages"], ["ru"])
        directory = self.root / "runs" / run.hex
        self.assertFalse((directory / "enriched_items.json").exists())
        digest_path = f"/v1/digests/{run.hex}/ru"
        digest = self.client.get(digest_path).json()
        self.assertEqual(digest["items"][0]["body"], "Краткое описание новости на русском.")
        self.assertEqual(digest["items"][0]["url"], "https://example.org/fixture-news")
        self.assertIn("Краткое описание новости на русском.", digest["markdown"])

        # A second candidate in the saved stage must not be sent to Horizon.
        filtered = directory / "filtered_items.json"
        items = json.loads(filtered.read_bytes())
        other = {**items[0], "id": "other-saved-id", "title": "Unselected sentinel"}
        filtered.write_text(json.dumps([*items, other]))
        article = f"/v1/article-context/{run.hex}/{key_for(items[0]['id'])}"
        self.assertEqual(self.client.get(f"{article}/ru").json(), {"state": "idle"})
        self.assertEqual(self.client.post(f"{article}/ru", json={},
                                         headers={"Authorization": "Bearer wrong"}).status_code, 401)
        self.client.put("/v1/digest-language", json={"language": "zh"})

        def choose():
            return self.client.post(f"{article}/ru", json={}).json()

        with ThreadPoolExecutor(max_workers=2) as pool:
            requests = [pool.submit(choose), pool.submit(choose)]
            replies = [request.result() for request in requests]
        self.assertEqual(replies[0]["job"], replies[1]["job"])
        self.assertEqual(self.wait(replies[0]["job"])["state"], "success")
        ready = self.client.get(f"{article}/ru").json()
        self.assertEqual(ready["state"], "ready")
        self.assertEqual(ready["blocks"][0]["content"], "Текст новости на русском языке.")
        self.assertEqual(self.client.get(f"{article}/zh").status_code, 404)
        self.assertEqual(len(self.fixture.enrichment_requests), 1)
        self.assertNotIn("Unselected sentinel", json.dumps(self.fixture.enrichment_requests))
        self.assertEqual(self.client.get(digest_path).json(), digest)
        self.assertEqual(self.client.get("/v1/digest-marker").json(), {"id": run.hex})
        # Fetch through a separately created agent instance with the same state.
        restarted = TestClient(create_app(self.root, TOKEN), headers=AUTH)
        self.assertEqual(restarted.post(f"{article}/ru", json={}).json(), ready)
        self.assertEqual(len(self.fixture.enrichment_requests), 1)
        journal = self.client.get("/v1/events").json()
        self.assertTrue(any(x["code"] == "ARTICLE_CONTEXT_STARTED" for x in journal))
        self.assertTrue(any(x["code"] == "ARTICLE_CONTEXT_SUCCESS" and x["detail"].endswith(" ms") for x in journal))

    def test_article_timeout_can_be_retried_without_regenerating_the_issue(self):
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        run = uuid4()
        self.client.post("/v1/jobs", json={"id": str(run)})
        self.assertEqual(self.wait(run.hex)["state"], "success")
        item = self.client.get(f"/v1/digests/{run.hex}/en").json()["items"][0]
        path = f"/v1/article-context/{run.hex}/{key_for(item['id'])}/en"
        config = self.config("slow-context")
        config["revision"] = self.client.get("/v1/config").json()["revision"]
        self.assertEqual(self.client.put("/v1/config", json=config).status_code, 200)
        with patch("horizon_manager.jobs.CONTEXT_TIMEOUT", 0.5):
            first = self.client.post(path, json={}).json()["job"]
            self.assertEqual(self.wait(first)["state"], "timeout")
        self.assertEqual(self.client.get(path).json(), {"state": "failed", "code": "TIMEOUT"})
        self.assertTrue(any(x["code"] == "ARTICLE_CONTEXT_TIMEOUT" and x["detail"].endswith(" · TIMEOUT")
                            for x in self.client.get("/v1/events").json()))
        config["config"]["ai"]["model"] = "fixture-model"
        config["revision"] += 1
        self.assertEqual(self.client.put("/v1/config", json=config).status_code, 200)
        second = self.client.post(path, json={}).json()["job"]
        self.assertNotEqual(second, first)
        self.assertEqual(self.wait(second)["state"], "success")
        self.assertEqual(self.client.get(path).json()["state"], "ready")

    def test_translate_an_existing_digest(self):
        self.client.put("/v1/enrichment-mode", json={"mode": "automatic"})
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        run = uuid4()
        self.client.post("/v1/jobs", json={"id": str(run)})
        self.assertEqual(self.wait(run.hex)["state"], "success")
        # Simulate a pre-0.1.6 issue with metadata lacking an immutable language.
        with Store(self.root).db() as db:
            db.execute("UPDATE jobs SET result=json_remove(result, '$.output_language') WHERE id=?", (run.hex,))
        self.client.put("/v1/digest-language", json={"language": "ru"})
        self.assertEqual(self.client.get("/v1/digests").json()[0]["languages"], ["en"])

        translation = uuid4()
        response = self.client.post("/v1/jobs", json={
            "id": str(translation), "kind": "localize", "run": run.hex})
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(self.wait(translation.hex)["state"], "success")
        self.assertEqual(self.client.get("/v1/digest-marker").json()["id"], run.hex)
        result = self.client.get(f"/v1/digests/{run.hex}/ru").json()
        self.assertEqual(result["items"][0]["body"], "Краткое описание новости на русском.")
        self.assertEqual(result["total"], 1)
        self.assertEqual(set(self.client.get("/v1/digests").json()[0]["languages"]), {"en", "ru"})
        self.assertEqual(self.client.get(f"/v1/digests/{run.hex}/zh").status_code, 404)

    def test_existing_digest_recovers_placeholder_and_relays_cover_without_another_ai_request(self):
        self.client.put("/v1/digest-language", json={"language": "ru"})
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        run = uuid4()
        self.client.post("/v1/jobs", json={"id": str(run)})
        self.assertEqual(self.wait(run.hex)["state"], "success")
        directory = self.root / "runs" / run.hex
        localized = directory / "localized_items.json"
        raw = json.loads(localized.read_text())
        raw[0].update(source_type="telegram", author="mrizonNews",
                      metadata={"msg_url": "https://t.me/mrizonNews/123", "channel": "mrizonNews"})
        raw[0]["title"] = "Настоящий заголовок новости"
        raw[0]["content"] = "Сохранившийся русский текст публикации."
        artifact = raw[0]["processing"]["artifacts"]["ru"]
        artifact["title"] = "Перевод недоступен"
        artifact["blocks"][0]["content"] = "Читайте эту новость в первоисточнике."
        localized.write_text(json.dumps(raw, ensure_ascii=False))
        (directory / "summary-ru.md").write_text("# Перевод недоступен")
        requests_before = len(self.fixture.translation_requests)
        result = self.client.get(f"/v1/digests/{run.hex}/ru").json()
        self.assertEqual(result["content_version"], 3)
        self.assertEqual(result["items"][0]["title"], raw[0]["title"])
        self.assertEqual(result["items"][0]["body"], raw[0]["content"])
        self.assertEqual(result["items"][0]["telegram_channel"], "mrizonNews")
        self.assertEqual(result["items"][0]["telegram_url"], "https://t.me/mrizonNews/123")
        self.assertEqual(result["items"][0]["source_url"], "https://t.me/mrizonNews/123")
        self.assertNotIn("Перевод недоступен", result["markdown"])
        self.assertIn(raw[0]["title"], result["markdown"])
        self.assertEqual(len(self.fixture.translation_requests), requests_before)

        item_id = raw[0]["id"]
        known = {"kind": "original", "url": "https://example.com/photo.jpeg"}
        (directory / "media.json").write_text(json.dumps({item_id: known}))
        photo = bytes.fromhex("ffd8ff") + bytes(20)
        cover_path = f"/v1/covers/{run.hex}/{key_for(item_id)}"
        with patch("horizon_manager.media.fetch_image", AsyncMock(return_value=photo)) as download:
            response = self.client.get(cover_path)
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(base64.b64decode(response.json()["image"]), photo)
            self.assertEqual(self.client.get(cover_path).json(), response.json())
            download.assert_awaited_once()
        self.assertEqual(self.client.get(cover_path, headers={"Authorization": "Bearer wrong"}).status_code, 401)
        self.assertEqual(self.client.get(f"/v1/covers/{run.hex}/{key_for('not-in-this-digest')}").status_code, 404)

    def test_digest_exposes_discussion_without_changing_external_url(self):
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        run = uuid4()
        self.client.post("/v1/jobs", json={"id": str(run)})
        self.assertEqual(self.wait(run.hex)["state"], "success")
        localized = self.root / "runs" / run.hex / "localized_items.json"
        raw = json.loads(localized.read_text())
        for source, thread in (
            ("reddit", "https://www.reddit.com/r/android/comments/abc123/thread/"),
            ("hackernews", "https://news.ycombinator.com/item?id=42"),
        ):
            external = "https://example.org/linked-story"
            raw[0].update(source_type=source, url=external, metadata={"discussion_url": thread})
            localized.write_text(json.dumps(raw))
            result = self.client.get(f"/v1/digests/{run.hex}/en").json()
            self.assertEqual(result["items"][0]["url"], external)
            self.assertEqual(result["items"][0]["source_url"], thread)

    def test_ai_preflight_and_push_registration(self):
        response = self.client.post("/v1/jobs", json={"id": str(uuid4()), "kind": "ai"})
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["detail"]["fields"], ["ai.model", "OPENAI_API_KEY"])
        self.assertEqual(self.client.get("/v1/jobs").json(), [])
        device = "/v1/push/" + str(uuid4())
        data = {"endpoint": "https://ntfy.sh/upFixtureDevice", "secret": "2" * 64}
        response = self.client.put(device, json=data)
        self.assertEqual(response.json(), {"registered": True, "id": ""})
        self.assertNotIn(data["secret"], response.text)
        invalid = dict(data, endpoint="https://ntfy.sh@localhost/upFixtureDevice")
        self.assertEqual(self.client.put(device, json=invalid).status_code, 422)
        self.assertEqual(self.client.delete(device).json(), {"registered": False})

    def test_router_catalog_uses_a_persisted_key_and_returns_no_credentials(self):
        path = "/v1/ai/catalog/openrouter"
        self.assertEqual(self.client.get(path).json()["detail"]["code"], "ROUTER_KEY_MISSING")
        incoming = self.config()
        incoming["config"]["ai"]["base_url"] = "https://openrouter.ai/api/v1"
        self.assertEqual(self.client.put("/v1/config", json=incoming).status_code, 200)

        catalog = {"data": [{"id": "sample/text-model", "name": "Sample", "context_length": 16384}]}
        fetch = AsyncMock(return_value=catalog)
        with patch("horizon_manager.router_catalog.fetch", fetch):
            with TestClient(create_app(self.root, TOKEN), headers=AUTH) as reconnected:
                self.assertNotIn(SECRET, reconnected.get("/v1/config").text)
                response = reconnected.get(path)
                self.assertEqual(response.json(), catalog)
                self.assertNotIn(SECRET, response.text)
                fetch.assert_awaited_once_with("openrouter", SECRET)
                entered = "fixture-preview-key"
                self.assertEqual(reconnected.post(path, json={"key": entered}).json(), catalog)
                fetch.assert_awaited_with("openrouter", entered)
                reconnected.get(path)
                fetch.assert_awaited_with("openrouter", SECRET)
        self.assertEqual(self.client.get("/v1/ai/catalog/custom").status_code, 422)

    def test_archive_cursor_orders_by_completion_time(self):
        ids = ["run-20261119T090000Z-aaaaaaaa", "run-20261119T103000Z-bbbbbbbb", "run-20261119T090000Z-cccccccc"]
        for run, stamp in zip(ids, (1600010000, 1600008000, 1600007000)):
            directory = self.root / "runs" / run
            directory.mkdir(parents=True)
            (directory / "meta.json").write_text(json.dumps({"created_at": "2026-11-19T09:00:00Z"}))
            summary = directory / "summary-en.md"
            summary.write_text("# Digest")
            os.utime(summary, (stamp, stamp))
        first = self.client.get("/v1/digests?limit=2").json()
        self.assertEqual([row["id"] for row in first], ids[:2])
        self.assertEqual([row["id"] for row in self.client.get("/v1/digests", params={"after": ids[1]}).json()], ids[2:])

    def test_ai_wrong_secret_and_cancel(self):
        self.assertEqual(self.client.put("/v1/config", json=self.config(secret="wrong")).status_code, 200)
        first = uuid4()
        self.client.post("/v1/jobs", json={"id": str(first), "kind": "ai"})
        self.assertEqual(self.wait(first.hex)["state"], "failed")
        events = self.client.get("/v1/events").text
        self.assertIn("HTTP_401", events)
        self.assertNotIn(SECRET, events)
        self.assertEqual(self.client.put("/v1/config", json=self.config("slow")).status_code, 200)
        second = uuid4()
        self.client.post("/v1/jobs", json={"id": str(second), "kind": "ai"})
        self.wait(second.hex, final=False)
        self.assertEqual(self.client.post(f"/v1/jobs/{second.hex}/stop", json={}).status_code, 200)
        self.assertEqual(self.wait(second.hex)["state"], "cancelled")

    def test_scheduler_persists_and_runs_independently(self):
        self.client.put("/v1/digest-language", json={"language": "zh"})
        self.assertEqual(self.client.put("/v1/config", json=self.config()).status_code, 200)
        response = self.client.put("/v1/schedule", json={"mode": "interval", "hours": 2})
        self.assertGreater(response.json()["next"], time.time())
        with Store(self.root).db() as db:
            db.execute("UPDATE schedule SET next=? WHERE id=1", (time.time() - 1,))
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            jobs = self.client.get("/v1/jobs").json()
            if jobs:
                break
            time.sleep(0.1)
        self.assertTrue(jobs)
        job = self.wait(jobs[0]["id"])
        self.assertEqual(job["state"], "success")
        self.assertEqual(job["result"]["stage"], "filtered")
        self.assertEqual(job["result"]["enrichment_mode"], "on_demand")
        self.assertEqual(job["result"]["languages"], ["zh"])
        self.assertEqual(self.client.get(f"/v1/digests/{job['id']}/zh").json()["items"][0]["body"], "简体中文新闻摘要。")
        self.assertEqual(len(self.fixture.translation_requests), 1)
        self.assertEqual(self.fixture.enrichment_requests, [])
        self.assertFalse(any(event["code"] == "enrich" for event in self.client.get("/v1/events").json()))
        self.assertEqual(self.client.get("/v1/schedule").json()["spec"]["hours"], 2)

    def test_interval_starts_at_selected_wall_time_and_keeps_cadence(self):
        settings = {"mode": "interval", "hours": 7, "interval_start": "08:15", "zone": "Asia/Yekaterinburg"}
        stamp = datetime(2026, 11, 25, 2, 0, tzinfo=timezone.utc).timestamp()
        expected = datetime(2026, 11, 25, 3, 15, tzinfo=timezone.utc).timestamp()
        with patch("horizon_manager.api.time.time", return_value=stamp):
            result = self.client.put("/v1/schedule", json=settings).json()
        self.assertEqual(result["next"], expected)
        self.assertEqual(self.client.get("/v1/schedule").json()["spec"]["interval_start"], "08:15")
        with patch("horizon_manager.api.time.time", return_value=expected + 20):
            self.assertEqual(self.client.put("/v1/schedule", json=settings).json()["next"], expected)
        schedule = Schedule.model_validate(settings)
        self.assertEqual(next_run(schedule, expected + 20, previous=expected), expected + 7 * 3600)
        self.assertEqual(next_run(schedule, expected + 7 * 3600 + 60, previous=expected), expected + 14 * 3600)
        self.assertEqual(self.client.put("/v1/schedule", json={**settings, "interval_start": "24:00"}).status_code, 422)

    def test_restart_marks_inflight_without_resubmission(self):
        id = uuid4().hex
        with Store(self.root).db() as db:
            Store.queue(db, id, "run", {})
            db.execute("UPDATE jobs SET state='running' WHERE id=?", (id,))
        with TestClient(create_app(self.root, TOKEN), headers=AUTH) as fresh:
            self.assertEqual(fresh.get("/v1/jobs").json()[0]["state"], "interrupted")


class UnitTests(unittest.TestCase):
    def test_push_queue_is_idempotent_and_uses_a_signed_marker(self):
        with TemporaryDirectory() as folder:
            store = Store(Path(folder))
            with store.db() as db:
                db.execute("INSERT INTO push_devices VALUES (?,?,?)",
                           ("fixture", "https://ntfy.sh/upFixtureDevice", "2" * 64))
                enqueue_digest(db, "a" * 32)
                enqueue_digest(db, "a" * 32)
                self.assertEqual(db.execute("SELECT count(*) FROM push_outbox").fetchone()[0], 1)
        self.assertEqual(
            signed_message("a" * 32, 1700000000, "2" * 64),
            "a" * 32 + ".1700000000." + "ca999f48e9c0bf0987f0de510c159599bca466905b6a08f28b29c1bb6443c704",
        )
        self.assertIsNone(valid_endpoint("http://ntfy.sh/upFixtureDevice"))
        self.assertIsNone(valid_endpoint("https://ntfy.sh/upFixtureDevice?attach=https://x.example"))

    def test_weekly_calendar_skips_spring_gap_and_duplicate_fall_time(self):
        spec = Schedule(mode="calendar", zone="Europe/Amsterdam", hour=2, minute=30, days=[6])
        stamp = datetime(2026, 3, 28, tzinfo=timezone.utc).timestamp()
        result = datetime.fromtimestamp(next_run(spec, stamp), timezone.utc)
        self.assertEqual(result, datetime(2026, 4, 5, 0, 30, tzinfo=timezone.utc))
        stamp = datetime(2026, 10, 25, 0, 45, tzinfo=timezone.utc).timestamp()
        result = datetime.fromtimestamp(next_run(spec, stamp), timezone.utc)
        self.assertEqual(result, datetime(2026, 11, 1, 1, 30, tzinfo=timezone.utc))

    def test_backup_rejects_tampered_data(self):
        data = bytearray(base64.b64decode(pack({"value": "secret"}, "backup-password")))
        data[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "BACKUP_INVALID"):
            unpack(base64.b64encode(data).decode(), "backup-password")

    def test_source_selection_isolated(self):
        original = {"sources": {"telegram": {"enabled": False, "channels": [
            {"channel": "one", "enabled": True}, {"channel": "two", "enabled": False}
        ]}}}
        selected = only_source(original, "telegram", "channels", 1)
        self.assertEqual(len(selected["sources"]["telegram"]["channels"]), 1)
        self.assertEqual(len(original["sources"]["telegram"]["channels"]), 2)
        self.assertTrue(selected["sources"]["telegram"]["channels"][0]["enabled"])
        self.assertFalse(original["sources"]["telegram"]["channels"][1]["enabled"])


if __name__ == "__main__":
    unittest.main()
