import { expect, test } from "@playwright/test";
import { camera, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { cinematicWorld, launchPromoPair, promoClip, sleepMs, take, type PromoPair } from "./promo-kit";
import { admitPromoSettlersFast, foundPromoColonyFast } from "./fast-setup";
import { AUDIT, COMMISSION, CREW, HEIGHT, SIMPLE_CONSTRUCTION, STOCK_ALL, STOCK_ITEMS } from "./candb-kit";
import { HERO, type Vec } from "./candb-cases";

function pose(position: Vec, target: Vec, fov = 60) {
  return { position, ...cameraLookingAt(position, target), fov };
}

test("C&B resident construction", async ({}, testInfo) => {
  test.setTimeout(90 * 60_000);
  const scope = "candb";
  const run = take(scope);
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair(scope, { config: "blockwright.toml#promo-candb" });
    const { server, client } = pair;
    const player = (await waitForGroundedPlayer(server)).name;
    await world.command(server, `op ${player}`);
    await cinematicWorld(server);
    for (const command of [
      "gamerule commandModificationBlockLimit 200000", "gamerule doDaylightCycle false",
      "time set 5000", "weather clear",
      `gamemode spectator ${player}`, "forceload add -32 -32 80 64"]) {
      await world.command(server, command);
    }
    const setup = await runColonyTask(server, HEIGHT, { depth: 4 });
    await world.command(server, `tp ${player} -4 64 0`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways_core:colony_book`);
    const { colony_id } = await foundPromoColonyFast(server, player, setup.members);
    const workers = Number(process.env.FOLKWAYS_CANDB_WORKERS ?? 1);
    const admission = await admitPromoSettlersFast(server, player, colony_id, workers);
    run.note("admission", admission);
    expect(admission.residents, "pending admissions must finish before the crew is outfitted").toBe(workers);
    run.note("crew", await runColonyTask(server, CREW, { colony_id, y: 64, stageX: 0 }));
    const chests = Array.from({ length: 12 }, (_, i) => ({ x: -15 + i, y: 64, z: -10 }));
    for (const at of chests) {
      await world.command(server, `setblock ${at.x} ${at.y} ${at.z} minecraft:chest`);
    }
    await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: chests.flatMap(p => [p.x, p.y, p.z]) });
    await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
    await screen.dismiss(client);
    await camera.hud(client, false);
    const simpleConstruction = process.env.FOLKWAYS_CANDB_SIMPLE === "1";
    if (simpleConstruction) {
      run.note("constructionWorkaround", await server.run(SIMPLE_CONSTRUCTION, undefined));
    }
    run.note("configuration", { profile: "promo-candb", tickRate: 160, residents: workers,
      simpleConstruction, drawSeconds: 30, productionVoxelLimits: true });
    run.note("commission", await runColonyTask(server, COMMISSION, { colony_id, sites: [HERO] }));
    await tick.sprint(server, 2);
    const args = { colony_id, chests: [...chests, { x: -6, y: 64, z: 0 }, { x: -8, y: 64, z: 0 }] };
    let initial = await runColonyTask(server, AUDIT, args);
    for (let round = 0; round < 120 && (initial.expected === 0 || initial.growing > 0); round++) {
      await tick.sprint(server, 5);
      initial = await runColonyTask(server, AUDIT, args);
    }
    expect(initial.expected, "the real commission produces a blueprint").toBeGreaterThan(0);
    run.note("initial", initial);
    const stocked = await runColonyTask(server, STOCK_ALL, args);
    run.note("stocked", stocked);
    expect(stocked.unstocked, "the stores contain every material owed by the C&B commission").toEqual({});
    await runColonyTask(server, STOCK_ITEMS, { chests, items: {
      "minecraft:scaffolding": 512, "minecraft:iron_pickaxe": 12, "minecraft:iron_axe": 6,
      "minecraft:iron_shovel": 6, "minecraft:bread": 256,
    } });
    const before = await runColonyTask(server, AUDIT, args);
    run.note("before", before);
    const heroCamera = pose({ x: 24, y: 76, z: 35 }, { x: 8, y: 68, z: 14 }, 55);
    await camera.fov(client, 55);
    await world.command(server, `tp ${player} 24 76 35`);
    await world.command(server, "tick rate 160");
    const samples: Record<string, any>[] = [];
    let final = initial;
    const started = Date.now();
    await promoClip(run, client, server, "01-construction", {
      subject: "Residents construct a flying-eave pavilion from a real commission; accelerated simulation",
      worldState: { HERO, initial }, camera: heroCamera, seconds: 0, fps: 30,
      during: async () => {
        let stagnant = 0;
        let lastCorrect = initial.correct;
        for (let round = 0; round < 240; round++) {
          await sleepMs(5_000);
          final = await runColonyTask(server, AUDIT, args);
          samples.push({ ...final, elapsedMs: Date.now() - started });
          run.note("construction", { elapsedMs: Date.now() - started, samples, final });
          stagnant = final.correct === lastCorrect ? stagnant + 1 : 0;
          lastCorrect = final.correct;
          // Retain the samples and clip if real construction stops progressing.
          if (stagnant >= 12 && final.correct < final.expected) {
            throw new Error("Resident construction made no progress for 60 seconds; see construction samples");
          }
          if (final.expected > 0 && final.correct === final.expected && final.orders === 0 && final.growing === 0
            && final.sites.length === 0) {
            await sleepMs(3_000);
            break;
          }
        }
      },
    });
    await world.command(server, "tick rate 20");
    run.note("finished", final);
    await run.shot(client, "construction-finished", {
      subject: "Resident-built pavilion, including exact voxel payload audit", worldState: final, camera: heroCamera,
    });
    expect.soft(final.correct, "resident construction must match every cell and voxel").toBe(final.expected);
    expect.soft(final.orders, "resident build order retires").toBe(0);
    expect.soft(final.scaffolds, "temporary scaffolding is recovered").toBe(0);
    expect.soft(final.dropped, "construction leaves no dropped items").toEqual({});
    // AUDIT.stock includes both stores and residents' packs.
    const consumed = Object.fromEntries(Object.keys(final.owed).map(item => [item,
      (initial.stock[item] ?? 0) + (stocked.stocked[item] ?? 0) - (final.stock[item] ?? 0)]));
    const result = { repeat: testInfo.repeatEachIndex + 1, elapsedMs: samples.at(-1)?.elapsedMs,
      correct: final.correct, expected: final.expected, consumed,
      scaffolds: final.scaffolds, dropped: final.dropped };
    run.note("result", result);
    console.log("CANDB_RESULT", JSON.stringify(result));
    run.expectTaken(["01-construction"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
