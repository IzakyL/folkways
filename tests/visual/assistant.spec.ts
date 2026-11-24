import { expect, test } from "@playwright/test";
import { seedInstance } from "@izakyl/blockwright-client";
import { camera, input, screen, tick } from "@izakyl/blockwright-minecraft";
import { readFileSync, rmSync } from "node:fs";
import { createServer } from "node:http";
import { clickElement, panelElements, scrollElementIntoView, typeIntoElement, waitForElement } from "../ldlib2";
import { anchorOf } from "../ldlib2/rules";
import { foundColonyFast, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { SET_UP_GATE } from "../shared/combat/rts-gate";
import { QUIET_TOASTS, setVisualLanguage, SET_GUI_SCALE } from "../shared/capture/ponder";
import { VisualRun } from "../shared/capture/visual-kit";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { markSeeded, seededOffThreadServer } from "../shared/harness/mod-settings";
import { REPO_ROOT, tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { APPEND_ASSISTANT_HISTORY, ASSISTANT_KEY_MASKED } from "../shared/ui/assistant-history";
import { DRAWING_GHOSTS } from "../shared/ui/assistant-ghosts";

const prefix = "folkways.assistant.";
const close = (window: string) => ({ id: "folkways.window.close." + prefix + window });

for (const locale of ["en_us", "zh_cn"]) {
  test(`Assistant panel (${locale})`, async ({}, testInfo) => {
    test.setTimeout(BUDGET.heavy);
    const run = new VisualRun(`assistant-${locale}`);
    const id = newRunId();
    const clientName = `visual-assistant-client-${id}`;
    let script: string | undefined;
    const provider = createServer(async (request, response) => {
      for await (const _ of request) {
        // Drain the model request before returning its next scripted response.
      }
      const source = script;
      script = undefined;
      const delta = source ? { tool_calls: [{ index: 0, id: `run-${newRunId()}`, type: "function",
        function: { name: "run", arguments: JSON.stringify({ script: source }) } }] }
        : { content: locale === "zh_cn" ? "我已检查殖民地。" : "I inspected the colony." };
      response.writeHead(200, { "Content-Type": "text/event-stream" });
      response.end(`data: ${JSON.stringify({ choices: [{ index: 0, delta, finish_reason: null }] })}\n\n`
        + `data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: source ? "tool_calls" : "stop" }] })}\n\n`
        + "data: [DONE]\n\n");
    });
    await new Promise<void>(resolve => provider.listen(0, "127.0.0.1", resolve));
    const address = provider.address();
    if (!address || typeof address === "string") {
      throw new Error("Missing loopback provider address");
    }
    const seed = seedInstance(clientName, { cwd: REPO_ROOT });
    seed.write("config/folkways-assistant.json", JSON.stringify({
      entries: [
        { id: "local", provider: "openai-compatible", model: "local-model",
          baseUrl: `http://127.0.0.1:${address.port}/v1` },
      ],
      selectedEntryId: "local",
      autoApprove: [],
    }));
    const credential = seed.write("config/jpi-mini/credentials.json",
      JSON.stringify({ "folkways_assistant.entry.local": { apiKey: "local-test-key" } }));
    markSeeded(clientName);
    let pair: E2EPair | undefined;
    try {
      pair = await launchPair({
        config: "blockwright.toml#assistant-screenshot",
        server: { instance: seededOffThreadServer(`visual-assistant-server-${id}`),
          trace: tracePath("visual", `assistant-${locale}-server`) },
        client: { instance: clientName, trace: tracePath("visual", `assistant-${locale}-client`) },
      });
      const { server, client } = pair;
      const grounded = await waitForGroundedPlayer(server);
      await setVisualLanguage(client, locale);
      await client.run(SET_GUI_SCALE, { scale: 2 });
      const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
      const { evidence } = await foundColonyFast(server, client, grounded.name, { origin, residentCount: 2 });
      await tryCommand(server, `fill ${origin.x - 15} ${origin.y - 1} ${origin.z - 12} `
        + `${origin.x + 15} ${origin.y - 1} ${origin.z + 15} minecraft:grass_block`);
      await server.run(SET_UP_GATE, { ...origin, x: origin.x + 8, z: origin.z + 10, colony: evidence.colonyId });
      await tryCommand(server, `tp ${grounded.name} ${origin.x + 0.5} ${origin.y + 2} ${origin.z + 1.5} 0 25`);
      await tick.sprint(server, 10);
      await screen.dismiss(client);
      await input.key(client, { keybind: "key.folkways.assistant" });
      const select = (token: string) => ({ id: prefix + token });
      const click = (token: string) => clickElement(client, select(token), { scrollIntoView: true });
      const shot = async (name: string, subject: string) => {
        await input.move(client, { x: 0, y: 0 });
        await frames(client, 4);
        await run.shot(client, name, { subject, worldState: { locale }, hard: true });
      };
      const wait = (token: string) => waitForElement(client, select(token), { timeoutMs: 30_000 });
      const catalog: Record<string, string> = JSON.parse(readFileSync(
        `mods/assistant/src/main/resources/assets/folkways_assistant/lang/${locale}.json`, "utf8"));
      const ready = () => expect.poll(async () => {
        await tick.sprint(server, 5);
        await frames(client, 3);
        return (await panelElements(client)).find(element => element.id === prefix + "status")?.text;
      }, { timeout: 45_000 }).toBe(catalog[prefix + "ready"]);
      const contains = async (text: string) => (await panelElements(client)).some(element => element.text?.includes(text));
      const showLine = async (text: string) => {
        const elements = await panelElements(client);
        const line = elements.slice().reverse().find(element => element.text?.includes(text));
        expect(line, `Transcript must show ${text}`).toBeDefined();
        await scrollElementIntoView(client, anchorOf(elements, line!));
      };
      const send = async (prompt: string, source: string) => {
        script = source;
        await typeIntoElement(client, select("input"), prompt, { submit: true });
      };

      await wait("input");
      await client.run(QUIET_TOASTS);
      await click("settings");
      await wait("model");
      await expect.poll(() => client.run(ASSISTANT_KEY_MASKED)).toBe(true);
      await shot("settings-provider", "Provider, model, endpoint and masked key");
      await scrollElementIntoView(client, select("auto.drawings"));
      await shot("settings-auto-approve", "Assistant auto-approve settings");
      await clickElement(client, close("preferences"));

      await send(locale === "zh_cn" ? "查看殖民地。" : "Inspect the colony.", 'print(get("colony"))');
      await wait("show_script");
      await ready();
      await click("show_script");
      await shot("transcript-script", "Assistant transcript with an expanded script");
      await client.run(APPEND_ASSISTANT_HISTORY);
      await click("new");
      await click("history");
      await expect.poll(async () => (await panelElements(client)).some(element =>
        element.id?.startsWith(prefix + "history."))).toBe(true);
      await shot("history", "Saved assistant sessions");
      await clickElement(client, close("history"));

      const points = [{ x: origin.x - 6, y: origin.y + 2, z: origin.z + 8 },
        { x: origin.x + 6, y: origin.y + 2, z: origin.z + 8 }];
      const route = JSON.stringify({ dimension: "minecraft:overworld", points });
      const build = `set("builds/railway", {"pattern": "folkways_vanilla:railway", `
        + `"dimension": "minecraft:overworld", "points": ${JSON.stringify(points)}})`;
      const changes = [
        ["residents", 'set("residents/" + ls("residents")[0] + "/name", "Rowan")'],
        ["work", 'set("residents/" + ls("residents")[0] + "/work/folkways_vanilla:building", {"allowed": False})'],
        ["settings", 'set("scripts/visual_example", "def example():\\n    return 1\\n")'],
        ["drawings", `set("facilities/visual-patrol", ${JSON.stringify({
          dimension: "minecraft:overworld", kind: "folkways_vanilla:patrol", points,
        })})`],
        ["surveys", `set("surveys/visual", ${route})`],
        ["facilities", 'set("facilities/North gate/runs/visual", {"routine": "close"})'],
        ["building", build],
      ];
      for (const [kind, source] of changes) {
        if (kind === "building") {
          // In creative a build can be set down at once or handed to the colony, as placing a blueprint offers.
          await tryCommand(server, `gamemode creative ${grounded.name}`);
        }
        await send(`Review ${kind} change.`, source);
        await expect.poll(async () => {
          await tick.sprint(server, 8);
          await frames(client, 3);
          return (await panelElements(client)).some(element =>
            element.id === prefix + "confirmation.summary" && element.width > 0);
        }, { timeout: 30_000, message: `Assistant must preview a ${kind} change` }).toBe(true);
        if (kind === "building") {
          expect((await client.run(DRAWING_GHOSTS)).count).toBeGreaterThan(0);
        }
        // Every change that stands somewhere is marked in the world; Locate turns the view to it beside the windows.
        const placed = (await panelElements(client)).some(element => element.id === prefix + "locate");
        expect(placed, `A ${kind} change must offer Locate`).toBe(kind !== "settings");
        if (placed) {
          await click("locate");
          await frames(client, 5);
        }
        if (kind === "building") {
          await wait("handoff");
          await click("raise");
        }
        await shot(`pending-${kind}`, `Pending ${kind} change in the world and its review window`);
        if (kind === "building") {
          await screen.dismiss(client);
          await frames(client, 5);
          expect((await client.run(DRAWING_GHOSTS)).count).toBeGreaterThan(0);
          await camera.set(client, {
            position: { x: origin.x + 0.5, y: origin.y + 13, z: origin.z + 2.5 },
            yaw: 0, pitch: 65,
          });
          try {
            await shot("pending-building-world", "Railway ghost blocks before the pending decision");
          } finally {
            await camera.reset(client);
          }
          await input.key(client, { keybind: "key.folkways.assistant" });
          await wait("reject");
        }
        await click(kind === "residents" ? "confirm" : "reject");
        await ready();
        if (kind === "residents" || kind === "building") {
          const outcome = kind === "residents"
            ? catalog[prefix + "script_result"].replace("%1$s", "0").replace("%2$s", "1")
            : catalog[prefix + "script_rejected"];
          await showLine(outcome);
          await shot(kind === "residents" ? "applied" : "rejected", "Assistant confirmation result");
        }
      }
      await send("Check a broken script.", 'fail("Missing survey marker")');
      await expect.poll(async () => {
        await tick.sprint(server, 5);
        return contains(catalog[prefix + "script_error.eval_error"]);
      }, { timeout: 30_000 }).toBe(true);
      await ready();
      await showLine(catalog[prefix + "script_error.eval_error"]);
      await shot("script-error", "Assistant script error in the transcript");
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      rmSync(credential, { force: true });
      await pair?.teardown(testInfo);
      provider.closeAllConnections();
      await new Promise<void>(resolve => provider.close(() => resolve()));
    }
  });
}
