# POYARA Voice MVP server

This is an **opt-in, single-user pilot**, not the future team backend. It transcribes audio and produces JSON proposals. It never modifies CRM records or sends messages.

## Start (behind HTTPS)

- Deploy the container from this folder on a private HTTPS service.
- Configure `OPENAI_API_KEY` and a randomly generated 32+ character `POYARA_VOICE_TOKEN` as **server environment variables**.
- Configure the same pilot token and HTTPS server URL inside the app's Voice Settings screen.
- Use a reverse proxy for HTTPS; never use a cleartext HTTP URL with real contact recordings.
- Check `GET /health`.
- The Android app sends `POST /v1/voice/analyze` with `audio` as multipart m4a, `context_json` (up to 25 candidate records), and `Authorization: Bearer <token>`.
- Maximum audio file: 25 MB. Each request can invoke both speech and extraction models, so usage costs apply.

Example local test:

    cd server
    python3 -m venv .venv
    . .venv/bin/activate
    pip install -r requirements.txt
    export OPENAI_API_KEY=...
    export POYARA_VOICE_TOKEN=$(python -c 'import secrets; print(secrets.token_urlsafe(32))')
    uvicorn voice_api:app --host 127.0.0.1 --port 8000

**Do not embed this pilot token into a released APK.** This pilot does not support account-level authorization, shared workspaces, audit logs, persistent cloud job queues or per-user quotas. Before multi-user production, create a dedicated POYARA backend, use Supabase Auth/JWT with membership-scoped RLS, private object storage, retryable job queue, and usage limits. The existing connected Supabase projects belong to other products and were not modified.

Model API docs: https://developers.openai.com/api/docs/guides/speech-to-text
