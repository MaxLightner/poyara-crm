# Poyara Voice – self-hosted no-email mode

This is the **no-paid-AI-API** option. The phone only records M4A and uploads after network reconnects.
A machine under your control runs faster-whisper for Persian speech-to-text and Ollama/Qwen for JSON extraction.
**Free models do not imply free hosting**; CPU/RAM, power and internet connectivity remain necessary.

## 1. Prepare server (not Supabase Edge Functions)
The previous Supabase Edge Function uses paid OpenAI endpoints; do not use it for this setup.
The new service needs a persistent machine (PC/mini PC/VPS) able to run an Ollama model, and an HTTPS reverse proxy.
On a local PC or server:

```bash
# Install Ollama: https://ollama.com/download
ollama pull qwen2.5:3b
cd server
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements-offline.txt
export POYARA_VOICE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(40))')"
export POYARA_WHISPER_MODEL=small
export POYARA_OLLAMA_MODEL=qwen2.5:3b
uvicorn offline_voice_api:app --host 127.0.0.1 --port 8000
```

Pin and keep the generated pairing token safely. Do **not** add it to GitHub/APK. Publish with HTTPS via a TLS reverse proxy with upload limits and rate limiting.
A VPN private network such as Tailscale can be used instead of exposing a public endpoint, but the Android client currently requires an HTTPS URL.
Do not run as an unauthenticated public service.

## 2. Android
Open a recorded voice's **دستیار صوتی پویارا** panel and tap **اتصال به سرور**.
Enter the server's HTTPS root URL (without /v1/jobs) and the private pairing token once.
No email, account creation or password screen is shown.
The pairing token is encrypted with Android Keystore.

The app enqueues audio in private local storage immediately. WorkManager waits for network, uploads in chunks,
shows actual bytes-uploaded percentage, then shows server stages:
- در صف پردازش (queued)
- تبدیل گفتار به متن (transcribing)
- استخراج اطلاعات (extracting)
- آماده بازبینی (needs_review)

**There is no fake numeric inference progress.** Models do not give a reliable percentage for the full process.
When the result arrives, review extracted contacts, company membership, reminders and questions before applying.
Temporary server audio is removed after processing; the original on the phone remains.
Failed jobs preserve the original and can be retried.

## 3. Protocol
- POST `/v1/jobs` multipart: `audio` M4A, `context_json` JSON list and `X-Voice-Job-Id` UUID header. Header: `Authorization: Bearer <pairing token>`.
- Returns HTTP 202 with `{"job_id":"...","status":"queued"}`. Repeated UUIDs are idempotent.
- GET `/v1/jobs/{job_id}` with same Authorization; status/stage or final JSON proposal.
- Final JSON: `job_id,status=needs_review,transcript,contacts,reminders,questions,observations`.
The phone, not the LLM, applies CRM writes.

## Constraints
- This is a single-operator pilot, with one pairing token. Future shared-team operation requires per-device credentials, revocation and a secured team database.
- Persistent SQLite queue is included. Jobs interrupted during inference can be resumed after service restart, but a restart while processing may delay completion.
- One worker processes one job at a time; actual speed depends on server CPU/GPU and voice length. Do not promise a number of seconds until benchmarked.
- A first deployment has **not** been made to a self-hosted inference machine; no server URL or pairing token has been provided. GitHub code alone does not activate remote AI.
- The Supabase project's older OpenAI-dependent function is NOT used by this branch. Do not remove old data while migrating.
