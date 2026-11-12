"""Run a one-off CPU-only Persian voice + CRM JSON proof of concept.
This does NOT host a public API and processes no personal customer data.
"""
from __future__ import annotations
import argparse
import json
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
from faster_whisper import WhisperModel

def proposal_schema():
    names = ["name", "phone", "company", "role", "address", "note", "related_to"]
    return {"type":"object","properties":{
      "contacts":{"type":"array","items":{"type":"object","properties":{
        name:{"type":["string","null"]} for name in names},
        "required":names,"additionalProperties":False}},
      "reminders":{"type":"array","items":{"type":"object","properties":{
        "person_name":{"type":["string","null"]},"company":{"type":["string","null"]},
        "title":{"type":"string"},"date_iso":{"type":["string","null"]},
        "time_local":{"type":["string","null"]},
        "remind_days_before":{"type":"integer"},"requires_confirmation":{"type":"boolean"}},
        "required":["person_name","company","title","date_iso","time_local",
                    "remind_days_before","requires_confirmation"],
        "additionalProperties":False}},
      "questions":{"type":"array","items":{"type":"string"}},
      "observations":{"type":"array","items":{"type":"string"}}},
      "required":["contacts","reminders","questions","observations"],
      "additionalProperties":False}

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--audio",required=True)
    p.add_argument("--out",default="demo_result.json")
    args = p.parse_args()
    clock = time.monotonic()
    model = WhisperModel("small", device="cpu", compute_type="int8")
    segments, _ = model.transcribe(args.audio,language="fa",beam_size=5,vad_filter=True)
    heard = " ".join(chunk.text.strip() for chunk in segments).strip()
    stt_time = round(time.monotonic()-clock,2)

    # The next sample is an explicit *text* CRM note, not a claim that the
    # downloaded voice is a customer conversation. We test both model stages separately.
    crm_note = ("امروز رفتم مبلمان آریا توی مشهد. مدیرش آقای رضایی بود و "
        "خانم محمدی هم کارمند فروششونه. شماره آقای رضایی ۰۹۱۵۱۲۳۴۵۶۷ هست. "
        "سه شنبه ساعت سه و نیم با رضایی جلسه بذاریم، "
        "دو روز قبلش بهم یادآوری کن. شماره خانم محمدی رو ندارم.")
    instructions = (
        "Extract Persian spoken CRM information. Reply with ONLY a JSON object "
        "matching the schema; do not fabricate missing facts. "
        "Multiple individuals at the same company are separate contacts. "
        "Unknown date, person details or phone are null; a weekday without "
        "a precise date has date_iso=null and requires_confirmation=true. "
        "All dates are Tehran local. No actions are executed.")
    payload = {
      "model":"qwen2.5:1.5b","stream":False,"format":proposal_schema(),
      "messages":[{"role":"system","content":instructions},
                  {"role":"user","content":json.dumps({
                    "now_iran":datetime.now(ZoneInfo("Asia/Tehran")).isoformat(),
                    "transcript":crm_note},ensure_ascii=False)}],
      "options":{"temperature":0.1,"num_predict":1700}
    }
    started = time.monotonic()
    with httpx.Client(timeout=600) as client:
        reply = client.post("http://127.0.0.1:11434/api/chat",json=payload)
        reply.raise_for_status()
        proposal = json.loads(reply.json()["message"]["content"])
    llm_time = round(time.monotonic()-started,2)
    for field in ["contacts","reminders","questions","observations"]:
        if not isinstance(proposal.get(field), list):
            raise ValueError("Invalid field in generated proposal: "+field)
    doc={
      "mode":"one-off GitHub Actions CPU test – not a running server",
      "speech_sample":{"source":"public Persian synthetic TTS sample (Fouladi-K pocket-tts-Persian-webui)",
                       "expected":"این یک نمونه است از تبدیل متن فارسی به گفتار روی پردازنده مرکزی، بدون نیاز به کارت گرافیک.",
                       "whisper_transcript":heard,"seconds":stt_time},
      "crm_extraction":{"source":"separate hand-written natural-language CRM test (NOT from sample audio)",
                        "input_text":crm_note,
                        "result":proposal,"seconds":llm_time},
      "total_seconds_excluding_model_download":round(time.monotonic()-clock,2)
    }
    Path(args.out).write_text(json.dumps(doc,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(doc,ensure_ascii=False,indent=2))
    with open(__import__("os").environ.get("GITHUB_STEP_SUMMARY","/dev/null"),"a",encoding="utf-8") as out:
        out.write("## Persian voice model trial (CPU / no VPS)\n")
        out.write(f"- Whisper transcript: {heard}\n")
        out.write(f"- Whisper processing: {stt_time}s\n")
        out.write(f"- Qwen JSON extraction: {llm_time}s\n")
        out.write(f"- Contacts extracted: {len(proposal['contacts'])}; reminders: {len(proposal['reminders'])}\n")
        out.write("- NOTE: This tests two model stages separately; it is not an end-to-end recording from the mobile app.\n")

if __name__=="__main__":
    main()
