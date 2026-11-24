import { expect, test } from "@playwright/test";
import { seedInstance } from "@izakyl/blockwright-client";
import { input, media, screen, world } from "@izakyl/blockwright-minecraft";
import { chmodSync, rmSync, writeFileSync } from "node:fs";
import {
  clickElement, panelElements, scrollElementIntoView, typeIntoElement, visibleBox, waitForElement, type Element,
} from "../ldlib2";
import { foundColonyFast, tryCommand } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { markSeeded, seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { ensureOut, REPO_ROOT, tracePath } from "../shared/harness/out-paths";
import { newRunId } from "../shared/harness/run-id";
import { ponderCatalog, QUIET_TOASTS, SET_GUI_SCALE, setVisualLanguage } from "../shared/capture/ponder";
import { APPEND_ASSISTANT_HISTORY, ASSISTANT_KEY_MASKED, REMOVE_ASSISTANT_HISTORY } from "../shared/ui/assistant-history";
import { ASSISTANT_SCRIPT_TRANSCRIPT, type ScriptQuestion } from "../shared/ui/assistant-transcript";

const READ_QUESTION = "How many residents do I have and what are they doing?";
const KEY_ENV = "FOLKWAYS_ASSISTANT_LIVE_DEEPSEEK_KEY";

function isToolActivity(element: Element) {
  return element.id === "folkways.assistant.runs.count" || element.classes?.includes("folkways_assistant_tool") === true;
}

test("live DeepSeek assistant reads a colony and confirms a rename", async ({}, testInfo) => {
  test.skip(!process.env[KEY_ENV], "Set FOLKWAYS_ASSISTANT_LIVE_DEEPSEEK_KEY for this opt-in live check.");
  test.setTimeout(BUDGET.standard);
  ensureOut("e2e");
  const run = newRunId();
  const clientName = `assistant-live-client-${run}`;
  const seed = seedInstance(clientName, { cwd: REPO_ROOT });
  seed.write("config/folkways-assistant.json", JSON.stringify({
    entries: [{ id: "live", provider: "deepseek", baseUrl: "https://api.deepseek.com", model: "deepseek-v4-flash" }],
    selectedEntryId: "live",
    autoApprove: [],
  }));
  const secret = process.env[KEY_ENV]!;
  const credentials = seed.write("config/jpi-mini/credentials.json",
    JSON.stringify({ "folkways_assistant.entry.live": { apiKey: secret } }));
  chmodSync(credentials, 0o600);
  markSeeded(clientName);
  // Neither Minecraft process nor the driver needs the harness's secret environment variable.
  delete process.env[KEY_ENV];
  let pair: E2EPair | undefined;
  const transcript: Record<string, unknown> = { question: READ_QUESTION };
  let previousQuestions: ScriptQuestion[] = [];
  try {
    pair = await launchPair({
      config: "blockwright.toml#assistant-live",
      server: { instance: seededServer(`assistant-live-server-${run}`), trace: tracePath("e2e", "assistant-live-server") },
      client: { instance: clientName, trace: tracePath("e2e", "assistant-live-client") },
      snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    const { server, client } = pair;
    const players = await world.players(server);
    const player = players[0];
    const origin = { x: Math.round(player.position.x) + 4, y: Math.round(player.position.y),
      z: Math.round(player.position.z) + 4 };
    await tryCommand(server, `gamemode survival ${player.name}`);
    await foundColonyFast(server, client, player.name, { origin, residentCount: 2 });
    await screen.dismiss(client);
    await client.run(QUIET_TOASTS, undefined);
    await input.key(client, { keybind: "key.folkways.assistant" });
    await waitForElement(client, { id: "folkways.assistant.input" });
    // Exercise actual keyboard typing and Enter submission, rather than setting widget state reflectively.
    await typeIntoElement(client, { id: "folkways.assistant.input" }, READ_QUESTION, { submit: true });
    await expect.poll(async () => (await panelElements(client)).some(isToolActivity), { timeout: 180_000 }).toBe(true);
    await expect.poll(async () => (await panelElements(client)).find(element =>
      element.id === "folkways.assistant.status")?.text, { timeout: 180_000 }).toBe("Ready");
    const answers = (await panelElements(client)).filter(element =>
      element.classes?.includes("folkways_assistant_assistant") && element.text?.trim());
    expect(answers.length).toBeGreaterThan(0);
    const answer = answers.map(element => element.text).join("\n");
    const tools = (await panelElements(client)).filter(isToolActivity)
      .map(element => element.text);
    Object.assign(transcript, { tools, answer });
    await client.run(QUIET_TOASTS, undefined);
    await media.screenshot(client, { path: testInfo.outputPath("assistant-answer.png"), settleTerrain: false });
    // The session must survive a genuine close and reopen, with controls visible even for long history.
    await screen.dismiss(client);
    await client.run(APPEND_ASSISTANT_HISTORY, undefined);
    await input.key(client, { keybind: "key.folkways.assistant" });
    await waitForElement(client, { id: "folkways.assistant.input" });
    expect((await panelElements(client)).some(element => element.text === answers.at(-1)?.text)).toBe(true);
    const toolActivityBeforeRename = (await panelElements(client)).filter(isToolActivity).length;
    await typeIntoElement(client, { id: "folkways.assistant.input" },
      'Please rename one of my residents to Ada. Choose any one resident.', { submit: true });
    await waitForElement(client, { id: "folkways.assistant.confirm" }, { timeoutMs: 180_000 });
    await client.run(REMOVE_ASSISTANT_HISTORY, undefined);
    const confirmationElements = await panelElements(client);
    const summary = confirmationElements.find(element => element.id === "folkways.assistant.confirmation.summary");
    expect(summary?.text).toContain("Ada");
    expect(summary?.text).toContain("Rename");
    expect(summary?.text).not.toMatch(/resident_id|\{|[0-9a-f]{8}-[0-9a-f-]{27}/i);
    expect(confirmationElements.filter(element => element.text === summary?.text)).toHaveLength(1);
    expect(confirmationElements.filter(isToolActivity).length).toBeGreaterThan(toolActivityBeforeRename);
    await client.run(QUIET_TOASTS, undefined);
    await media.screenshot(client, { path: testInfo.outputPath("assistant-confirmation.png"), settleTerrain: false });
    await clickElement(client, { id: "folkways.assistant.confirm" }, { scrollIntoView: true });
    await expect.poll(() => world.probe(server,
      'if entity @e[type=folkways_vanilla:resident,name=Ada,limit=1]'), { timeout: 30_000 }).toBe(true);
    await expect.poll(async () => (await panelElements(client)).find(element =>
      element.id === "folkways.assistant.status")?.text, { timeout: 180_000 }).toBe("Ready");
    await clickElement(client, { id: "folkways.assistant.settings" });
    await waitForElement(client, { id: "folkways.assistant.api_key" });
    await expect.poll(() => client.run(ASSISTANT_KEY_MASKED, undefined), { timeout: 10_000 }).toBe(true);
    let settingsElements = await panelElements(client);
    expect(settingsElements.some(element => element.id === "folkways.assistant.settings" && element.displayed)).toBe(true);
    expect(settingsElements.some(element => element.id === "folkways.window.folkways.assistant.preferences"
      && element.displayed)).toBe(true);
    for (const category of ["settings", "work", "residents", "drawings"]) {
      const id = `folkways.assistant.auto.${category}`;
      await scrollElementIntoView(client, { id });
      const elements = await panelElements(client);
      const toggle = elements.find(element => element.id === id)!;
      expect(visibleBox(elements, toggle)?.width).toBeCloseTo(toggle.width, 0);
    }
    await scrollElementIntoView(client, { id: "folkways.assistant.api_key" });
    settingsElements = await panelElements(client);
    expect(settingsElements.some(element => element.text?.includes(secret))).toBe(false);
    await client.run(QUIET_TOASTS, undefined);
    await media.screenshot(client, { path: testInfo.outputPath("assistant-settings-masked.png"), settleTerrain: false });
    await clickElement(client, { id: "folkways.window.close.folkways.assistant.preferences" });
    await waitForElement(client, { id: "folkways.assistant.input" });
    await screen.dismiss(client);
    await setVisualLanguage(client, "zh_cn");
    await client.run(SET_GUI_SCALE, { scale: 2 });
    await input.key(client, { keybind: "key.folkways.assistant" });
    previousQuestions = JSON.parse(await client.run(ASSISTANT_SCRIPT_TRANSCRIPT, undefined));
    await clickElement(client, { id: "folkways.assistant.new" });
    const chineseQuestion = "我有多少位居民？他们现在都在做什么？请用中文回答。";
    await typeIntoElement(client, { id: "folkways.assistant.input" }, chineseQuestion, { submit: true });
    const chineseCatalog = ponderCatalog("zh_cn");
    await expect.poll(async () => (await panelElements(client)).some(isToolActivity), { timeout: 180_000 }).toBe(true);
    await expect.poll(async () => (await panelElements(client)).find(element =>
      element.id === "folkways.assistant.status")?.text, { timeout: 180_000 }).toBe(chineseCatalog["folkways.assistant.ready"]);
    const chineseAnswer = (await panelElements(client)).filter(element =>
      element.classes?.includes("folkways_assistant_assistant") && element.text?.trim())
      .map(element => element.text).join("\n");
    expect([...chineseAnswer.matchAll(/\p{Script=Han}/gu)].length).toBeGreaterThan(10);
    await client.run(QUIET_TOASTS, undefined);
    await media.screenshot(client, { path: testInfo.outputPath("assistant-answer-zh-cn-scale-2.png"),
      settleTerrain: false });
    Object.assign(transcript, { chineseQuestion, chineseAnswer,
      rename: "Player confirmed a run batch containing rename; the server contains a resident named Ada." });
    const questions: ScriptQuestion[] = JSON.parse(await client.run(ASSISTANT_SCRIPT_TRANSCRIPT, undefined));
    transcript.scriptQuestions = [...previousQuestions, ...questions];
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    // Keep credentials out of retained failure evidence and instance directories.
    rmSync(credentials, { force: true });
    if (pair && !transcript.scriptQuestions) {
      try {
        const questions: ScriptQuestion[] = JSON.parse(
          await pair.client.run(ASSISTANT_SCRIPT_TRANSCRIPT, undefined));
        transcript.scriptQuestions = [...previousQuestions, ...questions];
      } catch {
        transcript.scriptQuestions = previousQuestions;
        transcript.scriptCaptureFailed = true;
      }
    }
    writeFileSync(testInfo.outputPath("transcript.json"), JSON.stringify(transcript, null, 2) + "\n");
    await pair?.teardown(testInfo);
    process.env[KEY_ENV] = secret;
  }
});
