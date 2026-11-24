import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { defineTask, input, javaTask, player as bwPlayer, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { setBookGesture } from "../shared/colony/colony-founding";
import { clickElement, panelElements, waitForElement } from "../ldlib2";
import { aimPoint } from "../ldlib2/rules";
import { countResidents, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames, safeAction, softly, tryCommand } from "../shared/harness/bw-helpers";
import { outAbs } from "../shared/harness/out-paths";
import { admitPromoSettlersFast, foundPromoColonyFast } from "./fast-setup";
import { assistantSeed, startProvider, type Provider } from "./assistant-provider";
import { rigMove, rigStop, type RigMove } from "./debug-rig";
import { cinematicWorld, hideHud, hudShown, launchPromoPair, promoClip, sleepMs, type PromoPair } from "./promo-kit";
import type { VisualRun } from "../shared/capture/visual-kit";
import { pickRtsTool, tapV } from "../shared/combat/overhead";
import { PROJECT } from "../shared/combat/rts-fixtures";
import { clientClear } from "./airport-stage";
import { setVitals } from "./cookery-scene";

// The assistant promo's shared stage: a launched pair whose assistant talks to the recording loopback, a colony with a
// few residents, the chat worked as a player works it (typed, clicked, reviewed), and the camera on the rig.

export const ASSISTANT_CONFIG = "blockwright.toml#promo-assistant";
const PREFIX = "folkways.assistant.";

export type Stage = {
  run: VisualRun;
  pair: PromoPair;
  server: MinecraftServer;
  client: MinecraftClient;
  player: string;
  provider: Provider;
  /** The ground cell a flat world stands on: x and z are fixed for every take, so recorded scripts find their blocks. */
  ground: number;
  at: (x: number, y: number, z: number) => Vec;
};

export async function openStage(segment: string, run: VisualRun): Promise<Stage> {
  const provider = await startProvider(segment);
  const seed = assistantSeed(provider);
  const dir = outAbs("promo", "keep", "assistant-seed", segment);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, "folkways-assistant.json"), seed.settings);
  writeFileSync(join(dir, "credentials.json"), seed.credentials);
  run.note("provider", { mode: provider.mode, segment });
  const pair = await launchPromoPair(`assistant-${segment}`, {
    config: ASSISTANT_CONFIG,
    files: { client: { "config/folkways-assistant.json": join(dir, "folkways-assistant.json"),
      "config/jpi-mini/credentials.json": join(dir, "credentials.json") } },
  });
  const { server, client } = pair;
  const grounded = await waitForGroundedPlayer(server);
  const player = grounded.name;
  await world.command(server, `op ${player}`);
  await cinematicWorld(server);
  for (const command of ["gamerule doMobSpawning false", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
    "time set 4000", "weather clear", "gamerule sendCommandFeedback false"]) await tryCommand(server, command);
  run.note("client", await client.run(CLIENT_SETUP, { guiScale: 2 }));
  // The player is never in the picture: the rig films, and the player only points at things.
  await tryCommand(server, `effect give ${player} minecraft:invisibility infinite 0 true`);
  const ground = Math.round(grounded.pos.y);
  return { run, pair, server, client, player, provider, ground, at: (x, y, z) => ({ x, y: ground + y, z }) };
}

export async function closeStage(stage: Stage | undefined) {
  await stage?.provider.close();
}

/** A colony over the given member cells, with `count` residents who stand where asked. */
export async function castColony(stage: Stage, members: Vec[], count: number, standAt: Vec[]) {
  const { server, player } = stage;
  await world.command(server, `item replace entity ${player} weapon.mainhand with folkways_core:colony_book`);
  await tick.sprint(server, 5);
  const founded = await foundPromoColonyFast(server, player, members);
  await admitPromoSettlersFast(server, player, founded.colony_id, count);
  const residents = (await world.entities(server, { types: ["folkways_vanilla:resident"], limit: 64 })).entities.map(one => one.uuid);
  if (residents.length !== (await countResidents(server))) throw new Error("resident count changed during casting");
  for (const [i, uuid] of residents.entries()) {
    const spot = standAt[i % standAt.length];
    // A cell stands its resident in the middle of it; an exact position (a deck aboard a hull) is kept as it is.
    const at = (v: number) => (Number.isInteger(v) ? v + 0.5 : v).toFixed(3);
    await world.command(server, `tp ${uuid} ${at(spot.x)} ${spot.y.toFixed(3)} ${at(spot.z)}`);
    await world.command(server, `data merge entity ${uuid} {Invulnerable:1b}`);
  }
  // Fed and rested, so nobody lies down to sleep on the grass in the middle of a shot.
  await setVitals(server, founded.colony_id, residents.map(uuid => ({ uuid, food: 20, tired: 0 })));
  // The book's world cards are not part of these shots: the assistant is opened by its key.
  await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
  return { colony_id: founded.colony_id, residents };
}

// --- the chat ----------------------------------------------------------------------------------------------------

export async function openChat(stage: Stage) {
  await safeAction(() => input.key(stage.client, { keybind: "key.folkways.assistant" }));
  await waitForElement(stage.client, { id: PREFIX + "input" }, { timeoutMs: 15_000 });
}

export async function closeChat(stage: Stage) {
  await softly(() => screen.dismiss(stage.client));
  await frames(stage.client, 3);
}

let pointer = { x: 960, y: 900 };

/** Slides the pointer to a place on the GUI, so a click is seen coming. */
export async function glidePointer(client: MinecraftClient, to: { x: number; y: number }, ms = 500) {
  const steps = Math.max(2, Math.round(ms / 16));
  const from = pointer;
  for (let i = 1; i <= steps; i++) {
    const t = i / steps, e = t * t * (3 - 2 * t);
    await safeAction(() => input.move(client, { x: from.x + (to.x - from.x) * e, y: from.y + (to.y - from.y) * e }));
    await sleepMs(14);
  }
  pointer = to;
}

/** Moves to an element of the assistant's windows and clicks it. */
export async function press(stage: Stage, id: string) {
  const element = (await panelElements(stage.client)).find(one => one.id === id);
  if (element) await glidePointer(stage.client, aimPoint(element));
  await sleepMs(180);
  await clickElement(stage.client, { id }, { scrollIntoView: true });
}

/** Types a question into the chat a few characters at a time, then sends it. */
export async function ask(stage: Stage, text: string) {
  const { client } = stage;
  await press(stage, PREFIX + "input");
  const chars = [...text];
  for (let i = 0; i < chars.length; i += 2) {
    await safeAction(() => input.key(client, { text: chars.slice(i, i + 2).join("") }));
    await sleepMs(55);
  }
  await sleepMs(500);
  await safeAction(() => input.key(client, { keyCode: 257 }));
}

/** Types into the chat's input as it stands (focused, its caret where it is), a few characters at a time, then sends. */
export async function typeOn(stage: Stage, text: string) {
  const chars = [...text];
  for (let i = 0; i < chars.length; i += 2) {
    await safeAction(() => input.key(stage.client, { text: chars.slice(i, i + 2).join("") }));
    await sleepMs(55);
  }
  await sleepMs(500);
  await safeAction(() => input.key(stage.client, { keyCode: 257 }));
}

// --- a box drawn with the book and handed to the assistant ----------------------------------------------------------

export const ASK_OFFER = "folkways.book.pick.folkways_assistant.ask";
const EYE = 1.62;

/** Where the player stands to draw, and the two corner blocks clicked, each aimed at a point on its face seen. */
export type BoxDraw = { stand: Vec; first: Vec; second: Vec; firstAim?: Vec; secondAim?: Vec;
  // Ship corners are Sable storage cells, which both the pick ray and the book report.
  shipSize?: number[] };

/** Transforms a point inside a block's outline with its sublevel's current pose. */
export const SHIP_AIM = defineTask<Vec, Vec>({
  name: "folkways.promo.assistant.ship-aim", side: "server", source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.phys.Vec3",
      "io.github.izakyl.folkways.core.world.api.WorldSpaces"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var cell = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var frame = WorldSpaces.containing(level, cell).orElseThrow();
var state = level.getBlockState(cell);
var shape = state.getShape(level, cell);
if (shape.isEmpty()) {
    throw new IllegalStateException("No outline at " + cell + ": " + state);
}
var outline = shape.bounds();
var point = frame.toWorld(Vec3.atLowerCornerOf(frame.local(cell)).add(outline.getCenter()));
return Map.of("x", point.x, "y", point.y, "z", point.z);`,
  }),
});

let look = { yaw: 0, pitch: 0 };

/**
 * Before the shot: the player in creative, flying where they stand (out of the picture, invisible), the colony book in
 * hand and turned to the box gesture, looking a little off the first corner.
 */
export async function readyBook(stage: Stage, draw: BoxDraw) {
  const { server, client, player } = stage;
  await world.command(server, `gamemode creative ${player}`);
  await world.command(server, `item replace entity ${player} weapon.mainhand with folkways_core:colony_book`);
  const s = draw.stand;
  await world.command(server, `tp ${player} ${s.x.toFixed(3)} ${s.y.toFixed(3)} ${s.z.toFixed(3)}`);
  await client.run(FLY, {});
  await tick.sprint(server, 5);
  const gesture = await setBookGesture(server, client, player, "box");
  await world.command(server, `tp ${player} ${s.x.toFixed(3)} ${s.y.toFixed(3)} ${s.z.toFixed(3)}`);
  const aim = aimOf(draw.firstAim ?? centre(draw.first));
  look = { yaw: aim.yaw - 14, pitch: aim.pitch + 6 };
  await safeAction(() => bwPlayer.place(client, look));
  await frames(client, 3);
  return gesture;

  function aimOf(at: Vec) {
    return cameraLookingAt({ x: s.x, y: s.y + EYE, z: s.z }, at);
  }
}

function sameCell(a: Vec | undefined, b: Vec) {
  return !!a && a.x === b.x && a.y === b.y && a.z === b.z;
}

function centre(v: Vec): Vec {
  return { x: v.x + 0.5, y: v.y + 0.5, z: v.z + 0.5 };
}

/** Turns the player's look to `at` over `ms`, eased: the box being drawn follows the crosshair. */
async function turnTo(stage: Stage, from: Vec, at: Vec, ms: number) {
  const to = cameraLookingAt({ x: from.x, y: from.y + EYE, z: from.z }, at);
  let dyaw = to.yaw - look.yaw;
  while (dyaw > 180) dyaw -= 360;
  while (dyaw < -180) dyaw += 360;
  const start = { ...look };
  const steps = Math.max(2, Math.round(ms / 30));
  for (let i = 1; i <= steps; i++) {
    const t = i / steps, e = t * t * (3 - 2 * t);
    look = { yaw: start.yaw + dyaw * e, pitch: start.pitch + (to.pitch - start.pitch) * e };
    await safeAction(() => bwPlayer.place(stage.client, look));
    await sleepMs(18);
  }
}

async function leftClick(client: MinecraftClient) {
  await safeAction(() => input.button(client, { button: "left", action: "press" }));
  await safeAction(() => input.button(client, { button: "left", action: "release" }));
}

/**
 * In the shot: the first corner clicked, the look swept to the second while the box grows after it, the second
 * clicked, the book used, and 交给助手 picked in the picker that asks what the box becomes. The chat opens with the
 * box attached and the sentence begun; the rest of the question is typed after it and sent. Marks `box`, `boxed`,
 * `offer`, `open` and `asked`.
 */
export async function askAboutBox(stage: Stage, draw: BoxDraw, rest: string, mark: (label: string) => void,
  afterBox?: () => Promise<void>) {
  const { client } = stage;
  mark("box");
  await sleepMs(500);
  const aimAt = (cell: Vec, aim?: Vec) => draw.shipSize
    ? stage.server.run(SHIP_AIM, cell) : Promise.resolve(aim ?? centre(cell));
  await turnTo(stage, draw.stand, await aimAt(draw.first, draw.firstAim), 700);
  await sleepMs(250);
  stage.run.note("aim-first", await settleAim(stage, draw.first, () => aimAt(draw.first, draw.firstAim)));
  await leftClick(client);
  await sleepMs(500);
  await turnTo(stage, draw.stand, await aimAt(draw.second, draw.secondAim), 1800);
  await sleepMs(300);
  stage.run.note("aim-second", await settleAim(stage, draw.second, () => aimAt(draw.second, draw.secondAim)));
  await leftClick(client);
  await frames(client, 4);
  const marked = await client.run(MARKED, {});
  stage.run.note("marked-box", marked);
  const want = { min: minOf(draw.first, draw.second), max: maxOf(draw.first, draw.second) };
  const size = marked.min && marked.max
    ? [marked.max.x - marked.min.x + 1, marked.max.y - marked.min.y + 1, marked.max.z - marked.min.z + 1].sort() : [];
  const right = sameCell(marked.min, want.min) && sameCell(marked.max, want.max)
    && (!draw.shipSize || JSON.stringify(size) === JSON.stringify([...draw.shipSize].sort()));
  if (!marked.marked || !right) {
    throw new Error(`the book marked ${JSON.stringify(marked)}, not ${JSON.stringify(draw.shipSize ?? want)}`);
  }
  mark("boxed");
  await afterBox?.();
  await sleepMs(900);
  await safeAction(() => input.button(client, { button: "right", action: "press" }));
  await safeAction(() => input.button(client, { button: "right", action: "release" }));
  await waitForElement(client, { id: ASK_OFFER }, { timeoutMs: 8_000 });
  mark("offer");
  await sleepMs(1200);
  await press(stage, ASK_OFFER);
  await waitForElement(client, { id: PREFIX + "input" }, { timeoutMs: 15_000 });
  mark("open");
  await sleepMs(900);
  const begun = (await panelElements(client)).find(one => one.id === PREFIX + "input")?.text;
  stage.run.note("prefilled", begun);
  await typeOn(stage, rest);
  mark("asked");
}

/**
 * Opens a facility's card from the colony book, as a player reaches one that stands on no block of its own (a build
 * site): the book used on open ground opens the colony's panel, its Facilities page is turned to and the facility's
 * row clicked. The player stands at `stand` (out of the picture), aiming at `aim`. Answers once the card is up.
 */
export async function openCardFromBook(stage: Stage, stand: Vec, aim: Vec, facility: string, mark?: (label: string) => void) {
  const { server, client, player } = stage;
  await world.command(server, `gamemode creative ${player}`);
  await world.command(server, `item replace entity ${player} weapon.mainhand with folkways_core:colony_book`);
  await world.command(server, `tp ${player} ${stand.x.toFixed(3)} ${stand.y.toFixed(3)} ${stand.z.toFixed(3)}`);
  await client.run(FLY, {});
  await tick.sprint(server, 3);
  await setBookGesture(server, client, player, "point");
  await world.command(server, `tp ${player} ${stand.x.toFixed(3)} ${stand.y.toFixed(3)} ${stand.z.toFixed(3)}`);
  look = cameraLookingAt({ x: stand.x, y: stand.y + EYE, z: stand.z }, aim);
  await safeAction(() => bwPlayer.place(client, look));
  await frames(client, 4);
  mark?.("book");
  await safeAction(() => input.button(client, { button: "right", action: "press" }));
  await safeAction(() => input.button(client, { button: "right", action: "release" }));
  await waitForElement(client, { id: "folkways.tab.facilities" }, { timeoutMs: 10_000 });
  mark?.("panel");
  await sleepMs(900);
  await press(stage, "folkways.tab.facilities");
  const row = `folkways.book.facility.${facility}`;
  await waitForElement(client, { id: row }, { timeoutMs: 10_000 });
  await sleepMs(900);
  await press(stage, row);
  for (let wait = 0; wait < 40; wait++) {
    await sleepMs(250);
    if ((await panelElements(client)).some(one => one.id?.startsWith("folkways.card.") && one.width > 0)) {
      mark?.("card");
      return wait;
    }
  }
  throw new Error(`the card of ${facility} did not open from the Facilities page`);
}

function minOf(a: Vec, b: Vec) { return { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), z: Math.min(a.z, b.z) }; }
function maxOf(a: Vec, b: Vec) { return { x: Math.max(a.x, b.x), y: Math.max(a.y, b.y), z: Math.max(a.z, b.z) }; }

const FLY = defineTask<Record<string, any>, any>({
  name: "folkways.promo.assistant.fly", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var p = Minecraft.getInstance().player;
p.getAbilities().flying = true;
p.onUpdateAbilities();
return Map.of("flying", p.getAbilities().flying);` }),
});

/** The box the book holds marked, in the level's cells (or a ship's, for a box drawn on one). */
/**
 * Before a corner is clicked: the look is re-aimed from where the player's eye really is, until the client's crosshair
 * rests on `cell`. Recomputes the aim on every retry for moving blocks.
 */
async function settleAim(stage: Stage, cell: Vec, aim: () => Promise<Vec>) {
  const tries: Array<Vec | null> = [];
  for (let i = 0; i < 12; i++) {
    await frames(stage.client, 2);
    const seen = await stage.client.run(CROSSHAIR, {});
    tries.push(seen.hit ?? null);
    if (sameCell(seen.hit, cell)) {
      return tries;
    }
    look = cameraLookingAt(seen.eye, await aim());
    await safeAction(() => bwPlayer.place(stage.client, look));
    await sleepMs(60);
  }
  throw new Error(`the book aimed at ${JSON.stringify(tries)}, expected ${JSON.stringify(cell)}`);
}

/** Points the player's view at a ship block without changing the marked selection. */
export async function pointAtShipCell(stage: Stage, cell: Vec, offset: Vec) {
  const aim = await stage.server.run(SHIP_AIM, cell);
  const stand = { x: aim.x + offset.x, y: aim.y + offset.y, z: aim.z + offset.z };
  await world.command(stage.server, `tp ${stage.player} ${stand.x} ${stand.y} ${stand.z}`);
  await turnTo(stage, stand, aim, 300);
  return settleAim(stage, cell, () => stage.server.run(SHIP_AIM, cell));
}

const CROSSHAIR = defineTask<Record<string, any>, { eye: Vec; hit?: Vec }>({
  name: "folkways.promo.assistant.crosshair", side: "client", source: javaTask({ body: `
var mc = net.minecraft.client.Minecraft.getInstance();
var eye = mc.player.getEyePosition();
var out = new java.util.HashMap<String, Object>();
out.put("eye", Map.of("x", eye.x, "y", eye.y, "z", eye.z));
// The colony book's own ray: 64 blocks, block outlines, no fluids.
if (mc.player.pick(64.0D, 1.0F, false) instanceof net.minecraft.world.phys.BlockHitResult hit
    && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
  var at = hit.getBlockPos();
  out.put("hit", Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ()));
}
return out;` }),
});

const MARKED = defineTask<Record<string, any>, { marked: boolean; min?: Vec; max?: Vec }>({
  name: "folkways.promo.assistant.marked", side: "client", source: javaTask({ body: `
var type = Class.forName("io.github.izakyl.folkways.core.front.client.ZoneSelection");
var marked = (java.util.Optional<?>) type.getMethod("marked").invoke(null);
if (marked.isEmpty()) return Map.of("marked", false);
var box = marked.get();
var min = (net.minecraft.core.BlockPos) box.getClass().getMethod("min").invoke(box);
var max = (net.minecraft.core.BlockPos) box.getClass().getMethod("max").invoke(box);
return Map.of("marked", true, "min", Map.of("x", min.getX(), "y", min.getY(), "z", min.getZ()),
  "max", Map.of("x", max.getX(), "y", max.getY(), "z", max.getZ()));` }),
});

export async function status(stage: Stage) {
  return (await panelElements(stage.client)).find(one => one.id === PREFIX + "status")?.text;
}

export type Review = { summary: string; handoff: boolean };

/**
 * Waits out one answer of the assistant. Each change it puts up for review is handed to `decide`, which approves or
 * rejects it (and, for a build in creative, picks who builds it). Returns when the assistant is idle again.
 */
export async function converse(stage: Stage, decide: (review: Review) => Promise<void>, options: { timeoutMs?: number;
  idle?: string; mark?: (label: string) => void; /** Return once the first review is decided. */ once?: boolean } = {}) {
  const deadline = Date.now() + (options.timeoutMs ?? 420_000);
  const reviews: Review[] = [];
  await sleepMs(1500);
  while (Date.now() < deadline) {
    const elements = await panelElements(stage.client).catch(() => []);
    const summary = elements.find(one => one.id === PREFIX + "confirmation.summary" && one.width > 0);
    if (summary) {
      const review = { summary: summary.text ?? "", handoff: elements.some(one => one.id === PREFIX + "handoff") };
      reviews.push(review);
      options.mark?.(`review-${reviews.length}`);
      await decide(review);
      if (options.once) return reviews;
      await sleepMs(1200);
      continue;
    }
    const now = elements.find(one => one.id === PREFIX + "status")?.text;
    if (options.idle && now === options.idle
        && !elements.some(one => one.id === PREFIX + "confirm" && one.width > 0)) {
      return reviews;
    }
    await sleepMs(500);
  }
  throw new Error(`The assistant did not finish within the take: ${JSON.stringify(reviews)}`);
}

// --- camera ------------------------------------------------------------------------------------------------------

export async function camera(stage: Stage, move: RigMove) {
  if (hudShown(stage.client)) await hideHud(stage.client);
  return rigMove(stage.client, move);
}

/**
 * A point to look at from `eye` that puts `subject` at `across` of the picture's width (0 left, 1 right), level with
 * it: the chat window takes the middle of the picture, so what it talks about stands beside it.
 */
export function beside(eye: Vec, subject: Vec, across: number, fov: number): Vec {
  const dx = subject.x - eye.x, dz = subject.z - eye.z;
  const half = Math.atan(Math.tan(fov * Math.PI / 360) * 16 / 9);
  const off = Math.atan((2 * across - 1) * Math.tan(half));
  // Minecraft looks along +z with +x to its left: turning the view left moves the subject right.
  const heading = Math.atan2(dz, dx) - off;
  const reach = Math.hypot(dx, dz);
  return { x: eye.x + Math.cos(heading) * reach, y: subject.y, z: eye.z + Math.sin(heading) * reach };
}

export async function cameraOff(stage: Stage) {
  await rigStop(stage.client);
}

/** One clip of the take, with the moments `mark` names kept for the cut. */
export async function shot(stage: Stage, name: string, subject: string, during: (mark: (label: string) => void) => Promise<void>,
  seconds = 1) {
  const marks: Record<string, number> = {};
  let t0 = Date.now();
  await promoClip(stage.run, stage.client, stage.server, name, {
    subject, worldState: {}, seconds,
    during: async () => {
      t0 = Date.now();
      await during(label => { marks[label] ??= Math.round((Date.now() - t0) / 100) / 10; });
    },
  });
  stage.run.note(`marks-${name}`, marks);
  return marks;
}

const CLIENT_SETUP = defineTask<{ guiScale: number }, any>({
  name: "folkways.promo.assistant.client", side: "client", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.client.KeyMapping", "net.minecraft.client.tutorial.TutorialSteps",
      "com.mojang.blaze3d.platform.InputConstants"],
    body: `
var mc = Minecraft.getInstance();
mc.options.guiScale().set((int) Args.of(ctx).integer("guiScale"));
mc.options.tutorialStep = TutorialSteps.NONE;
mc.getTutorial().setStep(TutorialSteps.NONE);
mc.getToasts().clear();
for (var key : mc.options.keyMappings) {
    if (key.getName().equals("key.folkways.assistant")) mc.options.setKey(key, InputConstants.getKey("key.keyboard.k"));
}
KeyMapping.resetMapping();
mc.options.save();
mc.resizeDisplay();
return Map.of("guiScale", mc.getWindow().getGuiScale(), "lang", mc.options.languageCode);`,
  }),
});

// --- the view from above -----------------------------------------------------------------------------------------

/** The tactical view's eye, placed exactly: Overhead keeps it private, so it is reached the way a test reaches it. */
export const TACTICAL = defineTask<Record<string, any>, any>({
  name: "folkways.promo.assistant.tactical", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3"],
    body: `
var a = Args.of(ctx);
var type = Class.forName("io.github.izakyl.folkways.core.front.client.Overhead");
var on = type.getDeclaredMethod("on"); on.setAccessible(true);
if (a.string("action").equals("state")) return Map.of("on", (Boolean) on.invoke(null));
if (a.string("action").equals("pose")) {
  var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
  var at = camera.getPosition();
  var look = at.add(Vec3.directionFromRotation(camera.getXRot(), camera.getYRot()).scale(a.number("reach")));
  return Map.of("on", (Boolean) on.invoke(null), "eye", Map.of("x", at.x, "y", at.y, "z", at.z),
    "look", Map.of("x", look.x, "y", look.y, "z", look.z));
}
if (a.string("action").equals("leave")) {
  var leave = type.getDeclaredMethod("leave", Minecraft.class); leave.setAccessible(true);
  leave.invoke(null, Minecraft.getInstance());
  return Map.of("on", (Boolean) on.invoke(null));
}
var eye = a.object("eye"); var focus = a.object("focus");
var from = new Vec3(eye.number("x"), eye.number("y"), eye.number("z"));
var to = new Vec3(focus.number("x") + 0.5, focus.number("y"), focus.number("z") + 0.5);
var d = to.subtract(from);
float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
var place = type.getDeclaredMethod("place", Vec3.class, float.class, float.class); place.setAccessible(true);
place.invoke(null, from, yaw, pitch);
var focusField = type.getDeclaredField("focus"); focusField.setAccessible(true); focusField.set(null, null);
return Map.of("yaw", yaw, "pitch", pitch);`,
  }),
});

/**
 * Opens the RTS view with its eye at `eye`, looking at `focus`; the book is held for it. With `keepRig` a rig already
 * holding this very picture stays on while the view opens beneath it, and lets go once the view is placed, so the
 * picture never shows the player's own eyes in between.
 */
export async function openTactical(stage: Stage, focus: Vec, eye: Vec, keepRig = false) {
  const { server, client, player } = stage;
  if (!keepRig) await rigStop(client);
  // RTS orders are refused to a spectator: the player stands on the ground behind the view, out of the picture.
  const back = Math.hypot(eye.x - focus.x, eye.z - focus.z) || 1;
  const stand = { x: eye.x + (eye.x - focus.x) / back * 6, z: eye.z + (eye.z - focus.z) / back * 6 };
  await world.command(server, `gamemode creative ${player}`);
  await world.command(server, `tp ${player} ${stand.x} ${focus.y + 1} ${stand.z}`);
  await world.command(server, `item replace entity ${player} weapon.mainhand with folkways_core:colony_book`);
  await frames(client, 5);
  if (!(await client.run(TACTICAL, { action: "state" })).on) {
    await tapV(client);
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    await pickRtsTool(client);
  }
  await client.run(TACTICAL, { action: "place", eye, focus });
  await frames(client, 20);
  if (keepRig) {
    await rigStop(client);
    await frames(client, 2);
  }
  await clientClear(client);
}

/**
 * Opens the RTS view framed from `eye` looking at `look`, as a rig shot is framed: a clip films its action from this
 * one still picture, the card open over it, instead of flying the rig between beats.
 */
export async function openTacticalAt(stage: Stage, eye: Vec, look: Vec) {
  await openTactical(stage, { x: look.x - 0.5, y: look.y, z: look.z - 0.5 }, eye, true);
}

export async function leaveTactical(stage: Stage) {
  if ((await stage.client.run(TACTICAL, { action: "state" })).on) {
    await stage.client.run(TACTICAL, { action: "leave" });
    await frames(stage.client, 5);
  }
  await world.command(stage.server, `item replace entity ${stage.player} weapon.mainhand with minecraft:air`);
  await clientClear(stage.client);
}

/** Leaves the tactical view without a cut: the rig takes over the very pose the view drew from, then glides. */
export async function glideFromTactical(stage: Stage, move: RigMove, seconds: number) {
  const pose = await stage.client.run(TACTICAL, { action: "pose", reach: 24 });
  await rigMove(stage.client, { eye: [pose.eye], look: [pose.look] });
  await frames(stage.client, 3);
  await leaveTactical(stage);
  await frames(stage.client, 2);
  return rigMove(stage.client, { ...move, glide: seconds });
}

/**
 * Takes the picture off the tactical view without leaving it: the rig starts at the very pose the view drew from and
 * glides to `move`, while the view (and the card open in it) stays up over the picture.
 */
export async function rigOverTactical(stage: Stage, move: RigMove, seconds: number) {
  const pose = await stage.client.run(TACTICAL, { action: "pose", reach: 24 });
  await rigMove(stage.client, { eye: [pose.eye], look: [pose.look] });
  await frames(stage.client, 3);
  return rigMove(stage.client, { ...move, glide: seconds });
}

/** Glides the rig back to the tactical view's own pose and hands the picture back to it, for clicks on the world. */
export async function backToTactical(stage: Stage, seconds: number) {
  const pose = await stage.client.run(TACTICAL, { action: "pose", reach: 24 });
  await rigMove(stage.client, { eye: [pose.eye], look: [pose.look], glide: seconds });
  await sleepMs(seconds * 1000 + 300);
  await rigStop(stage.client);
  await frames(stage.client, 2);
}

/** Moves the tactical view's eye (the picture cuts there): for orders clicked on a part of the world out of view. */
export async function placeTactical(stage: Stage, focus: Vec, eye: Vec) {
  await stage.client.run(TACTICAL, { action: "place", eye, focus });
  await frames(stage.client, 5);
}

/** The units the tactical view lists for the colony: kind, name, where each stands. */
export const UNITS = defineTask<Record<string, any>, Array<{ id: string; kind: string; name: string; x: number; y: number; z: number }>>({
  name: "folkways.promo.assistant.units", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.command.combat.net.ClientUnits"], body: `
var out = new ArrayList<Object>();
for (var unit : ClientUnits.units()) {
    var at = unit.position().cell();
    out.add(Map.of("id", unit.id().toString(), "kind", String.valueOf(unit.kind()), "name", unit.name().getString(),
        "x", at.getX(), "y", at.getY(), "z", at.getZ(), "facility", String.valueOf(unit.facility().map(f -> f.extent()).orElse(null))));
}
return out;` }),
});

/** Clicks a facility's cell in the tactical view until its card opens (the view syncs the colony for a moment). */
export async function selectFacility(stage: Stage, cell: Vec) {
  for (let attempt = 0; attempt < 6; attempt++) {
    await clickCell(stage, cell);
    for (let wait = 0; wait < 8; wait++) {
      await sleepMs(250);
      if ((await panelElements(stage.client)).some(one => one.id?.startsWith("folkways.card."))) return attempt + 1;
    }
    await sleepMs(1200);
  }
  throw new Error(`no facility card opened for ${JSON.stringify(cell)}`);
}

/** Clicks a world cell in the tactical view, the pointer gliding there first. */
export async function clickCell(stage: Stage, cell: Vec, button: "left" | "right" = "left") {
  const at = await stage.client.run(PROJECT, { x: cell.x, y: cell.y, z: cell.z, entity: "" });
  await glidePointer(stage.client, { x: at.x, y: at.y }, 700);
  await sleepMs(200);
  await input.click(stage.client, { x: at.x, y: at.y }, { button });
}

/** Clicks an entity in the tactical view. */
export async function clickEntity(stage: Stage, entity: string, button: "left" | "right" = "left") {
  const at = await stage.client.run(PROJECT, { x: 0, y: 0, z: 0, entity });
  await glidePointer(stage.client, { x: at.x, y: at.y }, 700);
  await sleepMs(200);
  await input.click(stage.client, { x: at.x, y: at.y }, { button });
}

/** What the colony's labour is stuck on, and the facility runs still waiting: read when a resident never acts. */
export const LABOR_DIAGNOSIS = defineTask<{ colony: string }, any>({
  name: "folkways.promo.assistant.labor", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.runtime.Labor", "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.facility.api.DefinedFacilities", "net.minecraft.world.level.Level"],
    body: `
var id = UUID.fromString(Args.of(ctx).string("colony"));
var out = new LinkedHashMap<String, Object>();
Labor.site(id, Level.OVERWORLD).ifPresent(labor -> {
    out.put("stalls", new ArrayList<>(labor.stalls()));
    out.put("diagnosis", String.valueOf(labor.diagnosis()));
});
Colonies.of(ctx.server(), id).flatMap(DefinedFacilities::of).ifPresent(services -> {
    var runs = new LinkedHashMap<String, Object>();
    for (var name : services.list()) runs.put(name, Map.of("running", services.running(name),
        "recent", String.valueOf(services.recent(name))));
    out.put("facilities", runs);
    out.put("facility_stalls", services.stalls(Level.OVERWORLD).stream().map(String::valueOf).toList());
});
return out;`,
  }),
});
