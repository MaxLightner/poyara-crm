import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

const endpoint = 'https://test.invalid/v1/text/analyze';
const sharedCode = 'a'.repeat(40);
const dummy = {AI:{run: async (model) => model.includes('whisper') ? {text:'آقای حسینی'} : ({response:JSON.stringify({
  corrected_text: 'آقای حسینی دو شماره دارد',
  contacts: [{ name: 'حسینی', phone: '09158151204' }],
  reminders: [],
  actions: [{ type:'add_phone', value:'05134225053', requires_confirmation:false }],
  questions: []
})})}};
const request = (body, code=sharedCode) => new Request(endpoint, {
  method: 'POST',
  headers: { authorization:'Bearer '+code, 'content-type':'application/json' },
  body: JSON.stringify(body)
});
test('public health omits admin secret', async () => {
  const res = await worker.fetch(new Request('https://test.invalid/health'), {...dummy, POYARA_APP_ACCESS_KEY:sharedCode});
  assert.equal(res.status, 200);
  const text = await res.text();
  assert.ok(!text.includes(sharedCode));
});
test('API rejects unauthenticated requests', async () => {
  const res = await worker.fetch(request({transcript:'سلام', timezone:'Asia/Tehran'}, 'wrong'), {...dummy, POYARA_APP_ACCESS_KEY:sharedCode});
  assert.equal(res.status,401);
});
test('API extracts structured CRM proposals', async () => {
  const res = await worker.fetch(request({transcript:'آقای حسینی دو شماره دارد',timezone:'Asia/Tehran'}), {...dummy, POYARA_APP_ACCESS_KEY:sharedCode});
  assert.equal(res.status,200);
  const data = await res.json();
  assert.equal(data.actions[0].type,'add_phone');
});
test('API blocks invalid payloads', async () => {
  const res = await worker.fetch(request({transcript:'',timezone:'Asia/Tehran'}), {...dummy, POYARA_APP_ACCESS_KEY:sharedCode});
  assert.equal(res.status,422);
});
test('No backend key means no access', async () => {
  const res = await worker.fetch(request({transcript:'سلام',timezone:'Asia/Tehran'}), dummy);
  assert.equal(res.status,401);
});

test('authenticated app-check requires no AI inference', async () => {
  const r = await worker.fetch(new Request('https://test.invalid/v1/auth/check', {
    method: 'POST', headers: {authorization:'Bearer '+sharedCode}
  }), {POYARA_APP_ACCESS_KEY:sharedCode});
  assert.equal(r.status, 200);
});

test('central audio transcription accepts M4A bytes and returns text', async () => {
  const audio = new Uint8Array(400);
  const r = await worker.fetch(new Request('https://test.invalid/v1/audio/transcribe', {
    method:'POST',
    headers:{authorization:'Bearer '+sharedCode,'content-type':'audio/mp4'},
    body: audio
  }), {...dummy, POYARA_APP_ACCESS_KEY:sharedCode});
  assert.equal(r.status, 200);
  assert.equal((await r.json()).text,'آقای حسینی');
});

test('server rejects unsupported audio content types', async () => {
  const r = await worker.fetch(new Request('https://test.invalid/v1/audio/transcribe', {
    method:'POST',
    headers:{authorization:'Bearer '+sharedCode,'content-type':'text/plain'},
    body: 'fake-recording'
  }), {...dummy, POYARA_APP_ACCESS_KEY:sharedCode});
  assert.equal(r.status, 415);
});

test('rate limiter rejects requests before AI usage', async () => {
  let called = false;
  const env = {
    POYARA_APP_ACCESS_KEY:sharedCode,
    REQUEST_LIMITER:{limit: async()=>({success:false})},
    AI:{run: async()=>{called = true; throw Error('Should not call AI');}}
  };
  const r = await worker.fetch(request({transcript:'جلسه فردا',timezone:'Asia/Tehran'}),env);
  assert.equal(r.status,429);
  assert.equal(called,false);
});

function agentWith(proposal) {
  return { POYARA_APP_ACCESS_KEY:sharedCode,
    AI:{run:async()=>({response:JSON.stringify({
      corrected_text:"",
      contacts:[],reminders:[],actions:[],questions:[],
      ...proposal
    })})}};
}

test('second phone utterance attaches to current open customer', async () => {
  const env = agentWith({contacts:[{name:"حسینی",phone:"09151112233"}]});
  const response = await worker.fetch(request({
    transcript:"یه شماره دیگه برای همین آقا اضافه کن ۰۹۱۵۱۱۱۲۲۳۳",
    timezone:"Asia/Tehran",now_local:"2026-11-16T13:00:00+03:30",
    contact:{name:"حسینی",phone:"09150000000",extra_phones:[]}
  }),env);
  assert.equal(response.status,200);
  const result=await response.json();
  assert.deepEqual(result.actions.map(a=>a.type),["add_phone"]);
  assert.equal(result.actions[0].value,"09151112233");
});

test('role-only followup becomes set_role on same contact', async () => {
  const response = await worker.fetch(request({
    transcript:"سمت این فرد مدیر هست",
    timezone:"Asia/Tehran",now_local:"2026-11-16T10:00:00+03:30",
    contact:{name:"حسینی",phone:"09150000000",role:""}
  }),agentWith({}));
  assert.equal(response.status,200);
  const result=await response.json();
  assert.equal(result.actions[0].type,"set_role");
  assert.equal(result.actions[0].value,"مدیر");
});

test('tomorrow 11 business meeting generates one follow-up reminder', async () => {
  const response = await worker.fetch(request({
    transcript:"برای فردا با این فرد جلسه درباره خرید مبل دارم، ساعت 11 یادآوری تنظیم کن",
    timezone:"Asia/Tehran",now_local:"2026-11-16T18:40:00+03:30",
    contact:{name:"حسینی",phone:"09150000000",role:""}
  }),agentWith({}));
  assert.equal(response.status,200);
  const result=await response.json();
  assert.equal(result.actions.length,1);
  assert.equal(result.actions[0].type,"create_reminder");
  assert.equal(result.actions[0].date_iso,"2026-11-17");
  assert.equal(result.actions[0].time_local,"11:00");
  assert.match(result.actions[0].title,/خرید مبل/);
});

test('does not infer arbitrary early hour of meeting', async () => {
  const response = await worker.fetch(request({
    transcript:"فردا جلسه ساعت 3",
    timezone:"Asia/Tehran",now_local:"2026-11-16T16:00:00+03:30",
    contact:{name:"حسینی"}
  }),agentWith({}));
  assert.equal(response.status,200);
  const result=await response.json();
  assert.equal(result.actions.length,0);
});
