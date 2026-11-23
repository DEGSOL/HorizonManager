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
    async def test_custom_headline_limit_controls_prompt_and_offline_fallback(self):
        item = article("Компания представила новую компактную модель телефона")
        item.content = "Устройство появится в продаже в следующем месяце."
        calls = AsyncMock(return_value='{"items":[]}')
        config = SimpleNamespace(enrichment_concurrency=1, max_tokens=1500)
        with patch("horizon_manager.localized_digest.create_ai_client", return_value=SimpleNamespace(complete=calls)):
            self.assertEqual(await complete_artifacts([item], "ru", config, headline_range=(3, 8)), 0)
        self.assertIn("3 to 8 characters", calls.await_args.kwargs["system"])
        self.assertEqual(item.processing.artifacts["ru"].title, "Компани…")

    async def test_short_title_is_rewritten_and_spaces_and_punctuation_count(self):
        short = article("Сбой")
        short.content = "Новая модель появилась в открытом доступе."
        correct = article("ИИ в РФ!")
        correct.content = short.content
        calls = AsyncMock(return_value='{"items":[{"id":"0","title":"Сбой ИИ!"}]}')
        config = SimpleNamespace(enrichment_concurrency=1, max_tokens=1500)
        with patch("horizon_manager.localized_digest.create_ai_client", return_value=SimpleNamespace(complete=calls)):
            self.assertEqual(await complete_artifacts([short, correct], "ru", config, headline_range=(8, 8)), 0)
        calls.assert_awaited_once()
        self.assertEqual(json.loads(calls.await_args.kwargs["user"])[0]["title"], "Сбой")
        self.assertEqual(short.processing.artifacts["ru"].title, "Сбой ИИ!")
        self.assertEqual(correct.processing.artifacts["ru"].title, "ИИ в РФ!")

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
        self.assertEqual(items[1].processing.artifacts["ru"].title, "Second news")
        self.assertEqual(items[1].processing.artifacts["ru"].blocks[0].content, "Short original description")
        first_payload, second_payload = [json.loads(call.kwargs["user"]) for call in calls.await_args_list]
        self.assertEqual([row["id"] for row in first_payload], ["0", "1"])
        self.assertEqual(second_payload, {"title": "Second news", "text": "Short original description"})
        self.assertEqual(first_payload[0]["text"], "Short original description")


    async def test_native_article_prefers_russian_source_to_english_analysis(self):
        item = article("Точка Банк запустил MCP-сервер")
        item.content = "Банк запустил новый сервис для работы с платежами."
        client = SimpleNamespace(complete=AsyncMock())
        config = SimpleNamespace(enrichment_concurrency=1, max_tokens=1500)
        with patch("horizon_manager.localized_digest.create_ai_client", return_value=client) as create:
            self.assertEqual(await complete_artifacts([item], "ru", config), 0)
        create.assert_not_called()
        self.assertEqual(item.processing.artifacts["ru"].title, item.title)
        self.assertEqual(item.processing.artifacts["ru"].blocks[0].content, item.content)

    async def test_truncated_response_retains_complete_item_and_retries_only_missing_id(self):
        items = [article("First news"), article("Second news")]
        responses = ['{"items":[{"id":0,"title":"Первая новость","text":"Подробное описание новости."},'
                     '{"id":"1","title":"Вторая', json.dumps(
                         {"title": "Вторая новость", "text": "Второе подробное описание."})]
        client = SimpleNamespace(complete=AsyncMock(side_effect=responses))
        config = SimpleNamespace(enrichment_concurrency=1, max_tokens=1536)
        report = unittest.mock.Mock()
        with patch("horizon_manager.localized_digest.create_ai_client", return_value=client):
            self.assertEqual(await complete_artifacts(items, "ru", config, report=report), 0)
        self.assertEqual([item.processing.artifacts["ru"].title for item in items], ["Первая новость", "Вторая новость"])
        self.assertEqual(json.loads(client.complete.await_args.kwargs["user"])["title"], "Second news")
        report.assert_called_once_with("LOCALIZATION_RECOVERED", 1)

    async def test_only_long_native_telegram_headline_is_rewritten(self):
        item = article("Тестовая новость")
        item.source_type = "telegram"
        headline = "Компания представила новую модель и рассказала когда начнутся продажи во всех регионах страны"
        item.title = headline[:80]
        item.content = headline + "\n\nПодробности о выходе устройства."
        short = article("Компания выпустила обновление")
        short.content = "В обновлении исправлены ошибки приложения."
        model_title = "Компания представила новую модель"
        calls = AsyncMock(return_value=json.dumps({"items": [{"id": "0", "title": model_title}]}))
        config = SimpleNamespace(enrichment_concurrency=1, max_tokens=1536)
        with patch("horizon_manager.localized_digest.create_ai_client", return_value=SimpleNamespace(complete=calls)):
            self.assertEqual(await complete_artifacts([item, short], "ru", config), 0)
        calls.assert_awaited_once()
        self.assertEqual(json.loads(calls.await_args.kwargs["user"])[0]["title"], headline)
        self.assertEqual(item.processing.artifacts["ru"].title, model_title)
        self.assertEqual(short.processing.artifacts["ru"].title, short.title)

    async def test_invalid_title_keeps_translation_and_falls_back_at_word_boundary(self):
        item = article("Source headline")
        long = "Компания представила новую модель и рассказала когда начнутся продажи во всех регионах страны"
        first = {"items": [{"id": "0", "title": long, "text": "Сохранённый перевод описания."}]}
        second = {"items": [{"id": "0", "title": "Я"}]}
        calls = AsyncMock(side_effect=[json.dumps(first), json.dumps(second)])
        config = SimpleNamespace(enrichment_concurrency=1, max_tokens=1536)
        with patch("horizon_manager.localized_digest.create_ai_client", return_value=SimpleNamespace(complete=calls)):
            self.assertEqual(await complete_artifacts([item], "ru", config), 0)
        self.assertEqual(calls.await_count, 2)
        self.assertIn("3 to 60 characters", calls.await_args_list[0].kwargs["system"])
        artifact = item.processing.artifacts["ru"]
        self.assertEqual(artifact.title, "Компания представила новую модель и рассказала когда…")
        self.assertEqual(artifact.blocks[0].content, "Сохранённый перевод описания.")
