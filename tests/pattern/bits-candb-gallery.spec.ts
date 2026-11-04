import { launchPair, type E2EPair } from "../shared/harness/pair";
import { expect, test } from "@playwright/test";
import { camera, defineTask, screen } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { readFileSync } from "node:fs";
import { VisualRun } from "../shared/capture/visual-kit";
import { outPath, tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const CAMERA_SETUP = defineTask<void, unknown>({
  name: "folkways.visual.gallery-camera", side: "client", timeoutMs: 20_000,
  source: readFileSync("tests/pattern/galleries/arch-bridge/CameraSetup.java", "utf8"),
});
const SETTLE_MS = 5_000;
const PATTERN = "mods/chiselsandbits/src/main/resources/data/folkways_chiselsandbits/folkways/pattern/curved_gallery.star";
const gallery = {
  pattern: "bits-candb-gallery",
  task: defineTask<Record<string, any>, any>({
    name: "folkways.visual.bits-gallery", side: "server", timeoutMs: 180_000,
    source: readFileSync("tests/pattern/galleries/bits-candb-gallery/Gallery.java", "utf8"),
  }),
  args: () => ({ source: readFileSync(PATTERN, "utf8") }),
  stand: "0 16 18", forceload: "-32 -32 32 32",
  before: ["fill -24 -3 -24 24 -1 24 minecraft:dirt",
    "fill -24 0 -24 24 0 24 minecraft:grass_block"],
  check: (value: any) => {
    expect(value.partial).toBeGreaterThan(0);
    expect(value.whole).toBeGreaterThan(0);
  },
  views: (_value: any) => [
    { name: "overview", position: { x: 12, y: 11, z: 16 }, target: { x: -3, y: 3, z: 2 },
      subject: "Curved gallery with continuous roof and sloping stone paving", fov: 60 },
    { name: "front", position: { x: -7, y: 5, z: -16 }, target: { x: -7, y: 3, z: 1 },
      subject: "Entrance and perspective along the gallery", fov: 60 },
    { name: "eave-detail", position: { x: -1, y: 5.5, z: -4 }, target: { x: -6, y: 4.1, z: -2 },
      subject: "Planar verge, rolled crest and tapered spruce columns", fov: 60 },
    { name: "roof-tiles", position: { x: -2, y: 8.5, z: -1 }, target: { x: -7, y: 4.4, z: -1 },
      subject: "Geometric tile rolls run down both roof slopes", fov: 60 },
    { name: "inside", position: { x: -7, y: 2.5, z: -3 }, target: { x: -7, y: 5, z: 1 },
      subject: "Curved timber ties and knee braces from within", fov: 80 },
  ],
};
test(`pattern gallery: ${gallery.pattern}`, async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const scope = `pattern-${gallery.pattern.replace(/_/g, "-")}`;
  const run = new VisualRun(scope, outPath("pattern", "takes"));
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#chiselsandbits",
      server: {
        trace: tracePath("pattern", `${scope}-server`),
        instance: seededOffThreadServer(`${scope}-server-${runId}`),
        startFrozen: false,
      },
      client: { trace: tracePath("pattern", `${scope}-client`), instance: `${scope}-client-${runId}` },
    });
    const { server, client } = pair;
    for (const command of [
      "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
      "gamerule randomTickSpeed 0", "time set 5000", "weather clear", "gamemode spectator @a",
      `tp @a ${gallery.stand}`, `forceload add ${gallery.forceload}`, ...(gallery.before ?? []),
    ]) {
      await tryCommand(server, command);
    }
    await screen.dismiss(client);
    await client.run(CAMERA_SETUP, undefined);

    let value: any;
    try {
      value = await server.run(gallery.task, gallery.args());
    } catch (error) {
      run.note("report", { status: "error", error: String(error) });
      throw new Error(`${gallery.pattern}: gallery script failed ${String(error).slice(0, 2000)}`, { cause: error });
    }
    run.note("report", value);
    if (value && typeof value === "object" && "ok" in value) {
      expect(value.ok, `gallery report not ok ${JSON.stringify(value).slice(0, 2000)}`).toBe(true);
    }
    gallery.check?.(value);

    const views = gallery.views(value);
    await new Promise((resolve) => setTimeout(resolve, SETTLE_MS));
    for (const one of views) {
      const rotation = cameraLookingAt(one.position, one.target);
      await camera.set(client, { position: one.position, ...rotation });
      await frames(client, 30);
      await run.shot(client, one.name, {
        subject: one.subject,
        worldState: { pattern: gallery.pattern, target: one.target },
        camera: { position: one.position, ...rotation, fov: one.fov ?? 60 },
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
