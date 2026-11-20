// Tests for the alarm schedule logic in index.html (the block between "schedule:start" and "schedule:end").
// Run: TZ=Australia/Melbourne node tests/schedule.test.js
const fs = require("fs");
const path = require("path");
const assert = require("assert");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const m = html.match(/\/\/ ---- schedule:start[^\n]*\n([\s\S]*?)\/\/ ---- schedule:end/);
if (!m) throw new Error("Couldn't find the schedule block in index.html");
const { nextAlarm, alarmPlan } = new Function("S", m[1] + "\nreturn { nextAlarm, alarmPlan };")({});

const DEF = { time: "06:30", days: [1, 2, 3, 4, 5], dur: 30, skip: 0, pauseUntil: "", perDay: false, dayTimes: {} };
const set = o => ({ ...DEF, ...o });
const at = (y, mo, d, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi);
const local = t => t && `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")} ${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}`;

let passed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log("  ✓ " + name); }
  catch (e) { console.error("  ✗ " + name + "\n    " + e.message); process.exitCode = 1; }
}

console.log(`Schedule tests (TZ=${process.env.TZ || "system"})`);

test("weekday alarm later today", () => {
  assert.strictEqual(local(nextAlarm(at(2026, 11, 23, 5, 0), set({}))), "2026-11-23 06:30");   // Monday
});
test("after today's time, goes to the next weekday", () => {
  assert.strictEqual(local(nextAlarm(at(2026, 11, 23, 7, 0), set({}))), "2026-11-24 06:30");
});
test("Friday evening skips the weekend", () => {
  assert.strictEqual(local(nextAlarm(at(2026, 11, 27, 21, 0), set({}))), "2026-11-30 06:30");
});
test("exactly at the alarm time counts as passed", () => {
  assert.strictEqual(local(nextAlarm(at(2026, 11, 23, 6, 30), set({}))), "2026-11-24 06:30");
});
test("no days chosen = one-off at the next occurrence", () => {
  assert.strictEqual(local(nextAlarm(at(2026, 11, 28, 22, 0), set({ days: [] }))), "2026-11-29 06:30");
});
test("skip moves past one alarm only", () => {
  const skip = +at(2026, 11, 24, 6, 30);
  assert.strictEqual(local(nextAlarm(at(2026, 11, 23, 7, 0), set({ skip }))), "2026-11-25 06:30");
});
test("holiday pause includes the end date", () => {
  assert.strictEqual(local(nextAlarm(at(2026, 11, 23, 7, 0), set({ pauseUntil: "2026-11-26" }))), "2026-11-27 06:30");
});
test("per-day times", () => {
  const s = set({ perDay: true, dayTimes: { 2: "05:45", 3: "07:15" } });
  assert.strictEqual(local(nextAlarm(at(2026, 11, 23, 7, 0), s)), "2026-11-24 05:45");   // Tuesday
  assert.strictEqual(local(nextAlarm(at(2026, 11, 24, 6, 0), s)), "2026-11-25 07:15");   // Wednesday
});
test("daylight saving start keeps the wall-clock time (Melbourne: 4 Oct 2026)", () => {
  const s = set({ days: [0, 1, 2, 3, 4, 5, 6] });
  assert.strictEqual(local(nextAlarm(at(2026, 10, 3, 22, 0), s)), "2026-10-04 06:30");
  assert.strictEqual(local(nextAlarm(at(2026, 10, 4, 7, 0), s)), "2026-10-05 06:30");
});
test("daylight saving end keeps the wall-clock time (Melbourne: 5 Apr 2026)", () => {
  const s = set({ days: [0, 1, 2, 3, 4, 5, 6] });
  assert.strictEqual(local(nextAlarm(at(2026, 4, 4, 22, 0), s)), "2026-04-05 06:30");
  assert.strictEqual(local(nextAlarm(at(2026, 4, 5, 7, 0), s)), "2026-04-06 06:30");
});
test("late-night alarm just before midnight", () => {
  assert.strictEqual(local(nextAlarm(at(2026, 11, 23, 23, 0), set({ time: "23:55" }))), "2026-11-23 23:55");
});
test("plan lists the next alarms with sunrise start = alarm − duration", () => {
  const p = alarmPlan(at(2026, 11, 23, 7, 0), set({ dur: 20 }), 5);
  assert.deepStrictEqual(p.map(([, a]) => local(new Date(a))),
    ["2026-11-24 06:30", "2026-11-25 06:30", "2026-11-26 06:30", "2026-11-27 06:30", "2026-11-30 06:30"]);
  p.forEach(([s, a]) => assert.strictEqual(a - s, 20 * 60000));
});
test("plan respects skip and pause", () => {
  const p = alarmPlan(at(2026, 11, 23, 7, 0), set({ skip: +at(2026, 11, 24, 6, 30), pauseUntil: "2026-11-26" }), 2);
  assert.deepStrictEqual(p.map(([, a]) => local(new Date(a))), ["2026-11-27 06:30", "2026-11-30 06:30"]);
});
test("one-off plan has a single entry", () => {
  assert.strictEqual(alarmPlan(at(2026, 11, 23, 7, 0), set({ days: [] })).length, 1);
});
test("plan is in time order with no duplicates", () => {
  const p = alarmPlan(at(2026, 11, 23, 7, 0), set({ days: [0, 1, 2, 3, 4, 5, 6] }), 8);
  for (let i = 1; i < p.length; i++) assert.ok(p[i][1] > p[i - 1][1]);
});

console.log(`${passed} passed` + (process.exitCode ? ", some failed" : ""));
