"""POYARA pilot voice-to-CRM proposal API.

This service never mutates CRM data. The Android client reviews the returned
proposals and applies them locally. Deploy only behind HTTPS with a unique
per-installation bearer credential; replace pilot auth with team JWTs before
multi-user production use.
"""
import hmac
import json
import os
import re
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field
from fastapi.responses import JSONResponse
from gemini_agent import extract_gemini_proposal, extract_gemini_audio_proposal, DEFAULT_GEMINI_MODEL

MAX_AUDIO_BYTES = 25 * 1024 * 1024
OPENAI_ENDPOINT = "https://api.openai.com/v1"
TIME_ZONE = "Asia/Tehran"

app = FastAPI(title="POYARA Voice Proposal API", version="0.1.0",
              docs_url=None, redoc_url=None)

CONTACT_PROPERTIES = {
    name: {"type": ["string", "null"]}
    for name in ("name", "phone", "company", "role", "category", "address", "note", "related_to")
}
REMINDER_PROPERTIES = {
    "person_name": {"type": ["string", "null"]},
    "company": {"type": ["string", "null"]},
    "title": {"type": "string"},
    "date_iso": {"type": ["string", "null"]},
    "time_local": {"type": ["string", "null"]},
    "remind_days_before": {"type": "integer"},
    "requires_confirmation": {"type": "boolean"},
}
PROPOSAL_SCHEMA = {
    "type": "object",
    "properties": {
        "corrected_text": {"type": "string"},
        "actions": {"type": "array", "items": {
            "type": "object", "properties": {
                "type": {"type": "string", "enum": [
                    "correct_name", "correct_phone", "remove_phone", "add_phone", "set_company", "set_role", "set_category",
                    "create_reminder", "update_reminder", "delete_reminder", "append_note"]},
                "value": {"type": ["string", "null"]},
                "target_reminder_id": {"type": ["integer", "null"]},
                "title": {"type": ["string", "null"]},
                "date_iso": {"type": ["string", "null"]},
                "time_local": {"type": ["string", "null"]},
                "requires_confirmation": {"type": "boolean"},
                "remind_days_before": {"type": "integer"}
            },
            "required": ["type", "value", "target_reminder_id", "title",
                "date_iso", "time_local", "requires_confirmation", "remind_days_before"],
            "additionalProperties": False
        }},
        "contacts": {"type": "array", "items": {
            "type": "object", "properties": CONTACT_PROPERTIES,
            "required": list(CONTACT_PROPERTIES), "additionalProperties": False}},
        "reminders": {"type": "array", "items": {
            "type": "object", "properties": REMINDER_PROPERTIES,
            "required": list(REMINDER_PROPERTIES), "additionalProperties": False}},
        "questions": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["contacts", "reminders", "questions", "corrected_text", "actions"],
    "additionalProperties": False,
}

def require_pilot_auth(header: str | None) -> None:
    secret = os.getenv("POYARA_VOICE_TOKEN", "")
    if len(secret) < 32:
        raise HTTPException(503, "Voice service credentials are not configured")
    if not header or not header.startswith("Bearer "):
        raise HTTPException(401, "Bearer token required")
    if not hmac.compare_digest(header[7:], secret):
        raise HTTPException(403, "Invalid bearer token")

def normalize_numbers(value: str) -> str:
    digits = "۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩"
    return value.translate(str.maketrans(digits, "0123456789" * 2))

def trim_context(raw: str) -> list[dict]:
    try:
        data = json.loads(raw or "[]")
    except (TypeError, ValueError):
        return []
    if not isinstance(data, list):
        return []
    items = []
    for row in data[:25]:
        if isinstance(row, dict):
            items.append({
                key: normalize_numbers(str(row.get(key, "")))[:120]
                for key in ("name", "company", "phone")
            })
    return items


class TextAgentRequest(BaseModel):
    transcript: str = Field(min_length=1, max_length=10000)
    contacts: list[dict] = Field(default_factory=list)
    timezone: str = Field(default="Asia/Tehran", max_length=60)
    reminders: list[dict] = Field(default_factory=list)


@app.post("/v1/text/analyze")
async def analyze_text(
    request: TextAgentRequest,
    authorization: str | None = Header(None),
):
    """Small, single-turn text extraction. No persistent agent or audio upload."""
    require_pilot_auth(authorization)
    provider = os.getenv("POYARA_TEXT_PROVIDER", "gemini").strip().lower()
    if provider not in ("gemini", "openai"):
        raise HTTPException(503, "Unsupported text provider configured")
    transcript = request.transcript.strip()
    if not transcript:
        raise HTTPException(422, "Empty transcript")
    instructions = (
        "You are a Persian CRM command parser. Return only strictly grounded facts "
        "in the provided JSON schema. Extract a person's name, every explicit "
        "phone number, their organization, role (e.g. مدیر مجموعه), and industry "
        "category (e.g. مبل فروشی or مهندسی ساختمان), and specific calls/meetings. "
        "Never invent or infer numbers not in the transcript. Keep Persian contact "
        "names as spoken. For relative dates interpret using today and timezone "
        "provided. For 'ساعت 2:30' without day and AM/PM, ask in Persian what "
        "day and whether morning or afternoon; do NOT schedule. A clear 24h "
        "date and time may be returned without confirmation. Ensure reminders "
        "are future and date_iso is Gregorian YYYY-MM-DD; time_local is HH:mm. "
        "If an exact participant is ambiguous, requires_confirmation=true. "
        "Transcribe or normalize natural Persian accurately. Keep corrected_text as a "
        "clean readable version of the supplied text; preserve actual facts. "
        "If the speaker corrects their own surname or number, use the final explicit "
        "correction and add action correct_name or add_phone only as appropriate. "
        "Use the actions array for commands: add_phone, correct_phone, remove_phone, "
        "set_company, set_role, set_category, "
        "create_reminder, update_reminder, delete_reminder, append_note, correct_name. "
        "Never invent an ID; target_reminder_id must come from known_reminders "
        "provided in context. If the user only says 'ساعت سه', interpret a likely "
        "working afternoon as 15:00 only when an explicit date is given; otherwise "
        "ask which date to use and requires_confirmation=true. "
        "Never write data, send a message, or call anyone. Return proposal only."
    )
    try:
        target_zone = ZoneInfo(request.timezone)
    except (KeyError, ValueError):
        raise HTTPException(422, "Invalid timezone")
    payload = json.dumps({
        "now_local": datetime.now(target_zone).isoformat(),
        "timezone": request.timezone,
        "transcript": transcript,
        "known_contacts": trim_context(json.dumps(request.contacts, ensure_ascii=False)),
        "known_reminders": request.reminders[:15],
    }, ensure_ascii=False)
    if provider == "gemini":
        proposals = await extract_gemini_proposal(
            api_key=os.getenv("GEMINI_API_KEY", ""),
            model=os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL),
            instructions=instructions,
            payload=payload,
            schema=PROPOSAL_SCHEMA,
        )
    else:
        # Legacy paid provider is NEVER selected unless explicitly configured.
        api_key = os.getenv("OPENAI_API_KEY", "")
        if not api_key:
            raise HTTPException(503, "OpenAI text model key is not configured")
        async with httpx.AsyncClient(timeout=75.0) as client:
            try:
                response = await client.post(
                    f"{OPENAI_ENDPOINT}/responses",
                    headers={"Authorization": f"Bearer {api_key}",
                             "Content-Type": "application/json"},
                    json={
                        "model": os.getenv("POYARA_EXTRACTION_MODEL", "gpt-6-luna"),
                        "instructions": instructions,
                        "input": payload,
                        "store": False,
                        "text": {"format": {
                            "type": "json_schema", "name": "crm_text_proposal",
                            "strict": True, "schema": PROPOSAL_SCHEMA
                        }}
                    }
                )
                response.raise_for_status()
                result = response.json()
                output = "".join(
                    part.get("text", "")
                    for item in result.get("output", [])
                    if item.get("type") == "message"
                    for part in item.get("content", [])
                    if part.get("type") == "output_text"
                )
                proposals = json.loads(output)
            except (httpx.HTTPError, ValueError, KeyError) as exc:
                raise HTTPException(502, "Text agent extraction unavailable") from exc
    for contact in proposals["contacts"][:10]:
        if not isinstance(contact, dict):
            raise HTTPException(502, "Invalid contact data from text provider")
        if contact.get("phone"):
            contact["phone"] = normalize_numbers(str(contact["phone"])).strip()
    for reminder in proposals["reminders"][:10]:
        if not isinstance(reminder, dict):
            raise HTTPException(502, "Invalid reminder data from text provider")
        try:
            reminder["remind_days_before"] = max(
                0, min(7, int(reminder.get("remind_days_before", 0)))
            )
        except (TypeError, ValueError):
            reminder["remind_days_before"] = 0
        date = reminder.get("date_iso")
        time = reminder.get("time_local")
        if not (isinstance(date, str) and isinstance(time, str)
                and re.fullmatch(r"\d{4}-\d{2}-\d{2}", date)
                and re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", time)):
            reminder["date_iso"] = None
            reminder["time_local"] = None
            reminder["requires_confirmation"] = True
    return {"status": "needs_review", "corrected_text": proposals.get("corrected_text", ""),
            "actions": proposals.get("actions", [])[:15], "contacts": proposals["contacts"][:10],
            "reminders": proposals["reminders"][:10],
            "questions": proposals["questions"][:10]}


@app.post("/v1/audio/analyze")
async def analyze_audio_gemini(
    audio: UploadFile = File(...),
    context_json: str = Form("{}"),
    authorization: str | None = Header(None),
):
    """One-tap multimodal voice-to-CRM analysis with the original M4A file.

    No automatic mutation: caller validates every structured operation.
    """
    require_pilot_auth(authorization)
    if os.getenv("POYARA_TEXT_PROVIDER", "gemini") != "gemini":
        raise HTTPException(503, "Audio-free-tier path requires Gemini provider")
    try:
        context = json.loads(context_json)
        if not isinstance(context, dict):
            raise ValueError()
        request = TextAgentRequest(
            transcript=str(context.get("transcript", "")).strip() or " ",
            timezone=str(context.get("timezone", "Asia/Tehran")),
            contacts=context.get("contacts", [])[:1],
            reminders=context.get("reminders", [])[:15],
        )
        target_zone = ZoneInfo(request.timezone)
    except (ValueError, TypeError, KeyError):
        raise HTTPException(422, "Invalid audio processing context")
    if audio.content_type not in ("audio/mp4", "audio/m4a", "audio/x-m4a",
                                  "application/octet-stream"):
        raise HTTPException(415, "Expected an M4A voice recording")
    blob = await audio.read(10 * 1024 * 1024 + 1)
    await audio.close()
    if not 256 <= len(blob) <= 10 * 1024 * 1024:
        raise HTTPException(413, "Voice recording exceeds free-tier file limit")
    instructions = (
        "Listen carefully to the original Persian audio. Ignore background speakers "
        "unless the user is explicitly reporting their words; distinguish the main "
        "speaker even in noisy environments when possible. Preserve phone digits "
        "in spoken order, read surnames and companies carefully. Pay special "
        "attention to later self-corrections: a corrected final name replaces "
        "the earlier one. Do not invent missing digits. Extract fields and work "
        "actions from clear commands, with the JSON output schema provided. "
        "corrected_text must contain a readable Persian transcription corrected "
        "for spelling and grammar but not altered facts. For dates without "
        "sufficient day or morning/afternoon, ask a question and mark as needing "
        "confirmation; never create a guessed calendar event. "
        "Known reminders may be referenced by their exact ID for update or deletion. "
        "Only respond with structured JSON, no explanation."
    )
    context_payload = json.dumps({
        "now_local": datetime.now(target_zone).isoformat(),
        "timezone": request.timezone,
        "transcript_hint": request.transcript,
        "known_contacts": trim_context(json.dumps(request.contacts, ensure_ascii=False)),
        "known_reminders": request.reminders,
    }, ensure_ascii=False)
    proposal = await extract_gemini_audio_proposal(
        api_key=os.getenv("GEMINI_API_KEY", ""),
        model=os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL),
        instructions=instructions, payload=context_payload,
        schema=PROPOSAL_SCHEMA, audio=blob,
    )
    return {
        "status": "needs_review",
        "corrected_text": proposal.get("corrected_text", ""),
        "contacts": proposal.get("contacts", [])[:10],
        "reminders": proposal.get("reminders", [])[:10],
        "actions": proposal.get("actions", [])[:15],
        "questions": proposal.get("questions", [])[:10],
    }


@app.get("/health")
def health():
    provider = os.getenv("POYARA_TEXT_PROVIDER", "gemini").strip().lower()
    return {
        "status": "ok", "service": "poyara-voice", "version": 2,
        "text_provider": provider,
        "text_model": os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL)
            if provider == "gemini" else os.getenv("POYARA_EXTRACTION_MODEL", "gpt-6-luna"),
    }

@app.post("/v1/voice/analyze")
async def analyze(
    audio: UploadFile = File(...),
    context_json: str = Form("[]"),
    authorization: str | None = Header(None),
    x_voice_job_id: str | None = Header(None),
):
    require_pilot_auth(authorization)
    api_key = os.getenv("OPENAI_API_KEY", "")
    if not api_key:
        raise HTTPException(503, "Transcription provider is not configured")
    if audio.content_type not in (
        "audio/mp4", "audio/m4a", "audio/x-m4a", "application/octet-stream",
    ):
        raise HTTPException(415, "Expected a recorded m4a audio file")
    blob = await audio.read(MAX_AUDIO_BYTES + 1)
    await audio.close()
    if len(blob) > MAX_AUDIO_BYTES:
        raise HTTPException(413, "Recording exceeds the 25MB limit")
    if len(blob) < 64:
        raise HTTPException(400, "Audio file is empty")
    try:
        job_id = str(uuid.UUID(x_voice_job_id)) if x_voice_job_id else str(uuid.uuid4())
    except ValueError:
        raise HTTPException(400, "Invalid job ID")
    candidate_context = trim_context(context_json)
    now = datetime.now(ZoneInfo(TIME_ZONE)).isoformat()
    headers = {"Authorization": f"Bearer {api_key}"}

    async with httpx.AsyncClient(timeout=120.0) as client:
        try:
            transcription = await client.post(
                f"{OPENAI_ENDPOINT}/audio/transcriptions",
                headers=headers,
                data={
                    "model": "gpt-transcribe",
                    "prompt": "مخاطبان فروشگاهی، مبلمان، کابینت، معماری، نام شخص، "
                              "نام مجموعه، شماره تلفن و قرار ملاقات به فارسی."
                },
                files={"file": ("recording.m4a", blob, "audio/mp4")},
            )
            transcription.raise_for_status()
            transcript = transcription.json().get("text", "").strip()
        except (httpx.HTTPError, ValueError) as exc:
            raise HTTPException(502, "Voice transcription unavailable") from exc
        if not transcript:
            raise HTTPException(422, "No speech was detected")
        instructions = (
            "You extract Persian business CRM facts from a spoken note, not commands "
            "to execute. Extract ALL distinct people and business names, including "
            "employees of a previously named manager/company. Keep role and address. "
            "Never invent phone numbers, dates, addresses or person names. "
            "Use null for missing fields, do not merge two people. "
            "For explicit unambiguous dates give Gregorian YYYY-MM-DD and "
            "24-hour HH:mm in Asia/Tehran. For ambiguous weekday references "
            "(e.g. 'سه‌شنبه' without a clear week), set date_iso=null and "
            "requires_confirmation=true, add a Persian question. "
            "Do not infer timezone from user's network location. "
            "If user requests messages or other unsupported actions, add a "
            "Persian question; do not claim to send anything. "
            "remind_days_before must be 0..7. "
            "Return only facts grounded in transcript."
        )
        prompt = json.dumps({
            "now_iran": now, "timezone": TIME_ZONE, "transcript": transcript,
            "nearby_existing_contacts": candidate_context,
        }, ensure_ascii=False)
        try:
            extraction = await client.post(
                f"{OPENAI_ENDPOINT}/responses",
                headers={**headers, "Content-Type": "application/json"},
                json={
                    "model": os.getenv("POYARA_EXTRACTION_MODEL", "gpt-6-luna"),
                    "instructions": instructions,
                    "input": prompt,
                    "store": False,
                    "text": {"format": {
                        "type": "json_schema", "name": "crm_voice_proposal",
                        "strict": True, "schema": PROPOSAL_SCHEMA,
                    }},
                },
            )
            extraction.raise_for_status()
            result = extraction.json()
            output = "".join(
                part.get("text", "")
                for item in result.get("output", [])
                if item.get("type") == "message"
                for part in item.get("content", [])
                if part.get("type") == "output_text"
            )
            proposals = json.loads(output)
        except (httpx.HTTPError, ValueError, KeyError) as exc:
            raise HTTPException(502, "CRM information extraction unavailable") from exc

    for contact in proposals["contacts"]:
        if contact["phone"]:
            contact["phone"] = normalize_numbers(contact["phone"]).strip()
    for reminder in proposals["reminders"]:
        reminder["remind_days_before"] = max(0, min(7, reminder["remind_days_before"]))
        date = reminder.get("date_iso")
        if date and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
            reminder["date_iso"] = None
            reminder["requires_confirmation"] = True
    return JSONResponse({
        "job_id": job_id,
        "status": "needs_review",
        "transcript": transcript,
        "contacts": proposals["contacts"][:30],
        "reminders": proposals["reminders"][:30],
        "questions": proposals["questions"][:20],
    })
