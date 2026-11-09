import { launchPair, type E2EPair } from "../shared/harness/pair";
import { expect, test } from "@playwright/test";
import { camera, defineTask, screen, type TaskTemplate } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { readFileSync } from "node:fs";
import { VisualRun } from "../shared/capture/visual-kit";
import { outPath, tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const TASK = defineTask<void, any>({
  name: "folkways.visual.bridge",
  side: "server",
  timeoutMs: 180_000,
  source: readFileSync("tests/pattern/galleries/bits-candb-bridge/BridgeGallery.java", "utf8"),
});
const CAMERA_SETUP = defineTask<void, unknown>({
  name: "folkways.visual.bridge-camera",
  side: "client",
  source: readFileSync("tests/pattern/galleries/bits-candb-bridge/CameraSetup.java", "utf8"),
});

test("bits stone arch bridge gallery", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("bits-candb-bridge", outPath("pattern", "takes"));
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#carving",
      server: { trace: tracePath("pattern", "bridge-server"),
        instance: seededOffThreadServer(`bridge-server-${runId}`), startFrozen: false },
      client: { trace: tracePath("pattern", "bridge-client"), instance: `bridge-client-${runId}` },
    });
    const { server, client } = pair;
    for (const command of [
      "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
      "gamerule randomTickSpeed 0", "time set 5000", "weather clear", "gamemode spectator @a",
      "tp @a 32 -40 32", "forceload add -32 -32 48 48", "fill -24 0 -24 32 0 40 minecraft:smooth_stone",
    ]) {
      await tryCommand(server, command);
    }
    await screen.dismiss(client);
    await client.run(CAMERA_SETUP, undefined);
    const value = await server.run(TASK, undefined);
    run.note("report", value);
    expect(value.ok).toBe(true);
    expect(value.partial).toBeGreaterThan(0);
    const views = [
      { name: "overview", position: { x: 16, y: 11, z: 25 }, target: { x: 1.5, y: 4, z: 10 } },
      { name: "side", position: { x: 22, y: 4, z: 10 }, target: { x: 1.5, y: 4, z: 10 } },
      { name: "intrados", position: { x: 6, y: 2, z: 4 }, target: { x: 1.5, y: 3, z: 4 } },
      { name: "deck", position: { x: 1.5, y: 7, z: -3 }, target: { x: 1.5, y: 5.7, z: 11 } },
    ];
    await new Promise((resolve) => setTimeout(resolve, 5000));
    for (const one of views) {
      const rotation = cameraLookingAt(one.position, one.target);
      await camera.set(client, { position: one.position, ...rotation });
      await frames(client, 30);
      await run.shot(client, one.name, {
        subject: `Stone arch bridge ${one.name}`, worldState: { pattern: "stone_arch_bridge" },
        camera: { position: one.position, ...rotation, fov: 60 },
      });
    }
    run.expectTaken(views.map((one) => one.name));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
