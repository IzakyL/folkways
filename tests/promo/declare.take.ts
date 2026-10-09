import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement } from "../ldlib2";
import { waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { setMaintainDemandInView } from "../e2e/membership-optin";
import { buttonText, openContainerScreen, pickChoice } from "../e2e/site-panel";
import { colonyRegistry, setBookGesture, zoneCount, zoneKindToken } from "../e2e/zone-tools";
import { drainActionBar, FIRST_CORNER, readActionBar, ZONE_MARKED } from "../e2e/zone-receipt";
import { BUDGET } from "../shared/budgets";
import { frames, safeAction, softly, tryCommand, waitForElement } from "../shared/bw-helpers";
import { runColonyTask } from "../e2e/colony-tasks";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast } from "./fast-setup";
import {
  cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, writeLook,
  type Look, type PromoPair,
} from "./promo-kit";
import { shaderEvidence } from "./shaders";

// Three short shots, each one declaration and the colony carrying it out: an order for lanterns in a chest, a plot
// framed as a field, and a plot framed over standing trees as a wood lot. The camera holds still in each; the player
// only says what should be there, and the residents work out the rest, down to every step it takes.

const EYE = 1.62;
const CRAFTERS = 1;
const FARMERS = 4;
// A lantern is several trades deep: raw iron smelted to ingots and cut to nuggets, a log split to planks and sticks,
// a stick and coal made a torch, and the nuggets closed round the torch. Nothing of it is in stock but the raw goods.
const LANTERN = "minecraft:lantern";
const LANTERN_COUNT = 4;
const WORKSHOP_STOCK: Array<[string, number]> = [
  ["minecraft:oak_log", 4], ["minecraft:raw_iron", 8], ["minecraft:coal", 16],
];
// Birch: a straight trunk under a crown that starts well off the ground, so there is room to stand and fell it.
// A low oak's leaves can come down beside its trunk and leave no footing there.
const SAPLING = "minecraft:birch_sapling";
const LOG = "minecraft:birch_log";
const TREE = "minecraft:birch";
// The world runs faster while the work is filmed, so a whole job fits a short shot; the cut speeds it up again.
const WORK_RATE = 40;
const POLL_MS = 1_000;
const STEADY_POLLS = 5;
const HOLD_AFTER_MS = 2_500;
const WORK_TIMEOUT_MS = 120_000;
// Through the field shot the wheat grows fast enough to watch, from the first seed on: the shot ends once every cell
// has been cut ripe at least once.
const FIELD_GROW_SPEED = 2_500;
// Through the wood lot shot the saplings grow slowly, one tree at a time: the shot ends once the trees that stood
// there are down and a few that came up again have been felled in turn.
const WOOD_GROW_SPEED = 40;
const REGROWN_TREES = 2;
const CROP_TOKEN = "folkways.draft.value.crop";
const CHAT_FADE_MS = 12_000;
const REVEAL_MS = 3_500;
// The player's gestures go at a pace a viewer can follow: each turn of the head, and a hold after each step.
const GLIDE_MS = 1_400;
const BEAT_MS = 1_200;
// Narrower than the other takes: each of these shots is one small plot, which should fill the frame.
const FOV = 50;

type Box = { min: Vec; max: Vec };
type Spot = { feet: Vec; aim: Vec };

test("each declaration is carried out by the colony on its own", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("declare");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("declare");
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    // Nothing grows until the field shot, which sets its own pace, as the wood lot shot does after it.
    await tryCommand(server, "gamerule randomTickSpeed 0");
    const o: Vec = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
    const at = (x: number, z: number, dy = 0): Vec => ({ x: o.x + x, y: o.y + dy, z: o.z + z });
    const box = (x1: number, z1: number, x2: number, z2: number, dy = -1): Box => ({ min: at(x1, z1, dy), max: at(x2, z2, dy) });

    const set = {
      quarters: { beds: [at(-6, 14), at(-6, 16), at(-6, 18), at(-6, 20), at(-2, 14), at(-2, 16)], food: at(2, 14) },
      // Store, table and furnace along the back, a spare chest for what is left over and the ordered chest at the
      // front, within the eight blocks the server lets the player file an order from: every step stays in frame.
      workshop: { store: at(-28, -9), spare: at(-28, -7), table: at(-25, -10), furnace: at(-22, -10),
        shelf: at(-21, -6), crew: at(-25, -6) },
      // A ditch of water along each long side, outside the plot: the wheat grows fast from the first furrow, and
      // farmland with no water within four blocks dries back to dirt under it before a seed goes in.
      field: { plot: box(-4, -14, 4, -8), ditches: [box(-4, -15, 4, -15), box(-4, -7, 4, -7)], barn: at(7, -6),
        crew: at(7, -3) },
      // Clear grass to the far left of the trees, so the look to that corner passes beside the canopies, not into them.
      woodlot: { plot: box(18, -24, 34, -5), trunks: [at(26, -16), at(31, -14), at(25, -10), at(31, -9)],
        shed: at(26, -4) },
    };
    const spots: Record<string, Spot> = {
      workshop: { feet: at(-24, -1, 4), aim: at(-24, -8, 0) },
      field: { feet: at(0, -2, 5), aim: at(0, -11, 0) },
      woodlot: { feet: at(26, 2, 10), aim: at(26, -14, 0) },
    };

    run.note("ground", await stageGround(server, o, set, spots));
    await world.command(server, `tp ${player} ${o.x + 0.5} ${o.y} ${o.z + 12.5} 180 20`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 10);
    const founded = await foundPromoColonyFast(server, player, [
      ...set.quarters.beds, set.quarters.food,
      set.workshop.store, set.workshop.spare, set.workshop.table, set.workshop.furnace, set.workshop.shelf,
      set.field.barn, set.woodlot.shed,
    ]);
    const colony_id = founded.colony_id;
    run.note("founded", founded);
    const admitted = await admitPromoSettlersFast(server, player, colony_id, CRAFTERS + FARMERS);
    run.note("admitted", admitted);
    const crew = await castCrew(server, set);
    run.note("crew", crew);

    await world.command(server, `gamemode survival ${player}`);
    await world.command(server, `attribute ${player} minecraft:player.block_interaction_range base set 64`);
    await setFov(client, FOV);
    await safeAction(() => screen.dismiss(client));

    const shots: any = {};

    // ── An order: this chest should hold four lanterns. ──
    // The order is filed bare-handed (a chest used with the book opens the colony panel instead), and from within
    // eight blocks of the chest: the server answers for a block no farther away.
    await world.command(server, `item replace entity ${player} hotbar.8 from entity ${player} weapon.mainhand`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
    let look = await standAt(server, client, player, spots.workshop);
    // The chat lines and the join toasts fade before the first shot rolls.
    await sleepMs(CHAT_FADE_MS);
    shots.workshop = await film(run, client, server, "01-workshop", {
      subject: "Fixed camera over a small workshop: the player files an order for 4 lanterns in the front chest, and "
        + "a crafter fetches raw iron, logs and coal, smelts the iron, splits planks and sticks, makes torches and "
        + "nuggets and closes them into lanterns, all in frame, then carries the lanterns in",
      during: async (mark) => {
        await sleepMs(1_200);
        look = await glide(client, look, lookAt(spots.workshop, set.workshop.shelf), GLIDE_MS);
        mark("declare");
        const order = await setMaintainDemandInView(server, client, set.workshop.shelf, LANTERN, LANTERN_COUNT, BEAT_MS);
        mark("declared");
        // The book back in hand while the crafter works: residents show their cards only to a player holding it.
        await world.command(server, `item replace entity ${player} weapon.mainhand from entity ${player} hotbar.8`);
        look = await glide(client, look, lookAt(spots.workshop, spots.workshop.aim), GLIDE_MS);
        await hideHud(client);
        await setTickRate(server, WORK_RATE);
        const done = await waitSteady(mark, async () =>
          (await survey(server, { chest: set.workshop.shelf, item: LANTERN })).items as number,
        (count) => count >= LANTERN_COUNT);
        await setTickRate(server, 20);
        // DIAGNOSTIC: where the chain stopped.
        const stuck: any = {};
        if (!done.reached) {
          for (const [name, at] of Object.entries({ store: set.workshop.store, spare: set.workshop.spare,
            furnace: set.workshop.furnace, shelf: set.workshop.shelf }) as Array<[string, Vec]>) {
            stuck[name] = (await tryCommand(server, `data get block ${at.x} ${at.y} ${at.z} Items`)).output;
          }
          for (const one of (crew.crew as any[]).slice(0, CRAFTERS)) {
            stuck[one.uuid] = (await tryCommand(server,
              `data get entity ${one.uuid} "neoforge:attachments"."folkways:body_state".Pack.Items`)).output;
          }
          stuck.placards = await runColonyTask(server, PLACARDS, { colony_id });
        }
        await sleepMs(HOLD_AFTER_MS);
        // The order met: the chest opens on the lanterns, bare-handed again.
        await hideHud(client);
        await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
        look = await glide(client, look, lookAt(spots.workshop, set.workshop.shelf), GLIDE_MS);
        mark("reveal");
        const shown = await openContainerScreen(client, set.workshop.shelf);
        await sleepMs(REVEAL_MS);
        await safeAction(() => screen.dismiss(client));
        return { order, done, shown, stuck };
      },
    });

    // ── A field: frame the ground and say it is a farm. ──
    await world.command(server, `item replace entity ${player} weapon.mainhand from entity ${player} hotbar.8`);
    await world.command(server, `item replace entity ${player} hotbar.8 with minecraft:air`);
    look = await standAt(server, client, player, spots.field);
    shots.book = await setBookGesture(server, client, player, "box");
    look = await standAt(server, client, player, spots.field);
    await tryCommand(server, `gamerule randomTickSpeed ${FIELD_GROW_SPEED}`);
    shots.field = await film(run, client, server, "02-field", {
      subject: "Fixed camera over bare grass: the player frames a plot with the colony book and makes it a wheat farm, "
        + "and the farmers walk in, till it and sow it; the wheat grows ripe in fast time as they go and they harvest "
        + "and resow it",
      during: async (mark) => {
        await sleepMs(1_200);
        mark("declare");
        const zone = await frameZone(server, client, spots.field, look, set.field.plot, null);
        look = zone.look;
        mark("declared");
        await hideHud(client);
        await setTickRate(server, WORK_RATE);
        // Ripe wheat that is no longer ripe at the next look has been cut. The field is never sown whole at once:
        // the first cells ripen and are cut while the last are still being tilled.
        const field = lift(set.field.plot);
        const harvest = cycling(async () =>
          (await survey(server, { box: field, block: "minecraft:wheat", up: true })).up as string[]);
        const done = await harvest.until(mark, "done", (cut) => cut.size >= cells(set.field.plot));
        await setTickRate(server, 20);
        await sleepMs(HOLD_AFTER_MS);
        await hideHud(client);
        return { zone: zone.evidence, done };
      },
    });

    // ── A wood lot over trees that were already there: they come down and saplings go back in. ──
    look = await standAt(server, client, player, spots.woodlot);
    await tryCommand(server, `gamerule randomTickSpeed ${WOOD_GROW_SPEED}`);
    shots.woodlot = await film(run, client, server, "03-woodlot", {
      subject: "Fixed camera over a stand of birches: the player frames it as a farm growing birch saplings, and the farmers "
        + "come over from the field, fell every tree that stood there and plant saplings in their place; the saplings "
        + "grow up in fast time as they go and a few are felled in turn",
      during: async (mark) => {
        await sleepMs(1_200);
        mark("declare");
        const zone = await frameZone(server, client, spots.woodlot, look, set.woodlot.plot, SAPLING);
        look = zone.look;
        mark("declared");
        await hideHud(client);
        await setTickRate(server, WORK_RATE);
        // A tree stands on the ground layer by its lowest log: a log there that is gone at the next look was felled.
        const stood = set.woodlot.trunks.map((trunk: Vec) => key(trunk));
        const felling = cycling(async () =>
          (await survey(server, { box: lift(set.woodlot.plot), block: LOG, up: true })).up as string[]);
        const felled = await felling.until(mark, "felled", (cut) => stood.every((one: string) => cut.has(one)));
        const done = await felling.until(mark, "done", (_cut, count) => count >= stood.length + REGROWN_TREES);
        await setTickRate(server, 20);
        await sleepMs(HOLD_AFTER_MS);
        await hideHud(client);
        return { zone: zone.evidence, felled, done };
      },
    });

    run.note("shots", shots);
    expect(shots.workshop.done.reached, `the shelf must reach ${LANTERN_COUNT} lanterns on camera`).toBe(true);
    expect(shots.field.done.reached, "every cell must be harvested ripe on camera").toBe(true);
    expect(shots.woodlot.felled.reached, "every standing birch must be felled on camera").toBe(true);
    expect(shots.woodlot.done.reached, `${REGROWN_TREES} regrown birches must be felled on camera`).toBe(true);
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

type Mark = (name: string) => void;

// One clip, with the moments it passes through stamped in seconds from its start, for the cut to time its pieces by.
async function film(run: any, client: any, server: any, name: string,
  options: { subject: string; during: (mark: Mark) => Promise<any> }) {
  const marks: Record<string, number> = {};
  let started = 0;
  let result: any;
  const gates = await promoClip(run, client, server, name, {
    subject: options.subject,
    worldState: {},
    seconds: 1,
    during: async () => {
      started = Date.now();
      result = await options.during((mark) => { marks[mark] = Math.round((Date.now() - started) / 100) / 10; });
      marks.end = Math.round((Date.now() - started) / 100) / 10;
    },
  });
  return { ...result, marks, gates };
}

// Polls until the reading passes and then holds still for a few polls: the job is done, not merely under way.
async function waitSteady(mark: Mark, read: () => Promise<number>, passes: (value: number) => boolean) {
  const started = Date.now();
  const readings: number[] = [];
  let steady = 0;
  let last = Number.NaN;
  while (Date.now() - started < WORK_TIMEOUT_MS) {
    await sleepMs(POLL_MS);
    const value = await read().catch(() => Number.NaN);
    readings.push(value);
    if (readings.length === 1 || value !== last) mark(`reading_${readings.length}=${value}`);
    steady = value === last && passes(value) ? steady + 1 : 0;
    last = value;
    if (steady >= STEADY_POLLS) {
      mark("done");
      return { reached: true, value, readings };
    }
  }
  return { reached: passes(last), value: last, readings };
}

// Watches the cells that are up (ripe, or standing as a tree) and counts each one that has come down since the
// last look, the same cell again whenever it comes up and down again; it cannot miss one that is up for a poll.
function cycling(read: () => Promise<string[]>) {
  let up = new Set<string>();
  const cut = new Set<string>();
  let count = 0;
  return {
    async until(mark: Mark, name: string, passes: (cut: Set<string>, count: number) => boolean) {
      const started = Date.now();
      const counts: number[] = [];
      while (Date.now() - started < WORK_TIMEOUT_MS) {
        await sleepMs(POLL_MS);
        const now = new Set(await read().catch(() => [...up]));
        for (const one of up) {
          if (!now.has(one)) {
            cut.add(one);
            count++;
          }
        }
        up = now;
        if (counts.length === 0 || counts[counts.length - 1] !== count) mark(`${name}_${count}`);
        counts.push(count);
        if (passes(cut, count)) {
          mark(name);
          return { reached: true, count, cut: cut.size, counts };
        }
      }
      return { reached: false, count, cut: cut.size, counts };
    },
  };
}

function key(at: Vec) {
  return `${at.x},${at.y},${at.z}`;
}

async function standAt(server: any, client: MinecraftClient, player: string, spot: Spot): Promise<Look> {
  const look = lookAt(spot, spot.aim);
  await world.command(server, `tp ${player} ${spot.feet.x + 0.5} ${spot.feet.y} ${spot.feet.z + 0.5} ${look.yaw} ${look.pitch}`);
  await tick.sprint(server, 10);
  await writeLook(client, look);
  await safeAction(() => frames(client, 30));
  await sleepMs(2_000);
  return look;
}

function lookAt(spot: Spot, target: Vec): Look {
  const eye = { x: spot.feet.x + 0.5, y: spot.feet.y + EYE, z: spot.feet.z + 0.5 };
  return cameraLookingAt(eye, { x: target.x + 0.5, y: target.y + 0.5, z: target.z + 0.5 });
}

// Frames the plot with the book from where the camera stands, makes it a farm and, when asked, picks its crop.
async function frameZone(server: any, client: MinecraftClient, spot: Spot, from: Look, plot: Box, crop: string | null) {
  const evidence: any = {};
  let look = from;
  const registry = await colonyRegistry(server);
  evidence.zones_before = await zoneCount(server, registry);
  await drainActionBar(client);
  for (const [corner, receipt, key] of [[plot.min, FIRST_CORNER, "corner_1"], [plot.max, ZONE_MARKED, "corner_2"]] as const) {
    look = await glide(client, look, lookAt(spot, { ...corner, y: corner.y + 0.4 }), GLIDE_MS);
    await sleepMs(BEAT_MS / 2);
    const tries: string[] = [];
    for (let attempt = 0; attempt < 3; attempt++) {
      await leftClick(client);
      await sleepMs(400);
      const bar = await readActionBar(client);
      tries.push(bar.text);
      if (receipt.test(bar.text)) break;
    }
    evidence[key] = tries;
    await sleepMs(BEAT_MS);
  }
  const middle = { x: (plot.min.x + plot.max.x) / 2, y: plot.min.y, z: (plot.min.z + plot.max.z) / 2 };
  // The marked box stays on screen a moment, so the plot reads before the panel covers it.
  look = await glide(client, look, lookAt(spot, middle), GLIDE_MS);
  await sleepMs(BEAT_MS);
  evidence.open = await rightClick(client);
  await waitForElement(client, { id: zoneKindToken("farm") }, { timeoutMs: 8_000 });
  await sleepMs(BEAT_MS);
  evidence.kind = await softly(() => clickElement(client, { id: zoneKindToken("farm") }));
  await sleepMs(BEAT_MS);
  if (crop) {
    // The picker holds open on the chosen sapling before it is confirmed.
    evidence.crop = await pickChoice(server, client, CROP_TOKEN, crop, () => buttonText(client, CROP_TOKEN),
      () => sleepMs(BEAT_MS));
    await sleepMs(BEAT_MS);
  }
  await waitForElement(client, { id: "folkways.zoneconfig.confirm" }, { timeoutMs: 8_000 });
  evidence.confirm = await softly(() => clickElement(client, { id: "folkways.zoneconfig.confirm" }));
  await sleepMs(BEAT_MS / 2);
  evidence.close = await safeAction(() => screen.dismiss(client));
  evidence.zones_after = await zoneCount(server, registry);
  if (evidence.zones_after !== evidence.zones_before + 1) {
    throw new Error(`framing ${JSON.stringify(plot)} made no zone: ${JSON.stringify(evidence)}`);
  }
  look = await glide(client, look, lookAt(spot, spot.aim), GLIDE_MS);
  return { look, evidence };
}

function cells(plot: Box) {
  return (Math.abs(plot.max.x - plot.min.x) + 1) * (Math.abs(plot.max.z - plot.min.z) + 1);
}

function lift(plot: Box): Box {
  return { min: { ...plot.min, y: plot.min.y + 1 }, max: { ...plot.max, y: plot.max.y + 1 } };
}

async function glide(client: any, from: Look, to: Look, ms: number): Promise<Look> {
  const steps = Math.max(1, Math.round(ms / 30));
  const yawDelta = ((to.yaw - from.yaw + 540) % 360) - 180;
  for (let step = 1; step <= steps; step++) {
    const t = step / steps;
    const eased = t * t * (3 - 2 * t);
    await writeLook(client, { yaw: from.yaw + yawDelta * eased, pitch: from.pitch + (to.pitch - from.pitch) * eased });
    await sleepMs(30);
  }
  return { yaw: from.yaw + yawDelta, pitch: to.pitch };
}

async function leftClick(client: MinecraftClient) {
  await safeAction(() => input.button(client, { button: "left", action: "press" }));
  await sleepMs(60);
  return safeAction(() => input.button(client, { button: "left", action: "release" }));
}

async function rightClick(client: MinecraftClient) {
  await safeAction(() => input.button(client, { button: "right", action: "press" }));
  await sleepMs(60);
  return safeAction(() => input.button(client, { button: "right", action: "release" }));
}

async function stageGround(server: any, o: Vec, set: any, spots: Record<string, Spot>) {
  const fill = (a: Vec, b: Vec, block: string) =>
    world.command(server, `fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} ${block}`);
  // Clearing ground that is already clear fills nothing, which the command reports as a failure.
  const clear = (a: Vec, b: Vec, block: string) =>
    tryCommand(server, `fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} ${block}`);
  const setblock = (p: Vec, block: string) => world.command(server, `setblock ${p.x} ${p.y} ${p.z} ${block}`);
  await world.command(server, `forceload add ${o.x - 48} ${o.z - 40} ${o.x + 48} ${o.z + 32}`);
  for (let y = o.y; y <= o.y + 24; y += 4) {
    await clear({ x: o.x - 48, y, z: o.z - 40 }, { x: o.x + 48, y: y + 3, z: o.z + 32 }, "minecraft:air");
  }
  await clear({ x: o.x - 48, y: o.y - 1, z: o.z - 40 }, { x: o.x + 48, y: o.y - 1, z: o.z + 32 }, "minecraft:grass_block");
  await tryCommand(server, `kill @e[type=!minecraft:player,x=${o.x},y=${o.y},z=${o.z},distance=..80]`);

  for (const foot of set.quarters.beds) {
    await setblock(foot, "minecraft:red_bed[part=foot,facing=east]");
    await setblock({ ...foot, x: foot.x + 1 }, "minecraft:red_bed[part=head,facing=east]");
  }
  await setblock(set.quarters.food, "minecraft:chest[facing=north]{Items:[{Slot:0b,id:\"minecraft:cooked_beef\",count:64},"
    + "{Slot:1b,id:\"minecraft:cooked_beef\",count:64}]}");

  // The workshop: the raw goods in the store at the back left, the table and the furnace along the back, a spare
  // chest at the side, and the chest the order is for at the front, nearest the camera.
  const shop = set.workshop;
  await fill({ x: shop.store.x - 1, y: o.y - 1, z: shop.table.z - 1 },
    { x: shop.shelf.x + 1, y: o.y - 1, z: shop.shelf.z + 1 }, "minecraft:coarse_dirt");
  await setblock(shop.table, "minecraft:crafting_table");
  await setblock(shop.furnace, "minecraft:furnace[facing=south]");
  const stock = WORKSHOP_STOCK.map(([id, count], slot) => `{Slot:${slot}b,id:\"${id}\",count:${count}}`).join(",");
  await setblock(shop.store, `minecraft:chest[facing=south]{Items:[${stock}]}`);
  await setblock(shop.spare, "minecraft:chest[facing=east]{Items:[]}");
  await setblock(shop.shelf, "minecraft:chest[facing=west]{Items:[]}");

  for (const ditch of set.field.ditches) {
    await fill(ditch.min, ditch.max, "minecraft:water");
  }

  // The field's seed store, at its corner; the wood lot's shed, before the trees.
  await setblock(set.field.barn, "minecraft:chest[facing=west]{Items:[{Slot:0b,id:\"minecraft:wheat_seeds\",count:64},"
    + "{Slot:1b,id:\"minecraft:birch_sapling\",count:32}]}");
  await setblock(set.woodlot.shed, "minecraft:chest[facing=south]{Items:[]}");
  const planted: string[] = [];
  for (const trunk of set.woodlot.trunks) {
    const placed = await tryCommand(server, `place feature ${TREE} ${trunk.x} ${trunk.y} ${trunk.z}`);
    planted.push(placed.status === "ok" && placed.success ? "ok" : JSON.stringify(placed));
  }

  // The camera stands on a barrier, unseen, above each shot.
  for (const spot of Object.values(spots)) {
    await setblock({ ...spot.feet, y: spot.feet.y - 1 }, "minecraft:barrier");
  }
  await tick.sprint(server, 5);
  return { origin: o, trees: planted };
}

async function castCrew(server: any, set: any) {
  const standing = await world.entities(server, { dimension: "minecraft:overworld", limit: 100, types: ["folkways:resident"] });
  const uuids: string[] = standing.entities.map((one: any) => String(one.uuid));
  if (uuids.length < CRAFTERS + FARMERS) throw new Error(`only ${uuids.length} residents to cast`);
  const crew: Array<{ uuid: string; trades: string[] }> = [];
  for (let i = 0; i < CRAFTERS + FARMERS; i++) {
    const crafter = i < CRAFTERS;
    const post: Vec = crafter ? set.workshop.crew : set.field.crew;
    const items: Array<[string, number]> = crafter ? [] : [["minecraft:wooden_hoe", 1], ["minecraft:iron_axe", 1],
      ["minecraft:wheat_seeds", 64], [SAPLING, 8]];
    for (let k = 0; k < items.length; k++) {
      await world.command(server, `data modify entity ${uuids[i]} "neoforge:attachments"."folkways:body_state".Pack.Items `
        + `append value {Slot:${8 + k}b,Stack:{id:"${items[k][0]}",count:${items[k][1]}}}`);
    }
    await world.command(server, `tp ${uuids[i]} ${post.x + 0.5 + (i % 3)} ${post.y} ${post.z + 0.5 + Math.floor(i / 3)} 180 0`);
    crew.push({ uuid: uuids[i], trades: crafter ? ["crafting", "hauling"] : ["farming"] });
  }
  const licensed = await licenseCrewFast(server, crew);
  await tick.sprint(server, 5);
  return { crew, licensed: licensed.licensed, idle: uuids.slice(CRAFTERS + FARMERS) };
}

async function survey(server: any,
  args: { box?: Box; block?: string; also?: string; up?: boolean; chest?: Vec; item?: string }) {
  return runColonyTask(server, SURVEY, args);
}

// How many of a block stand in a box (and of a second one, and how many crops there are ripe), and when asked, where
// each of that block is up: ripe if it is a crop, standing if not. Or how many of an item a container holds.
const SURVEY = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.declare-survey",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.Container",
      "net.minecraft.world.level.block.CropBlock",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var out=new LinkedHashMap<String,Object>();out.put("ok",true);
if(args.value("box")!=null){
  var b=(Map<?,?>)args.value("box");var lo=(Map<?,?>)b.get("min");var hi=(Map<?,?>)b.get("max");
  var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse(args.string("block")));
  var also=args.value("also")==null?null:BuiltInRegistries.BLOCK.get(ResourceLocation.parse(String.valueOf(args.value("also"))));
  int n=0,m=0,r=0;var up=new ArrayList<String>();
  for(var p:BlockPos.betweenClosed(((Number)lo.get("x")).intValue(),((Number)lo.get("y")).intValue(),((Number)lo.get("z")).intValue(),
      ((Number)hi.get("x")).intValue(),((Number)hi.get("y")).intValue(),((Number)hi.get("z")).intValue())){
    var s=level.getBlockState(p);if(s.is(block))n++;if(also!=null&&s.is(also))m++;
    if(s.getBlock() instanceof CropBlock crop&&crop.isMaxAge(s))r++;
    if(s.is(block)&&(!(s.getBlock() instanceof CropBlock grown)||grown.isMaxAge(s)))up.add(p.getX()+","+p.getY()+","+p.getZ());}
  out.put("blocks",n);out.put("also",m);out.put("ripe",r);
  if(Boolean.TRUE.equals(args.value("up")))out.put("up",up);
}
if(args.value("chest")!=null){
  var c=(Map<?,?>)args.value("chest");
  var be=level.getBlockEntity(new BlockPos(((Number)c.get("x")).intValue(),((Number)c.get("y")).intValue(),((Number)c.get("z")).intValue()));
  var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("item")));
  int n=0;
  if(be instanceof Container box)for(int i=0;i<box.getContainerSize();i++)if(box.getItem(i).is(item))n+=box.getItem(i).getCount();
  out.put("items",n);
}
return Sync.stamp(out);
`,
  }),
});


// DIAGNOSTIC
const PLACARDS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.declare-placards",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.api.Facing",
      "io.github.izakyl.folkways.front.api.notice.Line",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var view=colony.view(level);var out=new ArrayList<Object>();
for(var facing:Facing.all(colony))for(var placard:facing.placards(view)){
  var lines=new ArrayList<Object>();
  for(var line:placard.lines())lines.add(switch(line){
    case Line.Said said->said.notice().component().getString();
    case Line.Literal literal->literal.text();
    default->line.toString();
  });
  out.add(Map.of("id",placard.id().toString(),"lines",lines));
}
return Map.of("ok",true,"placards",out);
`,
  }),
});
