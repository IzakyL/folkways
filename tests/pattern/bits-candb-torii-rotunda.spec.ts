import { expect, test } from "@playwright/test";
import { camera, defineTask, screen } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { readFileSync } from "node:fs";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { VisualRun } from "../shared/capture/visual-kit";
import { outPath, tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const gallery = defineTask<void, any>({
  name: "folkways.visual.bits-torii-rotunda", side: "server", timeoutMs: 180_000,
  source: readFileSync("tests/pattern/galleries/bits-candb-torii-rotunda/BitsGallery.java", "utf8"),
});
const setup = defineTask<void, unknown>({
  name: "folkways.visual.bits-camera", side: "client", timeoutMs: 20_000,
  source: readFileSync("tests/pattern/galleries/arch-bridge/CameraSetup.java", "utf8"),
});
const v = (x: number, y: number, z: number) => ({ x, y, z });

test("bits gallery: torii and rotunda", async ({}, testInfo) => {
  test.setTimeout(600_000);
  const scope = "bits-candb-torii-rotunda";
  const run = new VisualRun(scope, outPath("pattern", "takes"));
  const id = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#carving",
      server: { trace: tracePath("pattern", `${scope}-server`),
        instance: seededOffThreadServer(`${scope}-server-${id}`), startFrozen: false },
      client: { trace: tracePath("pattern", `${scope}-client`), instance: `${scope}-client-${id}` },
    });
    const { server, client } = pair;
    for (const command of [
      "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
      "time set 5000", "weather clear", "gamemode spectator @a", "tp @a 12 15 24",
      "forceload add -32 -32 64 48", "fill -24 0 -24 60 0 36 minecraft:smooth_stone",
      "fill -24 1 -24 60 4 36 minecraft:air",
    ]) {
      await tryCommand(server, command);
    }
    await screen.dismiss(client);
    await client.run(setup, undefined);
    const report = await server.run(gallery, undefined);
    run.note("report", report);
    expect(report.ok).toBe(true);
    const views = [
      ["torii-overview", v(15, 10, -10), v(4.5, 3, 1.5)],
      ["torii-front", v(4.5, 4, -12), v(4.5, 3.2, 1.5)],
      ["torii-detail", v(9, 6.5, -3), v(7.5, 5.4, 1.5)],
      ["torii-upward", v(3, 2, -3), v(4.5, 5.2, 1.5)],
      ["rotunda-overview", v(39, 12, -9), v(28, 4.5, 4)],
      ["rotunda-front", v(28, 5, -10), v(28, 4.5, 4)],
      ["rotunda-detail", v(34, 6, -1), v(30, 5, 2)],
      ["rotunda-ribs", v(34, 10, -2), v(28, 7, 4)],
      ["rotunda-interior", v(28, 3.3, 4), v(28.1, 8.8, 4.1)],
    ] as const;
    for (const [name, position, target] of views) {
      const rotation = cameraLookingAt(position, target);
      await camera.set(client, { position, ...rotation });
      await frames(client, 60);
      await run.shot(client, name, { subject: name,
        camera: { position, ...rotation, fov: 55 }, worldState: { target } });
    }
    run.expectTaken(views.map(([name]) => name));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
