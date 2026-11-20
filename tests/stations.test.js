// The native Wake-up sound screen has its own copy of the built-in stations (SoundSettings.kt).
// This checks it matches the page's STATIONS list, so station numbers mean the same on both sides.
// Run: node tests/stations.test.js
const fs = require("fs"), path = require("path"), assert = require("assert");
const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const kt = fs.readFileSync(path.join(__dirname, "..", "android/app/src/main/java/au/sunrise/alarm/SoundSettings.kt"), "utf8");

const ak = html.match(/const AK = "([^"]+)", MH = "([^"]+)"/);
const page = [...html.slice(html.indexOf("const STATIONS = [")).split("];")[0].matchAll(/\["([^"]+)",\s*\[([^\]]*)\]\]/g)]
  .map(m => [m[1], m[2].split(",").map(x => x.trim()).filter(Boolean).map(x => x.replace(/^AK\+/, ak[1]).replace(/^MH\+/, ak[2]).replace(/"/g, ""))]);
const kAK = kt.match(/AK = "([^"]+)"/)[1], kMH = kt.match(/MH = "([^"]+)"/)[1];
const native = [...kt.matchAll(/"([^"]+)" to listOf\(([^)]*)\)/g)]
  .map(m => [m[1], m[2].split(",").map(x => x.trim()).filter(Boolean).map(x => x.replace(/^AK \+ /, kAK).replace(/^MH \+ /, kMH).replace(/"/g, ""))]);

assert.ok(page.length >= 6, "found the page's stations");
assert.deepStrictEqual(native, page);
console.log(`Stations match (${page.length})`);
