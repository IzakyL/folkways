import { expect, test } from "@playwright/test";
import { seedInstance } from "@izakyl/blockwright-client";
import { input, screen, tick } from "@izakyl/blockwright-minecraft";
import { readFileSync, rmSync } from "node:fs";
import { createServer } from "node:http";
import { clickElement, panelElements, typeIntoElement, waitForElement } from "../ldlib2";
import { foundColonyFast, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { QUIET_TOASTS, setVisualLanguage, SET_GUI_SCALE } from "../shared/capture/ponder";
import { VisualRun } from "../shared/capture/visual-kit";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { markSeeded, seededOffThreadServer } from "../shared/harness/mod-settings";
import { REPO_ROOT, tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";

// The conversation itself: a model that runs script after script, a run still going, and a prompt longer than the
// box it is typed into.

const prefix = "folkways.assistant.";
const locale = "zh_cn";
const ENTER = 257;
const LEFT = 263;
const UP = 265;

type Turn = { script: string } | { text: string } | { hold: Promise<void> };

test(`Assistant chat (${locale})`, async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun(`assistant-chat-${locale}`);
  const id = newRunId();
  const clientName = `visual-assistant-chat-client-${id}`;
  const turns: Turn[] = [];
  const provider = createServer(async (request, response) => {
    for await (const _ of request) {
      // Drain the model request before returning its next scripted response.
    }
    let turn = turns.shift() ?? { text: "" };
    while ("hold" in turn) {
      await turn.hold;
      turn = turns.shift() ?? { text: "" };
    }
    const delta = "script" in turn ? { tool_calls: [{ index: 0, id: `run-${newRunId()}`, type: "function",
      function: { name: "run", arguments: JSON.stringify({ script: turn.script }) } }] } : { content: turn.text };
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.end(`data: ${JSON.stringify({ choices: [{ index: 0, delta, finish_reason: null }] })}\n\n`
      + `data: ${JSON.stringify({ choices: [{ index: 0, delta: {},
        finish_reason: "script" in turn ? "tool_calls" : "stop" }] })}\n\n`
      + "data: [DONE]\n\n");
  });
  await new Promise<void>(resolve => provider.listen(0, "127.0.0.1", resolve));
  const address = provider.address();
  if (!address || typeof address === "string") {
    throw new Error("Missing loopback provider address");
  }
  const seed = seedInstance(clientName, { cwd: REPO_ROOT });
  seed.write("config/folkways-assistant.json", JSON.stringify({
    entries: [{ id: "local", provider: "openai-compatible", model: "local-model",
      baseUrl: `http://127.0.0.1:${address.port}/v1` }],
    selectedEntryId: "local",
    autoApprove: [],
  }));
  const credential = seed.write("config/jpi-mini/credentials.json",
    JSON.stringify({ "folkways_assistant.entry.local": { apiKey: "local-test-key" } }));
  markSeeded(clientName);
  let pair: E2EPair | undefined;
  let release = () => {};
  try {
    pair = await launchPair({
      config: "blockwright.toml#assistant-screenshot",
      server: { instance: seededOffThreadServer(`visual-assistant-chat-server-${id}`),
        trace: tracePath("visual", `assistant-chat-${locale}-server`) },
      client: { instance: clientName, trace: tracePath("visual", `assistant-chat-${locale}-client`) },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    await setVisualLanguage(client, locale);
    await client.run(SET_GUI_SCALE, { scale: 2 });
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await foundColonyFast(server, client, grounded.name, { origin, residentCount: 2 });
    await tryCommand(server, `tp ${grounded.name} ${origin.x + 0.5} ${origin.y + 2} ${origin.z + 1.5} 0 25`);
    await tick.sprint(server, 10);
    await screen.dismiss(client);
    await input.key(client, { keybind: "key.folkways.assistant" });
    const select = (token: string) => ({ id: prefix + token });
    const shot = async (name: string, subject: string) => {
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 4);
      await run.shot(client, name, { subject, worldState: { locale }, hard: true });
    };
    const catalog: Record<string, string> = JSON.parse(readFileSync(
      `mods/assistant/src/main/resources/assets/folkways_assistant/lang/${locale}.json`, "utf8"));
    const status = async () => (await panelElements(client)).find(element => element.id === prefix + "status")?.text;
    const settle = async () => {
      await tick.sprint(server, 5);
      await frames(client, 3);
    };
    const ready = () => expect.poll(async () => {
      await settle();
      return status();
    }, { timeout: 45_000 }).toBe(catalog[prefix + "ready"]);
    const elements = (token: string) => panelElements(client).then(all => all.filter(element =>
      element.id === prefix + token && element.width > 0));
    const send = async (prompt: string) => typeIntoElement(client, select("input"), prompt, { submit: true });

    await waitForElement(client, select("input"), { timeoutMs: 30_000 });
    await client.run(QUIET_TOASTS);

    // Six scripts in a row, two of them failing, then the answer.
    turns.push(
      { script: 'print(len(ls("residents")))' },
      { script: 'print(get("colony"))' },
      { script: 'fail("No gate named North gate")' },
      { script: 'print(ls("facilities"))' },
      { script: 'print(gate.state)' },
      { script: 'print(ls("residents"))' },
      { text: "殖民地有两位居民，但还没有名为“北门”的设施。要我先帮你规划一座吗？" },
    );
    await send("北门现在开着吗？");
    await ready();
    // One line stands for all six runs and counts the two that failed; their errors stay folded away.
    expect(await elements("runs")).toHaveLength(1);
    expect((await elements("runs.count"))[0]?.text).toBe(catalog[prefix + "runs"].replace("%s", "6"));
    expect((await elements("runs.failed"))[0]?.text).toBe(catalog[prefix + "runs_failed"].replace("%s", "2"));
    expect(await elements("show_script")).toHaveLength(0);
    await shot("runs-collapsed", "Six script runs folded into one line");
    await clickElement(client, select("runs"));
    await frames(client, 3);
    expect(await elements("show_script")).toHaveLength(6);
    await clickElement(client, select("show_script"));
    await shot("runs-expanded", "The six runs unfolded under their line, the first with its script");
    await clickElement(client, select("show_script"));
    await clickElement(client, select("runs"));

    // A run that is still going: two scripts back, the third not answered yet.
    let held!: () => void;
    turns.push(
      { script: 'print(ls("residents"))' },
      { script: 'fail("Missing survey marker")' },
      { hold: new Promise<void>(resolve => { held = resolve; }) },
      { script: 'print(get("colony"))' },
      { text: "完成。" },
    );
    release = () => held();
    await send("再检查一遍。");
    await expect.poll(async () => {
      await settle();
      return (await elements("runs.failed")).length;
    }, { timeout: 30_000 }).toBe(2);
    expect(await elements("runs.live")).toHaveLength(1);
    await shot("runs-live", "Script runs while the assistant is still working");
    release();
    await ready();
    expect(await elements("runs.live")).toHaveLength(0);

    // A prompt longer than the box wraps onto more lines; Shift+Enter starts one of its own, and sending it leaves
    // one line again.
    const single = (await elements("input"))[0].height;
    const long = "帮我在北门外修一条通往麦田站的铁路，沿途避开河流和居民的房子，"
      + "到了站台再接上干线，然后把北门设成白天开、夜里关。";
    await clickElement(client, select("input"));
    await input.key(client, { text: long });
    await input.key(client, { keyCode: ENTER, modifiers: ["shift"] });
    await input.key(client, { text: "Keep the old track." });
    await frames(client, 3);
    const grown = (await elements("input"))[0].height;
    expect(grown).toBeGreaterThan(single * 2);
    await shot("input-wrapped", "A long prompt wrapped inside the composer");
    // Up steps a line, not to the start; typing there lands mid-text.
    await input.key(client, { keyCode: UP });
    await input.key(client, { keyCode: LEFT });
    await input.key(client, { text: "!" });
    await input.key(client, { keyCode: ENTER });
    await ready();
    const asked = (await panelElements(client)).filter(element => element.text?.includes("Keep the old track"));
    expect(asked.length).toBeGreaterThan(0);
    expect(asked.some(element => element.text!.includes("!") && !element.text!.startsWith("!"))).toBe(true);
    await shot("input-sent", "The composer back to one line after sending");
    expect((await elements("input"))[0].height).toBe(single);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    release();
    rmSync(credential, { force: true });
    await pair?.teardown(testInfo);
    provider.closeAllConnections();
    await new Promise<void>(resolve => provider.close(() => resolve()));
  }
});
