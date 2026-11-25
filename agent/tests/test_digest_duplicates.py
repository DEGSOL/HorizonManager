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

    async def test_cross_profile_pass_reuses_topic_merging_before_enrichment(self):
        first = article("Vendor announced a seven-inch device")
        second = article("The new mobile device launches today")
        first.url = "https://example.org/news"
        second.url = "https://example.net/news"
        second.processing.classification.profile = "tech-blog"
        orchestrator = SimpleNamespace(
            config=SimpleNamespace(processing=SimpleNamespace(profile_settings={})),
            merge_topic_duplicates=AsyncMock(return_value=[first]),
        )
        report = Mock()
        self.assertEqual(await digest_duplicates([first, second], orchestrator, report), [first])
        orchestrator.merge_topic_duplicates.assert_awaited_once_with([first, second], log=False)
        report.assert_not_called()

        orchestrator.merge_topic_duplicates.reset_mock()
        orchestrator.config.processing.profile_settings = {"tech-blog": SimpleNamespace(topic_dedup=False)}
        self.assertEqual(await digest_duplicates([first, second], orchestrator, report), [first, second])
        orchestrator.merge_topic_duplicates.assert_not_awaited()
