"""Self-hosted, no-email POYARA voice service.
No paid API calls. Whisper and Qwen run on the operator's own server.
Run behind HTTPS; configure a private bearer pairing token, not in the APK.
"""
from __future__ import annotations
import hmac
import json
import os
import re
import sqlite3
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile

DATA_DIR = Path(os.getenv("POYARA_DATA_DIR", "./voice_data")).resolve()
DATA_DIR.mkdir(parents=True, exist_ok=True)
DB_FILE = DATA_DIR / "jobs.sqlite3"
MAX_SIZE = 25 * 1024 * 1024
TOKEN = os.getenv("POYARA_VOICE_TOKEN", "")
WHISPER_MODEL = os.getenv("POYARA_WHISPER_MODEL", "small")
WHISPER_DEVICE = os.getenv("POYARA_WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE = os.getenv("POYARA_WHISPER_COMPUTE", "int8")
OLLAMA_URL = os.getenv("POYARA_OLLAMA_URL", "http://127.0.0.1:11434")
OLLAMA_MODEL = os.getenv("POYARA_OLLAMA_MODEL", "qwen2.5:7b")
pool = ThreadPoolExecutor(max_workers=1)
lock = threading.RLock()
speech_model = None
app = FastAPI(title="Poyara Local Voice Processor", docs_url=None, redoc_url=None)

def db():
    connection = sqlite3.connect(DB_FILE, timeout=30)
    connection.row_factory = sqlite3.Row
    return connection

def init():
    with db() as connection:
        connection.execute("""CREATE TABLE IF NOT EXISTS jobs(
          job_id TEXT PRIMARY KEY, status TEXT NOT NULL, stage TEXT NOT NULL,
          result_json TEXT, error TEXT, audio_path TEXT NOT NULL,
          context_json TEXT NOT NULL, created_at TEXT NOT NULL)""")
        connection.execute("UPDATE jobs SET status='queued', stage='queued' WHERE status='processing'")
        connection.commit()
    with db() as connection:
        jobs = connection.execute("SELECT job_id FROM jobs WHERE status='queued'").fetchall()
    for row in jobs:
        pool.submit(process_job, row["job_id"])

def authorize(value: str | None):
    if len(TOKEN) < 32:
        raise HTTPException(503, "Server pairing token is missing")
    if not value or not value.startswith("Bearer ") or not hmac.compare_digest(TOKEN, value[7:]):
        raise HTTPException(401, "Invalid pairing token")

def set_job(job_id: str, status: str, stage: str, result=None, error=""):
    with lock, db() as connection:
        connection.execute("""UPDATE jobs SET status=?, stage=?, result_json=?, error=?
        WHERE job_id=?""", (status, stage,
            json.dumps(result, ensure_ascii=False) if result is not None else None,
            error[:240], job_id))
        connection.commit()

def proposal_schema():
    fields = ["name", "phone", "company", "role", "address", "note", "related_to"]
    return {
        "type": "object", "properties": {
            "contacts": {"type":"array","items":{"type":"object","properties":{
                key:{"type":["string","null"]} for key in fields},
                "required":fields,"additionalProperties":False}},
            "reminders":{"type":"array","items":{"type":"object","properties":{
                "person_name":{"type":["string","null"]},
                "company":{"type":["string","null"]},
                "title":{"type":"string"},
                "date_iso":{"type":["string","null"]},
                "time_local":{"type":["string","null"]},
                "remind_days_before":{"type":"integer"},
                "requires_confirmation":{"type":"boolean"}},
                "required":["person_name","company","title","date_iso","time_local",
                  "remind_days_before","requires_confirmation"],"additionalProperties":False}},
            "questions":{"type":"array","items":{"type":"string"}},
            "observations":{"type":"array","items":{"type":"string"}}},
        "required":["contacts","reminders","questions","observations"],
        "additionalProperties":False
    }

def normalize_numbers(value):
    return value.translate(str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "0123456789"*2))

def process_job(job_id):
    global speech_model
    with db() as connection:
        job = connection.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
    if job is None: return
    try:
        set_job(job_id, "processing", "transcribing")
        if speech_model is None:
            from faster_whisper import WhisperModel
            speech_model = WhisperModel(WHISPER_MODEL, device=WHISPER_DEVICE,
                                        compute_type=WHISPER_COMPUTE)
        segments, _ = speech_model.transcribe(
            job["audio_path"], language="fa", beam_size=5, vad_filter=True)
        transcript = " ".join(seg.text.strip() for seg in segments).strip()
        if not transcript:
            raise ValueError("No speech was detected")
        set_job(job_id, "processing", "extracting")
        now = datetime.now(ZoneInfo("Asia/Tehran")).isoformat()
        context = json.loads(job["context_json"])
        prompt = ("You are a meticulous data EXTRACTOR, not a creative writer. "
          "Extract facts from NATURAL, UNSTRUCTURED spoken Persian. "
          "Use only information stated in the transcript; never guess or fabricate. "
          "A business/brand name is the company, a city is an address, not a company. "
          "Each human is a separate contact. Never invent first names. "
          "Only assign a phone number to the person whose number was given. "
          "If no number was stated for someone, set phone=null. "
          "Unknown fields MUST be null. Do not create duplicate people or reminders. "
          "When the speaker says ساعت سه و نیم use time_local=15:30. "
          "Weekday without a precise date must have date_iso=null and requires_confirmation=true. "
          "Do not invent dates in the past. "
          "Remind days 0..7. Put notes not tied to a contact in observations. "
          "Never execute calls or messages; output facts for user review. "
          "Return JSON ONLY matching the supplied schema. Times use Asia/Tehran.\n"
          +json.dumps({"now_iran":now,"transcript":transcript,
                "existing_contacts":context},ensure_ascii=False))
        with httpx.Client(timeout=180) as client:
            result = client.post(OLLAMA_URL.rstrip("/") + "/api/chat", json={
                "model": OLLAMA_MODEL, "stream":False, "format":proposal_schema(),
                "messages":[{"role":"user","content":prompt}],
                "options":{"temperature":0.1, "num_predict":1800}
            })
            result.raise_for_status()
            proposal = json.loads(result.json()["message"]["content"])
        if not isinstance(proposal,dict): raise ValueError("Invalid JSON")
        for field in ("contacts","reminders","questions","observations"):
            if not isinstance(proposal.get(field), list): raise ValueError("Missing " + field)
        for person in proposal["contacts"]:
            if person.get("phone"):
                person["phone"] = normalize_numbers(person["phone"]).strip()
        for reminder in proposal["reminders"]:
            reminder["remind_days_before"] = max(0,min(7,int(reminder.get("remind_days_before",1))))
            date=reminder.get("date_iso")
            if not date or not re.fullmatch(r"\d{4}-\d{2}-\d{2}",date):
                reminder["date_iso"]=None
                reminder["requires_confirmation"]=True
        set_job(job_id,"needs_review","complete",{
            "job_id":job_id,"status":"needs_review", "transcript":transcript,
            "contacts":proposal["contacts"][:30],"reminders":proposal["reminders"][:30],
            "questions":proposal["questions"][:20],
            "observations":proposal["observations"][:30]})
    except Exception as exc:
        set_job(job_id,"failed","failed",error=str(exc))
    finally:
        # Original file remains on the phone. Remove server's temporary copy.
        try: Path(job["audio_path"]).unlink(missing_ok=True)
        except OSError: pass

init()

@app.get("/health")
def health():
    return {"status":"ok","provider":"self_hosted","model":OLLAMA_MODEL}

@app.post("/v1/jobs", status_code=202)
async def submit(audio: UploadFile=File(...), context_json: str=Form("[]"),
                 authorization: str|None=Header(None),
                 x_voice_job_id: str|None=Header(None)):
    authorize(authorization)
    try:
        job_id=str(uuid.UUID(x_voice_job_id or ""))
    except ValueError:
        raise HTTPException(400,"Invalid voice job ID")
    with db() as connection:
        existing=connection.execute("SELECT status FROM jobs WHERE job_id=?",(job_id,)).fetchone()
    if existing: return {"job_id":job_id,"status":existing["status"]}
    try: context=json.loads(context_json)
    except ValueError: context=[]
    if not isinstance(context,list): context=[]
    context=[{k:str(item.get(k,""))[:120] for k in ("name","company","phone")}
             for item in context[:5] if isinstance(item,dict)]
    target=DATA_DIR / (job_id + ".m4a")
    total=0
    try:
        with target.open("wb") as output:
            while True:
                chunk=await audio.read(128*1024)
                if not chunk: break
                total += len(chunk)
                if total > MAX_SIZE: raise HTTPException(413,"Audio exceeds 25MB")
                output.write(chunk)
        if total < 64: raise HTTPException(400,"Empty audio")
    except Exception:
        target.unlink(missing_ok=True)
        raise
    finally:
        await audio.close()
    with lock, db() as connection:
        connection.execute("""INSERT OR IGNORE INTO jobs
        (job_id,status,stage,result_json,error,audio_path,context_json,created_at)
        VALUES(?,?,?,?,?,?,?,?)""",
        (job_id,"queued","queued",None,"",str(target),
         json.dumps(context,ensure_ascii=False),datetime.utcnow().isoformat()))
        connection.commit()
    pool.submit(process_job,job_id)
    return {"job_id":job_id,"status":"queued"}

@app.get("/v1/jobs/{job_id}")
def status(job_id:str, authorization: str|None=Header(None)):
    authorize(authorization)
    try: job_id=str(uuid.UUID(job_id))
    except ValueError: raise HTTPException(400,"Invalid job ID")
    with db() as connection:
        job=connection.execute("SELECT * FROM jobs WHERE job_id=?",(job_id,)).fetchone()
    if not job: raise HTTPException(404,"Unknown job")
    if job["status"]=="needs_review" and job["result_json"]:
        return json.loads(job["result_json"])
    return {"job_id":job_id,"status":job["status"],"stage":job["stage"],
            "detail":job["error"] if job["status"]=="failed" else ""}
