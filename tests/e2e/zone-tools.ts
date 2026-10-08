import { expect } from "@playwright/test";
import { input, player, reflect, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement, type Element, panelElements } from "../ldlib2";
import { openColonyPanelViaBook, setBookGesture, type Vec } from "./colony-founding";
import { cellTextIn, pickCell, settlePanel, toggleCell, typeCell } from "./site-panel";
import { drainActionBar, readActionBar } from "./zone-receipt";
import { frames, safeAction, tryCommand, screenName, waitForElement, softly } from "../shared/bw-helpers";

export { setBookGesture };
export type { BookGesture } from "./colony-founding";

const KEY_LEFT_CONTROL = 341;

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

export async function aimAndLeftClick(server: MinecraftServer, client: MinecraftClient, playerName: string, standAt: Vec, block: Vec) {
  const eye = { x: standAt.x, y: standAt.y + 1.62, z: standAt.z };
  const target = { x: block.x + 0.5, y: block.y + 1, z: block.z + 0.5 };
  const camera = cameraLookingAt(eye, target);
  const teleport = await tryCommand(
    server,
    `tp ${playerName} ${standAt.x} ${standAt.y} ${standAt.z} ${camera.yaw} ${camera.pitch}`,
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
  await safeAction(() => frames(client, 4));
  await safeAction(() =>
    input.key(client, { keyCode: KEY_LEFT_CONTROL, action: "press" }),
  );
  const scroll = await safeAction(() =>
    input.scroll(client, { yOffset: notches }),
  );
  await safeAction(() =>
    input.key(client, { keyCode: KEY_LEFT_CONTROL, action: "release" }),
  );
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
) {
  await drainActionBar(client);
  const corner1 = await aimAndLeftClick(server, client, playerName, standAt, min);
  await tick.sprint(server, 3);
  const corner2 = await aimAndLeftClick(server, client, playerName, standAt, max);
  await tick.sprint(server, 5);
  return { corner_1: corner1, corner_2: corner2, marked: await readActionBar(client) };
}

export const ZONE_KIND_ID = {
  farm: "folkways:farm",
  pasture: "folkways:pasture",
  fish: "folkways:fish",
} as const;

export type ZoneKind = keyof typeof ZONE_KIND_ID;

function sanitize(id: string): string {
  return id.replace(/[:/]/g, ".");
}

export function zoneKindToken(kind: ZoneKind): string {
  return `folkways.zoneconfig.kind.${sanitize(ZONE_KIND_ID[kind])}`;
}

export function zoneSettingToken(key: string): string {
  return `folkways.zoneconfig.value.${key}`;
}

export async function openBookPage(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
) {
  const open_panel = await openColonyPanelViaBook(server, client, playerName, standBlock);
  await settlePanel(server, client);
  return { open_panel };
}

export async function createMarkedZone(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
  kind: ZoneKind,
) {
  const token = zoneKindToken(kind);
  const registry = await colonyRegistry(server);
  const before = await zoneCount(server, registry);
  const evidence: any = { kind, kind_token: token, zones_before: before };
  Object.assign(evidence, await openBookPage(server, client, playerName, standBlock));
  await waitForElement(client, { id: token }, { timeoutMs: 8_000 });
  evidence.click_kind = await clickElement(client, { id: token });
  await settlePanel(server, client);
  await waitForElement(client, { id: "folkways.zoneconfig.confirm" }, { timeoutMs: 8_000 });
  evidence.click_confirm = await clickElement(client, { id: "folkways.zoneconfig.confirm" });
  await expect.poll(async () => {
    await tick.sprint(server, 3);
    return zoneCount(server, registry);
  }, { timeout: 8_000, message: `after creating ${kind} the server must register a new zone` }).toBe(before + 1);
  evidence.zones_after = before + 1;
  evidence.close = await screen.dismiss(client);
  await tick.sprint(server, 10);
  return evidence;
}

export type ZoneEdit =
  | { key: string; count: number }
  | { key: string; option: string; shot?: string }
  | { key: string; toggle: true };

export async function applyZoneSetting(
  server: MinecraftServer, client: MinecraftClient, edit: ZoneEdit,
) {
  const token = zoneSettingToken(edit.key);
  if ("count" in edit) {
    return { key: edit.key, ...await typeCell(server, client, token, String(edit.count)) };
  }
  if ("option" in edit) {
    return { key: edit.key, ...await pickCell(server, client, token, edit.option, edit.shot) };
  }
  const before = cellTextIn(await panelElements(client), token);
  const evidence = await toggleCell(server, client, token);
  return { key: edit.key, before, ...evidence };
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
        player: playerName, face: "up", from: { x: cell.x + 0.5, y: cell.y + 1, z: cell.z + 0.5 },
      }),
      click: await player.click(client, { button: "right" }),
    })));
    await softly(() => screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 8_000 }));
    await settlePanel(server, client);
    try {
      evidence.settings_row = await waitForElement(
        client, { idPattern: "folkways.zoneconfig.setting.*" }, { timeoutMs: 4_000 });
      return evidence;
    } catch (error) {
      missed = error;
    }
  }
  throw missed;
}

export async function configureZone(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
  box: { min: Vec; max: Vec },
  edits: ZoneEdit[],
) {
  const evidence: any = { box, edits: [] };
  try {
    Object.assign(evidence, await holdZoneAndOpen(server, client, playerName, box));
    for (const edit of edits) {
      await waitForElement(client, { id: zoneSettingToken(edit.key) }, { timeoutMs: 8_000 });
      evidence.edits.push(await applyZoneSetting(server, client, edit));
      await tick.sprint(server, 3);
    }
  } finally {
    evidence.release = await releaseHeldZone(client);
    await tryCommand(server, `tp ${playerName} ${standBlock.x + 0.5} ${standBlock.y} ${standBlock.z + 1.5}`);
    await tick.sprint(server, 5);
  }
  return evidence;
}

export async function releaseHeldZone(client: MinecraftClient) {
  const closed = await safeAction(() => screen.dismiss(client));
  await safeAction(() => client.settle({ requireNoScreen: true, timeoutMs: 6_000 }));
  return closed;
}

const COLONY_REGISTRY = "io.github.izakyl.folkways.core.engine.colony.ColonyRegistry";

const COLONY_FRONT = "io.github.izakyl.folkways.front.engine.colony.ColonyFront";
const COLONY_INTERFACE = "io.github.izakyl.folkways.core.api.colony.Colony";

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
    path: "colonies()[0].holdings()",
    method: "blocks",
    limits: { maxDepth: 1, maxItems: 1, maxNodes: 256 },
  });
  const tree: any = result.returned;
  const length = listLength(tree);
  if (length === undefined) {
    throw new Error(`Holdings.blocks() did not return a set: ${JSON.stringify(tree).slice(0, 300)}`);
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
