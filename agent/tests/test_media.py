import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from horizon_manager.media import commons_image, rss_images, source_cover, web_url


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
        self.assertEqual(rss_images(xml), {"https://example.com/a": "https://example.com/a.png"})

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
