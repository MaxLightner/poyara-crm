"""Gemini free-tier text-to-CRM adapter. No API key is ever shipped in the APK.

This module makes one text-only request; it never uploads recorded audio, writes CRM
records, or sends messages. The caller validates and applies commands separately.
"""
import json
import re

import httpx
from fastapi import HTTPException

GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"


def build_gemini_request(instructions: str, payload: str, schema: dict) -> dict:
    return {
        "systemInstruction": {"parts": [{"text": instructions}]},
        "contents": [{"role": "user", "parts": [{"text": payload}]}],
        "generationConfig": {
            "temperature": 0,
            "maxOutputTokens": 1800,
            "responseMimeType": "application/json",
            "responseSchema": schema,
        },
    }


def parse_gemini_response(body: dict) -> dict:
    candidates = body.get("candidates", [])
    if not candidates:
        raise HTTPException(422, "Gemini returned no CRM suggestion")
    candidate = candidates[0]
    if candidate.get("finishReason") not in (None, "STOP"):
        raise HTTPException(502, "Gemini did not finish the CRM extraction")
    segments = candidate.get("content", {}).get("parts", [])
    output = "".join(str(part.get("text", "")) for part in segments)
    try:
        proposal = json.loads(output)
    except (ValueError, TypeError) as exc:
        raise HTTPException(502, "Gemini returned invalid JSON") from exc
    if not isinstance(proposal, dict) or not all(
        isinstance(proposal.get(field), list)
        for field in ("contacts", "reminders", "questions")
    ):
        raise HTTPException(502, "Gemini returned an unexpected response schema")
    return proposal


async def extract_gemini_proposal(
    api_key: str, model: str, instructions: str, payload: str, schema: dict
) -> dict:
    if not api_key.strip():
        raise HTTPException(503, "Free Gemini API key is not configured on the server")
    if not re.fullmatch(r"gemini-[a-z0-9.\-]{3,64}", model):
        raise HTTPException(503, "Invalid Gemini model name")
    url = f"{GEMINI_API_BASE}/models/{model}:generateContent"
    try:
        async with httpx.AsyncClient(timeout=45.0) as client:
            response = await client.post(
                url,
                headers={
                    "x-goog-api-key": api_key,
                    "Content-Type": "application/json",
                },
                json=build_gemini_request(instructions, payload, schema),
            )
            if response.status_code == 429:
                raise HTTPException(
                    429, "Gemini free quota was reached; the local transcript is preserved"
                )
            if response.status_code in (401, 403):
                raise HTTPException(503, "Gemini API key or account access is invalid")
            if response.status_code not in (200,):
                raise HTTPException(502, "Gemini API request failed")
            data = response.json()
    except httpx.RequestError as exc:
        raise HTTPException(503, "Gemini is temporarily unreachable") from exc
    except (ValueError, TypeError) as exc:
        raise HTTPException(502, "Gemini returned an invalid network response") from exc
    return parse_gemini_response(data)
