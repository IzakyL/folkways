import { expect, test } from "@playwright/test";
import { camera, defineTask, screen } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { readFileSync, writeFileSync } from "node:fs";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { VisualRun } from "../shared/capture/visual-kit";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const build = defineTask<Record<string, unknown>, any>({
  name: "folkways.visual.grain", side: "server", timeoutMs: 180_000,
  source: readFileSync("tests/pattern/galleries/bits-candb-grain/GrainGallery.java", "utf8"),
});
const setup = defineTask<void, unknown>({
  name: "folkways.visual.grain-camera", side: "client",
  source: readFileSync("tests/pattern/galleries/arch-bridge/CameraSetup.java", "utf8"),
});
const v = (x: number, y: number, z: number) => ({ x, y, z });
const views = {
  pavilion: [["overview", v(15, 13, 17), v(0, 5, 0)], ["detail", v(9, 9, 9), v(4, 7, 4)]],
  gothic_cloister: [["overview", v(22, 16, 27), v(3, 6, 8)], ["detail", v(-5, 6, 3), v(0.5, 6, 3)]],
  stone_arch_bridge: [["overview", v(16, 11, 25), v(1.5, 4, 10)], ["detail", v(10, 5, 6), v(1.5, 4, 6)]],
  rotunda: [["overview", v(15, 12, -9), v(4, 4.5, 4)], ["detail", v(10, 10, -2), v(4, 7, 4)]],
  rampart: [["overview", v(24, 18, 35), v(2, 5, 12)], ["detail", v(14, 5, 12), v(2, 3, 12)]],
  curved_gallery: [["overview", v(12, 11, 16), v(-3, 3, 2)], ["detail", v(-2, 8.5, -1), v(-7, 4.4, -1)]],
  torii: [["overview", v(15, 10, -10), v(4.5, 3, 1.5)], ["detail", v(9, 6.5, -3), v(7.5, 5.4, 1.5)]],
} as const;

test("bundled bits patterns compare geometry grain", async ({}, testInfo) => {
  test.setTimeout(1_200_000);
  const final = process.env.CANDB_FINAL === "1";
  const directory = final ? "final" : "grain";
  const run = new VisualRun(directory, "tests/out/pattern");
  const id = newRunId();
  let pair: E2EPair | undefined;
  const report: Record<string, unknown> = {};
  const expected: string[] = [];
  try {
    pair = await launchPair({
      config: "blockwright.toml#carving",
      server: { instance: seededOffThreadServer(`grain-server-${id}`), startFrozen: false },
      client: { instance: `grain-client-${id}` },
    });
    const { server, client } = pair;
    for (const command of [
      "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
      "gamerule randomTickSpeed 0", "time set 5000", "weather clear", "gamemode spectator @a",
      "tp @a 0 18 30", "forceload add -32 -32 48 48", "fill -24 0 -24 40 0 40 minecraft:smooth_stone",
    ]) {
      await tryCommand(server, command);
    }
    await screen.dismiss(client);
    await client.run(setup, undefined);
    for (const [name, cameras] of Object.entries(views)) {
      const original = readFileSync(
        `mods/carving/src/main/resources/data/folkways_carving/folkways/pattern/${name}.star`, "utf8");
      for (const requested of final ? [undefined] : [16, 8, 4]) {
        const source = requested === undefined ? original : original.replace(/^grain\s*=.*\n/gm, "")
          .replace(/^(detail\s*=.*)$/m, `$1\ngrain = ${requested}`);
        const value = await server.run(build, { name, source });
        expect(value.ok).toBe(true);
        if (requested !== undefined) {
          expect(value.grain).toBe(requested);
        }
        const grain = value.grain;
        report[`${name}-g${grain}`] = value;
        run.note(`${name}-g${grain}`, value);
        await new Promise((resolve) => setTimeout(resolve, 1500));
        for (const [view, position, target] of cameras) {
          const rotation = cameraLookingAt(position, target);
          await camera.set(client, { position, ...rotation });
          await frames(client, 30);
          const file = `${name}-${view}-g${grain}`;
          expected.push(file);
          await run.shot(client, file, { subject: `${name} ${view} grain ${grain}`,
            worldState: { name, grain, target }, camera: { position, ...rotation, fov: 60 } });
        }
      }
    }
    writeFileSync(`tests/out/pattern/${directory}/report.json`, JSON.stringify(report, null, 2));
    run.expectTaken(expected);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
