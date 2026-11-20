// The native screens' colours (ThemeData.kt) are generated from the page's THEMES. This fails if they drift apart.
// Run: node tests/themes.test.js   (fix with: node tools/gen-theme-data.js > android/app/src/main/java/au/sunrise/alarm/ThemeData.kt)
const fs = require("fs"), path = require("path"), assert = require("assert");
const { generate } = require("../tools/gen-theme-data.js");
const file = fs.readFileSync(path.join(__dirname, "..", "android/app/src/main/java/au/sunrise/alarm/ThemeData.kt"), "utf8");
assert.strictEqual(file, generate(), "ThemeData.kt is out of date: run node tools/gen-theme-data.js");
console.log("Theme colours match the page");
