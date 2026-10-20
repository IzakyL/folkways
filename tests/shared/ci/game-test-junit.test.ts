import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const script = fileURLToPath(new URL("../../../scripts/ci/write-game-test-junit.mjs", import.meta.url));

async function run(profile: string, conclusion: string, log?: string) {
  const cwd = await mkdtemp(path.join(os.tmpdir(), "folkways-game-test-junit-"));
  const ci = path.join(cwd, "run", "gametest", "ci");
  await mkdir(ci, { recursive: true });
  if (log !== undefined) await writeFile(path.join(ci, `${profile}.log`), log, "utf8");

  const result = spawnSync(process.execPath, [script, profile, conclusion], { cwd, encoding: "utf8" });
  const xml = await readFile(path.join(ci, `${profile}-junit.xml`), "utf8").catch(() => "");
  return { cwd, result, xml };
}

test("GameTestServer passes only after completed and required test counts appear in its log", async () => {
  const outcome = await run("base", "success", "3 GAME TESTS COMPLETE\nAll 2 required tests passed\n");
  try {
    assert.equal(outcome.result.status, 0, outcome.result.stderr);
    assert.match(outcome.xml, /<testsuite name="NeoForge GameTestServer \(base\)" tests="1" failures="0"/);
    assert.match(outcome.xml, /<testcase classname="NeoForge GameTestServer \(base\)" name="runGameTestServer">/);
  } finally {
    await rm(outcome.cwd, { recursive: true, force: true });
  }
});

test("a zero-exit Gradle run without completed GameTests fails", async () => {
  const outcome = await run("base", "success", "BUILD SUCCESSFUL\n");
  try {
    assert.notEqual(outcome.result.status, 0);
    assert.match(outcome.xml, /failures="1"/);
    assert.match(outcome.xml, /complete=0, requiredPassed=0/);
  } finally {
    await rm(outcome.cwd, { recursive: true, force: true });
  }
});

test("a GameTest failure cannot pass even if the Gradle process exits successfully", async () => {
  const outcome = await run("cookery", "success", "4 GAME TESTS COMPLETE\nSome required tests failed\n");
  try {
    assert.notEqual(outcome.result.status, 0);
    assert.match(outcome.xml, /failures="1"/);
  } finally {
    await rm(outcome.cwd, { recursive: true, force: true });
  }
});

test("a missing server log fails and still writes a JUnit failure result", async () => {
  const outcome = await run("base", "success");
  try {
    assert.notEqual(outcome.result.status, 0);
    assert.match(outcome.xml, /failures="1"/);
    assert.match(outcome.xml, /complete=0, requiredPassed=0/);
  } finally {
    await rm(outcome.cwd, { recursive: true, force: true });
  }
});

test("JUnit output escapes profile text", async () => {
  const profile = "cookery&beta";
  const conclusion = "failure<&>\"'";
  const outcome = await run(profile, conclusion, "2 GAME TESTS COMPLETE\nAll 2 required tests passed\n");
  try {
    assert.notEqual(outcome.result.status, 0);
    assert.match(outcome.xml, /NeoForge GameTestServer \(cookery&amp;beta\)/);
    assert.match(outcome.xml, /GameTest job concluded failure&lt;&amp;&gt;&quot;&apos;/);
    assert.doesNotMatch(outcome.xml, /GameTest job concluded failure<\&>/);
  } finally {
    await rm(outcome.cwd, { recursive: true, force: true });
  }
});
