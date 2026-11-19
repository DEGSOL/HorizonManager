import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from src.models import ContentItem

from horizon_manager.localized_digest import complete_artifacts


def article(title):
    return ContentItem.model_validate({
        "id": title, "title": title, "url": "https://example.com/news",
        "source_type": "rss", "published_at": "2026-11-19T12:00:00Z",
        "content": "Longer original source " * 500,
        "processing": {
            "classification": {"profile": "tech-news", "method": "source_override"},
            "analysis": {"score": 7, "summary": "Short original description", "reason": "fixture"},
        },
    })


class DigestLocalizationTests(unittest.IsolatedAsyncioTestCase):
    async def test_preserves_successful_translation_when_another_item_fails(self):
        items = [article("First news"), article("Second news")]
        responses = [{"items": [
            {"id": "0", "title": "Первая новость", "text": "Краткое описание первой новости."},
            {"id": "1", "title": "Second news", "text": "This text is still in English."},
        ]}, {"items": []}]
        calls = AsyncMock(side_effect=[json.dumps(value) for value in responses])
        client = SimpleNamespace(complete=calls)
        config = SimpleNamespace(enrichment_concurrency=1, max_tokens=1500)
        with patch("horizon_manager.localized_digest.create_ai_client", return_value=client):
            unavailable = await complete_artifacts(items, "ru", config)
        self.assertEqual(unavailable, 1)
        self.assertEqual([set(item.processing.artifacts) for item in items], [{"ru"}, {"ru"}])
        self.assertEqual(items[0].processing.artifacts["ru"].title, "Первая новость")
        self.assertEqual(items[1].processing.artifacts["ru"].title, "Перевод недоступен")
        first_payload, second_payload = [json.loads(call.kwargs["user"]) for call in calls.await_args_list]
        self.assertEqual([row["id"] for row in first_payload], ["0", "1"])
        self.assertEqual([row["id"] for row in second_payload], ["1"])
        self.assertEqual(first_payload[0]["text"], "Short original description")
