import assert from "node:assert/strict";

const base = process.env.POYARA_CENTRAL_AGENT_URL?.replace(/\/$/, "");
const token = process.env.POYARA_APP_ACCESS_KEY;
if (!base?.startsWith("https://") || !token || token.length < 32)
  throw new Error("No deployed backend and app access key for smoke test");

const now = new Date();
const dateLocal = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Tehran", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute:"2-digit", hourCycle:"h23"
}).formatToParts(now).reduce((a,part) => (a[part.type] = part.value,a), {});
const nowLocal = `${dateLocal.year}-${dateLocal.month}-${dateLocal.day}T${dateLocal.hour}:${dateLocal.minute}:00+03:30`;
const fixtures = [
  {
    name:"second_phone",
    transcript:"شماره دیگری برای همین مشتری اضافه کن 09151112233",
    contact:{name:"آقای حسینی",company:"مبلمان نمونه",phone:"09150000000",
      extra_phones:[],role:"",category:"مبل فروشی"},
    assertResponse:(json)=>{
      assert.ok(json.actions.some(a => a.type === "add_phone" &&
        a.value === "09151112233"),"no add_phone action");
    }
  },
  {
    name:"role_manager",
    transcript:"سمت این شخص مدیر مجموعه است",
    contact:{name:"آقای حسینی",phone:"09150000000",extra_phones:[],role:""},
    assertResponse:(json)=>{
      assert.ok(json.actions.some(a => a.type === "set_role" &&
        a.value?.includes("مدیر")),"no set_role");
    }
  },
  {
    name:"tomorrow_11",
    transcript:"فردا ساعت ۱۱ صبح با آقای حسینی جلسه درباره خرید مبلمان دارم، یادآوری کن",
    contact:{name:"آقای حسینی",phone:"09150000000",extra_phones:[],role:""},
    assertResponse:(json)=>{
      const proposal = json.actions.find(a=>a.type==="create_reminder") ||
        json.reminders.find(a=>a.date_iso && a.time_local);
      assert.ok(proposal,"no meeting proposal");
      assert.equal(proposal.time_local,"11:00");
    }
  }
];

for (const fx of fixtures) {
  const req = {
    transcript:fx.transcript, now_local:nowLocal, timezone:"Asia/Tehran",
    contact:fx.contact, reminders:[]
  };
  const response = await fetch(`${base}/v1/text/analyze`, {
    method:"POST",
    headers:{Authorization:`Bearer ${token}`, "Content-Type":"application/json"},
    body:JSON.stringify(req),
    signal:AbortSignal.timeout(120000)
  });
  if (!response.ok) throw new Error(`${fx.name}: endpoint returned HTTP ${response.status}`);
  const json = await response.json();
  fx.assertResponse(json);
  // Only the test name goes to logs; no token or customer details are printed.
  console.log(`PASS ${fx.name}`);
}
