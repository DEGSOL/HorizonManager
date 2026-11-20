import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from horizon_manager.media import commons_image, prepare_images, relay_cover, rss_images, source_cover, web_url


class PublicationMediaTests(unittest.IsolatedAsyncioTestCase):
    def test_origin_and_telegram_post_identity(self):
        self.assertEqual(source_cover(b'<meta property="og:image" content="/story.jpg">', "https://example.com/news"),
                         "https://example.com/story.jpg")
        markup = b"""<article data-post="world/11"><a class="tgme_widget_message_photo_wrap"
            style="background-image:url('https://cdn.example.com/11.jpg')"></a></article>"""
        self.assertEqual(source_cover(markup, "https://t.me/world/11"), "https://cdn.example.com/11.jpg")
        self.assertEqual(source_cover(markup, "https://t.me/world/12"), "")
        xml = b"""<rss version="2.0"><channel><item><title>One</title><link>https://example.com/a</link>
            <enclosure url="https://example.com/a.png" type="image/png" length="420"/></item></channel></rss>"""
        self.assertEqual(rss_images(xml), {"https://example.com/a": ["https://example.com/a.png"]})

    def test_rss_thumbnail_without_extension_and_relative_url(self):
        xml = b"""<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><item>
            <title>One</title><link>https://example.com/a</link>
            <media:thumbnail url="/resize/123?w=800"/>
            <enclosure url="/other.png" type="image/png"/></item></channel></rss>"""
        self.assertEqual(rss_images(xml, "https://example.com/feed"), {
            "https://example.com/a": ["https://example.com/resize/123?w=800", "https://example.com/other.png"]})

    async def test_invalid_metadata_falls_through_to_working_page_cover(self):
        item = SimpleNamespace(id="news", url="https://example.com/article",
                               metadata={"image_url": "https://example.com/expired.jpeg"})
        config = SimpleNamespace(sources=SimpleNamespace(rss=[]))
        response = (b'<meta name="twitter:image" content="/other.jpg">'
                    b'<meta property="og:image" content="cover.jpg">', "https://example.com/landing/news")
        with TemporaryDirectory() as folder, patch("horizon_manager.media.fetch_document",
                AsyncMock(return_value=response)), patch("horizon_manager.media.usable_image",
                AsyncMock(side_effect=[False, True])) as probe:
            await prepare_images(Path(folder), [item], config, False)
            artwork = json.loads((Path(folder) / "media.json").read_bytes())
            self.assertEqual(artwork["news"]["url"], "https://example.com/landing/cover.jpg")
            self.assertEqual(probe.await_count, 2)

    async def test_telegram_cover_comes_from_original_post_when_story_links_out(self):
        item = SimpleNamespace(id="news", url="https://example.com/article",
                               metadata={"msg_url": "https://telegram.me/NewsChannel/71"})
        config = SimpleNamespace(sources=SimpleNamespace(rss=[]))
        pages = [(b"<html></html>", "https://telegram.me/NewsChannel/71"),
                 (b"""<div data-post="newschannel/70"><video poster="https://cdn.example.com/other.jpg"></video></div>
                      <div data-post="newschannel/71"><video poster="https://cdn.example.com/post.jpg"></video></div>""",
                  "https://telegram.me/s/NewsChannel?before=72")]
        with TemporaryDirectory() as folder, patch("horizon_manager.media.fetch_document",
                AsyncMock(side_effect=pages)) as fetch, patch("horizon_manager.media.usable_image",
                AsyncMock(return_value=True)) as probe:
            await prepare_images(Path(folder), [item], config, False)
            artwork = json.loads((Path(folder) / "media.json").read_bytes())
            self.assertEqual(artwork["news"]["source"], item.metadata["msg_url"])
            self.assertEqual(artwork["news"]["url"], "https://cdn.example.com/post.jpg")
            self.assertEqual(fetch.await_args.args[1], "https://telegram.me/s/NewsChannel?before=72")
            self.assertEqual(probe.await_args.args[1], "https://cdn.example.com/post.jpg")

    async def test_relay_caches_download_without_another_cdn_request(self):
        image = bytes.fromhex("ffd8ff") + bytes(20)
        item = {"url": "https://example.com/article"}
        known = {"url": "https://cdn.example.com/old-cover.jpg"}
        with TemporaryDirectory() as folder, patch("horizon_manager.media.fetch_image",
                AsyncMock(return_value=image)) as fetch:
            cached = Path(folder) / "media" / "relay.img"
            self.assertEqual(await relay_cover(item, known, cached), image)
            self.assertEqual(await relay_cover(item, known, cached), image)
            fetch.assert_awaited_once()

    async def test_unusable_wikimedia_result_is_not_published(self):
        item = SimpleNamespace(id="news", url="https://example.com/article", metadata={})
        config = SimpleNamespace(ai=None, sources=SimpleNamespace(rss=[]))
        with TemporaryDirectory() as folder, patch("horizon_manager.media.fetch_document",
                AsyncMock(return_value=(b"<html></html>", item.url))), patch("horizon_manager.media.usable_image",
                AsyncMock(return_value=False)) as probe, patch("horizon_manager.media.commons_image",
                AsyncMock(return_value={"url": "https://example.com/dead.jpg"})), patch(
                "horizon_manager.media.create_ai_client"):
            self.assertEqual(await prepare_images(Path(folder), [item], config, True), 0)
            self.assertEqual((Path(folder) / "media.json").read_text(), "{}")
            probe.assert_awaited_once()

    def test_rejects_non_public_and_credential_urls(self):
        for url in ("file:///etc/passwd", "https://127.0.0.1/a", "https://[::1]/a", "http://example.com/a",
                    "https://user:pass@example.com/a", "https://224.0.0.1/a"):
            self.assertEqual(web_url(url), "")

    async def test_ai_selects_among_eligible_images_or_abstains(self):
        def image(i, license):
            return {"title": f"File:Planet {i}", "imageinfo": [{"mime": "image/jpeg",
                    "thumburl": f"https://upload.wikimedia.org/{i}.jpg",
                    "descriptionurl": f"https://commons.wikimedia.org/wiki/File:{i}",
                    "extmetadata": {"LicenseShortName": {"value": license}}}]}
        response = {"query": {"pages": {"1": image(1,"CC BY-SA"), "2":image(2,"CC0"), "3":image(3,"Public domain")}}}
        ai = SimpleNamespace(complete=AsyncMock(side_effect=['{"query":"Earth orbit"}','{"index":1}',
                                                            '{"query":"Earth orbit"}','{"index":-1}']))
        item = SimpleNamespace(title="Earth from orbit",content="Space station")
        with patch("horizon_manager.media.document", AsyncMock(return_value=json.dumps(response).encode())):
            choice = await commons_image(None,ai,item)
            self.assertEqual(choice["url"], "https://upload.wikimedia.org/3.jpg")
            self.assertEqual(choice["kind"], "thematic")
            self.assertEqual(await commons_image(None,ai,item), {})
        self.assertNotIn("File:Planet 1", ai.complete.await_args_list[1].kwargs["user"])
