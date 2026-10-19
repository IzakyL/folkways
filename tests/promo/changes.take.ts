import { expect, test } from "@playwright/test";
import { camera, defineTask, input, javaTask, player, reflect, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { ancestorsOf, clickElement, panelElements, waitForElement } from "../ldlib2";
import {
  foundColonyFast, openColonyPanelViaBook, setBookGesture, stockToolsForResidents, TRADE_TOOLS, waitForGroundedPlayer, type Vec,
} from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { WIRE_NETWORK } from "../shared/colony/integration-tasks";
import { buildRailLine } from "../shared/rail/rail-fixture";
import { settlePanel } from "../shared/ui/site-panel";
import { zoneKindToken } from "../shared/ui/zone-tools";
import { HANDOFF_CONFIRM } from "../shared/ui/schematic-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { readDoing } from "../shared/colony/resident-probe";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, safeAction, safeScreen, screenName, tryCommand } from "../shared/harness/bw-helpers";
import { CURSOR, holdKey, KEY, View } from "./overhead-view";
import { copyFileSync, mkdirSync, writeFileSync } from "node:fs";
import { DATA_VERSION, encodeNbtFile } from "../shared/colony/colony-nbt";
import { outPath } from "../shared/harness/out-paths";
import path from "node:path";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import {
  cinematicWorld, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, writeLook, type PromoPair,
} from "./promo-kit";
import { shaderEvidence } from "../shared/capture/shaders";
import type { VisualRun } from "../shared/capture/visual-kit";

// What is new in Folkways, in six shots: the tidied UI and its pickers, the models Touhou Little Maid lends when it is
// installed, the built-in .nbt blueprint set down from the colony book, a groundwork zone cleared and filled by the
// crew, a box of blocks swept into the colony at once, and a resident packing goods by hand into a frogport and a
// postbox. The cuts live in film/promo-changes.toml.

const CONFIG = "blockwright.toml#promo-maids";
const WORK_RATE = 60;
const POLL_MS = 400;
const WORK_TIMEOUT_MS = 40_000;
const EYE = 1.62;
const GUI_SCALE = 3;
const LIMITS = { maxNodes: 60_000, maxDepth: 200 } as const;
const BLUEPRINT = path.join(process.cwd(), "tests/shared/assets/ghost-hut.nbt");
const MAID = "touhou_little_maid:maid/geckolib/winefox";
const MAID_LOOK = "folkways.looks.look.touhou_little_maid.maid.geckolib.winefox";
const MAID_FOLDER = "folkways.looks.folder.touhou_little_maid.maid";
const MAID_SUB = "folkways.looks.folder.touhou_little_maid.maid.geckolib";
const POOL_MAID_FOLDER = "folkways.pool.looks.folder.touhou_little_maid.maid";
const POOL_MAID_SUB = "folkways.pool.looks.folder.touhou_little_maid.maid.geckolib";
const POOL_MAID_SET = "folkways.pool.looks.replace.touhou_little_maid.maids.geckolib";
const SWEEP_OFFER = "folkways.zoneconfig.offer.folkways.sweep";
const SWEEP_STORES = "folkways.sweep.kind.folkways.store";
const SWEEP_STATIONS = "folkways.sweep.kind.folkways.station";
const SWEEP_TAKE = "folkways.sweep.take";
const GROUND_KIND = zoneKindToken("ground");
const ZONE_CONFIRM = "folkways.zoneconfig.confirm";
// Nothing else in the take yields bricks, so the colony's stores back home hold none to carry instead.
const PACK_ITEM = "minecraft:bricks";
const PACK_ORDER = 16;
const FAR = 64;
const CHAIN_LIFT = 4;
const KILN = { height: 2, bricks: PACK_ORDER } as const;

type FilmMark = (name: string) => void;

type ClipOptions = {
  subject: string;
  camera?: { position: Vec; yaw: number; pitch: number; fov?: number } | null;
  during: (mark: FilmMark) => Promise<any>;
};

test("what is new: UI and pickers, maid models, .nbt blueprints, groundwork, sweep, packing by hand", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("changes");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("changes", { config: CONFIG });
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "gamerule randomTickSpeed 3");
    await tryCommand(server, "time set day");
    await tryCommand(server, `effect give ${player} minecraft:fire_resistance infinite 0 true`);
    run.note("gui_scale", await setGuiScale(client, GUI_SCALE));

    const o: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `gamemode survival ${player}`);
    const founded = await foundColonyFast(server, client, player, { origin: o, residentCount: 6 });
    const colony_id = String(founded.evidence.colonyId);
    run.note("founded", { origin: o, residents: founded.residents, stand: founded.standBlock, colony_id });
    const residents = await residentsOf(server);
    expect(residents.length, "a colony of six should stand on its beds").toBeGreaterThanOrEqual(6);

    // One flat yard outside the founding walls holds every scene, so each shot is staged by clearing its own patch.
    await tryCommand(server, `gamemode creative ${player}`);
    await tryCommand(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 5);
    await tryCommand(server, `gamemode survival ${player}`);

    const U = at(o, 0, 32);        // the book panel, at a small stone plaza
    const M = at(o, 18, 32);       // the resident wearing the maid model
    const B = at(o, 0, 56);        // the blueprint plot
    const S = at(o, 44, 32);       // the swept row of blocks
    const F = at(o, -40, 72);      // the frogport chain
    const P = at(o, 52, 96);       // the postbox line

    // A wood between the founding walls and the yard, so no shot looks back onto bare stone.
    await treeline(server, at(o, -64, 24), o.x + 72, 3);

    // CHANGES_ONLY=packing (a comma list of ui, maids, blueprint, sweep, packing) films only those shots, for a retake
    // of the ones that did not come out.
    const only = process.env.CHANGES_ONLY?.split(",").map(one => one.trim()).filter(Boolean);
    const filming = (shot: string) => !only?.length || only.includes(shot);
    if (filming("ui")) await stageUi(run, server, client, player, U, residents);
    if (filming("maids")) await stageMaids(run, server, client, player, M, colony_id, residents);
    if (filming("blueprint")) await stageBlueprint(run, server, client, player, B, pair);
    else {
      // The kiln the frogport's far end builds waits on the shelf the blueprint shot would have stocked.
      await putBlueprint(pair, "kiln", writeKiln());
      await world.command(server, "reload");
      await tick.sprint(server, 20);
    }
    if (filming("sweep")) await stageSweep(run, server, client, player, S);
    if (filming("packing")) await stagePacking(run, server, client, player, F, P, colony_id, residents);

    run.note("looks", await runColonyTask(server, RESIDENT_STATE, { colony_id }));
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    // The server's log, for the labor heartbeat that says why a resident left work standing.
    if (pair) {
      const log = path.join((pair.server as any).instance.instanceDir, "logs", "latest.log");
      try { copyFileSync(log, path.join(outPath("promo", "takes", "changes"), "server.log")); } catch { /* none */ }
    }
    await pair?.teardown(testInfo);
  }
});

// One clip, its moments stamped in seconds from its start for the cut to time its pieces by.
async function film(run: VisualRun, client: any, server: any, name: string, options: ClipOptions) {
  const marks: Record<string, number> = {};
  let started = 0;
  let result: any;
  const gates = await promoClip(run, client, server, name, {
    subject: options.subject,
    worldState: {},
    seconds: 1,
    camera: options.camera ?? null,
    during: async () => {
      started = Date.now();
      result = await options.during((mark) => { marks[mark] = Math.round((Date.now() - started) / 100) / 10; });
      marks.end = Math.round((Date.now() - started) / 100) / 10;
    },
  });
  return { ...gates, marks, ...result };
}

function at(base: Vec, dx: number, dz: number, dy = 0): Vec {
  return { x: base.x + dx, y: base.y + dy, z: base.z + dz };
}

function above(cell: Vec, dy = 1): Vec {
  return { ...cell, y: cell.y + dy };
}

function lookAt(eye: Vec, target: Vec) {
  return cameraLookingAt(
    { x: eye.x + 0.5, y: eye.y + EYE, z: eye.z + 0.5 },
    { x: target.x + 0.5, y: target.y + 0.5, z: target.z + 0.5 },
  );
}

async function standAt(server: any, client: any, player: string, feet: Vec, aim: Vec, fov = 62) {
  const look = lookAt(feet, aim);
  await world.command(server, `tp ${player} ${feet.x + 0.5} ${feet.y} ${feet.z + 0.5} ${look.yaw} ${look.pitch}`);
  await setFov(client, fov);
  await tick.sprint(server, 10);
  await writeLook(client, look);
  await safeAction(() => frames(client, 20));
  return look;
}

async function setGuiScale(client: MinecraftClient, scale: number) {
  const integer = await reflect.invoke(client, {
    target: { kind: "static", className: "java.lang.Integer" },
    method: "valueOf", args: [scale], argTypes: ["int"], returnHandle: true,
  });
  await reflect.invoke(client, {
    target: { kind: "static", className: "net.minecraft.client.Minecraft" },
    path: "getInstance().options.guiScale()", method: "set",
    args: [{ $handle: integer.handle }], argTypes: ["java.lang.Object"],
  });
  await reflect.invoke(client, {
    target: { kind: "static", className: "net.minecraft.client.Minecraft" },
    path: "getInstance()", method: "resizeDisplay",
  });
  await safeAction(() => frames(client, 8));
  return scale;
}

async function click(client: MinecraftClient, id: string) {
  return clickElement(client, { id }, { scrollIntoView: true, ...LIMITS });
}

async function waitFor(client: MinecraftClient, id: string, timeoutMs = 8_000) {
  return waitForElement(client, { id }, { timeoutMs, ...LIMITS });
}

// clickTab, with a node budget wide enough for a page with many rows: the default read probe cuts the tree at
// 8,000 elements, and a colony with six residents runs past that.
async function openTab(server: any, client: MinecraftClient, id: string) {
  for (let attempt = 0; attempt < 64; attempt++) {
    const elements = await panelElements(client, { maxNodes: 60_000, maxDepth: 200 });
    const target = elements.find(element => element.id === id);
    const nav = elements.find(element => element.id === "folkways.navigation");
    if (!target || !nav) throw new Error(`Missing navigation or registered page ${id}`);
    const menu = elements.find(element => element.id === "folkways.navigation.menu");
    if (nav.displayed === false && menu && menu.displayed !== false) {
      await clickElement(client, { id: menu.id }, LIMITS);
      await frames(client, 3);
      continue;
    }
    if (!ancestorsOf(elements, target).some(ancestor => ancestor.id === nav.id)
        || (target.y >= nav.y && target.y + target.height <= nav.y + nav.height)) {
      await clickElement(client, { id }, LIMITS);
      await frames(client, 4);
      await input.move(client, { x: 0, y: 0 });
      await settle(server, client, 4);
      return;
    }
    await input.move(client, { x: nav.x + nav.width / 2, y: nav.y + nav.height / 2 });
    await input.scroll(client, { yOffset: target.y < nav.y ? 1 : -1 });
    await frames(client, 3);
  }
  throw new Error(`Could not scroll to registered page ${id}`);
}

async function setHud(client: MinecraftClient, visible: boolean) {
  await safeAction(() => camera.hud(client, visible));
}

async function visible(client: MinecraftClient, id: string) {
  try {
    return (await panelElements(client, { maxNodes: 60_000, maxDepth: 200 }))
      .some(element => element.id === id && element.displayed !== false);
  } catch {
    return false;
  }
}

async function settle(server: any, client: MinecraftClient, n = 5) {
  await safeAction(() => frames(client, n));
  await tick.sprint(server, 2);
}

async function residentsOf(server: any): Promise<Array<{ uuid: string; name: string }>> {
  const listed = await world.entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 32 });
  return (listed.entities ?? []).map((one: any) => ({ uuid: one.uuid, name: String(one.name ?? "") }));
}

async function placeResident(server: any, uuid: string, cell: Vec, yaw = 0, pitch = 0) {
  return runColonyTask(server, PLACE_RESIDENT, { uuid, x: cell.x, y: cell.y, z: cell.z, yaw, pitch });
}

async function teleport(server: any, name: string, cell: Vec, yaw = 0, pitch = 0) {
  await world.command(server, `tp ${name} ${cell.x + 0.5} ${cell.y} ${cell.z + 0.5} ${yaw} ${pitch}`);
}

// The book opened in the hand, used on nothing: no block to use it on has to stand in the shot.
async function openBook(server: any, client: MinecraftClient) {
  await tick.sprint(server, 5);
  await screen.dismiss(client);
  await safeAction(() => player.useItem(client));
  await screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 10_000 });
}

// The book's own view from above, as a player works it: V lifts the camera off the player, the mouse (drawn into the
// picture, which has none of its own) clicks the world, and the wheel and a middle drag move the view.
async function enterOverhead(client: MinecraftClient) {
  await screen.dismiss(client);
  await client.run(CURSOR, {});
  await client.run(CLEAR_TOASTS, {});
  await holdKey(client, KEY.V);
  await waitOverhead(client);
  return new View(client, ([x, z]) => ({ x, y: 0, z }));
}

async function waitOverhead(client: MinecraftClient, timeoutMs = 10_000) {
  const until = Date.now() + timeoutMs;
  let open = "";
  while (Date.now() < until) {
    open = screenName(await safeScreen(client));
    if (open.includes("OverheadScreen")) return;
    await sleepMs(150);
  }
  throw new Error(`the view from above did not come back (${open})`);
}

// The box being marked, as the book holds it on the client.
async function markedBox(client: MinecraftClient): Promise<{ min: number[]; max: number[] } | null> {
  const read: any = await client.run(MARKED_BOX, {}).catch(() => null);
  return read?.min ? read : null;
}

// The box's top pulled up with Ctrl and the wheel over it, as a player raises a zone to take in what stands on the
// ground, until it reaches the given height.
async function raiseBox(client: MinecraftClient, view: View, top: number) {
  let box = await markedBox(client);
  if (!box) throw new Error("no box is marked to raise");
  const spot = () => ({ x: (box!.min[0] + box!.max[0]) / 2 + 0.5, y: box!.max[1] + 1, z: (box!.min[2] + box!.max[2]) / 2 + 0.5 });
  await view.hover(spot(), 500);
  await sleepMs(300);
  await safeAction(() => input.key(client, { keyCode: KEY.CTRL, action: "press" }));
  try {
    let up = -1;
    for (let turn = 0; turn < 12 && box!.max[1] < top; turn++) {
      const before = box!.max[1];
      await safeAction(() => input.scroll(client, { yOffset: up }));
      await sleepMs(350);
      box = (await markedBox(client)) ?? box;
      if (box!.max[1] < before) up = -up;
      await view.hover(spot(), 200);
    }
  } finally {
    await safeAction(() => input.key(client, { keyCode: KEY.CTRL, action: "release" }));
  }
  return box;
}

// A panel opened from the view from above is closed with Escape, and the view takes the screen back.
async function closePanel(client: MinecraftClient) {
  await safeAction(() => input.key(client, { keyCode: 256 }));
  await waitOverhead(client);
}

async function clearBox(server: any, min: Vec, max: Vec, block = "minecraft:air") {
  await tryCommand(server, `fill ${commandPos(min)} ${commandPos(max)} ${block}`);
}

// ── set dressing, after the logistics and rail takes: paved yards, lamp posts, trees behind every shot ────────
const TREES = ["minecraft:oak", "minecraft:birch", "minecraft:fancy_oak", "minecraft:spruce"];

// Trees in two staggered rows along x, so the back of a shot reads as a wood's edge rather than a hedge.
async function treeline(server: any, from: Vec, toX: number, gap = 5) {
  let i = 0;
  for (let x = from.x; x <= toX; x += gap, i++) {
    const cell = { x: x + ((i * 3) % 2), y: from.y, z: from.z - (i % 2) * 3 };
    await tryCommand(server, `place feature ${TREES[i % TREES.length]} ${commandPos(cell)}`);
  }
}

// Trees in a row along z, for a shot that looks along x or down a line.
async function treeRow(server: any, from: Vec, toZ: number, gap = 6) {
  let i = 0;
  for (let z = from.z; z <= toZ; z += gap, i++) {
    const cell = { x: from.x + (i % 2) * 3, y: from.y, z };
    await tryCommand(server, `place feature ${TREES[(i + 1) % TREES.length]} ${commandPos(cell)}`);
  }
}

const PLANTS = ["minecraft:short_grass", "minecraft:poppy", "minecraft:short_grass", "minecraft:dandelion",
  "minecraft:fern", "minecraft:oxeye_daisy", "minecraft:short_grass", "minecraft:cornflower"];

// A tuft of grass and flowers at each spot, set block by block: a scattering feature would seed weeds into a zone.
async function meadow(server: any, spots: Vec[]) {
  let i = 0;
  for (const spot of spots) {
    for (const [dx, dz] of [[0, 0], [1, 0], [0, 1], [-1, 1]]) {
      await tryCommand(server, `setblock ${commandPos(at(spot, dx, dz))} ${PLANTS[i++ % PLANTS.length]} keep`);
    }
  }
}

// Paves the ground under a yard, leaving whatever already stands on it.
async function pave(server: any, min: Vec, max: Vec, block = "minecraft:stone_bricks") {
  const floor = (cell: Vec) => commandPos({ ...cell, y: cell.y - 1 });
  for (const ground of ["minecraft:grass_block", "minecraft:smooth_stone", "minecraft:dirt"]) {
    await tryCommand(server, `fill ${floor(min)} ${floor(max)} ${block} replace ${ground}`);
  }
}

async function lamp(server: any, cell: Vec, height = 2) {
  await clearBox(server, cell, above(cell, height - 1), "minecraft:oak_fence");
  await tryCommand(server, `setblock ${commandPos(above(cell, height))} minecraft:lantern`);
}

// A spruce awning on four posts, open on every side.
async function awning(server: any, min: Vec, max: Vec, height = 3) {
  for (const [x, z] of [[min.x, min.z], [min.x, max.z], [max.x, min.z], [max.x, max.z]]) {
    await clearBox(server, { x, y: min.y, z }, { x, y: min.y + height - 1, z }, "minecraft:spruce_fence");
  }
  await clearBox(server, { ...min, y: min.y + height }, { ...max, y: min.y + height }, "minecraft:spruce_slab");
}

// ── 1. the UI and its pickers ─────────────────────────────────────────────────────────────────────────────
async function stageUi(run: VisualRun, server: any, client: any, player: string, at_: Vec, residents: Array<{ uuid: string }>) {
  await tick.freeze(server, true);
  await clearBox(server, at(at_, -4, -6), at(at_, 4, 3));
  await pave(server, at(at_, -5, -6), at(at_, 5, 3));
  // Something worth looking at past the panel once it closes, where the player faces: a market stall under an
  // awning, barrels either side, and lamp posts at the plaza's corners.
  await awning(server, at(at_, -3, -5), at(at_, 3, -3));
  for (const [dx, dz, block, dy = 0] of [
    [-2, -4, "minecraft:barrel[facing=up]"], [-1, -4, "minecraft:barrel[facing=up]"], [-2, -4, "minecraft:barrel[facing=up]", 1],
    [1, -4, "minecraft:composter"], [2, -4, "minecraft:barrel[facing=up]"], [2, -4, "minecraft:potted_poppy", 1],
  ] as Array<[number, number, string, number?]>) {
    await tryCommand(server, `setblock ${commandPos(at(at_, dx, dz, dy))} ${block}`);
  }
  for (const dx of [-5, 5]) await lamp(server, at(at_, dx, -1), 2);

  const shot = await film(run, client, server, "01-ui", {
    subject: "The colony panel opened on a living colony, moved through residents, stock and settings, then the item "
      + "picker opened on tools: a mod folder, a tag folder ticked as one rule, a search kept as another rule",
    during: async (mark) => {
      await tick.freeze(server, true);
      await setHud(client, false);
      await screen.dismiss(client);
      // Facing the stall, so it is what the panel opens over and closes onto.
      await teleport(server, player, at(at_, 0, 1), 180, 12);
      await openBook(server, client);
      await safeAction(() => frames(client, 10));
      mark("panel");
      await sleepMs(1_400);
      for (const [tab, name, content] of [
        ["folkways.tab.residents", "residents", "folkways.resident.search"],
        ["folkways.tab.metrics", "stock", "folkways.metrics.refresh"],
        ["folkways.tab.settings", "settings", "folkways.settings.reset_layout"]] as const) {
        await openTab(server, client, tab);
        await waitFor(client, content, 10_000);
        mark(name);
        await sleepMs(1_100);
      }
      await click(client, "folkways.settings.open.tool");
      await waitFor(client, "folkways.selector.close");
      await settle(server, client, 8);
      mark("picker");
      await sleepMs(1_200);
      await click(client, "folkways.selector.folder.minecraft");
      await settle(server, client);
      await click(client, "folkways.selector.folder.minecraft.minecraft.arrows");
      await settle(server, client);
      await click(client, "folkways.selector.folder_rule.minecraft.minecraft.arrows");
      await settle(server, client);
      mark("ticked");
      await sleepMs(1_000);
      await click(client, "folkways.selector.search");
      await safeAction(() => input.key(client, { text: "@minecraft *_planks" }));
      await settle(server, client);
      mark("search");
      await sleepMs(1_200);
      await click(client, "folkways.selector.add_rule");
      await settle(server, client);
      mark("rule");
      await sleepMs(1_000);
      await click(client, "folkways.selector.close");
      await settle(server, client);
      await screen.dismiss(client);
      await safeAction(() => frames(client, 20));
      await setHud(client, false);
      await sleepMs(1_500);
      return { residents: residents.length };
    },
  });
  run.note("ui", shot);
  await tick.freeze(server, false);
}

// ── 1.5 the models Touhou Little Maid lends ───────────────────────────────────────────────────────────────
async function stageMaids(run: VisualRun, server: any, client: any, player: string, at_: Vec, colony_id: string,
  residents: Array<{ uuid: string }>) {
  const who = residents[0];
  await tick.freeze(server, true);
  await clearBox(server, at(at_, -5, -4), at(at_, 5, 5));
  await clearBox(server, at(at_, -2, -2, -1), at(at_, 2, 2, -1), "minecraft:stone_bricks");
  await pave(server, at(at_, -1, 3), at(at_, 1, 7), "minecraft:dirt_path");
  // A dais: the resident on the paving, a hedge behind, potted azaleas and lamp posts either side.
  await tryCommand(server, `setblock ${commandPos(at(at_, 0, -2))} minecraft:stone_bricks`);
  await clearBox(server, at(at_, -3, -3), at(at_, 3, -3, 1), "minecraft:oak_leaves[persistent=true]");
  for (const dx of [-2, 2]) await tryCommand(server, `setblock ${commandPos(at(at_, dx, -1))} minecraft:potted_flowering_azalea_bush`);
  for (const dx of [-3, 3]) await lamp(server, at(at_, dx, 1), 2);
  await meadow(server, [at(at_, -4, 3), at(at_, 4, 4), at(at_, -5, -1), at(at_, 5, -2)]);
  // Facing south, down the path to where the player stands.
  await placeResident(server, who.uuid, at_, 0, 0);
  await tryCommand(server, `data merge entity ${who.uuid} {NoAI:1b,Silent:1b}`);
  await tryCommand(server, `gamerule doMobSpawning false`);
  const feet = at(at_, 0, 7);
  const aim = at_;
  const shot = await film(run, client, server, "02-maids", {
    subject: "A resident's own window: the look change dialog opens, its Touhou Little Maid folder opened twice down "
      + "to Wine Fox and picked, the resident shown in that model, then the colony's look pool opened on the same folder",
    during: async (mark) => {
      await screen.dismiss(client);
      await standAt(server, client, player, feet, aim, 50);
      await setHud(client, false);
      await tick.freeze(server, true);
      await openBook(server, client);
      await openTab(server, client, "folkways.tab.residents");
      await settle(server, client, 8);
      mark("citizens");
      await sleepMs(1_200);
      const head = who.uuid.slice(0, 8);
      await click(client, `folkways.resident.name.${head}`);
      await waitFor(client, "folkways.resident.look.change");
      await settle(server, client, 8);
      mark("person");
      await sleepMs(1_200);
      await click(client, "folkways.resident.look.change");
      await waitFor(client, "folkways.looks.picker");
      await settle(server, client, 8);
      mark("dialog");
      await sleepMs(1_200);
      if (!(await visible(client, MAID_LOOK))) {
        await click(client, MAID_FOLDER);
        await settle(server, client);
      }
      if (!(await visible(client, MAID_LOOK))) {
        await click(client, MAID_SUB);
        await settle(server, client, 8);
      }
      mark("folder");
      await sleepMs(1_000);
      await click(client, MAID_LOOK);
      await settle(server, client, 8);
      mark("picked");
      await sleepMs(1_400);
      await click(client, "folkways.looks.close");
      await settle(server, client, 8);
      await click(client, "folkways.resident.back");
      await settle(server, client, 5);
      await screen.dismiss(client);
      await standAt(server, client, player, feet, aim, 50);
      await tick.freeze(server, false);
      await tryCommand(server, "tick rate 20");
      await safeAction(() => frames(client, 30));
      mark("worn");
      // The resident turns to the camera in its new model while the pool is opened over it.
      await world.command(server, `tp ${who.uuid} ${at_.x + 0.5} ${at_.y} ${at_.z + 0.5} 0 0`);
      await sleepMs(2_500);
      await setHud(client, false);
      await tick.freeze(server, true);
      await standAt(server, client, player, feet, aim, 50);
      await openBook(server, client);
      await openTab(server, client, "folkways.page.person");
      await settle(server, client, 8);
      await click(client, "folkways.board.open.folkways.person.3.0");
      await waitFor(client, "folkways.window.folkways.looks");
      await settle(server, client, 8);
      mark("pool");
      await sleepMs(1_200);
      if (!(await visible(client, "folkways.pool.looks.look.touhou_little_maid.maid.geckolib.winefox"))) {
        await click(client, POOL_MAID_FOLDER);
        await settle(server, client);
      }
      if (!(await visible(client, "folkways.pool.looks.look.touhou_little_maid.maid.geckolib.winefox"))) {
        await click(client, POOL_MAID_SUB);
        await settle(server, client, 8);
      }
      mark("pool_folder");
      await sleepMs(1_400);
      await click(client, POOL_MAID_SET);
      await settle(server, client, 8);
      mark("pool_set");
      await sleepMs(1_600);
      await click(client, "folkways.window.close.folkways.looks");
      await settle(server, client, 8);
      await openTab(server, client, "folkways.tab.residents");
      await settle(server, client, 5);
      await screen.dismiss(client);
      await standAt(server, client, player, feet, aim, 50);
      await tick.freeze(server, false);
      await tick.sprint(server, 20);
      await sleepMs(2_000);
      await tryCommand(server, `data merge entity ${who.uuid} {NoAI:0b,Silent:0b}`);
      return { look: MAID, who: who.uuid.slice(0, 8) };
    },
  });
  run.note("maids", shot);
  const seen = await runColonyTask(server, RESIDENT_STATE, { colony_id });
  const worn = (seen.crew as any[]).find(one => String(one.id).startsWith(who.uuid))?.look;
  expect(worn, `the resident should wear the maid model after the dialog: ${JSON.stringify(seen.crew)}`).toBe(MAID);
  await tick.freeze(server, false);
}

// ── 2. a .nbt blueprint set down from the book ────────────────────────────────────────────────────────────
async function stageBlueprint(run: VisualRun, server: any, client: any, player: string, at_: Vec, pair: PromoPair) {
  await tick.freeze(server, true);
  await clearBox(server, at(at_, -10, -10), at(at_, 10, 12));
  await clearBox(server, at(at_, -10, -10, -1), at(at_, 10, 12, -1), "minecraft:grass_block");
  // A plot on open grass: a path up to it, lamps at the corners, a wood behind.
  await clearBox(server, at(at_, -1, 5, -1), at(at_, 1, 12, -1), "minecraft:dirt_path");
  for (const [dx, dz] of [[-6, -6], [6, -6], [-6, 6], [6, 6]]) await lamp(server, at(at_, dx, dz), 2);
  await treeline(server, at(at_, -14, -12), at_.x + 14, 4);
  await meadow(server, [at(at_, -8, 2), at(at_, 8, 0), at(at_, -7, 9), at(at_, 8, 10), at(at_, -9, -3)]);
  await putBlueprint(pair, "hut", BLUEPRINT);
  // The kiln the frogport's far end builds from the bricks it is sent waits on the same shelf.
  await putBlueprint(pair, "kiln", writeKiln());
  await world.command(server, "reload");
  await tick.sprint(server, 20);

  const feet = at(at_, 0, 9);
  const look = lookAt(feet, at(at_, 0, 0));
  await world.command(server, `tp ${player} ${feet.x + 0.5} ${feet.y} ${feet.z + 0.5} ${look.yaw} ${look.pitch}`);
  await safeAction(() => frames(client, 20));
  const corner = at(at_, 0, 0, -1);
  // The view from above, over the plot from the path's end, before the shot: it opens on the view's own buttons.
  const view = await enterOverhead(client);
  await view.goTo({ x: at_.x + 0.5, y: at_.y, z: at_.z + 2.5 }, 180, 20, 50);
  await view.park();

  const shot = await film(run, client, server, "03-blueprint", {
    subject: "In the book's view from above: the colony book opened with a right click, its build page opens the "
      + "blueprint shelf, the hut .nbt is picked, the ghost follows the mouse over the plot, and a click raises it whole",
    during: async (mark) => {
      await tick.freeze(server, true);
      // The view and its buttons held a moment before the book opens over them.
      await sleepMs(2_500);
      mark("hud");
      await view.click({ x: at_.x + 0.5, y: at_.y, z: at_.z + 4.5 }, "right");
      await screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 10_000 });
      await openTab(server, client, "folkways.page.build");
      await settle(server, client, 8);
      mark("build");
      await sleepMs(1_200);
      await click(client, "folkways.board.open.folkways.build.0.0");
      await waitFor(client, "folkways.window.folkways.blueprints");
      await settle(server, client, 8);
      mark("shelf");
      await sleepMs(1_300);
      await click(client, "folkways.blueprints.entry.local.blueprint.hut");
      await settle(server, client, 8);
      await waitOverhead(client);
      await tick.freeze(server, false);
      await tryCommand(server, `gamemode creative ${player}`);
      await safeAction(() => frames(client, 20));
      mark("ghost");
      // The ghost follows the mouse across the grass before it is set down on the plot.
      await view.hover({ x: at_.x - 3.5, y: at_.y, z: at_.z + 1.5 }, 700);
      await sleepMs(500);
      await view.hover({ x: at_.x + 3.5, y: at_.y, z: at_.z - 1.5 }, 900);
      await sleepMs(500);
      await view.click({ x: corner.x + 0.5, y: at_.y, z: corner.z + 0.5 }, "left", 700);
      await softlyWait(() => waitFor(client, "folkways.window.blueprint_handoff"));
      await settle(server, client, 8);
      mark("panel");
      await sleepMs(1_300);
      await drainActionBar(client);
      await click(client, "folkways.blueprint.handoff.raise");
      await settle(server, client, 20);
      const bar = await readActionBar(client);
      await waitOverhead(client).catch(() => undefined);
      await view.park();
      mark("raised");
      await sleepMs(2_500);
      await tryCommand(server, `gamemode survival ${player}`);
      return { file: "hut.nbt", bar: bar.text };
    },
  });
  await screen.dismiss(client);
  run.note("blueprint", shot);
  expect((shot as any).bar, `the blueprint should be raised at once: ${JSON.stringify(shot)}`)
    .toMatch(/Built at once|已直接建成/);
  await tick.freeze(server, false);
}

// A brick kiln: a ring of eight bricks, two courses high, sixteen bricks in all, the order the frogport sends.
function writeKiln() {
  const int = (v: number) => ({ k: "int", v });
  const ints = (v: number[]) => ({ k: "list", item: 3, v: v.map(int) });
  const cells: number[][] = [];
  for (let y = 0; y < KILN.height; y++) for (let x = 0; x < 3; x++) for (let z = 0; z < 3; z++) {
    if (x !== 1 || z !== 1) cells.push([x, y, z]);
  }
  const nbt = encodeNbtFile({
    DataVersion: int(DATA_VERSION),
    size: ints([3, KILN.height, 3]),
    palette: { k: "list", item: 10, v: [{ k: "compound", v: { Name: { k: "string", v: PACK_ITEM } } }] },
    blocks: { k: "list", item: 10, v: cells.map(pos => ({ k: "compound", v: { pos: ints(pos), state: int(0) } })) },
    entities: { k: "list", item: 0, v: [] },
  } as any);
  const dir = outPath("promo", "takes", "changes");
  mkdirSync(dir, { recursive: true });
  const file = path.join(dir, "promo-kiln.nbt");
  writeFileSync(file, nbt);
  return file;
}

async function putBlueprint(pair: PromoPair, name: string, source: string) {
  const dir = path.join((pair.server as any).instance.instanceDir, "folkways", "blueprint");
  mkdirSync(dir, { recursive: true });
  copyFileSync(source, path.join(dir, `${name}.nbt`));
  return path.join(dir, `${name}.nbt`);
}

async function softlyWait<T>(fn: () => Promise<T>): Promise<T | null> {
  try {
    return await fn();
  } catch {
    return null;
  }
}

// ── 3. groundwork, on real ground ───────────────────────────────────────────────────────────────────────────
// A hillside of the promo-land world (Terralith over Tectonic, seed folkways-promo-land): grass in terraces a block
// or two high, stone breaking through, a lone tree on the upper terrace. The zone takes a slice off the upper
// terrace with the tree on it; a corner of the lower terrace beside it is filled up level with the middle one.
const LAND_CONFIG = "blockwright.toml#promo-land";
const REAL = {
  // A low pile of mossy stones on the middle terrace marks the zone's one corner, the upper terrace's grass beside
  // the tree its other: clicked from above, the box runs from the middle terrace's air up through the upper terrace,
  // the tree's foot in it.
  pile: { x: 85, z: -43 },
  upper: { x: 74, z: -38 },
  trunk: { x: 75, z: -39 },
  min: { x: 74, z: -43 },
  max: { x: 85, z: -38 },
  // The lower terrace's corner, filled to the middle terrace's height.
  pit: { min: { x: 88, z: -37 }, max: { x: 91, z: -35 } },
  store: { x: 88, z: -41 },
  stack: { x: 82, z: -40 },
  feet: { x: 82, z: -32 },
  crew: [{ x: 76, z: -31 }, { x: 78, z: -31 }, { x: 80, z: -30 }, { x: 82, z: -29 }, { x: 84, z: -30 }, { x: 74, z: -32 }],
};

test("what is new: groundwork on real ground", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("changes-ground");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("changes-ground", { config: LAND_CONFIG });
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "gamerule doMobSpawning false");
    await tryCommand(server, "time set 6500");
    await tryCommand(server, `effect give ${player} minecraft:fire_resistance infinite 0 true`);
    run.note("gui_scale", await setGuiScale(client, GUI_SCALE));

    const cells = [REAL.pile, REAL.upper, REAL.trunk, REAL.store, REAL.stack, REAL.feet, ...REAL.crew];
    const tops = (await runColonyTask(server, GROUND_TOPS, { cells })).tops as number[];
    const top = new Map(cells.map((cell, index) => [`${cell.x},${cell.z}`, tops[index]]));
    // A cell standing on the ground there: one above the top block that is not a tree.
    const on = (cell: { x: number; z: number }): Vec => ({ x: cell.x, y: top.get(`${cell.x},${cell.z}`)! + 1, z: cell.z });
    run.note("tops", Object.fromEntries(top));

    // The hillside is held loaded: what is set on it right after the player arrives would land on unloaded ground.
    await tryCommand(server, "forceload add 56 -64 104 -16");
    await teleport(server, player, on(REAL.feet), 180, 20);
    await tick.sprint(server, 40);
    const store = on(REAL.store);
    await tryCommand(server, `setblock ${commandPos(store)} minecraft:chest[facing=south]`);
    await tryCommand(server, `item replace block ${commandPos(store)} container.0 with minecraft:dirt 64`);
    // The colony is bound to the book in the player's hand.
    await tryCommand(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    const beds = (await runColonyTask(server, BED_CELLAR, { x: 92, z: -46, y: Math.min(...tops) - 12, count: 6 })).beds as Vec[];
    const founded = await foundPromoColonyFast(server, player, [store, ...beds]);
    const colony_id = founded.colony_id;
    run.note("admitted", await admitPromoSettlersFast(server, player, colony_id, 6));
    const residents = await residentsOf(server);
    expect(residents.length, "six settlers should be admitted").toBeGreaterThanOrEqual(6);

    await stageGroundwork(run, server, client, player, colony_id, residents, on);
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair) {
      const log = path.join((pair.server as any).instance.instanceDir, "logs", "latest.log");
      try { copyFileSync(log, path.join(outPath("promo", "takes", "changes-ground"), "server.log")); } catch { /* none */ }
    }
    await pair?.teardown(testInfo);
  }
});

async function stageGroundwork(run: VisualRun, server: any, client: any, player: string, colony_id: string,
  residents: Array<{ uuid: string }>, on: (cell: { x: number; z: number }) => Vec) {
  await tick.freeze(server, true);
  const store = on(REAL.store);
  const middle = on(REAL.pile).y;            // the middle terrace's air, where the pile and the crew stand
  const trunk = on(REAL.trunk);              // the upper terrace's air at the tree's foot
  const pile = { x: REAL.pile.x, y: middle, z: REAL.pile.z };
  await tryCommand(server, `setblock ${commandPos(pile)} minecraft:mossy_cobblestone`);
  await tryCommand(server, `setblock ${commandPos(at(pile, -1, 0))} minecraft:cobblestone`);
  await tryCommand(server, `setblock ${commandPos(at(pile, 0, 1))} minecraft:mossy_cobblestone`);
  await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: [store.x, store.y, store.z] });
  // Something loose to haul off the middle terrace.
  const stack = on(REAL.stack);
  await tryCommand(server, `summon minecraft:item ${stack.x}.5 ${stack.y} ${stack.z}.5 {Item:{id:"minecraft:emerald",count:5},PickupDelay:32767s,Age:-32768s}`);

  await stockToolsForResidents(server, store, [...TRADE_TOOLS.building, ...TRADE_TOOLS.farming], 4, 2);
  const trades = [
    { uuid: residents[0].uuid, trades: ["building", "hauling"] },
    { uuid: residents[1].uuid, trades: ["building", "hauling"] },
    { uuid: residents[2].uuid, trades: ["building", "hauling"] },
    { uuid: residents[3].uuid, trades: ["building", "hauling"] },
    { uuid: residents[4].uuid, trades: ["farming", "hauling"] },
    { uuid: residents[5].uuid, trades: ["farming", "hauling"] },
  ];
  await licenseCrewFast(server, trades);
  for (const [i, one] of trades.entries()) {
    await placeResident(server, one.uuid, on(REAL.crew[i]), 180, 0);
  }
  await tryCommand(server, `gamemode survival ${player}`);
  await tryCommand(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);

  await setBookGesture(server, client, player, "point");
  const feet = on(REAL.feet);
  await teleport(server, player, feet, 180, 20);
  await tick.sprint(server, 5);
  const upper = on(REAL.upper);
  const box = { min: { x: REAL.min.x, y: middle, z: REAL.min.z }, max: { x: REAL.max.x, y: upper.y - 1, z: REAL.max.z } };
  // The view from above, over the terraces from the south, before the shot.
  const view = await enterOverhead(client);
  await view.goTo({ x: 80.5, y: middle, z: -39.5 }, 180, 26, 48);
  await view.park();
  const shot = await film(run, client, server, "04-groundwork", {
    subject: "On a real hillside, in the book's view from above: the box gesture picked, a slice of the upper terrace "
      + "with the tree on it marked with two clicks, groundwork picked with a right click, weed, fell and haul ticked "
      + "and confirmed; the crew digs the terrace down level, fells the tree, pulls the flowers and grass, hauls a "
      + "loose stack, and fills the lower terrace's corner",
    during: async (mark) => {
      await tick.freeze(server, true);
      await sleepMs(1_500);
      mark("mark");
      await view.press("folkways.overhead.gesture.box");
      await sleepMs(600);
      // Each corner clicked on the top of its block.
      await view.click({ x: pile.x + 0.5, y: pile.y + 1, z: pile.z + 0.5 }, "left", 800);
      await tick.sprint(server, 3);
      await sleepMs(500);
      await view.click({ x: upper.x + 0.5, y: upper.y, z: upper.z + 0.5 }, "left", 900);
      await tick.sprint(server, 5);
      mark("marked");
      await sleepMs(800);
      // The terraces are a block apart: the box is pulled up over the tree's foot, so the tree is felled, not left
      // standing on nothing.
      const raised = await raiseBox(client, view, trunk.y);
      if (raised) box.max.y = raised.max[1];
      mark("raised");
      await sleepMs(1_200);
      await view.click({ x: 80.5, y: upper.y, z: -41.5 }, "right");
      await waitFor(client, GROUND_KIND);
      await settle(server, client, 8);
      mark("offer");
      await sleepMs(1_000);
      await click(client, GROUND_KIND);
      await settlePanel(server, client);
      await waitFor(client, ZONE_CONFIRM);
      await settle(server, client, 8);
      mark("works");
      await sleepMs(900);
      for (const work of ["weed", "fell", "haul"]) {
        // A tick box sits in no scrolling list, so it is pressed where it stands.
        await clickElement(client, { id: `folkways.draft.value.${work}` }, LIMITS);
        await settlePanel(server, client);
        await sleepMs(450);
      }
      mark("ticked");
      await sleepMs(900);
      // Confirmed, the panel turns to the new zone's own page; closed, the view from above comes back.
      await click(client, ZONE_CONFIRM);
      await settle(server, client, 8);
      mark("confirmed");
      await sleepMs(1_400);
      await closePanel(client);
      await view.park();
      run.note("pit_zone", await runColonyTask(server, DRAW_ZONE, {
        colony_id, works: "fill",
        box: [REAL.pit.min.x, middle - 1, REAL.pit.min.z, REAL.pit.max.x, middle - 1, REAL.pit.max.z],
      }));
      await tick.freeze(server, false);
      mark("drawn");
      await sleepMs(1_000);
      await setTickRate(server, WORK_RATE);
      const seen = { cleared: false, filled: false, weeded: false, felled: false, hauled: false };
      let last: any = {};
      const started = Date.now();
      while (Date.now() - started < 120_000) {
        await sleepMs(POLL_MS);
        last = await runColonyTask(server, GROUND_STATE, {
          box, trunk, pit: { min: { ...REAL.pit.min, y: middle - 1 }, max: { ...REAL.pit.max, y: middle - 1 } },
          item: stack,
        }).catch(() => last);
        if (!seen.cleared && last.solid === 0) { seen.cleared = true; mark("cleared"); }
        if (!seen.filled && last.pit === 0) { seen.filled = true; mark("filled"); }
        if (!seen.weeded && last.plants === 0) { seen.weeded = true; mark("weeded"); }
        if (!seen.felled && last.tree === "air") { seen.felled = true; mark("felled"); }
        if (!seen.hauled && last.items === 0) { seen.hauled = true; mark("hauled"); }
        if (Object.values(seen).every(Boolean)) break;
      }
      await setTickRate(server, 20);
      await sleepMs(2_000);
      return { seen, last, box };
    },
  });
  await screen.dismiss(client);
  run.note("groundwork", shot);
  expect.soft((shot as any).seen, `every groundwork work should be seen: ${JSON.stringify(shot)}`)
    .toEqual({ cleared: true, filled: true, weeded: true, felled: true, hauled: true });
}

const DRAW_ZONE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.draw-zone",
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
for (int i = 0; i < 6; i++) c[i] = ((Number) box.get(i)).intValue();
Set<BlockPos> cells = new LinkedHashSet<BlockPos>();
BlockPos.betweenClosed(new BlockPos(c[0], c[1], c[2]), new BlockPos(c[3], c[4], c[5]))
    .forEach(cell -> cells.add(cell.immutable()));
List<String> ticked = Arrays.asList(args.string("works").split(","));
ColonySettings chosen = ColonySettings.byDefault(ColonySchemas.ofDelegation(ground));
for (String work : List.of("clear", "fill", "weed", "fell", "haul")) {
    chosen = chosen.with(work, new ColonySettings.Value.Flag(ticked.contains(work)));
}
var drawn = ColonyFront.of(colony).addZone(ctx.server().overworld(), ground, cells, chosen);
return Sync.stamp(Map.of("drawn", drawn.isPresent(), "cells", cells.size()));
`,
  }),
});

const ERASE_GROUND = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.erase-ground",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var colony = ColonyGround.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony_id")))
    .orElseThrow(() -> Fail.notFound("no colony"));
var front = ColonyFront.of(colony);
var ground = ResourceLocation.fromNamespaceAndPath("folkways", "ground");
int erased = 0;
for (var zone : front.zones()) {
    if (zone.delegation().equals(ground) && front.erase(zone.id())) erased++;
}
return Sync.stamp(Map.of("erased", erased));
`,
  }),
});

// What is left of the groundwork: the box's solid blocks and plants, the trunk at the tree's foot, the pit's air
// cells, and the loose stack.
const GROUND_STATE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.ground-state",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.server.MinecraftServer", "net.minecraft.tags.BlockTags",
      "net.minecraft.world.entity.item.ItemEntity", "net.minecraft.world.phys.AABB"],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var box = (Map<?, ?>) args.value("box");
var pit = (Map<?, ?>) args.value("pit");
int solid = 0; int plants = 0; int open = 0;
for (var cell : BlockPos.betweenClosed(pos(box.get("min")), pos(box.get("max")))) {
  var state = level.getBlockState(cell);
  if (state.isAir()) continue;
  if (state.blocksMotion()) solid++; else plants++;
}
for (var cell : BlockPos.betweenClosed(pos(pit.get("min")), pos(pit.get("max")))) {
  if (level.getBlockState(cell).isAir()) open++;
}
boolean tree = level.getBlockState(pos(args.value("trunk"))).is(BlockTags.LOGS);
var item = pos(args.value("item"));
int items = level.getEntitiesOfClass(ItemEntity.class, new AABB(item).inflate(3, 2, 3)).size();
return Map.of("solid", solid, "plants", plants, "pit", open, "tree", tree ? "trunk" : "air", "items", items);
`,
    members: `
private static BlockPos pos(Object raw) {
  var p = (Map<?, ?>) raw;
  return new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
}
`,
  }),
});

// The settlers' beds and a chest of bread, sealed in stone under the hillside and out of the picture: the colony
// admits no settler it has no bed or food for.
const BED_CELLAR = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.bed-cellar",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.world.level.block.BedBlock",
      "net.minecraft.world.Container", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
      "net.minecraft.world.level.block.Blocks", "net.minecraft.world.level.block.state.properties.BedPart"],
    body: `
var level = ctx.server().overworld(); var args = Args.of(ctx);
int x0 = (int) args.integer("x"); int z0 = (int) args.integer("z"); int y0 = (int) args.integer("y");
int count = (int) args.integer("count");
for (int x = -1; x <= count * 2; x++) for (int y = -1; y <= 2; y++) for (int z = -2; z <= 1; z++)
  level.setBlock(new BlockPos(x0 + x, y0 + y, z0 + z), Blocks.STONE.defaultBlockState(), 2);
var beds = new ArrayList<Object>();
for (int i = 0; i < count; i++) {
  var foot = new BlockPos(x0 + i * 2, y0, z0);
  var bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
  level.setBlock(foot.above(), Blocks.AIR.defaultBlockState(), 2);
  level.setBlock(foot.north().above(), Blocks.AIR.defaultBlockState(), 2);
  level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
  level.setBlock(foot.north(), bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
  beds.add(Map.of("x", foot.getX(), "y", foot.getY(), "z", foot.getZ()));
}
var pantry = new BlockPos(x0 - 1, y0, z0);
level.setBlock(pantry, Blocks.CHEST.defaultBlockState(), 3);
var box = (Container) level.getBlockEntity(pantry);
for (int slot = 0; slot < 9; slot++) box.setItem(slot, new ItemStack(Items.BREAD, 64));
box.setChanged();
beds.add(Map.of("x", pantry.getX(), "y", pantry.getY(), "z", pantry.getZ()));
return Map.of("beds", beds);
`,
  }),
});

// The box the book is marking, read off the client.
const MARKED_BOX = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.marked-box",
  side: "client",
  timeoutMs: 10_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos"],
    body: `
var type = Class.forName("io.github.izakyl.folkways.front.client.ZoneSelection");
var minField = type.getDeclaredField("min"); minField.setAccessible(true);
var maxField = type.getDeclaredField("max"); maxField.setAccessible(true);
var min = (BlockPos) minField.get(null); var max = (BlockPos) maxField.get(null);
if (min == null || max == null) return Map.of("none", true);
return Map.of("min", List.of(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ())),
  "max", List.of(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ())));
`,
  }),
});

// Toasts left from joining (the chat it cannot vouch for) are cleared off the corner of the picture.
const CLEAR_TOASTS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.clear-toasts",
  side: "client",
  timeoutMs: 10_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
Minecraft.getInstance().getToasts().clear();
return Map.of("ok", true);
`,
  }),
});

// The top block of each cell that is not a tree's: the ground under the trunks and leaves.
const GROUND_TOPS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.ground-tops",
  side: "server",
  timeoutMs: 60_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.tags.BlockTags", "net.minecraft.world.level.levelgen.Heightmap"],
    body: `
var level = ctx.server().overworld();
var tops = new ArrayList<Object>();
for (var raw : Args.of(ctx).list("cells")) {
  var p = (Map<?, ?>) raw;
  int x = ((Number) p.get("x")).intValue(); int z = ((Number) p.get("z")).intValue();
  level.getChunk(x >> 4, z >> 4);
  int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
  while (level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LOGS)) y--;
  tops.add(y);
}
return Map.of("tops", tops);
`,
  }),
});

// ── 4. a box swept into the colony ───────────────────────────────────────────────────────────────────────
async function stageSweep(run: VisualRun, server: any, client: any, player: string, at_: Vec) {
  await tick.freeze(server, true);
  await clearBox(server, at(at_, -6, -4), at(at_, 6, 6));
  await clearBox(server, at(at_, -6, 5, -1), at(at_, 6, 6, -1), "minecraft:stone_bricks");
  await pave(server, at(at_, -6, -3), at(at_, 7, 6));
  // A market row under an awning, hay at either end and lamp posts by the counter.
  await awning(server, at(at_, -5, -2), at(at_, 7, 1));
  await tryCommand(server, `setblock ${commandPos(at(at_, 1, -1, 2))} minecraft:lantern[hanging=true]`);
  await tryCommand(server, `setblock ${commandPos(at(at_, -5, 0))} minecraft:hay_block`);
  await tryCommand(server, `setblock ${commandPos(at(at_, 7, 0))} minecraft:hay_block`);
  for (const dx of [-7, 8]) await lamp(server, at(at_, dx, 3), 2);
  const row: Array<[Vec, string]> = [
    [at(at_, -3, 0), "minecraft:barrel"], [at(at_, -1, 0), "minecraft:barrel"],
    [at(at_, 1, 0), "minecraft:chest"], [at(at_, 2, 0), "minecraft:chest[facing=south]"],
    [at(at_, 4, 0), "minecraft:furnace"], [at(at_, 5, 0), "minecraft:smoker"],
  ];
  for (const [cell, block] of row) await tryCommand(server, `setblock ${commandPos(cell)} ${block}`);
  await tick.sprint(server, 5);
  await setBookGesture(server, client, player, "point");
  const feet = at(at_, 0, 6);
  await teleport(server, player, feet, 180, 20);
  await tick.sprint(server, 5);
  // The view from above, over the market row from the south, before the shot.
  const view = await enterOverhead(client);
  await view.goTo({ x: at_.x + 1.5, y: at_.y, z: at_.z + 0.5 }, 180, 16, 50);
  await view.park();

  const shot = await film(run, client, server, "05-sweep", {
    subject: "In the book's view from above: the box gesture picked, a box marked with two clicks over a row of "
      + "barrels, a chest, a furnace and a smoker, Take in blocks picked with a right click, the stations unticked, "
      + "the rest taken in, and the colony's outlines stand on the blocks it took",
    during: async (mark) => {
      await tick.freeze(server, true);
      await sleepMs(1_200);
      mark("mark");
      await view.press("folkways.overhead.gesture.box");
      await sleepMs(600);
      for (const corner of [at(at_, -3, 0), at(at_, 5, 0)]) {
        await view.click({ x: corner.x + 0.5, y: corner.y + 1, z: corner.z + 0.5 }, "left", 800);
        await tick.sprint(server, 3);
        await sleepMs(400);
      }
      await tick.sprint(server, 5);
      mark("marked");
      await sleepMs(1_200);
      await view.click({ x: at_.x + 1.5, y: at_.y + 1, z: at_.z + 0.5 }, "right");
      await waitFor(client, SWEEP_OFFER);
      await settle(server, client, 8);
      mark("offer");
      await sleepMs(1_200);
      await click(client, SWEEP_OFFER);
      await settlePanel(server, client);
      await waitFor(client, SWEEP_STORES);
      await waitFor(client, SWEEP_STATIONS);
      await settle(server, client, 8);
      mark("kinds");
      await sleepMs(1_600);
      await click(client, SWEEP_STATIONS);
      await settlePanel(server, client);
      mark("unticked");
      await sleepMs(1_200);
      await drainActionBar(client);
      await click(client, SWEEP_TAKE);
      // Taking the blocks in closes the window, and the view from above takes the screen back.
      await expect.poll(async () => {
        await tick.sprint(server, 3);
        return screenName(await safeScreen(client)).includes("OverheadScreen");
      }, { timeout: 8_000, message: "taking the blocks in closes the window" }).toBe(true);
      await tick.freeze(server, false);
      await view.park();
      mark("taken");
      const bar = await readActionBar(client);
      await sleepMs(2_500);
      return { bar: bar.text };
    },
  });
  await screen.dismiss(client);
  run.note("sweep", shot);
  const bar: string = (shot as any).bar ?? "";
  expect(bar, `the sweep should report the blocks it took: ${JSON.stringify(shot)}`).toMatch(/纳入|Took in/);
  await tick.freeze(server, false);
}

// ── 5. packing by hand ────────────────────────────────────────────────────────────────────────────────────
async function stagePacking(run: VisualRun, server: any, client: any, player: string, frog: Vec, post: Vec,
  colony_id: string, residents: Array<{ uuid: string }>) {
  const packer = residents[5];
  const collector = residents[4];
  const builder = residents[1];
  await runColonyTask(server, FEED_CREW, { colony_id, crew: residents.map(one => ({ uuid: one.uuid, food: 20, tired: 0 })) });
  // Packs still full from the groundwork would send the crew home to unload before anyone packs.
  run.note("packs_emptied", await runColonyTask(server, EMPTY_PACKS, { crew: residents.map(one => one.uuid) }));
  await licenseCrewFast(server, [
    { uuid: packer.uuid, trades: ["hauling"] },
    // The far end builds with what it is sent: one resident there both takes the parcel off the port and lays it.
    { uuid: builder.uuid, trades: ["building", "hauling"] },
    // The postbox's collector waits at home until the postbox line, and carries nothing till then.
    ...[residents[0], collector, residents[2], residents[3]].map(one => ({ uuid: one.uuid, trades: [] })),
  ]);
  run.note("builder_kit", await runColonyTask(server, GIVE_PACK, { uuid: builder.uuid, items: [...TRADE_TOOLS.building] }));

  // A. frogport: the packager below a frogport, a chest of stock beside it, and another frogport down the chain.
  await clearBox(server, at(frog, -14, -6), at(frog, 72, 8));
  await clearBox(server, at(frog, -14, -1, 1), at(frog, 72, 1, 6));
  const packager = at(frog, 0, 0);
  const senderPort = above(packager);
  const source = at(frog, 0, -1);
  // The kiln waits at the far end of the line, so packing and sending is plainly cheaper than carrying it there: a
  // walk costs ~6 ticks a block, the chain well under one. Create joins hubs at most 32 apart. The hubs stand on
  // shafts four up, as in the logistics take, so the chain runs overhead against the sky rather than along the grass.
  const hubs = [3, 19, 35, 51, FAR].map(dx => at(frog, dx, 0, CHAIN_LIFT));
  const receiverPort = at(frog, FAR, 3);
  await dressFrogYard(server, frog);
  const link = at(frog, -6, 6);
  const ticker = at(frog, -5, 6);
  for (const [cell, block] of [
    [packager, "create:packager[facing=south]"],
    [senderPort, "create:package_frogport"],
    [source, "minecraft:chest[facing=south]"],
    [receiverPort, "create:package_frogport"],
    ...hubs.map((hub): [Vec, string] => [hub, "create:chain_conveyor"]),
    [link, "create:stock_link[face=wall,facing=north]"],
    [ticker, "create:stock_ticker[facing=east]"],
  ] as Array<[Vec, string]>) {
    await tryCommand(server, `setblock ${commandPos(cell)} ${block}`);
  }
  for (const chain of hubs) {
    await clearBox(server, { ...chain, y: frog.y - 1 }, { ...chain, y: chain.y - 1 }, "create:shaft[axis=y]");
    await tryCommand(server, `setblock ${commandPos({ ...chain, y: frog.y - 2 })} create:creative_motor[facing=up]{ScrollValue:256}`);
  }
  await tick.sprint(server, 20);
  // The goods lie in a colony store at the packager: carried from there, packing beats the walk; drawn from the
  // colony's stores back home, both start with the same long walk and the walk on to the kiln wins.
  await tryCommand(server, `item replace block ${commandPos(source)} container.0 with ${PACK_ITEM} ${PACK_ORDER + 8}`);
  await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: [source.x, source.y, source.z] });
  run.note("chains", await runColonyTask(server, CHAIN_SPEED, {
    chains: hubs.flatMap(cell => [cell.x, cell.y, cell.z]),
  }).catch((error) => ({ error: String(error) })));
  const wired = await runColonyTask(server, WIRE_NETWORK, {
    player, colony_id, packager, link, send: senderPort, receive: receiverPort, ticker,
    chains: hubs, send_chains: [0], receive_chain: hubs.length - 1, edges: hubs.slice(1).map((_, i) => [i, i + 1]),
    assign_crew: false,
  });
  run.note("frogport_wired", wired);
  run.note("reach", await runColonyTask(server, CHAIN_REACH, {
    from: senderPort, to: receiverPort,
  }).catch((error) => ({ error: String(error) })));
  run.note("counter_ready", await waitForCounter(server, colony_id, String(wired.network), senderPort, receiverPort));
  // The packer stands beside the packager, within its reach of five blocks, and packs where it stands; the builder
  // waits by the kiln's plot.
  await placeResident(server, packer.uuid, at(frog, -2, 3));
  await placeResident(server, builder.uuid, at(frog, FAR + 6, 6));

  // The kiln is ordered off camera before anything is packed: its bricks are what the packer packs, and the builder
  // takes them off the port and lays them. Ordering runs the world, so it is held again until the shot.
  const kilnCorner = at(frog, FAR + 3, 4, -1);
  // The log the book is opened from stands behind the aiming spot: between it and the corner, the aim lands on the
  // log and the kiln is set down on top of it. It is taken away again once the kiln is ordered, out of the shot.
  const farStand = at(frog, FAR - 4, 10);
  await tryCommand(server, `setblock ${commandPos(farStand)} minecraft:oak_log`);
  run.note("kiln_order", await orderBlueprint(server, client, player, "kiln", farStand, at(frog, FAR - 4, 8), kilnCorner));
  await tryCommand(server, `setblock ${commandPos(farStand)} minecraft:air`);
  await tick.freeze(server, true);
  await teleport(server, player, at(frog, 0, 10), 180, 10);
  run.note("dispatch_before", await runColonyTask(server, DISPATCH_STATE, {
    colony_id, network: String(wired.network),
  }).catch((error) => ({ error: String(error) })));

  // Low and to the left of the packing yard, tilted up a little so the chain leads away overhead to the right.
  const eyeA = { x: frog.x - 3.5, y: frog.y + 2.4, z: frog.z + 9.5 };
  const lookA = cameraLookingAt(eyeA, { x: frog.x + 4.5, y: frog.y + 2.5, z: frog.z + 0.5 });
  const shotA = await film(run, client, server, "06-frogport", {
    subject: "A resident walks up to a packager under a frogport, packs the goods by hand and puts the parcel into the "
      + "port, which throws it onto the chain for the port down the line",
    camera: { position: eyeA, yaw: lookA.yaw, pitch: lookA.pitch, fov: 68 },
    during: async (mark) => {
      await setHud(client, false);
      await tick.freeze(server, false);
      await setTickRate(server, 30);
      const seen = await watchPacking(server, packer.uuid, mark, () => portParcels(server, senderPort), 40_000);
      const probe = await runColonyTask(server, DISPATCH_STATE, {
        colony_id, network: String(wired.network),
      }).catch((error) => ({ error: String(error) }));
      const packerState = await runColonyTask(server, RESIDENT_STATE, { colony_id }).catch((error) => ({ error: String(error) }));
      await setTickRate(server, 20);
      await sleepMs(4_000);
      return { seen, probe, packerState };
    },
  });
  run.note("frogport", shotA);
  expect((shotA as any).seen?.packing || (shotA as any).seen?.parcel,
    `the resident should pack a parcel into the frogport: ${JSON.stringify(shotA)}`).toBe(true);

  // A, far end: the parcel lands, and the builder takes it off the port and lays the kiln from its bricks.
  const eyeF = { x: frog.x + FAR - 6.5, y: frog.y + 3, z: frog.z + 12.5 };
  const lookF = cameraLookingAt(eyeF, { x: frog.x + FAR + 2, y: frog.y + 1.5, z: frog.z + 2.5 });
  const cameraF = { position: eyeF, yaw: lookF.yaw, pitch: lookF.pitch, fov: 62 };
  const kilnBox = { min: at(kilnCorner, -1, -1), max: at(kilnCorner, 3, 3, KILN.height + 1) };
  const laid = async (last: number) => Number((await runColonyTask(server, BRICKS_IN, { ...kilnBox, item: PACK_ITEM })
    .catch(() => ({ count: last })) as any).count ?? last);
  const shotR = await film(run, client, server, "06b-frogport-far", {
    subject: "Down the line, from as far off: the parcel lands at the receiving frogport, and the resident there takes "
      + "the bricks off the port and lays them into a small kiln",
    camera: cameraF,
    during: async (mark) => {
      await setHud(client, false);
      await setTickRate(server, 30);
      let built = 0;
      let landed = false;
      const started = Date.now();
      while (Date.now() - started < 90_000) {
        await sleepMs(POLL_MS);
        if (!landed && await portParcels(server, receiverPort) > 0) { landed = true; mark("landed"); }
        const before = built;
        built = await laid(built);
        if (before === 0 && built > 0) mark("first");
        if (built >= KILN.bricks) { mark("built"); break; }
      }
      await setTickRate(server, 20);
      const probe = {
        port: await portParcels(server, receiverPort),
        crew: await runColonyTask(server, RESIDENT_STATE, { colony_id }).catch((error) => ({ error: String(error) })),
      };
      await sleepMs(2_500);
      return { landed, built, probe };
    },
  });
  run.note("frogport_far", shotR);
  expect.soft((shotR as any).built, `the far end should build the kiln from the parcel it was sent: ${JSON.stringify(shotR)}`)
    .toBeGreaterThanOrEqual(KILN.bricks);
  // The builder is done and nobody at the postbox line goes off to build; the collector takes up hauling there.
  await licenseCrewFast(server, [{ uuid: builder.uuid, trades: [] }, { uuid: collector.uuid, trades: ["hauling"] }]);

  // B. postbox: the same by hand, but the parcel waits in the postbox for the colony's train.
  const railOrigin = at(post, 0, 0);
  const playerUuid = (await world.players(server)).find((one: any) => one.name === player)?.uuid;
  expect(playerUuid, "the player uuid is needed to hand the train over").toBeTruthy();
  await tryCommand(server, `item replace entity ${player} inventory.0 with folkways:colony_book`);
  const rail = await buildRailLine(server, client, player, String(playerUuid), railOrigin, 56, "inventory.0", {
    cyclic: true,
    // Short stops, so the train brings a parcel sooner than a walk the length of the line would.
    dwell: { Alpha: 5, Beta: 5 },
    decorate: async (srv: any, _origin: Vec, frame: any) => {
      await tryCommand(srv, `setblock ${commandPos(frame.at(1, 3, 2))} minecraft:chest`);
    },
  });
  run.note("rail", { ok: rail.ok, stationA: rail.stationA, stationB: rail.stationB, handedOver: rail.handedOver, error: rail.error });
  expect(rail.ok, `the postbox line should be laid: ${rail.error} ${JSON.stringify(rail.steps).slice(0, 500)}`).toBe(true);
  await tryCommand(server, `gamemode survival ${player}`);
  const a = rail.stationA;
  const b = rail.stationB;
  const pPackager = { x: a.x + 5, y: a.y, z: a.z + 4 };
  const senderPost = above(pPackager);
  const pSource = { x: pPackager.x, y: a.y, z: pPackager.z + 1 };
  const receiverPost = { x: b.x + 5, y: b.y, z: b.z + 4 };
  const pTarget = { x: b.x + 8, y: b.y, z: b.z + 4 };
  for (const [cell, block] of [
    [pPackager, "create:packager[facing=south]"],
    [senderPost, "create:white_postbox"],
    [pSource, "minecraft:chest[facing=south]"],
    [receiverPost, "create:white_postbox"],
    [pTarget, "minecraft:chest[facing=south]"],
  ] as Array<[Vec, string]>) {
    await clearBox(server, cell, cell);
    await tryCommand(server, `setblock ${commandPos(cell)} ${block}`);
  }
  await tryCommand(server, `item replace block ${commandPos(pSource)} container.0 with ${PACK_ITEM} ${PACK_ORDER + 8}`);
  // A paved goods yard off the platform, barrels behind the packager, lamps, and woods either side of the line.
  await pave(server, { x: a.x + 4, y: a.y, z: a.z - 1 }, { x: a.x + 10, y: a.y, z: a.z + 9 });
  for (const [dx, dz, dy] of [[1, -1, 0], [2, -1, 0], [1, -1, 1], [-1, -1, 0]]) {
    await tryCommand(server, `setblock ${commandPos(at(pPackager, dx, dz, dy))} minecraft:barrel[facing=up] keep`);
  }
  await tryCommand(server, `setblock ${commandPos(at(pPackager, 2, -1, 1))} minecraft:lantern keep`);
  await lamp(server, at(pPackager, 4, -1), 2);
  await lamp(server, at(pPackager, 7, 3), 2);
  await treeRow(server, { x: a.x + 14, y: a.y, z: a.z - 40 }, a.z + 12, 6);
  await treeRow(server, { x: a.x - 11, y: a.y, z: a.z - 40 }, a.z + 12, 7);
  await meadow(server, [at(pPackager, 7, 2), at(pPackager, 8, -4), at(pPackager, 7, 8)]);
  const posts = await runColonyTask(server, WIRE_POSTS, {
    player,
    posts: [
      { post: senderPost, station: a, address: "", accepts: false },
      { post: receiverPost, station: b, address: "Beta Post", accepts: true },
    ],
  });
  run.note("posts", posts);
  expect(posts.attached, `both postboxes should stand by their stations: ${JSON.stringify(posts)}`).toEqual([true, true]);
  await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: [pTarget, pSource, senderPost, receiverPost].flatMap(cell => [cell.x, cell.y, cell.z]),
  });
  // The colony's train runs only with a resident licensed to drive it in the seat, and a parcel is sent only by a train
  // it can timetable: the order goes in once the train runs, so the packing happens on camera.
  const driver = residents[3];
  await licenseCrewFast(server, [{ uuid: driver.uuid, trades: ["conducting"] }]);
  await placeResident(server, driver.uuid, { x: a.x + 3, y: a.y, z: a.z });
  run.note("mail_reach", await waitForMail(server, senderPost, receiverPost));
  run.note("post_counter_ready", await waitForCounter(server, colony_id, ""));
  await placeResident(server, packer.uuid, { x: pPackager.x + 2, y: pPackager.y, z: pPackager.z + 3 });
  await placeResident(server, collector.uuid, { x: receiverPost.x, y: receiverPost.y, z: receiverPost.z + 4 });
  await teleport(server, player, { x: pPackager.x, y: pPackager.y, z: pPackager.z + 10 }, 180, 10);
  // The weave prices the parcel by the train that would carry it: ordered while the train is off down the line, the
  // next one is a round trip away and the walk wins. The order goes in while the train stands at the near platform.
  run.note("train_at_alpha", await waitForTrainAt(server, senderPost, receiverPost));
  await setMaintainDemandsFast(server, player, [{ at: pTarget, item: PACK_ITEM, count: PACK_ORDER, low: PACK_ORDER }]);

  // From south-west of the yard, the line on the left running off down the frame and the packager on the right: the
  // packing, and the train calling at the platform and pulling out with the parcel, all in one view.
  const eyeB = { x: a.x - 3.5, y: pPackager.y + 4, z: pPackager.z + 10.5 };
  const lookB = cameraLookingAt(eyeB, { x: pPackager.x - 1.5, y: pPackager.y + 1, z: pPackager.z - 4.5 });
  const shotB = await film(run, client, server, "07-postbox", {
    subject: "A resident packs goods by hand at a packager beside a postbox and puts the parcel in; it waits there "
      + "for the colony's train",
    camera: { position: eyeB, yaw: lookB.yaw, pitch: lookB.pitch, fov: 66 },
    during: async (mark) => {
      await setHud(client, false);
      await setTickRate(server, 30);
      const seen = await watchPacking(server, packer.uuid, mark, () => portParcels(server, senderPost), 60_000);
      const probe = await runColonyTask(server, DISPATCH_STATE, {
        colony_id, network: "",
      }).catch((error) => ({ error: String(error) }));
      // The parcel is taken off the postbox by the train, and the train is seen pulling out with it.
      const departure = seen.parcel ? await watchDeparture(server, senderPost, receiverPost, mark, 90_000) : null;
      await setTickRate(server, 20);
      await sleepMs(departure?.departed ? 4_000 : 2_000);
      return { seen, probe, departure };
    },
  });
  run.note("postbox", shotB);
  expect.soft((shotB as any).seen?.packing || (shotB as any).seen?.parcel,
    `the resident should pack a parcel into the postbox: ${JSON.stringify(shotB)}`).toBe(true);
}

// The packing yard: paved, a lean-to shed of barrels behind the packager, lamps, and a wood behind it all. Nothing
// here is a colony member, so the packer draws only on the chest beside the packager.
async function dressFrogYard(server: any, frog: Vec) {
  await pave(server, at(frog, -8, -5), at(frog, 8, 7));
  await pave(server, at(frog, FAR - 5, -1), at(frog, FAR + 6, 8));
  // The shed stands well back: a roof or barrels hard against the store chest leave the packer no way to it.
  await clearBox(server, at(frog, -5, -6), at(frog, 1, -6, 2), "minecraft:spruce_planks");
  for (const dx of [-5, 1]) await clearBox(server, at(frog, dx, -6), at(frog, dx, -6, 2), "minecraft:stripped_spruce_log");
  await clearBox(server, at(frog, -6, -6, 3), at(frog, 2, -4, 3), "minecraft:spruce_slab");
  for (const [dx, dz, dy] of [[-4, -5, 0], [-3, -5, 0], [-4, -5, 1], [0, -5, 0]]) {
    await tryCommand(server, `setblock ${commandPos(at(frog, dx, dz, dy))} minecraft:barrel[facing=up]`);
  }
  await tryCommand(server, `setblock ${commandPos(at(frog, -3, -5, 1))} minecraft:lantern`);
  await lamp(server, at(frog, -6, 2), 2);
  await lamp(server, at(frog, 7, 3), 2);
  await lamp(server, at(frog, FAR - 3, 0), 2);
  await treeline(server, at(frog, -16, -10), frog.x + 26, 4);
  await meadow(server, [at(frog, -11, 4), at(frog, 11, -3), at(frog, 14, 5), at(frog, -12, -4)]);
}

// Orders a blueprint from the shelf as a player would, off camera: the build page, the shelf, the ghost set down
// at the corner aimed at, and the hand-off's Place the build order.
async function orderBlueprint(server: any, client: MinecraftClient, player: string, name: string, standBlock: Vec,
  feet: Vec, corner: Vec) {
  await screen.dismiss(client);
  await openColonyPanelViaBook(server, client, player, standBlock);
  await openTab(server, client, "folkways.page.build");
  await settle(server, client, 8);
  await click(client, "folkways.board.open.folkways.build.0.0");
  await waitFor(client, "folkways.window.folkways.blueprints");
  await settle(server, client, 8);
  await click(client, `folkways.blueprints.entry.local.blueprint.${name}`);
  await settle(server, client, 8);
  await tick.freeze(server, false);
  await tryCommand(server, `gamemode creative ${player}`);
  await safeAction(() => frames(client, 10));
  // The ghost follows the crosshair only as frames are drawn: clicked straight after the turn, it is set down where
  // the book was opened from, on the log.
  const eye = { x: feet.x + 0.5, y: feet.y + EYE, z: feet.z + 0.5 };
  const look = cameraLookingAt(eye, { x: corner.x + 0.5, y: corner.y + 1, z: corner.z + 0.5 });
  await tryCommand(server, `tp ${player} ${eye.x} ${feet.y} ${eye.z} ${look.yaw} ${look.pitch}`);
  await tick.sprint(server, 3);
  await safeAction(() => frames(client, 20));
  await safeAction(() => input.button(client, { button: "left", action: "press" }));
  await safeAction(() => input.button(client, { button: "left", action: "release" }));
  await waitFor(client, "folkways.window.blueprint_handoff");
  await settle(server, client, 8);
  await drainActionBar(client);
  await click(client, HANDOFF_CONFIRM);
  await settle(server, client, 10);
  const bar = await readActionBar(client);
  await screen.dismiss(client);
  await tryCommand(server, `gamemode survival ${player}`);
  return { bar: bar.text };
}

async function waitForCounter(server: any, colony_id: string, network: string, from?: Vec, to?: Vec) {
  let last: any = null;
  let route: any = null;
  for (let attempt = 0; attempt < 40; attempt++) {
    await tick.sprint(server, 10);
    last = await runColonyTask(server, DISPATCH_STATE, { colony_id, network }).catch((error) => ({ error: String(error) }));
    const shops = Array.isArray(last?.workshops) ? last.workshops : [];
    if (!shops.some((shop: any) => shop.class === "Counter")) continue;
    if (!from || !to) return { ready: true, waited: attempt * 10, state: last, route };
    route = await runColonyTask(server, CHAIN_REACH, { from, to }).catch((error) => ({ error: String(error) }));
    if (route?.toReached && route?.ticks && route.ticks !== "OptionalInt.empty") {
      return { ready: true, waited: attempt * 10, state: last, route };
    }
  }
  return { ready: false, waited: 400, state: last, route };
}

async function waitForMail(server: any, from: Vec, to: Vec) {
  let last: any = null;
  for (let attempt = 0; attempt < 60; attempt++) {
    last = await runColonyTask(server, MAIL_REACH, { from, to }).catch((error) => ({ error: String(error) }));
    if (String(last?.leg ?? "").startsWith("Optional[")) return { ready: true, waited: attempt * 20, last };
    await tick.sprint(server, 20);
  }
  return { ready: false, waited: 1200, last };
}

// Until the train calls at the platform, takes the parcel waiting in the postbox aboard, and pulls out with it.
async function watchDeparture(server: any, from: Vec, to: Vec, mark: FilmMark, timeout: number) {
  const seen = { arrived: false, boarded: false, departed: false };
  const started = Date.now();
  while (Date.now() - started < timeout) {
    await sleepMs(POLL_MS);
    const reach: any = await runColonyTask(server, MAIL_REACH, { from, to }).catch(() => null);
    const atAlpha = (reach?.trains ?? []).some((train: any) => train.at === "Alpha");
    if (!seen.arrived && atAlpha) { seen.arrived = true; mark("arrived"); }
    if (!seen.arrived) continue;
    if (!seen.boarded && await portParcels(server, from) === 0) { seen.boarded = true; mark("boarded"); }
    if (seen.boarded && !atAlpha) { seen.departed = true; mark("departed"); break; }
  }
  return seen;
}

// Until the train that carries a parcel from one postbox to the other has just pulled in at the first: its leg is
// then the shortest it gets, one run down the line, with the stop still long enough to pack in.
async function waitForTrainAt(server: any, from: Vec, to: Vec) {
  let last: any = null;
  for (let attempt = 0; attempt < 120; attempt++) {
    last = await runColonyTask(server, MAIL_REACH, { from, to }).catch((error) => ({ error: String(error) }));
    const arrives = Number(/arrives=(\d+)/.exec(String(last?.leg ?? ""))?.[1] ?? Infinity);
    const train = (last?.trains ?? []).find((one: any) => one.at === "Alpha");
    const leaves = Number(/station=Alpha, arrivesIn=\d+, leavesIn=(\d+)/.exec(String(train?.stops ?? ""))?.[1] ?? 0);
    if (train && leaves >= 70) return { ready: true, waited: attempt * 10, arrives, leaves, last };
    await tick.sprint(server, 10);
  }
  return { ready: false, waited: 1200, last };
}

async function watchPacking(server: any, uuid: string, mark: FilmMark, parcels: () => Promise<number>, timeout = WORK_TIMEOUT_MS) {
  const seen = { walked: false, packing: false, parcel: false };
  const started = Date.now();
  while (Date.now() - started < timeout) {
    await sleepMs(POLL_MS);
    const doing = await readDoing(server, uuid).catch(() => null);
    if (!seen.walked && doing === null) { seen.walked = true; mark("walking"); }
    if (!seen.packing && String(doing ?? "").endsWith("packing")) { seen.packing = true; mark("packing"); }
    // A frogport throws its parcel on within a poll or two, so the packing ending counts as the parcel sent.
    const posted = seen.packing && !String(doing ?? "").endsWith("packing");
    if (!seen.parcel && (posted || await parcels() > 0)) { seen.parcel = true; mark("posted"); break; }
  }
  return seen;
}

async function portParcels(server: any, at_: Vec): Promise<number> {
  const read = await runColonyTask(server, PORT_PARCELS, { at: at_ }).catch(() => ({ parcels: 0 }));
  return Number((read as any).parcels ?? 0);
}

const FEED_CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.feed-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent",
      "java.lang.reflect.AccessibleObject",
      "java.lang.reflect.Constructor",
    ],
    body: `
var server = ctx.server(); var args = Args.of(ctx);
Colony colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
Object presence = colony.service(LivingContent.ID, Object.class).orElseThrow();
@SuppressWarnings("unchecked")
Map<UUID,Object> bellies = (Map<UUID,Object>) open(presence.getClass().getDeclaredField("bellies")).get(presence);
@SuppressWarnings("unchecked")
Map<UUID,Object> wear = (Map<UUID,Object>) open(presence.getClass().getDeclaredField("wear")).get(presence);
Class<?> hungerType = Class.forName("io.github.izakyl.folkways.plugins.person.living.Hunger");
Class<?> restType = Class.forName("io.github.izakyl.folkways.plugins.person.living.Rest");
Constructor<?> newHunger = open(hungerType.getDeclaredConstructor());
Constructor<?> newRest = open(restType.getDeclaredConstructor());
for (var raw : args.list("crew")) {
  var one = (Map<?,?>) raw; var id = UUID.fromString(String.valueOf(one.get("uuid")));
  Object belly = bellies.computeIfAbsent(id, key -> make(newHunger));
  open(hungerType.getDeclaredField("foodLevel")).setInt(belly, ((Number) one.get("food")).intValue());
  open(hungerType.getDeclaredField("saturationLevel")).setFloat(belly, 5.0F);
  Object rest = wear.computeIfAbsent(id, key -> make(newRest));
  open(restType.getDeclaredField("tired")).setDouble(rest, ((Number) one.get("tired")).doubleValue());
}
return Sync.stamp(Map.of("ok", true));
`,
    members: `
private static Object make(Constructor<?> constructor) {
    try { return constructor.newInstance(); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
}
private static <T extends AccessibleObject> T open(T member) { member.setAccessible(true); return member; }
`,
  }),
});

const GIVE_PACK = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.give-pack",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var level = ctx.server().overworld(); var args = Args.of(ctx);
var pack = Bodies.of(level.getEntity(UUID.fromString(args.string("uuid")))).orElseThrow().pack();
var given = new ArrayList<String>();
for (var raw : args.list("items")) {
  var stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(raw))));
  for (int slot = 0; slot < pack.getContainerSize(); slot++) {
    if (pack.getItem(slot).isEmpty()) { pack.setItem(slot, stack); given.add(stack.toString()); break; }
  }
}
return Map.of("ok", true, "given", given);
`,
  }),
});

const BRICKS_IN = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.bricks-in",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation"],
    body: `
var level = ctx.server().overworld(); var args = Args.of(ctx);
var min = (Map<?, ?>) args.value("min"); var max = (Map<?, ?>) args.value("max");
var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(args.string("item")));
int count = 0; var found = new ArrayList<String>();
for (var cell : BlockPos.betweenClosed(
    new BlockPos(((Number) min.get("x")).intValue(), ((Number) min.get("y")).intValue(), ((Number) min.get("z")).intValue()),
    new BlockPos(((Number) max.get("x")).intValue(), ((Number) max.get("y")).intValue(), ((Number) max.get("z")).intValue()))) {
  if (level.getBlockState(cell).is(block)) { count++; if (args.has("cells")) found.add(cell.toShortString()); }
}
return Map.of("count", count, "cells", found);
`,
  }),
});

const EMPTY_PACKS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.empty-packs",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.resident.body.Bodies", "net.minecraft.core.component.DataComponents"],
    body: `
var level = ctx.server().overworld(); var args = Args.of(ctx);
var held = new LinkedHashMap<String, Object>();
for (var raw : args.list("crew")) {
  var pack = Bodies.of(level.getEntity(UUID.fromString(String.valueOf(raw)))).orElseThrow().pack();
  var items = new ArrayList<String>();
  for (int slot = 0; slot < pack.getContainerSize(); slot++) {
    if (!pack.getItem(slot).isEmpty()) items.add(pack.getItem(slot).toString());
  }
  held.put(String.valueOf(raw), items);
  // The food stays: an empty larder in the pack is filled from the colony's stores, a walk home across the scene.
  for (int slot = 0; slot < pack.getContainerSize(); slot++) {
    if (!pack.getItem(slot).has(DataComponents.FOOD)) pack.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
  }
}
return Map.of("ok", true, "held", held);
`,
  }),
});

const PLACE_RESIDENT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.place-resident",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var entity = level.getEntity(UUID.fromString(args.string("uuid")));
if (entity == null) throw new IllegalStateException("No such resident: " + args.string("uuid"));
double x = ((Number) args.value("x")).doubleValue();
double y = ((Number) args.value("y")).doubleValue();
double z = ((Number) args.value("z")).doubleValue();
entity.teleportTo(x + 0.5, y, z + 0.5);
entity.setYRot(((Number) args.value("yaw")).floatValue());
entity.setXRot(((Number) args.value("pitch")).floatValue());
// The colony reads where a resident stands again only when its licences change: each is set again as it was, so a
// plan made after this starts from here rather than from where the resident was taken from.
var body = Bodies.of(entity).orElseThrow();
for (var vocation : Vocations.all()) body.licences().trade(vocation)
    .ifPresent(trade -> body.licences().allow(trade, body.licences().allows(trade)));
return Map.of("ok", true, "x", entity.getBlockX(), "z", entity.getBlockZ());
`,
  }),
});

const PORT_PARCELS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.port-parcels",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
      "net.minecraft.core.BlockPos", "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var p = (Map<?, ?>) args.value("at");
var at = new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
var port = (PackagePortBlockEntity) level.getBlockEntity(at);
if (port == null) return Map.of("parcels", 0, "note", "no port");
int parcels = 0;
for (int i = 0; i < port.inventory.getSlots(); i++) if (!port.inventory.getStackInSlot(i).isEmpty()) parcels++;
return Map.of("parcels", parcels);
`,
  }),
});

const WIRE_POSTS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.wire-posts",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
      "com.simibubi.create.content.logistics.packagePort.PackagePortTarget.TrainStationFrogportTarget",
      "com.simibubi.create.content.trains.station.StationBlockEntity",
      "net.minecraft.core.BlockPos", "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var attached = new ArrayList<Object>();
for (var raw : args.list("posts")) {
  var one = (Map<?, ?>) raw;
  var post = pos(one.get("post")); var station = pos(one.get("station"));
  var port = (PackagePortBlockEntity) level.getBlockEntity(post);
  port.addressFilter = String.valueOf(one.get("address"));
  port.acceptsPackages = Boolean.TRUE.equals(one.get("accepts"));
  port.target = new TrainStationFrogportTarget(station.subtract(post));
  port.target.setup(port, level, post);
  port.target.register(port, level, post);
  port.filterChanged();
  port.notifyUpdate();
  var sbe = (StationBlockEntity) level.getBlockEntity(station);
  attached.add(sbe.getStation() != null && sbe.getStation().connectedPorts.containsKey(post));
}
return Sync.stamp(Map.of("ok", true, "attached", attached));
`,
    members: `
private static BlockPos pos(Object raw) {
  var p = (Map<?, ?>) raw;
  return new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
}
`,
  }),
});

const MAIL_REACH = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.mail-reach",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.Create",
      "com.simibubi.create.content.trains.entity.Carriage",
      "io.github.izakyl.folkways.create.dispatch.PackageNetworks",
      "io.github.izakyl.folkways.create.rail.domain.TransitNetworks",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
BlockPos from = pos(args.value("from")); BlockPos to = pos(args.value("to"));
var network = PackageNetworks.get();
var fromStation = network.stationOf(level, from).orElse(null);
var toStation = network.stationOf(level, to).orElse(null);
var leg = network.route(level, from, to, id -> true).toString();
var trains = new ArrayList<Object>();
var transit = TransitNetworks.get();
for (var train : Create.RAILWAYS.sided(level).trains.values()) {
  boolean goods = false;
  for (Carriage carriage : train.carriages) goods |= carriage.storage.getAllItems().getSlots() > 0;
  var runtime = train.runtime;
  trains.add(Map.of("id", train.id.toString(), "goods", goods,
    "serviceable", transit.isServiceable(server, train.id),
    "stops", transit.timetable(server, train.id).toString(),
    "fault", transit.fault(server, train.id).toString(),
    "state", String.valueOf(runtime.state),
    "at", String.valueOf(train.getCurrentStation() == null ? "none" : train.getCurrentStation().name)));
}
return Map.of("fromStation", fromStation == null ? "none" : fromStation,
  "toStation", toStation == null ? "none" : toStation, "leg", leg, "trains", trains);
`,
    members: `
private static BlockPos pos(Object raw) {
  var p = (Map<?, ?>) raw;
  return new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
}
`,
  }),
});

const CHAIN_REACH = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.chain-reach",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.create.dispatch.PackageNetworks",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
BlockPos from = pos(args.value("from")); BlockPos to = pos(args.value("to"));
var net = PackageNetworks.get();
var reached = net.reachedFrom(level, from, id -> true);
var hops = new ArrayList<Object>();
for (var at : reached) hops.add(at.toShortString() + ":" + net.handTicks(level, from, at, id -> true).toString());
return Map.of("from", from.toShortString(), "to", to.toShortString(), "packer", net.packerAt(level, from).toString(),
  "reached", hops, "toReached", reached.contains(to), "ticks", net.handTicks(level, from, to, id -> true).toString());
`,
    members: `
private static BlockPos pos(Object raw) {
  var p = (Map<?, ?>) raw;
  return new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
}
`,
  }),
});

const CHAIN_SPEED = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.chain-speed",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var speeds = new ArrayList<Object>();
var flat = args.list("chains");
for (int i = 0; i < flat.size(); i += 3) {
  var at = new BlockPos(((Number) flat.get(i)).intValue(), ((Number) flat.get(i + 1)).intValue(),
      ((Number) flat.get(i + 2)).intValue());
  var be = level.getBlockEntity(at);
  speeds.add(be instanceof ChainConveyorBlockEntity chain
      ? chain.getSpeed() + "/" + chain.getTheoreticalSpeed() + "/" + chain.isOverStressed() : "not-a-chain");
}
return Map.of("speeds", speeds);
`,
  }),
});

const DISPATCH_STATE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.dispatch-state",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.engine.colony.ColonyWorks",
      "io.github.izakyl.folkways.create.dispatch.DispatchContent",
      "io.github.izakyl.folkways.create.dispatch.DispatchPresence",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var colony = (ColonyWorks) ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var workshops = new ArrayList<Object>();
for (var shop : colony.workshopsIn(level.dimension())) {
  var one = new LinkedHashMap<String, Object>();
  one.put("class", shop.getClass().getSimpleName());
  one.put("site", shop.site().where().cell().toShortString());
  // A Counter's desks, each reached port with what the weave prices it at: read through the package-private record.
  try {
    var desksOf = shop.getClass().getDeclaredMethod("desks");
    desksOf.setAccessible(true);
    var desks = new ArrayList<Object>();
    for (var desk : (List<?>) desksOf.invoke(shop)) {
      desks.add(String.valueOf(desk));
    }
    one.put("desks", desks);
  } catch (NoSuchMethodException ignored) {
  } catch (Exception error) {
    one.put("desks_error", String.valueOf(error));
  }
  workshops.add(one);
}
var dispatch = (DispatchPresence) colony.service(DispatchContent.ID, Object.class).orElseThrow();
var written = args.string("network");
UUID network = written.isEmpty() ? null : UUID.fromString(written);
var ports = colony.holdings(DispatchContent.PORT).size();
return Map.of("workshops", workshops,
  "held", network != null && dispatch.holds(network),
  "flaw", network == null ? "" : dispatch.flawOf(network),
  "ports", ports, "orders", colony.kept(ResourceLocation.fromNamespaceAndPath("folkways", "orders")).toString());
`,
  }),
});

const RESIDENT_STATE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.changes.resident-state",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var crew = new ArrayList<Object>();
for (var resident : colony.residents()) {
  var entity = (ResidentEntity) level.getEntity(resident.id());
  crew.add(Map.of(
    "id", resident.id().toString(),
    "look", entity == null ? "" : entity.lookId().map(key -> key.location().toString()).orElse(""),
    "doing", entity == null ? "" : Bodies.of(entity).flatMap(body -> body.doing())
        .map(doing -> doing.what().toString()).orElse(""),
    "x", entity == null ? -1 : entity.getBlockX(), "y", entity == null ? -1 : entity.getBlockY(),
    "z", entity == null ? -1 : entity.getBlockZ()));
}
return Map.of("ok", true, "crew", crew);
`,
  }),
});
