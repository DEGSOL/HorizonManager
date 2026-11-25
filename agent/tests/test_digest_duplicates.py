import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

from horizon_manager.digest_duplicates import digest_duplicates, exact_duplicates
from test_localization import article


class DigestDuplicatesTests(unittest.IsolatedAsyncioTestCase):
    def test_same_article_urls_and_titles_collapse_to_the_best_scored_item(self):
        first = article("Vendor announced a new compact device")
        second = article("A compact device launches today")
        third = article("Vendor announced a new compact device")
        update = article("Vendor announced a new compact device update")
        first.url = "https://example.org/news?id=7&utm_source=rss"
        second.url = "http://example.org/news?id=7&fbclid=tracker"
        third.url = "https://example.net/news"
        update.url = "https://example.org/news?id=8"
        second.processing.analysis.score = 9

        self.assertEqual(exact_duplicates([first, second, third, update]), [second, update])

    def orchestrator(self, complete):
        return SimpleNamespace(
            config=SimpleNamespace(processing=SimpleNamespace(profile_settings={})),
            ai_client=SimpleNamespace(complete=complete),
        )

    async def test_paraphrases_in_one_profile_merge_without_enrichment(self):
        first = article("Компания выпустила карманную консоль для облачного гейминга")
        second = article("Acme's StreamPocket goes on sale")
        follow_up = article("Acme cuts the price of the StreamPocket a month after release")
        first.url = "https://example.org/russian"
        second.url = "https://example.net/international"
        follow_up.url = "https://example.net/follow-up"
        first.content = "Acme начала продажи StreamPocket по цене 199 долларов 19 ноября."
        second.content = "Acme launched its StreamPocket on November 19 at $199."
        second.processing.analysis.score = 9
        complete = AsyncMock(return_value='{"duplicates": [[0, 1]]}')
        orchestrator = self.orchestrator(complete)
        report = Mock()

        self.assertEqual(await digest_duplicates([first, second, follow_up], orchestrator, report), [second, follow_up])
        complete.assert_awaited_once()
        request = json.loads(complete.await_args.kwargs["user"])
        self.assertEqual([value["title"] for value in request], [first.title, second.title, follow_up.title])
        self.assertIn("199 долларов", request[0]["excerpt"])
        report.assert_not_called()

        orchestrator.config.processing.profile_settings = {"tech-news": SimpleNamespace(topic_dedup=False)}
        complete.reset_mock()
        self.assertEqual(await digest_duplicates([first, second], orchestrator, report), [first, second])
        complete.assert_not_awaited()

    async def test_compares_distant_batches_and_multiple_profiles(self):
        items = [article(f"Unique story from magazine {index}") for index in range(58)]
        for index, item in enumerate(items):
            item.url = f"https://example.org/story/{index}"
        items[-1].processing.classification.profile = "tech-blog"

        def response(**request):
            ids = {value["id"] for value in json.loads(request["user"])}
            return '{"duplicates": [[0, 57]]}' if {0, 57} <= ids else '{"duplicates": []}'

        report = Mock()
        self.assertEqual(await digest_duplicates(items, self.orchestrator(AsyncMock(side_effect=response)), report),
                         items[:-1])
        report.assert_not_called()

    async def test_malformed_provider_response_retries_and_preserves_articles(self):
        items = [article("First reporting on computing hardware"), article("Other reporting on computing hardware")]
        items[1].url = "https://example.net/other"
        complete = AsyncMock(side_effect=['{"duplicates": [[0, 999]]}', '{"duplicates": [[0, true]]}'])
        report = Mock()
        self.assertEqual(await digest_duplicates(items, self.orchestrator(complete), report), items)
        self.assertEqual(complete.await_count, 2)
        report.assert_called_once_with("DEDUPE_PARTIAL")
