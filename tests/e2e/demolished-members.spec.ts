import { expect, test } from "@playwright/test";
import { appendFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, media, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { createColonyViaBook, foundingBox, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { commandPos, frames, tryCommand } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { instanceConfigDir, seededServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Vec } from "../shared/harness/stage";

const HOLDINGS = defineTask<{ cells: Vec[] }, { held: boolean[] }>({
  name: "folkways.demolished-members.read-ledger", side: "server",
  source: javaTask({ imports: ["net.minecraft.core.BlockPos", "io.github.izakyl.folkways.core.api.colony.Colonies",
    "io.github.izakyl.folkways.core.api.terms.WorldPos"], body: `
var level=ctx.server().overworld();var held=new ArrayList<Boolean>();
for(var raw:(List<?>)Args.of(ctx).value("cells")){
 var cell=(Map<?,?>)raw;
 var at=WorldPos.of(level,new BlockPos(((Number)cell.get("x")).intValue(),((Number)cell.get("y")).intValue(),((Number)cell.get("z")).intValue()));
 held.add(Colonies.all(ctx.server()).stream().anyMatch(colony->colony.holdingAt(at).isPresent()));
}
return Map.of("held",held);
` }),
});

const OUTLINES = defineTask<void, Vec[]>({
  name: "folkways.demolished-members.read-client-outlines", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.client.ColonyHighlightState"], body: `
return ColonyHighlightState.members().stream().map(cell->Map.of("x",cell.getX(),"y",cell.getY(),"z",cell.getZ())).toList();
` }),
});

for (const stage of twins()) test(stage.title("demolished members leave the ledger and world outlines before the diagnostic heartbeat"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  const run = newRunId(); let pair: E2EPair | undefined;
  const evidence: Record<string, any> = { stage: stage.kind };
  try {
    const instance = seededServer(stage.name("demolished-members-server-" + run));
    // Reconciliation must follow actual block changes, independently of diagnostic log cadence.
    appendFileSync(path.join(instanceConfigDir(instance), "folkways_core-server.toml"), "\n[diagnostics]\nheartbeatCycles = 600\n");
    pair = await launchPair({
      config: "blockwright.toml#" + (stage.kind === "ground" ? "cookery" : "cookery-sable"),
      server: { trace: tracePath("e2e", stage.name("demolished-members-server-" + run)), instance },
      client: { trace: tracePath("e2e", stage.name("demolished-members-client-" + run)), instance: stage.name("demolished-members-client-" + run) },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const box = foundingBox(origin); await stage.lay(server, box);
    const at = (cell: Vec) => commandPos(stage.at(cell));
    await tryCommand(server, "fill " + at(box.min) + " " + at(box.max) + " minecraft:air");
    await world.command(server, "fill " + at({ ...box.min, y: origin.y - 1 }) + " " + at({ ...box.max, y: origin.y - 1 }) + " minecraft:smooth_stone");
    await world.command(server, "setblock " + at(origin) + " minecraft:smooth_stone");
    await world.command(server, "gamemode survival " + player.name);
    await world.command(server, "item replace entity " + player.name + " weapon.mainhand with folkways:colony_book");
    // No resident is recruited: demolition bookkeeping cannot depend on an active workforce.
    evidence.founding = await createColonyViaBook(server, client, player.name, origin, stage);
    const barrel = { x: origin.x + 3, y: origin.y, z: origin.z + 3 };
    const composter = { x: origin.x + 6, y: origin.y, z: origin.z + 3 };
    const neighbour = { x: origin.x + 3, y: origin.y, z: origin.z + 6 };
    const cells = [barrel, composter, neighbour];
    for (const [cell, block] of [[barrel, "minecraft:barrel"], [composter, "minecraft:composter"], [neighbour, "minecraft:barrel"]] as const) {
      await world.command(server, "setblock " + at(cell) + " " + block);
      expect((await optInViaBook(server, client, player.name, cell, block.endsWith(":barrel") ? "chest" : "worksite", stage)).member).toBe(true);
    }
    await screen.dismiss(client);
    const ledger = async () => (await runColonyTask(server, HOLDINGS, { cells: cells.map(cell => stage.at(cell)) })).held;
    const outlines = () => shown(server, client, cells.map(cell => stage.at(cell)));
    expect(await ledger()).toEqual([true, true, true]);
    await expect.poll(outlines, { timeout: 10_000 }).toEqual([true, true, true]);

    await world.command(server, "setblock " + at(composter) + " minecraft:composter[level=1]");
    await tick.sprint(server, 1);
    expect(await ledger(), "a legitimate native point state update keeps its membership").toEqual([true, true, true]);

    await world.command(server, "setblock " + at(barrel) + " minecraft:air destroy");
    await tick.sprint(server, 1);
    evidence.afterBarrel = await ledger();
    expect(evidence.afterBarrel, "a removed block entity must leave the actual ledger on the next tick").toEqual([false, true, true]);
    await expect.poll(outlines, { timeout: 10_000, message: "the server's next overview removes the blue box over air" }).toEqual([false, true, true]);

    await world.command(server, "setblock " + at(composter) + " minecraft:air destroy");
    await tick.sprint(server, 1);
    evidence.afterComposter = await ledger();
    expect(evidence.afterComposter, "a removed native point follows the same automatic lifecycle").toEqual([false, false, true]);
    await expect.poll(outlines, { timeout: 10_000 }).toEqual([false, false, true]);

    await world.command(server, "setblock " + at(barrel) + " minecraft:barrel");
    await world.command(server, "setblock " + at(composter) + " minecraft:composter");
    await tick.sprint(server, 2);
    expect(await ledger(), "new blocks at the old addresses do not inherit demolished membership").toEqual([false, false, true]);
    await expect.poll(outlines, { timeout: 10_000 }).toEqual([false, false, true]);
    expect((await optInViaBook(server, client, player.name, composter, "worksite", stage)).member,
      "the released position can be explicitly enrolled again").toBe(true);
    evidence.finalLedger = await ledger();
    expect(evidence.finalLedger).toEqual([false, true, true]);
    await screen.dismiss(client);
    await media.screenshot(client, { path: testInfo.outputPath("demolished-members.png") });
  } catch (error) { pair?.failing(error); throw error; }
  finally {
    await testInfo.attach("demolished-members-evidence.json", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function shown(server: MinecraftServer, client: MinecraftClient, cells: Vec[]) {
  // The overview cooldown follows client ticks, not server sprint ticks. Poll the real client while
  // allowing server processing, well before the fixture's twelve-thousand-tick diagnostic heartbeat.
  await tick.sprint(server, 2); await frames(client, 3);
  const members = await client.run(OUTLINES, undefined);
  return cells.map(cell => members.some(member => member.x === cell.x && member.y === cell.y && member.z === cell.z));
}
