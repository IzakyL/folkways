import { launchPair, type E2EPair } from "../shared/harness/pair";
import { expect, test } from "@playwright/test";
import { camera, defineTask, screen, type TaskTemplate } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { readFileSync } from "node:fs";
import { VisualRun } from "../shared/capture/visual-kit";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const TASK = defineTask<void, any>({
  name: "folkways.visual.gothic",
  side: "server",
  timeoutMs: 180_000,
  source: readFileSync("tests/pattern/galleries/bits-candb-gothic/GothicGallery.java", "utf8"),
});
const CAMERA_SETUP = defineTask<void, unknown>({
  name: "folkways.visual.gothic-camera",
  side: "client",
  source: readFileSync("tests/pattern/galleries/arch-bridge/CameraSetup.java", "utf8"),
});

test("bits gothic cloister gallery", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun(process.env.GOTHIC_ROUND ?? "round-1", "tests/out/pattern/gothic-fix");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#chiselsandbits",
      server: { trace: tracePath("pattern", "gothic-server"),
        instance: seededOffThreadServer(`gothic-server-${runId}`), startFrozen: false },
      client: { trace: tracePath("pattern", "gothic-client"), instance: `gothic-client-${runId}` },
    });
    const { server, client } = pair;
    for (const command of [
      "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
      "gamerule randomTickSpeed 0", "time set 5000", "weather clear", "gamemode spectator @a",
      "tp @a 32 -40 32", "forceload add -32 -32 80 80", "fill -10 0 -10 58 0 20 minecraft:smooth_stone",
      "fill -10 0 21 58 0 64 minecraft:smooth_stone",
    ]) {
      await tryCommand(server, command);
    }
    await screen.dismiss(client);
    await client.run(CAMERA_SETUP, undefined);
    const value = await server.run(TASK, undefined);
    run.note("report", value);
    expect(value.ok).toBe(true);
    expect(value.scenes).toHaveLength(4);
    const views = [
      { name: "north-south", position: { x: 23, y: 17, z: 27 }, target: { x: 0, y: 6, z: 9 } },
      { name: "east-west", position: { x: 53, y: 17, z: 22 }, target: { x: 37, y: 6, z: 0 } },
      { name: "diagonal-turn", position: { x: 33, y: 23, z: 63 }, target: { x: 12, y: 6, z: 42 } },
      { name: "rising", position: { x: 63, y: 20, z: 62 }, target: { x: 40, y: 7, z: 42 } },
      { name: "end", position: { x: 0, y: 6, z: -12 }, target: { x: 0, y: 6, z: 8 } },
      { name: "column", position: { x: 8, y: 4, z: -4 }, target: { x: 3, y: 3, z: 0 } },
    ];
    await new Promise((resolve) => setTimeout(resolve, 5000));
    for (const one of views) {
      const rotation = cameraLookingAt(one.position, one.target);
      await camera.set(client, { position: one.position, ...rotation });
      await frames(client, 30);
      await run.shot(client, one.name, {
        subject: `Gothic cloister ${one.name}`, worldState: { pattern: "gothic_cloister" },
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
