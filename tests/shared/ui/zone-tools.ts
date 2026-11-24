import { expect } from "@playwright/test";
import { defineTask, input, javaTask, player, reflect, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement, type Element, panelElements } from "../../ldlib2";
import {
  BOOK_TOOLBAR, chooseBookTool, chooseInSelector, openColonyPanelViaBook, pendingBookPick, pickShape, setBookGesture, type Vec,
} from "../colony/colony-founding";
import { cellTextIn, pickCell, settlePanel, toggleCell, typeCell } from "./site-panel";
import { drainActionBar, readActionBar } from "./zone-receipt";
import { waitForPresent } from "./book-cards";
import { frames, safeAction, tryCommand, screenName, waitForElement, softly } from "../harness/bw-helpers";
import { GROUND, type Stage } from "../harness/stage";

export { chooseBookTool, pickShape, setBookGesture };
export type { BookGesture } from "../colony/colony-founding";

export const KEY_LEFT_CONTROL = 341;
export const KEY_LEFT_ALT = 342;

const CORNER_ATTEMPTS = 3;
const CORNER_POLLS = 5;

export async function markCorner(
  server: MinecraftServer,
  client: MinecraftClient,
  corner: Vec,
  expected: RegExp,
  click: () => Promise<any>,
) {
  const tries: any[] = [];
  const inTheWay = new Set<string>();
  for (let attempt = 0; attempt < CORNER_ATTEMPTS; attempt++) {
    const clicked = await click();
    let bar = { text: "", remaining_ticks: 0 };
    for (let poll = 0; poll < CORNER_POLLS; poll++) {
      await tick.sprint(server, 3);
      bar = await readActionBar(client);
      if (bar.remaining_ticks > 0 && expected.test(bar.text)) return { corner, attempt, action_bar: bar.text };
    }
    for (const one of await standingAt(server, corner)) inTheWay.add(one);
    tries.push({ click: clicked, action_bar: bar.text });
  }
  throw new Error(`could not mark corner ${JSON.stringify(corner)}`
    + (inTheWay.size > 0 ? `; standing on that cell: ${[...inTheWay].join(" / ")}` : "; no entity on that cell")
    + `: ${JSON.stringify(tries.map((one) => one.action_bar))}`);
}

const CORNER_REACH = 1.5;

async function standingAt(server: MinecraftServer, block: Vec): Promise<string[]> {
  const middle = { x: block.x + 0.5, y: block.y + 1, z: block.z + 0.5 };
  const result: any = await world
    .entities(server, { dimension: "minecraft:overworld", limit: 1000 })
    .catch(() => ({ entities: [] }));
  return (result.entities ?? [])
    .filter((entity: any) => {
      const at = entity.position;
      return at && Math.hypot(at.x - middle.x, at.y - middle.y, at.z - middle.z) <= CORNER_REACH;
    })
    .map((entity: any) => `${entity.type}@${round(entity.position.x)},${round(entity.position.z)}`);
}

function round(value: number) {
  return Math.round(value * 10) / 10;
}

export async function aimAndLeftClick(server: MinecraftServer, client: MinecraftClient, playerName: string, standAt: Vec, block: Vec,
  stage: Stage = GROUND) {
  // Looked at from where the player is set down: on a ship's deck a little above where it stands. Aimed as if from
  // the deck, a far block looked up at is passed over by that lift. Every coordinate is sent with its decimals:
  // /tp moves a whole-number x or z to the middle of its block.
  const at = stage.stand(standAt);
  const eye = { x: at.x, y: at.y + 1.62, z: at.z };
  const target = { x: block.x + 0.5, y: block.y + 1, z: block.z + 0.5 };
  const camera = cameraLookingAt(eye, target);
  const teleport = await tryCommand(
    server,
    `tp ${playerName} ${at.x.toFixed(3)} ${at.y.toFixed(3)} ${at.z.toFixed(3)} ${camera.yaw} ${camera.pitch}`,
  );
  await tick.sprint(server, 3);
  const press = await safeAction(() =>
    input.button(client, { button: "left", action: "press" }),
  );
  const release = await safeAction(() =>
    input.button(client, { button: "left", action: "release" }),
  );
  return { block, standAt, camera, teleport, click: press, release };
}

export async function ctrlScroll(server: MinecraftServer, client: MinecraftClient, notches: number) {
  return heldScroll(server, client, [KEY_LEFT_CONTROL], notches);
}

export async function heldScroll(server: MinecraftServer, client: MinecraftClient, keyCodes: number[], notches: number) {
  await safeAction(() => frames(client, 4));
  for (const keyCode of keyCodes) {
    await safeAction(() => input.key(client, { keyCode, action: "press" }));
  }
  const scroll = await safeAction(() =>
    input.scroll(client, { yOffset: notches }),
  );
  for (const keyCode of keyCodes) {
    await safeAction(() => input.key(client, { keyCode, action: "release" }));
  }
  await tick.sprint(server, 2);
  return { notches, scroll, bar: await readActionBar(client) };
}

export async function markBox(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standAt: Vec,
  min: Vec,
  max: Vec,
  stage: Stage = GROUND,
) {
  await drainActionBar(client);
  const corner1 = await aimAndLeftClick(server, client, playerName, standAt, min, stage);
  await tick.sprint(server, 3);
  const corner2 = await aimAndLeftClick(server, client, playerName, standAt, max, stage);
  await tick.sprint(server, 5);
  return { corner_1: corner1, corner_2: corner2, marked: await readActionBar(client) };
}

export const ZONE_KIND_ID = {
  farm: "folkways_vanilla:farm",
  dining: "folkways_vanilla:dining",
  pasture: "folkways_vanilla:pasture",
  fish: "folkways_vanilla:fish",
  ground: "folkways_vanilla:ground",
} as const;

export type ZoneKind = keyof typeof ZONE_KIND_ID;

/** The held book's toolbar entry that draws this kind of zone. */
export function zoneKindKey(kind: ZoneKind): string {
  return `kind:${ZONE_KIND_ID[kind]}`;
}

/** A facility's card window, and the parts of it these helpers reach. */
export const CARD_WINDOW = "folkways.card.window";
export const CARD_LINES = "folkways.card.lines";
export const CARD_RELEASE = "folkways.card.button.release";

/** The card control a zone setting is edited with: a flag's box, a count's field, a choice, an item list. */
export function zoneSettingToken(edit: ZoneEdit | string): string {
  if (typeof edit === "string") return `folkways.card.flag.${edit}`;
  if ("count" in edit) return `folkways.card.number.${edit.key}.field`;
  if ("option" in edit) return `folkways.card.choice.${edit.key}`;
  if ("item" in edit) return `folkways.card.items.${edit.key}`;
  return `folkways.card.flag.${edit.key}`;
}

/**
 * Hands the shape drawn to an entry: the book is turned to the entry's gesture (a shape drawn that way stays), used,
 * and the entry picked in the picker that asks what the shape becomes. A kind's card, or an offer's panel, opens.
 */
export async function handShapeTo(server: MinecraftServer, client: MinecraftClient, key: string) {
  const choose = await chooseBookTool(server, client, key);
  const use = await useBook(client);
  await tick.sprint(server, 5);
  return { key, choose, use };
}

/**
 * The book used where it is aimed, as a right click: it opens what is aimed at, or, on a whole shape, asks what the
 * shape becomes. That question is answered with the entry {@link chooseBookTool} last readied, as a player answers it.
 */
export async function useBook(client: MinecraftClient) {
  const pending = pendingBookPick(client);
  const whole = pending ? (await softly(() => client.run(BOOK_TOOLBAR, undefined)) as any)?.ready === true : false;
  const press = await safeAction(() => input.button(client, { button: "right", action: "press" }));
  const release = await safeAction(() => input.button(client, { button: "right", action: "release" }));
  const pick = pending && whole ? await pickShape(client, pending) : undefined;
  return { press, release, pick };
}

export async function openBookPage(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
  stage: Stage = GROUND,
) {
  const open_panel = await openColonyPanelViaBook(server, client, playerName, standBlock, stage);
  await settlePanel(server, client);
  return { open_panel };
}

/**
 * Gives the box already marked to a kind of zone: the book is used on it and the kind picked in the picker that
 * asks what it becomes, and the new zone's card opens; it is closed again here.
 */
export async function createMarkedZone(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
  kind: ZoneKind,
  stage: Stage = GROUND,
) {
  const key = zoneKindKey(kind);
  const registry = await colonyRegistry(server);
  const before = await zoneCount(server, registry);
  const evidence: any = { kind, kind_key: key, zones_before: before, player: playerName, stand: standBlock,
    stage: stage.name("") };
  evidence.choose = await chooseBookTool(server, client, key);
  evidence.use = await useBook(client);
  await expect.poll(async () => {
    await tick.sprint(server, 3);
    return zoneCount(server, registry);
  }, { timeout: 8_000, message: `after creating ${kind} the server must register a new zone` }).toBe(before + 1);
  evidence.zones_after = before + 1;
  evidence.card = await waitForElement(client, { id: CARD_WINDOW }, { timeoutMs: 8_000 });
  evidence.close = await screen.dismiss(client);
  await tick.sprint(server, 10);
  return evidence;
}

export type ZoneEdit =
  | { key: string; count: number }
  | { key: string; option: string; shot?: string }
  | { key: string; toggle: true }
  | { key: string; item: string; query?: string };

export async function applyZoneSetting(
  server: MinecraftServer, client: MinecraftClient, edit: ZoneEdit,
) {
  const token = zoneSettingToken(edit);
  if ("count" in edit) {
    return { key: edit.key, ...await typeCell(server, client, token, String(edit.count)) };
  }
  if ("option" in edit) {
    // A short choice lays its options out as chips; a long one opens them from a button.
    const chip = `${token}.${edit.option}`;
    if ((await panelElements(client)).some((element) => element.id === chip)) {
      const press = await clickElement(client, { id: chip });
      await settlePanel(server, client);
      return { key: edit.key, chip, press };
    }
    return { key: edit.key, ...await pickCell(server, client, `${token}.open`, edit.option, edit.shot) };
  }
  if ("item" in edit) {
    // The setting's button opens the item picker; the item is searched for, picked, and confirmed there.
    const before = cellTextIn(await panelElements(client), token);
    const open = await clickElement(client, { id: token });
    const picked = await chooseInSelector(client, edit.item, edit.query ?? edit.item, open);
    await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent", timeoutMs: 8_000 });
    await settlePanel(server, client);
    return { key: edit.key, before, after: cellTextIn(await panelElements(client), token), ...picked };
  }
  const evidence = await toggleCell(server, client, token);
  return { key: edit.key, ...evidence };
}

const HOLD_ZONE_ATTEMPTS = 3;

function middleCell(box: { min: Vec; max: Vec }): Vec {
  return {
    x: Math.floor((box.min.x + box.max.x) / 2),
    y: Math.min(box.min.y, box.max.y),
    z: Math.floor((box.min.z + box.max.z) / 2),
  };
}

export async function holdZoneAndOpen(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  box: { min: Vec; max: Vec },
  stage: Stage = GROUND,
) {
  const cell = middleCell(box);
  const evidence: any = { box, cell, right_click: [] };
  let missed: unknown;
  for (let attempt = 0; attempt < HOLD_ZONE_ATTEMPTS; attempt++) {
    await safeAction(() => screen.dismiss(client));
    await safeAction(() => client.settle({ requireNoScreen: true, timeoutMs: 6_000 }));
    // The book opens whichever zone the look passes through, whatever block the crosshair lands on, so a crop
    // standing on the middle cell must not stop the click.
    evidence.right_click.push(await safeAction(async () => ({
      aim: await player.aim(server, cell, {
        player: playerName, face: "up", from: stage.stand({ x: cell.x + 0.5, y: cell.y + 1, z: cell.z + 0.5 }),
      }),
      click: await player.click(client, { button: "right" }),
    })));
    try {
      evidence.card = await waitForElement(client, { id: CARD_WINDOW }, { timeoutMs: 8_000 });
      await settlePanel(server, client);
      evidence.lines = await waitForElement(client, { id: CARD_LINES }, { timeoutMs: 4_000 });
      return evidence;
    } catch (error) {
      missed = error;
    }
  }
  // What the client held when no click opened the zone: the zones it knows of, and where the player looked from.
  const seen = await softly(() => client.run(CLIENT_ZONES, undefined));
  throw new Error(`no right-click on ${JSON.stringify(cell)} opened a zone's card; the client knew ${JSON.stringify(seen)}: ${missed}`);
}

const CLIENT_ZONES = defineTask<undefined, Record<string, any>>({
  name: "folkways.shared.client-zones",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.client.ColonyHighlightState"],
    body: `
var player=net.minecraft.client.Minecraft.getInstance().player;
var zones=new ArrayList<Object>();
for(var zone:ColonyHighlightState.zones())zones.add(zone.min().toShortString()+".."+zone.max().toShortString());
return Map.of("zones",zones,"eye",String.valueOf(player==null?null:player.getEyePosition()),
  "look",String.valueOf(player==null?null:player.getLookAngle()));
`,
  }),
});

export async function configureZone(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
  box: { min: Vec; max: Vec },
  edits: ZoneEdit[],
  stage: Stage = GROUND,
) {
  const evidence: any = { box, edits: [] };
  try {
    Object.assign(evidence, await holdZoneAndOpen(server, client, playerName, box, stage));
    for (const edit of edits) {
      // A choice's line goes by the choice's own token; its chips or picker button sit in it.
      await waitForPresent(server, client, zoneSettingToken(edit));
      evidence.edits.push(await applyZoneSetting(server, client, edit));
      await tick.sprint(server, 3);
    }
  } finally {
    evidence.release = await releaseHeldZone(client);
    const stand = stage.stand(standBlock);
    await tryCommand(server, `tp ${playerName} ${stand.x + 0.5} ${stand.y} ${stand.z + 1.5}`);
    await tick.sprint(server, 5);
  }
  return evidence;
}

export async function releaseHeldZone(client: MinecraftClient) {
  const closed = await safeAction(() => screen.dismiss(client));
  await safeAction(() => client.settle({ requireNoScreen: true, timeoutMs: 6_000 }));
  return closed;
}

const COLONY_REGISTRY = "io.github.izakyl.folkways.core.colony.internal.ColonyRegistry";

const COLONY_FRONT = "io.github.izakyl.folkways.core.front.engine.colony.ColonyFront";
const COLONY_INTERFACE = "io.github.izakyl.folkways.core.colony.api.Colony";

export async function colonyRegistry(server: MinecraftServer): Promise<string> {
  const players = await world.players(server);
  const uuid = players[0]?.uuid;
  if (typeof uuid !== "string" || uuid.length === 0) {
    throw new Error(`players() gave no player uuid: ${JSON.stringify(players).slice(0, 200)}`);
  }
  const host = await reflect.invoke(server, {
    target: { kind: "entity", uuid },
    method: "getServer",
    returnHandle: true,
    limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
  });
  if (typeof host?.handle !== "string") {
    throw new Error(`no MinecraftServer handle: ${JSON.stringify(host).slice(0, 300)}`);
  }
  const registry = await reflect.invoke(server, {
    target: { kind: "static", className: COLONY_REGISTRY },
    method: "get",
    args: [{ $handle: host.handle }],
    argTypes: ["net.minecraft.server.MinecraftServer"],
    returnHandle: true,
    limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
  });
  if (typeof registry?.handle !== "string") {
    throw new Error(`no ColonyRegistry handle: ${JSON.stringify(registry).slice(0, 300)}`);
  }
  return registry.handle;
}

export async function colonyFront(server: MinecraftServer, registry: string): Promise<string> {
  const colony = await reflect.invoke(server, {
    target: { kind: "handle", handle: registry },
    path: "colonies()[0]",
    method: "works",
    returnHandle: true,
    limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
  });
  if (typeof colony?.handle !== "string") {
    throw new Error(`no Colony handle (ColonyData.works()): ${JSON.stringify(colony).slice(0, 300)}`);
  }
  const front = await reflect.invoke(server, {
    target: { kind: "static", className: COLONY_FRONT },
    method: "of",
    args: [{ $handle: colony.handle }],
    argTypes: [COLONY_INTERFACE],
    returnHandle: true,
    limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
  });
  if (typeof front?.handle !== "string") {
    throw new Error(`no ColonyFront handle: ${JSON.stringify(front).slice(0, 300)}`);
  }
  return front.handle;
}

// The agent's value walker returns a collection as an array, closed by { $more: remaining } when cut short.
function listLength(tree: unknown): number | undefined {
  if (!Array.isArray(tree)) return undefined;
  const last = tree[tree.length - 1];
  const more = last !== null && typeof last === "object" && "$more" in last ? last.$more : undefined;
  if (more === undefined) return tree.length;
  return typeof more === "number" ? tree.length - 1 + more : undefined;
}

export async function zoneCount(server: MinecraftServer, registry: string): Promise<number> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    method: "zones",
    limits: { maxDepth: 1, maxItems: 64, maxNodes: 256 },
  });
  const tree: any = result.returned;
  const length = listLength(tree);
  if (length === undefined) {
    throw new Error(`ColonyFront.zones() did not return a list: ${JSON.stringify(tree).slice(0, 300)}`);
  }
  return length;
}

export async function memberCellCount(server: MinecraftServer, registry: string): Promise<number> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: registry },
    path: "colonies()[0].claims()",
    method: "blocks",
    limits: { maxDepth: 1, maxItems: 1, maxNodes: 256 },
  });
  const tree: any = result.returned;
  const length = listLength(tree);
  if (length === undefined) {
    throw new Error(`Claims.blocks() did not return a set: ${JSON.stringify(tree).slice(0, 300)}`);
  }
  return length;
}

export async function zoneCellsAt(server: MinecraftServer, registry: string, index = 0): Promise<number> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    path: `zones()[${index}]`,
    method: "cells",
    limits: { maxDepth: 1, maxItems: 8, maxNodes: 64 },
  });
  const tree: any = result.returned;
  const length = listLength(tree);
  if (length === undefined) {
    throw new Error(`ColonyZone.cells() did not return a set: ${JSON.stringify(tree).slice(0, 300)}`);
  }
  return length;
}

export async function zoneKindAt(server: MinecraftServer, registry: string, index = 0): Promise<string> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    path: `zones()[${index}].delegation()`,
    method: "toString",
    returnType: "java.lang.String",
  });
  return reflect.string(result);
}

export async function zoneCountSettingAt(
  server: MinecraftServer, registry: string, key: string, index = 0,
): Promise<number> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    path: `zones()[${index}].settings()`,
    method: "count",
    args: [key],
    argTypes: ["java.lang.String"],
    returnType: "int",
  });
  return reflect.number(result);
}

export async function zoneFlagSettingAt(
  server: MinecraftServer, registry: string, key: string, index = 0,
): Promise<boolean> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    path: `zones()[${index}].settings()`,
    method: "flag",
    args: [key],
    argTypes: ["java.lang.String"],
    returnType: "boolean",
  });
  return reflect.boolean(result);
}

export async function zoneChoiceSettingAt(
  server: MinecraftServer, registry: string, key: string, index = 0,
): Promise<string> {
  const value = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    path: `zones()[${index}].settings()`,
    method: "choice",
    args: [key],
    argTypes: ["java.lang.String"],
    returnHandle: true,
    limits: { maxDepth: 1, maxFields: 4, maxNodes: 16 },
  });
  if (typeof value?.handle !== "string") {
    throw new Error(`settings().choice(${key}) did not return an object: ${JSON.stringify(value).slice(0, 300)}`);
  }
  const text = await reflect.invoke(server, {
    target: { kind: "handle", handle: value.handle },
    method: "toString",
    returnType: "java.lang.String",
  });
  return reflect.string(text);
}
