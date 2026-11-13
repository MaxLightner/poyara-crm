import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

const endpoint = 'https://test.invalid/v1/text/analyze';
const sharedCode = 'a'.repeat(40);
const dummy = {AI:{run: async () => ({response:JSON.stringify({
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
