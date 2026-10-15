import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { player, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, waitForElement } from "../ldlib2";
import { foundColonyFast, foundingBox, optInViaBook, pickViaSelector, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { deliverOrderViaSitePanel } from "../shared/colony/membership-optin";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, safeScreen, screenName } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Vec } from "../shared/harness/stage";

// No Cookery profile: the source and its lesson are useful even when that optional mod is absent.
for (const stage of twins()) test(stage.title("creative supplies: configure a real whitelist and make ordered bread from wheat"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: Record<string, unknown> = { stage: stage.kind, profile: stage.config };
  let target: Vec | undefined;
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("creative-supplies-server")), instance: seededServer(stage.name(
        "creative-supplies-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("creative-supplies-client")), instance: stage.name("creative-supplies-client-" + runId) },
      snapshot: () => ({ containers: target ? [stage.at(target)] : [], entityTypes: ["folkways:resident", "minecraft:item"] }),
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const name = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const box = foundingBox(origin);
    await stage.lay(server, box);
    await world.command(server, "fill " + commandPos(stage.at({ ...box.min, y: origin.y - 1 }))
      + " " + commandPos(stage.at({ ...box.max, y: origin.y - 1 })) + " minecraft:smooth_stone");
    evidence.founding = (await foundColonyFast(server, client, name, { origin, residentCount: 2, stage })).evidence;
    await world.command(server, "gamerule doDaylightCycle false");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "difficulty peaceful");
    await world.command(server, "time set day");
    const source = { x: origin.x - 6, y: origin.y, z: origin.z + 5 };
    const table = { x: origin.x - 3, y: origin.y, z: origin.z + 5 };
    target = { x: origin.x + 6, y: origin.y, z: origin.z + 5 };
    await world.command(server, "setblock " + commandPos(stage.at(source)) + " folkways:creative_supply_box");
    await world.command(server, "setblock " + commandPos(stage.at(table)) + " minecraft:crafting_table");
    await world.command(server, "setblock " + commandPos(stage.at(target)) + " minecraft:chest{Items:[]}");
    await world.command(server, "gamemode creative " + name);
    await screen.dismiss(client);
    await world.command(server, "clear " + name);
    const stand = stage.stand(source);
    await world.command(server, "tp " + name + " " + (stand.x + 0.5) + " " + stand.y + " " + (stand.z + 1.5) + " 180 30");
    await tick.sprint(server, 5);
    evidence.open = await player.useBlock(client, stage.at(source), { face: "south" });
    await waitForElement(client, { id: "folkways.supply.mode" });
    await clickElement(client, { id: "folkways.supply.mode" });
    evidence.pick = await pickViaSelector(client, "folkways.supply.whitelist", "minecraft:wheat", "*minecraft:wheat");
    await clickElement(client, { id: "folkways.supply.save" });
    await tick.sprint(server, 10);
    const configured = await world.block(server, stage.at(source), { nbt: true });
    expect(configured.loaded).toBe(true);
    const settings = configured.nbt as { all: number | boolean; whitelist: string[]; generated: Record<string, number> };
    expect(settings.all, "the UI really switches the server box out of all-items mode").toBeFalsy();
    expect(settings.whitelist, "the picker adds wheat, with no intermediate or finished foods").toEqual(["iminecraft:wheat"]);
    expect(settings.generated).toEqual({});
    evidence.configured = configured;

    // The source's own item link opens its independent lesson on a server without Cookery installed.
    await world.command(server, "ponder folkways:creative_supply_box " + name);
    await frames(client, 3);
    expect(screenName(await safeScreen(client))).toMatch(/PonderUI/);
    await screen.dismiss(client);
    await world.command(server, "give " + name + " folkways:colony_book");
    for (const cell of [source, table, target]) {
      await optInViaBook(server, client, name, cell, "worksite", stage);
    }
    await world.command(server, "gamemode survival " + name);
    evidence.order = await deliverOrderViaSitePanel(server, client, name, target, "minecraft:bread", 4, stage);
    await screen.dismiss(client);
    let bread = 0;
    for (let elapsed = 0; elapsed <= 4000; elapsed += 20) {
      bread = await countAt(server, stage.at(target), "minecraft:bread");
      if (bread >= 4) { evidence.elapsed = elapsed; break; }
      await tick.sprint(server, 20);
    }
    expect(bread, "residents must fetch wheat and actually craft the only ordered final food").toBeGreaterThanOrEqual(4);
    const final = await world.block(server, stage.at(source), { nbt: true });
    const counts = (final.nbt as { generated: Record<string, number> }).generated;
    expect(Object.keys(counts), "the source never supplies bread, tools or any unwhitelisted shortcut").toEqual(["minecraft:wheat"]);
    expect(Number(counts["minecraft:wheat"]), "four actual breads require twelve real wheat").toBe(12);
    evidence.final = { source: final, bread };
  } catch (error) {
    pair?.failing(error);
    evidence.failure = String(error);
    throw error;
  } finally {
    const file = testInfo.outputPath("creative-supplies-evidence.json");
    writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("creative-supplies-evidence", { path: file, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function countAt(server: MinecraftServer, at: Vec, item: string) {
  const box = await world.container(server, at, { nbt: true });
  expect(box.found).toBe(true);
  return ((box.nbt as { Items: Array<{ id: string; count: number }> }).Items)
    .filter(stack => stack.id === item).reduce((sum, stack) => sum + stack.count, 0);
}
