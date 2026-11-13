"""Offline tests for the pilot API: no provider calls or real credentials."""
import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from voice_api import app, normalize_numbers, trim_context, PROPOSAL_SCHEMA


class VoiceApiTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_health(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")

    def test_auth_required(self):
        with patch.dict(os.environ, {"POYARA_VOICE_TOKEN": "a" * 40}):
            response = self.client.post(
                "/v1/voice/analyze",
                files={"audio": ("clip.m4a", b"1234567890" * 30, "audio/mp4")},
            )
            self.assertEqual(response.status_code, 401)

    def test_bad_token(self):
        with patch.dict(os.environ, {"POYARA_VOICE_TOKEN": "a" * 40}):
            response = self.client.post(
                "/v1/voice/analyze",
                headers={"Authorization": "Bearer " + "b" * 40},
                files={"audio": ("clip.m4a", b"1234567890" * 30, "audio/mp4")},
            )
            self.assertEqual(response.status_code, 403)

    def test_wrong_file_type(self):
        with patch.dict(os.environ, {
            "POYARA_VOICE_TOKEN": "a" * 40,
            "OPENAI_API_KEY": "local-test-only"
        }):
            response = self.client.post(
                "/v1/voice/analyze",
                headers={"Authorization": "Bearer " + "a" * 40},
                files={"audio": ("clip.txt", b"not a recording", "text/plain")},
            )
            self.assertEqual(response.status_code, 415)

    def test_text_agent_requires_pairing_token(self):
        with patch.dict(os.environ, {"POYARA_VOICE_TOKEN": "a" * 40}):
            response = self.client.post("/v1/text/analyze",
                json={"transcript": "فردا ساعت ۱۴:۳۰ جلسه دارم"})
            self.assertEqual(response.status_code, 401)

    def test_text_agent_requires_free_gemini_key(self):
        with patch.dict(os.environ, {
            "POYARA_VOICE_TOKEN": "a" * 40,
            "POYARA_TEXT_PROVIDER": "gemini",
            "GEMINI_API_KEY": "",
            "OPENAI_API_KEY": "",
        }):
            response = self.client.post("/v1/text/analyze",
                headers={"Authorization": "Bearer " + "a" * 40},
                json={"transcript": "آقای حسینی مدیر مبلمان لیپاریس است"})
            self.assertEqual(response.status_code, 503)

    def test_paid_provider_not_selected_implicitly(self):
        with patch.dict(os.environ, {
            "POYARA_VOICE_TOKEN": "a" * 40,
            "POYARA_TEXT_PROVIDER": "no-such-provider",
            "OPENAI_API_KEY": "should-never-be-used",
        }):
            response = self.client.post("/v1/text/analyze",
                headers={"Authorization": "Bearer " + "a" * 40},
                json={"transcript": "آقای حسینی مدیر لیپاریس است"})
            self.assertEqual(response.status_code, 503)

    def test_agent_schema_has_industry_category(self):
        self.assertIn("category",
            PROPOSAL_SCHEMA["properties"]["contacts"]["items"]["properties"])

    def test_persian_digits(self):
        self.assertEqual(normalize_numbers("۰۹۱۲٣٤٥٦٧٨"), "0912345678")

    def test_context_is_bounded(self):
        data = str([{"name": "x"}])  # Invalid JSON intentionally
        self.assertEqual(trim_context(data), [])
        self.assertEqual(len(trim_context('[' + ','.join(
            ['{"name":"علی","company":"آریا","phone":"0912"}'] * 100
        ) + ']')), 25)

    def test_schema_requires_entities(self):
        self.assertEqual(
            set(PROPOSAL_SCHEMA["required"]), {"contacts", "reminders", "questions"}
        )


if __name__ == "__main__":
    unittest.main()
