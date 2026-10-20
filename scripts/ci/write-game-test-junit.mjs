import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

const [profile, conclusion] = process.argv.slice(2);
if (!profile || !conclusion) {
  throw new Error("Usage: write-game-test-junit.mjs <profile> <job-conclusion>");
}

const log = await readFile(resolve("run/gametest/ci", `${profile}.log`), "utf8").catch(() => "");
const completed = Number(log.match(/(\d+) GAME TESTS COMPLETE/)?.[1] ?? 0);
const requiredPassed = Number(log.match(/All (\d+) required tests passed/)?.[1] ?? 0);
const passed = conclusion === "success" && completed > 0 && requiredPassed > 0 && requiredPassed <= completed;
const suite = `NeoForge GameTestServer (${profile})`;
const xmlEscape = (value) => String(value)
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;")
  .replaceAll("'", "&apos;");
const failure = passed
  ? ""
  : `    <failure message="GameTest job concluded ${xmlEscape(conclusion)}; complete=${completed}, requiredPassed=${requiredPassed}">See run/gametest/ci/${xmlEscape(profile)}.log and the uploaded game directory logs.</failure>\n`;
const xml = `<?xml version="1.0" encoding="UTF-8"?>
<testsuites tests="1" failures="${passed ? 0 : 1}" errors="0" skipped="0">
  <testsuite name="${xmlEscape(suite)}" tests="1" failures="${passed ? 0 : 1}" errors="0" skipped="0">
    <testcase classname="${xmlEscape(suite)}" name="runGameTestServer">
${failure}    </testcase>
  </testsuite>
</testsuites>
`;
const output = resolve("run/gametest/ci", `${profile}-junit.xml`);
await mkdir(dirname(output), { recursive: true });
await writeFile(output, xml, "utf8");
console.log(`Wrote ${output} (${passed ? "passed" : `failed: ${conclusion}`})`);
if (!passed) process.exitCode = 1;
