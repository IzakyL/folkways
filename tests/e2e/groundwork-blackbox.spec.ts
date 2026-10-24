import { expect, test } from "@playwright/test";
import { tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import {
  foundColonyFast, foundingBox, probe, stockToolsForResidents, TRADE_TOOLS, waitForGroundedPlayer, type Vec,
} from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import {
  applyZoneSetting, createMarkedZone, holdZoneAndOpen, releaseHeldZone, setBookGesture, zoneSettingToken,
} from "../shared/ui/zone-tools";
import { aimAndWorldLeftClick, blockHasItem } from "../shared/ui/world-ui";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND, twins, type Stage } from "../shared/harness/stage";

// Issue #10: groundwork zones, each doing every work ticked in its settings. A boulder is cleared down to the ground
// and no further, a pit is filled with the block the zone names, grass is weeded, a tree is felled and a beam beside it
// is not, and a stack lying loose is carried off to the colony's stores. Two zones tick more than one: the wood is
// felled and weeded, and a patch cleared and filled together is dug out and laid with dirt, the dirt in it kept.

const RESIDENT_COUNT = 6;
const WORK_TICKS = 9_000;
const STEP_TICKS = 200;

let stage: Stage = GROUND;

function at(cell: Vec): string {
  return commandPos(stage.at(cell));
}

for (const on of twins()) test(on.title("groundwork end-to-end: clear, fill, weed, fell and haul"), async ({}, testInfo) => {
  stage = on;
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("groundwork-server")), instance: seededServer(stage.name(`groundwork-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("groundwork-client")), instance: stage.name(`groundwork-client-${runId}`) },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await layStage(server, player, origin);
    const founded = await foundColonyFast(server, client, player, { origin, residentCount: RESIDENT_COUNT, stage });
    const store = founded.foodChest;

    const ground = origin.y - 1;
    const cell = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y, z: origin.z + z });

    // A stone boulder two tall on the ground; the ground under it is part of nothing and must stay.
    const boulder = { min: cell(4, origin.y, 6), max: cell(5, origin.y + 1, 7) };
    const underBoulder = cell(4, ground, 6);
    await tryCommand(server, `setblock ${at(underBoulder)} minecraft:smooth_stone`);
    const pit = { min: cell(8, ground, 6), max: cell(9, ground, 7) };
    const weeds = [cell(-4, origin.y, 6), cell(-3, origin.y, 6), cell(-2, origin.y, 6)];
    const trunk = cell(-6, origin.y, 11);
    const beam = cell(-8, origin.y, 11);
    const woods = { min: cell(-8, ground, 10), max: cell(-5, origin.y, 12) };
    const yard = { min: cell(1, ground, 10), max: cell(3, origin.y, 12) };
    const woodsWeed = cell(-7, origin.y, 12);
    const patch = { min: cell(11, ground, 6), max: cell(12, ground, 7) };
    const patchDirt = cell(11, ground, 6);
    await tick.sprint(server, 5);

    for (const weed of weeds) {
      await tryCommand(server, `setblock ${at(weed)} minecraft:short_grass`);
    }
    // A tree, and beside it, in the same box, a beam of two logs with no leaves of its own.
    await tryCommand(server, `fill ${at(trunk)} ${at({ ...trunk, y: trunk.y + 3 })} minecraft:oak_log`);
    await tryCommand(server, `fill ${at({ x: trunk.x - 1, y: trunk.y + 2, z: trunk.z - 1 })} ${at({ x: trunk.x + 1, y: trunk.y + 4, z: trunk.z + 1 })} minecraft:oak_leaves[persistent=false] keep`);
    await tryCommand(server, `fill ${at(beam)} ${at({ ...beam, y: beam.y + 1 })} minecraft:oak_log`);
    await tryCommand(server, `setblock ${at(woodsWeed)} minecraft:short_grass`);
    await tryCommand(server, `fill ${at(patch.min)} ${at(patch.max)} minecraft:cobblestone`);
    await tryCommand(server, `setblock ${at(patchDirt)} minecraft:dirt`);
    const dropped = await tryCommand(server, `summon minecraft:item ${at(cell(2, origin.y, 11))} {Item:{id:"minecraft:emerald",count:5},PickupDelay:32767s,Age:-32768s}`);
    expect(dropped.status === "ok" && dropped.success, `the stack to haul lies in the yard: ${JSON.stringify(dropped)}`).toBe(true);
    await tick.sprint(server, 2);
    expect(await probe(server, `if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:emerald"}}]`), "the stack lies there before anyone hauls it").toBe(true);

    // Groundwork discovers its targets once. Prepare each scene before confirming its zone, so UI ticks
    // cannot capture an unfinished fixture or finish and erase an empty zone between setting edits.
    await setBookGesture(server, client, player, "box");
    await tryCommand(server, `setblock ${at(boulder.min)} minecraft:stone`);
    await tryCommand(server, `setblock ${at(boulder.max)} minecraft:stone`);
    await mark(server, client, player, boulder.min, boulder.max);
    await tryCommand(server, `fill ${at(boulder.min)} ${at(boulder.max)} minecraft:stone`);
    await createMarkedZone(server, client, player, founded.standBlock, "ground", stage);
    // Keep stone awaiting a pickaxe while its live zone is configured. Enable fill before clearing is
    // disabled, and excavate before that last setting discovers the final fill targets.
    await tryCommand(server, `fill ${at(pit.min)} ${at(pit.max)} minecraft:stone`);
    await mark(server, client, player, pit.min, pit.max);
    await createMarkedZone(server, client, player, founded.standBlock, "ground", stage);
    await configureFillZone(server, client, player, founded.standBlock, pit);
    const colony = founded.evidence.colonyId;
    await draw(server, colony, ["weed"], { min: cell(-4, ground, 6), max: weeds[2] });
    await draw(server, colony, ["fell", "weed"], woods);
    await draw(server, colony, ["haul"], yard);
    await draw(server, colony, ["clear", "fill"], patch);
    const filling = await runColonyTask(server, FILL_BLOCKS, { colony_id: colony });
    expect(filling.blocks, `the pit fills with what was picked, the patch with the dirt it was drawn with: ${JSON.stringify(filling)}`)
      .toEqual(["minecraft:cobblestone", "minecraft:dirt"]);

    // Supply work only after all zones have captured the prepared scene and their settings were checked.
    await stockToolsForResidents(server, stage.at(store), [...TRADE_TOOLS.building, ...TRADE_TOOLS.farming], 2, 4);
    await tryCommand(server, `item replace block ${at(store)} container.20 with minecraft:dirt 16`);
    await tryCommand(server, `item replace block ${at(store)} container.21 with minecraft:cobblestone 16`);

    const done = {
      cleared: () => allAre(server, boxCells(boulder), "minecraft:air"),
      groundKept: () => probe(server, `if block ${at(underBoulder)} minecraft:smooth_stone`),
      filled: () => allAre(server, boxCells(pit), "minecraft:cobblestone"),
      weeded: () => allAre(server, [...weeds, woodsWeed], "minecraft:air"),
      replaced: () => allAre(server, boxCells(patch), "minecraft:dirt"),
      felled: () => probe(server, `if block ${at(trunk)} minecraft:air`),
      hauled: () => probe(server, `unless entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:emerald"}}]`),
      stored: () => blockHasItem(server, stage.at(store), "minecraft:emerald"),
    };
    const seen: Record<string, boolean> = {};
    for (let spent = 0; spent < WORK_TICKS; spent += STEP_TICKS) {
      await tick.sprint(server, STEP_TICKS);
      for (const [name, check] of Object.entries(done)) {
        if (!seen[name]) {
          seen[name] = await check();
        }
      }
      if (Object.values(seen).every(Boolean) && seen.groundKept) {
        break;
      }
    }
    seen.groundKept = await done.groundKept();
    seen.beamKept = await probe(server, `if block ${at(beam)} minecraft:oak_log`);
    expect(seen, "every groundwork zone did its work, and only its own").toEqual({
      cleared: true, groundKept: true, filled: true, weeded: true, replaced: true, felled: true, hauled: true,
      beamKept: true, stored: true,
    });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

for (const on of twins()) test(on.title("fill retreats through a narrow hall and waits for the player before sealing"), async ({}, testInfo) => {
  stage = on;
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("safe-fill-server")), instance: seededServer(stage.name(`safe-fill-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("safe-fill-client")), instance: stage.name(`safe-fill-client-${runId}`) },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await layStage(server, player, origin);
    const founded = await foundColonyFast(server, client, player, { origin, residentCount: 3, stage });
    const cell = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
    const hall = { min: cell(7, 0, 10), max: cell(13, 1, 10) };
    await tryCommand(server, `fill ${at(cell(6, 0, 9))} ${at(cell(14, 2, 11))} minecraft:stone`);
    await tryCommand(server, `fill ${at(cell(6, 0, 10))} ${at(cell(13, 1, 10))} minecraft:air`);
    const inside = stage.stand(cell(10, 0, 10));
    await tryCommand(server, `tp ${player} ${inside.x + 0.5} ${inside.y} ${inside.z + 0.5}`);
    await draw(server, founded.evidence.colonyId, ["fill"], hall);
    await stockToolsForResidents(server, stage.at(founded.foodChest), TRADE_TOOLS.building, 3, 4);
    await tryCommand(server, `item replace block ${at(founded.foodChest)} container.20 with minecraft:dirt 64`);

    const deepEnd = [cell(13, 0, 10), cell(13, 1, 10)];
    let deepFilled = false;
    for (let spent = 0; spent < WORK_TICKS; spent += STEP_TICKS) {
      await tick.sprint(server, STEP_TICKS);
      expect(await allAre(server, [cell(7, 0, 10), cell(7, 1, 10)], "minecraft:air"),
        "the player's only exit stays open throughout construction").toBe(true);
      if (await allAre(server, deepEnd, "minecraft:dirt")) {
        deepFilled = true;
        break;
      }
    }
    expect(deepFilled, "builders still make progress at the far end while the player is inside").toBe(true);
    const outside = stage.stand(cell(4, 0, 10));
    await tryCommand(server, `tp ${player} ${outside.x + 0.5} ${outside.y} ${outside.z + 0.5}`);
    let completed = false;
    for (let spent = 0; spent < WORK_TICKS; spent += STEP_TICKS) {
      await tick.sprint(server, STEP_TICKS);
      if (await allAre(server, boxCells(hall), "minecraft:dirt")) {
        completed = true;
        break;
      }
    }
    expect(completed, "the hall, including its reserved passage and last opening, fills after the player leaves").toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function layStage(server: MinecraftServer, player: string, origin: Vec) {
  if (stage.kind === "aboard") {
    // Assembly removes the floor before the ship has collision. Keep the fixture's player above it until
    // the deck exists, then restore an ordinary, non-spectating player for the actual construction checks.
    await tryCommand(server, `gamemode spectator ${player}`);
    await tryCommand(server, `tp ${player} ${origin.x + 0.5} ${origin.y + 1} ${origin.z + 0.5}`);
  }
  await stage.lay(server, foundingBox(origin));
  if (stage.kind === "aboard") {
    const stand = stage.stand(origin);
    await tryCommand(server, `tp ${player} ${stand.x + 0.5} ${stand.y} ${stand.z + 0.5}`);
    await tryCommand(server, `gamemode creative ${player}`);
  }
}

async function mark(server: MinecraftServer, client: MinecraftClient, player: string, min: Vec, max: Vec) {
  // Click existing blocks without overwriting the scene; no zone exists until its fixture is ready.
  await tick.sprint(server, 2);
  await aimAndWorldLeftClick(server, client, player, min, stage);
  await tick.sprint(server, 3);
  await aimAndWorldLeftClick(server, client, player, max, stage);
  await tick.sprint(server, 5);
}

async function configureFillZone(server: MinecraftServer, client: MinecraftClient, player: string,
    standBlock: Vec, pit: { min: Vec; max: Vec }) {
  await holdZoneAndOpen(server, client, player, pit, stage);
  try {
    for (const edit of [{ key: "fill", toggle: true },
      { key: "block", item: "minecraft:cobblestone", query: "cobblestone" }] as const) {
      await waitForElement(client, { id: zoneSettingToken(edit.key) }, { timeoutMs: 8_000 });
      await applyZoneSetting(server, client, edit);
    }
    await tryCommand(server, `fill ${at(pit.min)} ${at(pit.max)} minecraft:air`);
    await applyZoneSetting(server, client, { key: "clear", toggle: true });
  } finally {
    await releaseHeldZone(client);
    const stand = stage.stand(standBlock);
    await tryCommand(server, `tp ${player} ${stand.x + 0.5} ${stand.y} ${stand.z + 1.5}`);
    await tick.sprint(server, 5);
  }
}

async function draw(server: MinecraftServer, colony: string, works: string[], box: { min: Vec; max: Vec }) {
  const min = stage.at(box.min);
  const max = stage.at(box.max);
  const drawn = await runColonyTask(server, DRAW_ZONE, {
    colony_id: colony, works: works.join(","), box: [min.x, min.y, min.z, max.x, max.y, max.z],
  });
  expect(drawn.drawn, `the ${works.join(" and ")} zone is drawn: ${JSON.stringify(drawn)}`).toBe(true);
}

function boxCells(box: { min: Vec; max: Vec }): Vec[] {
  const cells: Vec[] = [];
  for (let x = box.min.x; x <= box.max.x; x++) {
    for (let y = box.min.y; y <= box.max.y; y++) {
      for (let z = box.min.z; z <= box.max.z; z++) {
        cells.push({ x, y, z });
      }
    }
  }
  return cells;
}

async function allAre(server: MinecraftServer, cells: Vec[], block: string): Promise<boolean> {
  for (const cell of cells) {
    if (!(await probe(server, `if block ${at(cell)} ${block}`))) {
      return false;
    }
  }
  return true;
}

// A groundwork zone drawn onto the colony as the book's confirm draws one, with these works ticked and no others.
const DRAW_ZONE = defineTask<{ colony_id: string; works: string; box: number[] }, Record<string, any>>({
  name: "folkways.groundwork.draw-zone",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonySchemas",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "java.util.Arrays",
    ],
    body: `
Args args = Args.of(ctx);
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id")))
    .orElseThrow(() -> Fail.notFound("no colony"));
ResourceLocation ground = ResourceLocation.fromNamespaceAndPath("folkways", "ground");
List<?> box = (List<?>) ctx.arg("box");
int[] c = new int[6];
for (int i = 0; i < 6; i++) {
    c[i] = ((Number) box.get(i)).intValue();
}
Set<BlockPos> cells = new LinkedHashSet<BlockPos>();
BlockPos.betweenClosed(new BlockPos(c[0], c[1], c[2]), new BlockPos(c[3], c[4], c[5]))
    .forEach(cell -> cells.add(cell.immutable()));
List<String> ticked = Arrays.asList(args.string("works").split(","));
ColonySettings chosen = ColonySettings.byDefault(ColonySchemas.ofDelegation(ground));
for (String work : List.of("clear", "fill", "weed", "fell", "haul")) {
    chosen = chosen.with(work, new ColonySettings.Value.Flag(ticked.contains(work)));
}
var drawn = ColonyFront.of(colony).addZone(ctx.server().overworld(), ground, cells, chosen);
Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("drawn", Boolean.valueOf(drawn.isPresent()));
out.put("cells", Integer.valueOf(cells.size()));
return Sync.stamp(out);
`,
  }),
});

// What the fill zones lay, as their settings hold it, sorted.
const FILL_BLOCKS = defineTask<{ colony_id: string }, Record<string, any>>({
  name: "folkways.groundwork.fill-blocks",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.terms.ItemFilter",
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonyZone",
    ],
    body: `
Args args = Args.of(ctx);
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id")))
    .orElseThrow(() -> Fail.notFound("no colony"));
List<String> blocks = new ArrayList<String>();
for (ColonyZone zone : ColonyFront.of(colony).zones()) {
    if (zone.settings().flag("fill")) {
        for (ItemFilter rule : zone.settings().entries("block")) {
            blocks.add(rule.describe());
        }
    }
}
blocks.sort(null);
Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("blocks", blocks);
return Sync.stamp(out);
`,
  }),
});
