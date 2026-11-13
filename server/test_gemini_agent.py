"""Gemini free text agent regression tests: no provider calls or real keys."""
import asyncio
import os
import unittest
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

from gemini_agent import (
    build_gemini_request,
    extract_gemini_proposal,
    parse_gemini_response,
)
from voice_api import app, PROPOSAL_SCHEMA


SAMPLE = {
    "contacts": [{
        "name": "حسینی", "phone": "۰۹۱۵۸۱۵۱۲۰۴", "company": "مبلمان لیپاریس",
        "role": "مدیر", "category": "مبل‌فروشی", "address": None,
        "note": None, "related_to": None,
    }],
    "reminders": [{
        "person_name": "حسینی", "company": "مبلمان لیپاریس",
        "title": "جلسه با آقای حسینی", "date_iso": None,
        "time_local": "14:30", "remind_days_before": 2,
        "requires_confirmation": True,
    }],
    "questions": ["جلسه برای کدام روز است؟"],
}


class DummyResponse:
    def __init__(self, status=200, payload=None):
        self.status_code = status
        self.payload = payload or {}

    def json(self):
        return self.payload


class DummyClient:
    def __init__(self, status, payload):
        self.status = status
        self.payload = payload
        self.last_url = None
        self.last_headers = None
        self.last_json = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return None

    async def post(self, url, *, headers, json):
        self.last_url, self.last_headers, self.last_json = url, headers, json
        return DummyResponse(self.status, self.payload)


class GeminiAgentTests(unittest.TestCase):
    def test_build_request_contains_schema_and_no_tools(self):
        request = build_gemini_request("Persian CRM", "جلسه فردا", PROPOSAL_SCHEMA)
        self.assertEqual(request["generationConfig"]["responseMimeType"], "application/json")
        self.assertEqual(request["generationConfig"]["responseSchema"], PROPOSAL_SCHEMA)
        self.assertNotIn("tools", request)
        self.assertEqual(request["generationConfig"]["temperature"], 0)

    def test_parse_json_response(self):
        response = {"candidates": [{
            "finishReason": "STOP",
            "content": {"parts": [{"text": __import__("json").dumps(SAMPLE, ensure_ascii=False)}]},
        }]}
        self.assertEqual(parse_gemini_response(response), SAMPLE)

    def test_parse_fails_closed_on_missing_content(self):
        with self.assertRaises(HTTPException):
            parse_gemini_response({"candidates": []})

    def test_http_adapter_targets_google_only(self):
        payload = {"candidates": [{"finishReason": "STOP",
            "content": {"parts": [{"text": __import__("json").dumps(SAMPLE)}]}}]}
        dummy = DummyClient(200, payload)
        with patch("gemini_agent.httpx.AsyncClient", return_value=dummy):
            parsed = asyncio.run(extract_gemini_proposal(
                "test-key", "gemini-3.5-flash-lite", "instructions",
                "transcript", PROPOSAL_SCHEMA))
        self.assertEqual(parsed["contacts"][0]["role"], "مدیر")
        self.assertEqual(dummy.last_url,
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent")
        self.assertEqual(dummy.last_headers["x-goog-api-key"], "test-key")
        self.assertEqual(dummy.last_json["generationConfig"]["responseMimeType"], "application/json")

    def test_429_is_returned_without_openai_fallback(self):
        dummy = DummyClient(429, {})
        with patch("gemini_agent.httpx.AsyncClient", return_value=dummy):
            with self.assertRaises(HTTPException) as caught:
                asyncio.run(extract_gemini_proposal(
                    "test-key", "gemini-3.5-flash-lite", "instructions",
                    "transcript", PROPOSAL_SCHEMA))
        self.assertEqual(caught.exception.status_code, 429)

    def test_model_name_cannot_change_host(self):
        with self.assertRaises(HTTPException):
            asyncio.run(extract_gemini_proposal(
                "test-key", "../admin", "instructions", "transcript", PROPOSAL_SCHEMA))

    def test_server_text_endpoint_uses_gemini_without_openai_key(self):
        async def fake_provider(**kwargs):
            return __import__("copy").deepcopy(SAMPLE)

        with patch.dict(os.environ, {
            "POYARA_VOICE_TOKEN": "a" * 40,
            "POYARA_TEXT_PROVIDER": "gemini",
            "GEMINI_API_KEY": "test-key",
            "OPENAI_API_KEY": "",
        }):
            with patch("voice_api.extract_gemini_proposal", side_effect=fake_provider) as mock:
                response = TestClient(app).post("/v1/text/analyze",
                    headers={"Authorization": "Bearer " + "a" * 40},
                    json={"transcript": "آقای حسینی مدیر مبلمان لیپاریس است، جلسه ۲:۳۰"})
        self.assertEqual(response.status_code, 200)
        result = response.json()
        self.assertEqual(result["contacts"][0]["phone"], "09158151204")
        self.assertTrue(result["reminders"][0]["requires_confirmation"])
        self.assertIsNone(result["reminders"][0]["date_iso"])
        self.assertTrue(mock.called)
        self.assertEqual(mock.call_args.kwargs["model"], "gemini-3.5-flash-lite")


if __name__ == "__main__":
    unittest.main()
