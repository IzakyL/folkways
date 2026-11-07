import { readFileSync } from "node:fs";
import { tracePath } from "../shared/harness/out-paths";
import { expect, test } from "@playwright/test";
import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, safeScreen, screenName, tryCommand } from "../shared/harness/bw-helpers";
import { foundPromoColonyFast } from "./fast-setup";
import { CURSOR, holdKey, KEY, TOPS, View, type Cell, type Vec } from "./overhead-view";
import { cinematicWorld, launchPromoPair, promoClip, sleepMs, take, type PromoPair } from "./promo-kit";
import { shaderEvidence } from "../shared/capture/shaders";

import { SHOTS } from "./candb-cases";
import { CANDB_SERVER, CANDB_CLIENT } from "./candb-sync";

const LAND_CONFIG = "blockwright.toml#promo-candb-land";
process.env.FOLKWAYS_PROMO_SHADERS = "0";

for (const shot of SHOTS) {
  test(`${shot.pattern}: ${shot.drawings.length} mouse placements in one fixed view`, async ({}, testInfo) => {
    test.setTimeout(BUDGET.promo);
    const scope = `candb-${shot.pattern}`;
    const run = take(scope);
    let pair: PromoPair | undefined;
    try {
      pair = await launchPromoPair(scope, { config: LAND_CONFIG });
      const { server, client, clientInstance } = pair;
      const player = (await waitForGroundedPlayer(server)).name;
      await tryCommand(server, `op ${player}`);
      await cinematicWorld(server);
      await world.command(server, "time set 6500");

      // The player stands behind the camera, under the bottom edge of the picture, with the chest the colony is
      // founded on beside them.
      const { centre, yaw, off, pitch } = shot.camera;
      const heading = yaw * Math.PI / 180, back = Math.cos(pitch * Math.PI / 180) * off + 10;
      const stand = {
        x: Math.round(centre.x + Math.sin(heading) * back), z: Math.round(centre.z - Math.cos(heading) * back),
      };
      const chest = { x: stand.x + 2, z: stand.z };
      const sites = [...shot.drawings.flatMap(drawing => drawing.cells), ...(shot.fell ?? [])];
      const cells = [stand, chest, ...sites.map(([x, z]) => ({ x, z }))];
      // In creative the book raises a pattern at once, and the player comes to no harm on the way down to the ground.
      await world.command(server, `gamemode creative ${player}`);
      await world.command(server, `tp ${player} ${stand.x + 0.5} 200 ${stand.z + 0.5} ${yaw} 14`);
      await tick.sprint(server, 20);
      const surveyed = await runColonyTask(server, TOPS, { cells });
      const tops = new Map(cells.map((cell, index) => [
        `${cell.x},${cell.z}`, Number((surveyed.tops as number[])[index]),
      ]));
      const top = (cell: { x: number; z: number }) => tops.get(`${cell.x},${cell.z}`)!;
      const ground = ([x, z]: Cell): Vec => ({ x: x + 0.5, y: top({ x, z }) + 1, z: z + 0.5 });
      run.note("ground", { stand, felled: surveyed.felled });

      await world.command(server, `setblock ${chest.x} ${top(chest) + 1} ${chest.z} minecraft:chest`);
      await world.command(server, `tp ${player} ${stand.x + 0.5} ${top(stand) + 1} ${stand.z + 0.5} ${yaw} 14`);
      await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
      const founded = await foundPromoColonyFast(server, player, [{ x: chest.x, y: top(chest) + 1, z: chest.z }]);
      const colony_id = founded.colony_id;
      await tryCommand(server, "kill @e[type=minecraft:item]");
      await tick.sprint(server, 100);

      await safeAction(() => screen.dismiss(client));
      await client.run(CURSOR, {});
      await safeAction(() => frames(client, 30));
      await sleepMs(3_000);

      // The view is brought to where it stays before the film runs, with the game's time running for its keys.
      const view = new View(client, ground);
      await tick.freeze(server, false);
      await holdKey(client, KEY.V);
      await sleepMs(1_500);
      const open = screenName(await safeScreen(client));
      if (!open.includes("OverheadScreen")) {
        throw new Error(`the view from above did not open (${open})`);
      }
      await view.goTo(centre, yaw, off, pitch);
      await view.goTo(centre, yaw, off, pitch);
      await view.park();
      const spots = shot.drawings.flatMap(drawing => drawing.cells.map(ground));
      const pose = await view.pose(spots);
      run.note("pose", pose);
      const outside = spots.filter((_, index) => {
        const at = pose.at[index];
        return !(at.x > 12 && at.x < pose.width - 12 && at.y > 44 && at.y < pose.height - 12);
      });
      if (outside.length) {
        throw new Error(`the picture does not hold ${JSON.stringify(outside)} from ${JSON.stringify(pose)}`);
      }
      await tick.freeze(server, true);
      await sleepMs(2_000);

      const stamps: Array<{ start: number; drawn: number; raised: number; end: number }> = [];
      const said: any[] = [];
      let started = 0;
      let previousCells = 0;
      const now = () => Math.round((Date.now() - started) / 100) / 10;
      let gates: any;
      try {
        gates = await promoClip(run, client, server, shot.pattern, {
          subject: `From above, in creative, the camera still: ${shot.drawings.length} drawings handed to the `
            + `${shot.pattern} pattern with other settings each, and each raised at once`,
          worldState: { colony_id, pattern: shot.pattern, camera: shot.camera, drawings: shot.drawings },
          seconds: 1,
          during: async () => {
            started = Date.now();
            await sleepMs(800);
            for (const [index, drawing] of shot.drawings.entries()) {
              const start = now();
              let drawn = start;
              said.push(await view.drawn(shot.pattern, drawing, 0.6, true, async () => {
                drawn = now();
                await run.shot(client, `${shot.pattern}-${index + 1}-drawing`, {
                  subject: "Mouse-drawn selection before preview", worldState: { drawing },
                });
              }));
              await sleepMs(1_500);
              const printed = () => readFileSync(tracePath("promo", `${scope}-server`), "utf8")
                .split("\n").filter(line => line.includes("CREATIVE_PRINT cells="));
              await expect.poll(() => printed().length, { timeout: 90_000, intervals: [500, 1_000] })
                .toBe(index + 1);
              run.note("printing", printed().map(line => JSON.parse(line).data.line));
              const coordinates = shot.drawings.flatMap(one => one.cells);
              const bounds = {
                minX: Math.min(...coordinates.map(one => one[0])) - 8,
                maxX: Math.max(...coordinates.map(one => one[0])) + 8,
                minZ: Math.min(...coordinates.map(one => one[1])) - 8,
                maxZ: Math.max(...coordinates.map(one => one[1])) + 8,
              };
              let serverVoxels: Record<string, any> = {};
              await expect.poll(async () => {
                serverVoxels = await server.run(CANDB_SERVER, bounds);
                return Number(serverVoxels.count);
              }, { timeout: 90_000, intervals: [500, 1_000] }).toBeGreaterThan(previousCells);
              let clientVoxels: Record<string, any> = {};
              await expect.poll(async () => {
                clientVoxels = await client.run(CANDB_CLIENT, bounds);
                return JSON.stringify(clientVoxels.cells);
              }, { timeout: 20_000, intervals: [500] }).toBe(JSON.stringify(serverVoxels.cells));
              previousCells = Number(serverVoxels.count);
              await sleepMs(700);
              run.note(`sync-${index + 1}`, { server: serverVoxels, client: clientVoxels });
              expect(clientVoxels.cells, "live client voxels match the server after raiseNow")
                .toEqual(serverVoxels.cells);
              const raised = now();
              await run.shot(client, `${shot.pattern}-${index + 1}-raised`, {
                subject: shot.pattern, worldState: { drawing },
              });
              // A look at what was raised, the mouse out of its way.
              await view.park();
              const last = index === shot.drawings.length - 1;
              await sleepMs(last ? 3_500 : 1_600);
              stamps.push({ start, drawn, raised, end: now() });
            }
          },
        });
      } finally {
        run.note("shot", { name: shot.pattern, ...gates, drafts: stamps, said });
      }
      await holdKey(client, KEY.V);
      run.note("shaders", shaderEvidence(clientInstance));
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
    }
  });
}
