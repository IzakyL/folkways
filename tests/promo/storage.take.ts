import { expect, test } from "@playwright/test";
import {
  defineTask, input, javaTask, screen, tick, world, type MinecraftClient, type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { shaderEvidence } from "../shared/capture/shaders";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, safeAction, softly, tryCommand } from "../shared/harness/bw-helpers";
import { admitPromoSettlersFast, foundPromoColonyFast, setMaintainDemandsFast } from "./fast-setup";
import { setVitals } from "./cookery-scene";
import { cinematicWorld, launchPromoPair, promoClip, setFov, sleepMs, take, type PromoPair } from "./promo-kit";
import { rigMove, rigStop, type RigMove } from "./debug-rig";
import { CURSOR } from "./overhead-view";
import {
  AE2_RIG, AE2_SCREEN, CLIENT_SETUP, CLOSE, CREW, FILL, NETWORK_SERVICE, NETWORKS, OPEN_PANEL, RS2_RIG, STOCK, STORES,
} from "./storage-stage";

// Storage, networks and gear, in four movements:
//   1. the colony book takes in stores from several storage mods, each showing its card with what it holds;
//   2. residents order crafts from an ME network and a Refined Storage network for the colony's standing orders;
//   3. the player orders iron in the ME terminal and residents smelt it in the colony's furnaces;
//   4. an equipment profile gives a resident a backpack with a jukebox upgrade and a disc, which it fetches, wears
//      and plays as it walks.

const CONFIG = "blockwright.toml#promo-storage";
const GUI_SCALE = 2;
// The terminal and the panel are filmed close: one scale up, so their text reads in the film.
const SCREEN_SCALE = 3;
const FOV = 70;
const CREW_SIZE = 4;
const CURSOR_MS = 650;
const BEAT_MS = 900;
const ORDERED_IRON = 4;

type Run = ReturnType<typeof take>;
type Stage = Awaited<ReturnType<typeof stage>>;

test("storage: stores, networks and a singing backpack", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("storage");
  run.note("verified", false);
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("storage", { config: CONFIG });
    const staged = await stage(pair, run);
    const evidence: Record<string, any> = {};
    // While a later movement is being worked on, the earlier ones can be skipped: their stores and devices still
    // join the colony, unfilmed. A take that skips any is never verified for the film.
    const from = Number(process.env.FOLKWAYS_PROMO_FROM ?? 1);
    // And the later ones skipped too, to re-record one movement alone.
    const to = Number(process.env.FOLKWAYS_PROMO_TO ?? 4);
    if (from <= 1) evidence.stores = await movementStores(run, pair, staged);
    else await enrollQuietly(pair, staged, staged.row.map(one => one.at));
    if (from <= 2) evidence.networks = await movementNetworks(run, pair, staged);
    else await enrollQuietly(pair, staged, [staged.ae2.terminal, staged.torches, staged.rs2.grid, staged.chests]);
    if (from <= 3) evidence.terminal = await movementTerminal(run, pair, staged);
    if (to >= 4) evidence.backpack = await movementBackpack(run, pair, staged);
    await rigStop(pair.client);
    run.note("evidence", evidence);
    run.note("shaders", shaderEvidence(pair.clientInstance));
    run.note("verified", from <= 1 && to >= 4);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// ---------------------------------------------------------------------------------------------------------------
// The world

async function stage(pair: PromoPair, run: Run) {
  const { server, client } = pair;
  const grounded = await waitForGroundedPlayer(server);
  const player = grounded.name;
  await world.command(server, `op ${player}`);
  // Creative: no hearts or hunger on screen.
  await world.command(server, `gamemode creative ${player}`);
  await cinematicWorld(server);
  await tryCommand(server, "gamerule doMobSpawning false");
  await tryCommand(server, "gamerule fallDamage false");
  const o: Vec = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
  const at = (x: number, z: number, dy = 0): Vec => ({ x: o.x + x, y: o.y + dy, z: o.z + z });
  const set = (p: Vec, block: string) => world.command(server, `setblock ${commandPos(p)} ${block}`);
  const fill = (a: Vec, b: Vec, block: string) => tryCommand(server, `fill ${commandPos(a)} ${commandPos(b)} ${block}`);
  await tryCommand(server, `forceload add ${o.x - 40} ${o.z - 30} ${o.x + 40} ${o.z + 40}`);
  await tryCommand(server, `kill @e[type=!minecraft:player,x=${o.x},y=${o.y},z=${o.z},distance=..100]`);

  // The yard: a stone floor, the stores' row along its north side, the two networks behind it.
  await fill(at(-22, -6, -1), at(24, 26, -1), "minecraft:smooth_stone");
  await fill(at(-22, -6, 0), at(24, 26, 6), "minecraft:air");

  // Out of every shot: the beds and the larder that let settlers in.
  const beds: Vec[] = [];
  for (let i = 0; i < CREW_SIZE; i++) beds.push(at(-6 + i * 2, -4));
  for (const bed of beds) {
    await set(bed, "minecraft:red_bed[part=foot,facing=north]");
    await set({ ...bed, z: bed.z - 1 }, "minecraft:red_bed[part=head,facing=north]");
  }
  const larder = at(4, -4);
  await set(larder, "minecraft:chest[facing=south]");

  // 1 — stores from several mods along one row, fronts to the south, where the camera walks.
  const row: Array<{ name: string; at: Vec; block: string; items: Array<[string, number]> }> = [
    { name: "colossal", at: at(-13, 8), block: "colossalchests:colossal_chest_wood",
      items: [["minecraft:oak_log", 640], ["minecraft:cobblestone", 512], ["minecraft:stone_bricks", 256]] },
    { name: "chest", at: at(-9, 8), block: "minecraft:chest[facing=north]",
      items: [["minecraft:wheat", 64], ["minecraft:carrot", 40], ["minecraft:potato", 32]] },
    { name: "barrel", at: at(-7, 8), block: "minecraft:barrel[facing=north]",
      items: [["minecraft:apple", 24], ["minecraft:bread", 32]] },
    { name: "sophisticated-chest", at: at(-5, 8), block: "sophisticatedstorage:iron_chest[facing=north]",
      items: [["minecraft:iron_ingot", 64], ["minecraft:copper_ingot", 48], ["minecraft:gold_ingot", 20],
        ["sophisticatedbackpacks:backpack", 1], ["sophisticatedbackpacks:jukebox_upgrade", 1],
        ["minecraft:music_disc_pigstep", 1]] },
    { name: "sophisticated-barrel", at: at(-3, 8), block: "sophisticatedstorage:gold_barrel[facing=north]",
      items: [["minecraft:spruce_log", 128], ["minecraft:birch_log", 96], ["minecraft:oak_planks", 64]] },
    { name: "drawers-4", at: at(-1, 8), block: "storagedrawers:oak_full_drawers_4[facing=north]",
      items: [["minecraft:andesite", 256], ["minecraft:diorite", 192], ["minecraft:granite", 128],
        ["minecraft:tuff", 96]] },
    { name: "drawers-2", at: at(1, 8), block: "storagedrawers:spruce_full_drawers_2[facing=north]",
      items: [["minecraft:redstone", 320], ["minecraft:lapis_lazuli", 160]] },
    { name: "create-andesite", at: at(3, 8), block: "fxntstorage:andesite_storage_box[facing=north]",
      items: [["minecraft:bricks", 128], ["minecraft:glass", 96], ["minecraft:white_wool", 64]] },
    { name: "create-brass", at: at(5, 8), block: "fxntstorage:brass_storage_box[facing=north]",
      items: [["minecraft:quartz", 96], ["minecraft:amethyst_shard", 48]] },
  ];
  // The colossal chest forms when its core goes in last, at a corner of its walls.
  const colossal = row[0].at;
  await fill(colossal, { x: colossal.x + 1, y: colossal.y + 1, z: colossal.z + 1 }, "colossalchests:chest_wall_wood");
  await set({ ...colossal, y: colossal.y + 1 }, "colossalchests:interface_wood");
  for (const store of row) await set(store.at, store.block);
  // A lantern post at each end, so the row reads as a storehouse front rather than blocks on a slab.
  for (const x of [-15, 7]) {
    await set(at(x, 9), "minecraft:spruce_fence");
    await set(at(x, 9, 1), "minecraft:lantern");
  }
  run.note("fills", await server.run(FILL, { fills: row.map(one => ({ at: one.at, items: one.items })) }));

  // 2 — the ME network on the east of the yard, the Refined Storage network on the west, each with a chest of
  // the colony's that a standing order keeps stocked from it.
  const ae2 = { cell: at(10, 18), terminal: at(12, 17) };
  run.note("ae2", await server.run(AE2_RIG, {
    o: ae2.cell, terminal: ae2.terminal,
    patterns: [
      { recipe: "minecraft:torch", grid: ["minecraft:coal", "", "", "minecraft:stick", "", "", "", "", ""],
        out: ["minecraft:torch", 4] },
      { recipe: "minecraft:oak_planks", grid: ["minecraft:oak_log", "", "", "", "", "", "", "", ""],
        out: ["minecraft:oak_planks", 4] },
    ],
  }));
  const torches = at(9, 13);
  await set(torches, "minecraft:chest[facing=north]");
  // In one line, as Refined Storage's own test rig has it: the grid joins on the autocrafter's east face.
  const rs2 = { controller: at(-16, 18), grid: at(-13, 18) };
  const plank = "minecraft:oak_planks";
  run.note("rs2", await server.run(RS2_RIG, {
    o: rs2.controller, grid: rs2.grid,
    pattern: { grid: [plank, plank, plank, plank, "", plank, plank, plank, plank] },
  }));
  // The networks' nodes come up over the next ticks; they are stocked once they have.
  let stocked: any = null;
  for (let attempt = 0; attempt < 20 && !stocked?.ready; attempt++) {
    await tick.sprint(server, 10);
    stocked = await server.run(STOCK, { ae2: ae2.terminal, rs2: rs2.controller,
      ae2Stock: [["minecraft:coal", 256], ["minecraft:stick", 256], ["minecraft:raw_iron", 64], ["minecraft:oak_log", 64]],
      rs2Stock: [[plank, 512], ["minecraft:cobblestone", 256]] });
  }
  run.note("stocked", stocked);
  if (!stocked?.ready) throw new Error(`The networks never came up: ${JSON.stringify(stocked)}`);
  const chests = at(-12, 13);
  await set(chests, "minecraft:barrel[facing=north]");

  // 3 — the colony's furnaces, beside the ME network: the player's order for iron is smelted here.
  const furnaces = [at(15, 21), at(16, 21), at(17, 21)];
  for (const furnace of furnaces) await set(furnace, "minecraft:furnace[facing=north]");

  await world.command(server, `tp ${player} ${o.x + 0.5} ${o.y} ${o.z + 0.5} 0 0`);
  await world.command(server, `item replace entity ${player} hotbar.0 with folkways_core:colony_book`);
  await world.command(server, `item replace entity ${player} hotbar.1 with minecraft:air`);
  await tick.sprint(server, 10);
  const founded = await foundPromoColonyFast(server, player, [...beds, larder, ...furnaces]);
  const colony_id = founded.colony_id;
  await world.command(server, `item replace block ${commandPos(larder)} container.0 with minecraft:bread 64`);
  await world.command(server, `item replace block ${commandPos(larder)} container.1 with minecraft:cooked_beef 64`);
  await tick.sprint(server, 5);
  const admitted = await admitPromoSettlersFast(server, player, colony_id, CREW_SIZE);
  const uuids = (await world.entities(server, { types: ["folkways_vanilla:resident"], limit: 16 })).entities.map(one => one.uuid);
  if (uuids.length !== CREW_SIZE) throw new Error(`Expected ${CREW_SIZE} residents, found ${uuids.length}`);
  for (const uuid of uuids) await world.command(server, `data merge entity ${uuid} {Invulnerable:1b}`);
  run.note("service", await server.run(NETWORK_SERVICE, { uuids }));
  // The crew waits well behind the first shots' camera, by the beds.
  for (const [i, uuid] of uuids.entries()) {
    const p = at(-6 + i * 2, -9);
    await world.command(server, `tp ${uuid} ${p.x + 0.5} ${p.y} ${p.z + 0.5}`);
  }
  run.note("colony", { o, colony_id, founded, admitted, uuids });

  await setFov(client, FOV);
  run.note("client", await client.run(CLIENT_SETUP, { guiScale: GUI_SCALE }));
  await client.run(CURSOR, {});
  return { player, o, colony_id, uuids, row, ae2, rs2, torches, chests, furnaces };
}

// ---------------------------------------------------------------------------------------------------------------
// 1 — stores from several mods, taken in with the book

async function movementStores(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await rested(pair, s);
  const cells = s.row.map(one => one.at);
  const centre = (p: Vec, dy = 0.5): Vec => ({ x: p.x + 0.5, y: p.y + dy, z: p.z + 0.5 });
  const eye = (p: Vec, back = 3.2, up = 1.62): Vec => ({ x: p.x + 0.5, y: p.y + up, z: p.z - back });
  await hotbar(client, 0);
  await tick.freeze(server, false);
  // The colossal chest's card stands over its top; the camera takes it in a step further back.
  const first = s.row[0];
  await rigMove(client, { eye: [eye(first.at, 4.5, 1.9)], look: [centre({ ...first.at, x: first.at.x + 0.5 }, 1)], carry: true });
  await sleepMs(2_500);
  const enrolled: any[] = [];
  const shot = await clip(run, pair, "01-enroll", "First person with the colony book: walking along a row of stores from "
    + "Colossal Chests, vanilla, Sophisticated Storage, Storage Drawers and Create: Storage, each taken in with a left "
    + "click; its outline and its card with the goods it holds appear", async (mark) => {
      for (const [i, one] of s.row.entries()) {
        const big = one.name === "colossal";
        const target = big ? centre({ ...one.at, x: one.at.x + 0.5 }, 1) : centre(one.at);
        await rigMove(client, { eye: [eye(big ? { ...one.at, x: one.at.x + 0.5 } : one.at, big ? 4.5 : 3.2, big ? 1.9 : 1.62)],
          look: [target], seconds: i === 0 ? 0.6 : 1.1, carry: true, omega: 3.2 });
        await sleepMs(i === 0 ? 700 : 1_250);
        const click = await softly(() => input.click(client, { x: 480, y: 270 }, { button: "left" }));
        mark(`enroll-${one.name}`);
        await sleepMs(1_350);
        enrolled.push({ name: one.name, click: summarize(click) });
      }
      await sleepMs(800);
    });
  const stores = await server.run(STORES, { colony_id: s.colony_id, cells });
  run.note("enrolled", { enrolled, stores });
  const missing = stores.filter((one: any) => !one.member).map((one: any) => one.at);
  // A store the click missed is taken in now, so the rest of the film has the whole row; the film cuts the miss.
  if (missing.length) {
    run.note("enroll-retry", await runColonyTask(server, MARK_MEMBERS, { player: s.player, colony_id: s.colony_id,
      cells: s.row.filter(one => missing.includes(`${one.at.x}, ${one.at.y}, ${one.at.z}`)).flatMap(one => [one.at.x, one.at.y, one.at.z]) }));
  }
  await waitFor(server, 20_000, async () => {
    const read = await server.run(STORES, { colony_id: s.colony_id, cells });
    return read.every((one: any) => one.member && one.state === "OBSERVED" && one.kinds > 0) ? read : null;
  });

  // The whole row from further back, every card showing its goods.
  const rowMid = { x: (s.row[0].at.x + s.row.at(-1)!.at.x) / 2 + 0.5, y: s.o.y + 1.2, z: s.row[0].at.z + 0.5 };
  await rigMove(client, { eye: [{ x: rowMid.x - 4, y: s.o.y + 2.6, z: s.o.z + 2.5 }], look: [rowMid], carry: true });
  await sleepMs(2_000);
  const overview = await clip(run, pair, "02-row", "The row from further back: every store outlined and carded with the "
    + "goods the colony has read in it, the camera drifting along it", async () => {
      await rigMove(client, { eye: [{ x: rowMid.x - 4, y: s.o.y + 2.6, z: s.o.z + 2.5 }, { x: rowMid.x + 4, y: s.o.y + 3.4, z: s.o.z + 1 }],
        look: [rowMid, { ...rowMid, x: rowMid.x + 1 }], seconds: 9, carry: true });
      await sleepMs(9_000);
    });
  const after = await server.run(STORES, { colony_id: s.colony_id, cells });
  expect(after.filter((one: any) => one.member).length, "every store in the row joins the colony").toBe(s.row.length);
  expect(after.filter((one: any) => one.kinds > 0).length, "every store's goods are read").toBe(s.row.length);
  return { shot, overview, enrolled, stores: after, retried: missing };
}

// ---------------------------------------------------------------------------------------------------------------
// 2 — residents order from the ME and the Refined Storage networks

async function movementNetworks(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await rested(pair, s);
  // The devices join off camera; the orders that need them are filed as each shot begins.
  run.note("devices", await runColonyTask(server, MARK_MEMBERS, { player: s.player, colony_id: s.colony_id,
    cells: [s.ae2.terminal, s.torches, s.rs2.grid, s.chests].flatMap(p => [p.x, p.y, p.z]) }));
  const counts = () => server.run(NETWORKS, { ae2: s.ae2.terminal, rs2: s.rs2.controller,
    items: ["minecraft:torch", "minecraft:chest", "minecraft:iron_ingot", "minecraft:raw_iron"] });
  const held = (cell: Vec, item: string) => server.run(FILL_COUNT, { at: cell, item }) as Promise<number>;

  // The ME side: torches for the chest by the network, crafted by the assembler on the colony's order.
  // From the network's east side: the residents come up from the south-west, across the frame rather than at it.
  const ae2Eye = [{ x: s.ae2.cell.x + 8.5, y: s.o.y + 2.6, z: s.ae2.cell.z - 6 },
    { x: s.ae2.cell.x + 7.5, y: s.o.y + 2.9, z: s.ae2.cell.z - 7.5 }];
  const ae2Look = [{ x: s.ae2.cell.x + 1.5, y: s.o.y + 0.9, z: s.ae2.cell.z - 2.5 },
    { x: s.ae2.cell.x + 1, y: s.o.y + 0.9, z: s.ae2.cell.z - 3 }];
  await rigMove(client, { eye: [ae2Eye[0]], look: [ae2Look[0]], carry: true });
  await sleepMs(1_500);
  const ae2Samples: any[] = [];
  const ae2Shot = await clip(run, pair, "03-ae2-order", "A standing order for 32 torches in a chest beside an ME network "
    + "that has none: a resident walks to the ME terminal, orders the craft, the molecular assembler makes them and the "
    + "resident carries them to the chest; resident cards over their heads", async (mark) => {
      run.note("ae2-demand", await setMaintainDemandsFast(server, s.player, [{ at: s.torches, item: "minecraft:torch", count: 32, low: 32 }]));
      mark("ordered");
      void rigMove(client, { eye: ae2Eye, look: ae2Look, seconds: 40, carry: true, omega: 1.5 });
      await sampleUntil(ae2Samples, 120_000, async () => ({ chest: await held(s.torches, "minecraft:torch"), net: await counts() }),
        (one) => one.chest >= 32, mark, (one, prev) => {
          if (one.net.cpus.some((c: string) => c.includes("torch")) && !prev?.net.cpus.some((c: string) => c.includes("torch"))) mark("crafting");
          if (one.chest > 0 && !(prev?.chest > 0)) mark("delivering");
        });
      await sleepMs(3_000);
    });
  expect(ae2Samples.at(-1)?.chest ?? 0, "residents fill the torch chest from the ME network").toBeGreaterThanOrEqual(32);
  expect(ae2Samples.some((one) => one.net.cpus.some((c: string) => c.includes("torch"))),
    "the ME network crafts the torches on a crafting CPU").toBe(true);

  // The Refined Storage side: chests for the barrel, crafted by the autocrafter.
  // From the network's west side, for the same reason.
  const rsEye = [{ x: s.rs2.controller.x - 2.5, y: s.o.y + 2.4, z: s.rs2.controller.z - 7 },
    { x: s.rs2.controller.x - 1.5, y: s.o.y + 2.7, z: s.rs2.controller.z - 8 }];
  const rsLook = [{ x: s.rs2.controller.x + 2.5, y: s.o.y + 0.8, z: s.rs2.controller.z - 2.5 },
    { x: s.rs2.controller.x + 3, y: s.o.y + 0.8, z: s.rs2.controller.z - 3 }];
  await rigMove(client, { eye: [rsEye[0]], look: [rsLook[0]], carry: true });
  await sleepMs(1_500);
  const rsSamples: any[] = [];
  const rsShot = await clip(run, pair, "04-rs2-order", "A standing order for four chests in a barrel beside a Refined "
    + "Storage network: a resident orders them at the crafting grid, the autocrafter makes them and the resident "
    + "brings them over", async (mark) => {
      run.note("rs2-demand", await setMaintainDemandsFast(server, s.player, [{ at: s.chests, item: "minecraft:chest", count: 4, low: 4 }]));
      mark("ordered");
      void rigMove(client, { eye: rsEye, look: rsLook, seconds: 40, carry: true, omega: 1.5 });
      await sampleUntil(rsSamples, 120_000, async () => ({ barrel: await held(s.chests, "minecraft:chest"), net: await counts() }),
        (one) => one.barrel >= 4, mark, (one, prev) => {
          if (one.barrel > 0 && !(prev?.barrel > 0)) mark("delivering");
        });
      await sleepMs(3_000);
    });
  expect(rsSamples.at(-1)?.barrel ?? 0, "residents fill the barrel with chests from the Refined Storage network").toBeGreaterThanOrEqual(4);
  return { ae2: { shot: ae2Shot, samples: ae2Samples }, rs2: { shot: rsShot, samples: rsSamples } };
}

// ---------------------------------------------------------------------------------------------------------------
// 3 — the player orders in the ME terminal; the colony smelts it

async function movementTerminal(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await rested(pair, s);
  const t = s.ae2.terminal;
  const counts = () => server.run(NETWORKS, { ae2: t, rs2: s.rs2.controller,
    items: ["minecraft:iron_ingot", "minecraft:raw_iron", "minecraft:coal"] });
  const before = await counts();
  await hotbar(client, 1);
  await rigMove(client, { eye: [{ x: t.x + 0.5, y: s.o.y + 1.62, z: t.z - 2.3 }], look: [{ x: t.x + 0.5, y: t.y + 0.5, z: t.z + 0.5 }], carry: true });
  await sleepMs(2_000);
  const steps: Record<string, any> = {};
  await client.run(CLIENT_SETUP, { guiScale: SCREEN_SCALE });
  const gui = await clip(run, pair, "05-terminal", "The player opens the ME crafting terminal, searches for iron ingots "
    + "— craftable only by the colony's furnaces — asks for four and starts the job", async (mark) => {
      await sleepMs(600);
      steps.open = await rightClick(client);
      steps.screen = await softly(() => screen.waitFor(client, "CraftingTermScreen", { timeoutMs: 8_000 }));
      await safeAction(() => frames(client, 10));
      mark("open");
      let cursor = await screenCentre(client);
      const read = await client.run(AE2_SCREEN, {});
      steps.read = read;
      const search = (await screen.widgets(client)).find((w: any) => /TextField/i.test(String(w.className ?? w.class ?? "")));
      steps.search = search;
      const box = search ? { x: search.x + 20, y: search.y + 4 } : { x: read.left + 110, y: read.top + 8 };
      await sleepMs(BEAT_MS);
      cursor = await glide(client, cursor, box);
      await softly(() => input.click(client, box, { button: "left" }));
      await sleepMs(300);
      await typeSlowly(client, "铁锭");
      mark("search");
      await sleepMs(BEAT_MS);
      // The first slot of the terminal's grid, which the search leaves holding the ingot alone.
      const slot = { x: read.left + 18, y: read.top + 27 };
      steps.slot = slot;
      cursor = await glide(client, cursor, slot);
      await sleepMs(400);
      await softly(() => input.click(client, slot, { button: "left" }));
      steps.amount = await softly(() => screen.waitFor(client, "CraftAmountScreen", { timeoutMs: 5_000 }));
      mark("amount");
      await sleepMs(BEAT_MS);
      const widgets = async () => (await screen.widgets(client)) as any[];
      const plus = (await widgets()).find(w => w.label === "+1" || w.text === "+1" || w.message === "+1");
      steps.plus = plus;
      for (let i = 1; i < ORDERED_IRON; i++) {
        if (plus) {
          const p = { x: plus.x + plus.width / 2, y: plus.y + plus.height / 2 };
          if (i === 1) cursor = await glide(client, cursor, p);
          await softly(() => input.click(client, p, { button: "left" }));
          await sleepMs(220);
        }
      }
      await sleepMs(500);
      const next = (await widgets()).find(w => /下一页|Next/.test(String(w.label ?? w.text ?? w.message ?? "")));
      steps.next = next;
      if (next) {
        const p = { x: next.x + next.width / 2, y: next.y + next.height / 2 };
        cursor = await glide(client, cursor, p);
        await softly(() => input.click(client, p, { button: "left" }));
      }
      steps.confirm = await softly(() => screen.waitFor(client, "CraftConfirmScreen", { timeoutMs: 8_000 }));
      mark("plan");
      await sleepMs(2_200);
      const start = (await widgets()).find(w => /开始|Start/.test(String(w.label ?? w.text ?? w.message ?? "")));
      steps.start = start;
      if (start) {
        const p = { x: start.x + start.width / 2, y: start.y + start.height / 2 };
        cursor = await glide(client, cursor, p);
        await sleepMs(300);
        await softly(() => input.click(client, p, { button: "left" }));
      }
      mark("started");
      await sleepMs(1_500);
    });
  await client.run(CLOSE, {});
  await client.run(CLIENT_SETUP, { guiScale: GUI_SCALE });
  await hotbar(client, 0);
  const job = await waitFor(server, 10_000, async () => {
    const now = await counts();
    return now.cpus.some((c: string) => c.includes("iron_ingot")) ? now : null;
  });
  run.note("terminal-steps", steps);
  expect(job, "the terminal's order runs on an ME crafting CPU").toBeTruthy();

  // The colony at work on it: inputs out of the terminal, into the furnaces, the ingots back into the network.
  const f = s.furnaces[1];
  const smeltEye = [{ x: f.x - 6.5, y: s.o.y + 4.2, z: f.z - 8.5 }, { x: f.x - 2.5, y: s.o.y + 4.6, z: f.z - 9.5 }];
  const smeltLook = [{ x: f.x - 2, y: s.o.y + 0.8, z: f.z - 2.5 }, { x: f.x - 1.5, y: s.o.y + 0.8, z: f.z - 2 }];
  await rigMove(client, { eye: [smeltEye[0]], look: [smeltLook[0]], carry: true });
  await sleepMs(1_000);
  const samples: any[] = [];
  const smelt = await clip(run, pair, "06-smelt", "Residents with network service take the raw iron and coal the network "
    + "hands the colony at the terminal, load and fuel the colony's furnaces, and bring the ingots back into the network",
    async (mark) => {
      void rigMove(client, { eye: smeltEye, look: smeltLook, seconds: 50, carry: true, omega: 1.5 });
      await sampleUntil(samples, 300_000, async () => ({ net: await counts(), crew: await server.run(CREW, { colony_id: s.colony_id }) }),
        (one) => (one.net.ae2["minecraft:iron_ingot"] ?? 0) >= (before.ae2["minecraft:iron_ingot"] ?? 0) + ORDERED_IRON
          && !one.net.cpus.some((c: string) => c.includes("iron_ingot")), mark, (one, prev) => {
          const carried = (o: any) => Object.values(o?.crew ?? {}).some((r: any) => r.pack.some((p: string) => p.includes("raw_iron")));
          if (carried(one) && !carried(prev)) mark("carrying");
          if ((one.net.ae2["minecraft:iron_ingot"] ?? 0) > (prev?.net.ae2["minecraft:iron_ingot"] ?? before.ae2["minecraft:iron_ingot"] ?? 0)) mark("returned");
        });
      await sleepMs(2_500);
    });
  const last = samples.at(-1)?.net;
  expect((last?.ae2["minecraft:iron_ingot"] ?? 0) - (before.ae2["minecraft:iron_ingot"] ?? 0),
    "the colony's furnaces deliver the ordered ingots into the network").toBeGreaterThanOrEqual(ORDERED_IRON);
  return { gui, steps, smelt, samples: samples.map(one => ({ t: one.t, net: one.net })) };
}

// ---------------------------------------------------------------------------------------------------------------
// 4 — a backpack with a jukebox upgrade and a disc, given to a resident

async function movementBackpack(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await rested(pair, s);
  await hotbar(client, 0);
  const shelf = s.row.find(one => one.name === "sophisticated-chest")!.at;
  await rigMove(client, { eye: [{ x: shelf.x + 0.5, y: s.o.y + 1.62, z: shelf.z - 5 }], look: [{ x: shelf.x + 0.5, y: s.o.y + 1.2, z: shelf.z + 0.5 }], carry: true });
  await sleepMs(1_500);
  const steps: Record<string, any> = {};
  let assignedName = "";
  await client.run(CLIENT_SETUP, { guiScale: SCREEN_SCALE });
  const panel = await clip(run, pair, "07-profile", "The colony panel's equipment profiles: a new profile, a Sophisticated "
    + "backpack in the Curios back slot, a jukebox upgrade inside it, the Pigstep disc inside that, and the profile given "
    + "to a resident", async (mark) => {
      await sleepMs(500);
      steps.open = await rightClick(client);
      const opened = await softly(() => screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 4_000 }));
      if (!opened || (opened as any).status === "refused") steps.openByTask = await server.run(OPEN_PANEL, { player: s.player });
      await softly(() => screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 8_000 }));
      await safeAction(() => frames(client, 10));
      mark("panel");
      let cursor = await screenCentre(client);
      const press = async (id: string, label: string, options: { scrollIntoView?: boolean } = {}) => {
        const element = await findElement(client, id);
        if (element) cursor = await glide(client, cursor, { x: element.x + element.width / 2, y: element.y + element.height / 2 });
        await sleepMs(250);
        steps[label] = await softly(() => clickElement(client, { id }, options));
        await safeAction(() => frames(client, 4));
        mark(label);
        await sleepMs(BEAT_MS);
      };
      const pick = async (search: string, result: string, label: string) => {
        const box = await findElement(client, "folkways.selector.search");
        if (box) cursor = await glide(client, cursor, { x: box.x + 30, y: box.y + box.height / 2 });
        await softly(() => clickElement(client, { id: "folkways.selector.search" }));
        await typeSlowly(client, search);
        await sleepMs(600);
        await press(result, `${label}-pick`);
        const confirm = await findElement(client, "folkways.selector.confirm");
        if (confirm) await press("folkways.selector.confirm", `${label}-confirm`);
      };
      await press("folkways.equipment.title", "page", { scrollIntoView: true });
      await press("folkways.equipment.create", "create");
      await press("folkways.equipment.slot.curios.back/0", "back");
      await pick("@sophisticatedbackpacks", "folkways.selector.result.sophisticatedbackpacks.backpack", "backpack");
      await press("folkways.equipment.add_child.0", "add-upgrade");
      await pick("唱片机", "folkways.selector.result.sophisticatedbackpacks.jukebox_upgrade", "jukebox");
      await press("folkways.equipment.add_child.1", "add-disc");
      await pick("pigstep", "folkways.selector.result.minecraft.music_disc_pigstep", "disc");
      await sleepMs(1_200);
      await press("folkways.equipment.assign_residents", "assign");
      await press("folkways.equipment.resident.0", "resident");
      const choice = (await panelElements(client).catch(() => [] as any[]))
        .find((one: any) => typeof one.id === "string" && one.id.startsWith("folkways.equipment.choose.") && !one.id.endsWith(".none"));
      steps.choice = choice?.id;
      if (choice) await press(choice.id, "choose");
      await sleepMs(1_500);
    });
  await client.run(CLOSE, {});
  await client.run(CLIENT_SETUP, { guiScale: GUI_SCALE });
  const crew = await server.run(CREW, { colony_id: s.colony_id });
  const profiles = await server.run(ASSIGNED, { colony_id: s.colony_id });
  run.note("profile-steps", steps);
  run.note("assigned", profiles);
  const wearer = profiles.assigned?.[0];
  expect(wearer, "the profile is assigned to a resident").toBeTruthy();
  assignedName = crew[wearer]?.name ?? "";

  // The resident fetches the backpack, the upgrade and the disc, and its backpack starts to play.
  const samples: any[] = [];
  await rigMove(client, { follow: wearer, offset: { x: -3.2, y: 2.4, z: -3.6 }, lookAt: wearer, omega: 2.2, carry: true });
  await sleepMs(1_200);
  const gearUp = await clip(run, pair, "08-gear-up", "Following the resident the profile was given to: it fetches the "
    + "backpack from the Sophisticated chest and puts it on its back, then installs the jukebox upgrade and the disc",
    async (mark) => {
      await sampleUntil(samples, 150_000, async () => (await server.run(CREW, { colony_id: s.colony_id }))[wearer],
        (one) => !!one?.playing, mark, (one, prev) => {
          if (one?.back && !prev?.back) mark("worn");
          if (one?.upgrades?.length && !prev?.upgrades?.length) mark("upgrade");
          if (one?.discs?.length && !prev?.discs?.length) mark("disc");
          if (one?.playing && !prev?.playing) mark("playing");
        });
      await sleepMs(2_000);
    });
  const geared = samples.at(-1);
  expect(geared?.back, "the resident wears the backpack in its back slot").toContain("backpack");
  expect(geared?.upgrades ?? [], "the jukebox upgrade is installed").toContain("sophisticatedbackpacks:jukebox_upgrade");
  expect(geared?.discs ?? [], "the disc is in the jukebox").toContain("minecraft:music_disc_pigstep");
  expect(geared?.playing, "the backpack plays the disc").toBe(true);

  // The music on the move: the camera circles the resident as it goes about its work, the song playing from its back.
  const playing: any[] = [];
  const music = await clip(run, pair, "09-music", "The resident walks on with the backpack playing Pigstep; the camera "
    + "swings round to its back, where the backpack hangs", async (mark) => {
      void rigMove(client, { follow: wearer, offset: { x: 2.6, y: 1.9, z: 2.8 }, lookAt: wearer, omega: 1.4, carry: true });
      const t0 = Date.now();
      while (Date.now() - t0 < 16_000) {
        const one = (await server.run(CREW, { colony_id: s.colony_id }))[wearer];
        playing.push({ t: (Date.now() - t0) / 1000, playing: one?.playing, x: one?.x, z: one?.z });
        await sleepMs(1_000);
      }
      mark("end");
    });
  expect(playing.filter(one => one.playing).length, "the music keeps playing through the shot").toBeGreaterThanOrEqual(playing.length - 1);
  return { panel, steps, wearer, assignedName, gearUp, samples, music, playing };
}

// ---------------------------------------------------------------------------------------------------------------
// Helpers

// The crew fed and rested, so hunger or sleep does not break into what a shot is about.
async function rested(pair: PromoPair, s: Stage) {
  return softly(() => setVitals(pair.server, s.colony_id, s.uuids.map(uuid => ({ uuid, food: 19, tired: 0 }))));
}

async function screenCentre(client: MinecraftClient) {
  const read = await client.run(CLIENT_SETUP_READ, {}).catch(() => ({ width: 960, height: 540 }));
  return { x: Math.round(read.width / 2), y: Math.round(read.height / 2) };
}

async function enrollQuietly(pair: PromoPair, s: Stage, cells: Vec[]) {
  return runColonyTask(pair.server, MARK_MEMBERS, { player: s.player, colony_id: s.colony_id,
    cells: cells.flatMap(p => [p.x, p.y, p.z]) });
}

type Mark = (label: string) => void;

async function clip(run: Run, pair: PromoPair, name: string, subject: string, during: (mark: Mark) => Promise<void>) {
  const marks: Record<string, number> = {};
  let t0 = Date.now();
  const gates = await promoClip(run, pair.client, pair.server, name, {
    subject, worldState: {}, seconds: 1,
    during: async () => {
      t0 = Date.now();
      await during((label) => { marks[label] ??= (Date.now() - t0) / 1000; });
    },
  });
  run.note(`marks-${name}`, marks);
  return { gates, marks };
}

async function sampleUntil(samples: any[], timeoutMs: number, read: () => Promise<any>, done: (one: any) => boolean,
  mark: Mark, changed?: (one: any, prev: any) => void) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const one = await read().catch((error: any) => ({ error: String(error) }));
    one.t = Math.round((Date.now() - t0) / 100) / 10;
    changed?.(one, samples.at(-1));
    samples.push(one);
    if (done(one)) {
      mark("done");
      return true;
    }
    await sleepMs(1_000);
  }
  return false;
}

async function waitFor<T>(server: MinecraftServer, timeoutMs: number, read: () => Promise<T | null>) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const value = await read().catch(() => null);
    if (value) return value;
    await sleepMs(1_000);
  }
  return null;
}

async function hotbar(client: MinecraftClient, slot: number) {
  return safeAction(() => input.key(client, { keybind: `key.hotbar.${slot + 1}`, action: "tap" }));
}

async function rightClick(client: MinecraftClient) {
  await safeAction(() => input.button(client, { button: "right", action: "press" }));
  await sleepMs(60);
  return safeAction(() => input.button(client, { button: "right", action: "release" }));
}

async function glide(client: MinecraftClient, from: { x: number; y: number }, to: { x: number; y: number }, ms = CURSOR_MS) {
  const steps = Math.max(1, Math.round(ms / 30));
  for (let step = 1; step <= steps; step++) {
    const t = step / steps;
    const eased = t * t * (3 - 2 * t);
    await safeAction(() => input.move(client, {
      x: Math.round(from.x + (to.x - from.x) * eased), y: Math.round(from.y + (to.y - from.y) * eased),
    }));
    await sleepMs(30);
  }
  return to;
}

async function typeSlowly(client: MinecraftClient, text: string) {
  for (const ch of [...text]) {
    await safeAction(() => input.key(client, { text: ch }));
    await sleepMs(110);
  }
}

async function findElement(client: MinecraftClient, id: string) {
  const elements = await panelElements(client).catch(() => [] as any[]);
  return elements.find((one: any) => one.id === id && one.displayed !== false);
}

function summarize(value: any) {
  if (!value || typeof value !== "object") return value;
  return { status: value.status, target: value.target ?? value.hit ?? undefined };
}


/** The GUI-scaled screen size. */
const CLIENT_SETUP_READ = defineTask<Record<string, never>, { width: number; height: number }>({
  name: "folkways.promo.storage.screen-size",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var w = Minecraft.getInstance().getWindow();
return Map.of("width", w.getGuiScaledWidth(), "height", w.getGuiScaledHeight());`,
  }),
});

/** How many of an item stand in a container, read through its item handler. */
const FILL_COUNT = defineTask<{ at: Vec; item: string }, number>({
  name: "folkways.promo.storage.count",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.neoforged.neoforge.capabilities.Capabilities",
      "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation"],
    body: `
var a = Args.of(ctx);
var m = (Map<?, ?>) a.value("at");
var pos = new BlockPos(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
var handler = ctx.server().overworld().getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
if (handler == null) return 0;
var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(a.string("item")));
int n = 0;
for (int i = 0; i < handler.getSlots(); i++) if (handler.getStackInSlot(i).is(item)) n += handler.getStackInSlot(i).getCount();
return n;`,
  }),
});

/** The residents each equipment profile is assigned to. */
const ASSIGNED = defineTask<{ colony_id: string }, any>({
  name: "folkways.promo.storage.assigned",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles"],
    body: `
var colony = ColonyGround.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony_id"))).orElseThrow();
var profiles = EquipmentProfiles.of(colony);
var assigned = new ArrayList<String>();
var names = new ArrayList<String>();
for (var resident : colony.residents()) {
    profiles.assigned(resident.id()).ifPresent(profile -> { assigned.add(resident.id().toString()); names.add(profile.name()); });
}
return Map.of("assigned", assigned, "profiles", names);`,
  }),
});
