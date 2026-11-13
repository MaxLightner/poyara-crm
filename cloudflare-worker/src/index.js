// Poyara CRM centralized worker. Admin Cloudflare token stays in GitHub Actions,
// never in Android or this public repository. An app-specific access code is
// separate from the Cloudflare deployment token and must be provisioned securely.
const MODEL = "@cf/zai-org/glm-4.7-flash";
const ACTIONS = new Set([
  "correct_name", "correct_phone", "remove_phone", "add_phone",
  "set_company", "set_role", "set_category", "create_reminder",
  "update_reminder", "delete_reminder", "append_note",
]);

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}

async function authenticated(request, secret) {
  if (!secret || secret.length < 32) return false;
  const input = request.headers.get("authorization") || "";
  const got = input.startsWith("Bearer ") ? input.slice(7) : "";
  const enc = new TextEncoder();
  const expected = await crypto.subtle.digest("SHA-256", enc.encode(secret));
  const provided = await crypto.subtle.digest("SHA-256", enc.encode(got));
  const a = new Uint8Array(expected), b = new Uint8Array(provided);
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  return difference === 0;
}

function safeInput(body) {
  if (!body || typeof body !== "object") throw Error("Invalid request");
  if (typeof body.transcript !== "string" || body.transcript.trim().length < 1 ||
      body.transcript.length > 10000) throw Error("Transcript must be 1–10000 characters");
  if (typeof body.timezone !== "string" || body.timezone.length > 64 ||
      !/^[A-Za-z0-9_+\-/]+$/.test(body.timezone)) throw Error("Invalid timezone");
  const c = body.contact && typeof body.contact === "object" ? body.contact : {};
  const contact = Object.fromEntries(
    ["name", "company", "role", "category"].map(field =>
      [field, typeof c[field] === "string" ? c[field].slice(0, 120) : ""])
  );
  const reminders = Array.isArray(body.reminders) ? body.reminders.slice(0, 15) : [];
  return {
    transcript: body.transcript,
    now_local: typeof body.now_local === "string" ? body.now_local.slice(0, 50) : "",
    timezone: body.timezone,
    contact, reminders: reminders.map(r => ({
      id: Number.isSafeInteger(r?.id) ? r.id : null,
      title: typeof r?.title === "string" ? r.title.slice(0, 150) : "",
      due_local: typeof r?.due_local === "string" ? r.due_local.slice(0, 60) : ""
    }))
  };
}

const INSTRUCTIONS = `You extract commands from Persian voice transcripts for POYARA CRM.
Return ONLY a valid JSON object with EXACT keys corrected_text, contacts, reminders, actions, questions.
contacts is an array of objects with keys: name, phone, company, role, category, address, note, related_to.
reminders is an array of objects with keys: person_name, company, title, date_iso, time_local, remind_days_before, requires_confirmation.
actions is an array of objects with keys: type, value, target_reminder_id, title, date_iso, time_local, remind_days_before, requires_confirmation.
questions is an array of short Persian questions.
Keep arrays empty when nothing qualifies. Never invent phone numbers, date or personal details.
If multiple numbers were explicitly spoken for the same person, return first as main phone and add_phone actions for the others.
If speaker corrects a surname or number, prefer the last clear correction. If names conflict or the target is unclear, ask.
Use only these actions: correct_name, correct_phone, remove_phone, add_phone, set_company, set_role, set_category, create_reminder, update_reminder, delete_reminder, append_note.
Only use supplied target_reminder_id values; no invented IDs. Deleting or changing uncertain events requires_confirmation true.
For appointments use Gregorian YYYY-MM-DD and HH:mm in caller's timezone. Without explicit future date or morning/evening context ask clarification.
You are only a planner: do not call anyone, delete files or directly update the CRM. The client validates proposals.`;

function extractRaw(result) {
  if (typeof result === "string") return result;
  if (typeof result?.response === "string") return result.response;
  if (typeof result?.choices?.[0]?.message?.content === "string") return result.choices[0].message.content;
  throw Error("AI output missing");
}

function parseProposal(result) {
  const raw = extractRaw(result).trim().replace(/^\x60\x60\x60(?:json)?\s*/i, "").replace(/\s*\x60\x60\x60$/, "");
  const proposal = JSON.parse(raw);
  if (!proposal || typeof proposal !== "object" ||
      !Array.isArray(proposal.contacts) ||
      !Array.isArray(proposal.reminders) ||
      !Array.isArray(proposal.actions) ||
      !Array.isArray(proposal.questions)) throw Error("Invalid AI response");
  const actions = proposal.actions.slice(0, 16).filter(
    action => action && typeof action.type === "string" && ACTIONS.has(action.type)
  ).map(action => ({
    type: action.type,
    value: typeof action.value === "string" ? action.value.slice(0, 3000) : null,
    target_reminder_id: Number.isSafeInteger(action.target_reminder_id) ? action.target_reminder_id : null,
    title: typeof action.title === "string" ? action.title.slice(0, 150) : null,
    date_iso: typeof action.date_iso === "string" ? action.date_iso : null,
    time_local: typeof action.time_local === "string" ? action.time_local : null,
    remind_days_before: Math.min(7, Math.max(0, Number(action.remind_days_before) || 0)),
    requires_confirmation: Boolean(action.requires_confirmation)
  }));
  return {
    status: "needs_review",
    corrected_text: typeof proposal.corrected_text === "string" ? proposal.corrected_text.slice(0, 10000) : "",
    contacts: proposal.contacts.slice(0, 10),
    reminders: proposal.reminders.slice(0, 10),
    actions,
    questions: proposal.questions.slice(0, 8).filter(x => typeof x === "string").map(x => x.slice(0, 250))
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ status: "ok", service: "poyara-central-agent", provider: "cloudflare", model: MODEL });
    }
    const isText = url.pathname === "/v1/text/analyze";
    const isAudio = url.pathname === "/v1/audio/transcribe";
    const isAuthCheck = url.pathname === "/v1/auth/check";
    if (!isText && !isAudio && !isAuthCheck) return json({error:"Not found"},404);
    if (request.method !== "POST") return json({error:"Method not allowed"},405);
    if (!await authenticated(request, env.POYARA_APP_ACCESS_KEY)) return json({error:"Unauthorized"},401);
    if (isAuthCheck) return json({status:"ok", service:"poyara-central-agent"});
    // Apply approximate Cloudflare rate limits before invoking any AI model.
    // The global limit helps protect one team's quota from many source IPs.
    if (env.GLOBAL_LIMITER) {
      const { success } = await env.GLOBAL_LIMITER.limit({ key: "poyara-all" });
      if (!success) return json({error:"Team AI quota is temporarily rate-limited"},429);
    }
    if (env.REQUEST_LIMITER) {
      const ip = request.headers.get("cf-connecting-ip") || "unknown";
      const { success } = await env.REQUEST_LIMITER.limit({ key: ip });
      if (!success) return json({error:"Too many requests"},429);
    }
    if (isAudio) {
      if (!env.AI) return json({error:"AI binding unavailable"},503);
      const type = request.headers.get("content-type") || "";
      if (!/^audio\/(?:mp4|m4a|x-m4a|aac|mpeg|wav)(?:;|$)/i.test(type))
        return json({error:"Unsupported audio format"},415);
      const size = Number(request.headers.get("content-length") || 0);
      if (size > 4 * 1024 * 1024) return json({error:"Audio exceeds 4 MiB"},413);
      const bytes = new Uint8Array(await request.arrayBuffer());
      if (bytes.length < 256 || bytes.length > 4 * 1024 * 1024)
        return json({error:"Invalid audio length"},413);
      try {
        const result = await env.AI.run("@cf/openai/whisper", {
          audio: [...bytes], source_lang: "fa"
        });
        const transcript = result?.text?.trim() || "";
        if (!transcript) return json({error:"No speech recognized"},422);
        return json({text:transcript.slice(0,10000)});
      } catch {
        return json({error:"Audio transcription unavailable. Try live dictation"},503);
      }
    }
    const length = Number(request.headers.get("content-length") || 0);
    if (length > 32000) return json({error:"Request too large"},413);
    const raw = await request.text();
    if (raw.length > 32000) return json({error:"Request too large"},413);
    let input;
    try { input = safeInput(JSON.parse(raw)); }
    catch { return json({error:"Invalid input"},422); }
    if (!env.AI) return json({error:"AI binding unavailable"},503);
    try {
      const answer = await env.AI.run(MODEL,{
        messages: [
          { role:"system", content:INSTRUCTIONS },
          { role:"user", content:JSON.stringify(input) }
        ],
        max_completion_tokens:2200,
        temperature:0,
      });
      return json(parseProposal(answer));
    } catch (error) {
      // Do not reveal model details or sensitive transcript in public error.
      return json({error:"AI temporarily unavailable; local draft remains on the device"},503);
    }
  }
};
