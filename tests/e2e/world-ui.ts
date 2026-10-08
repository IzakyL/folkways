import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { isMinecraftError, player, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { errorSummary } from "@izakyl/blockwright-client";
import { type Vec } from "./colony-founding";
import { commandPos, distance, tryCommand } from "../shared/bw-helpers";

export { tryCommand, errorSummary };

/** A command result (world.command's, or tryCommand's `status: "ok"` answer) that ran and succeeded. */
export function commandSucceeded(result: unknown): result is world.CommandResult {
  const outcome = result as { status?: string; success?: unknown } | null | undefined;
  return outcome?.success === true && (outcome.status === undefined || outcome.status === "ok");
}

/** Runs a console command; throws MinecraftCommandError when it did not succeed. */
export function mustCommand(server: MinecraftServer, command: string): Promise<world.CommandResult> {
  return world.command(server, command);
}

function probe(server: MinecraftServer, condition: string): Promise<boolean> {
  return world.probe(server, condition);
}

const REPO_ROOT = process.cwd();

const RESIDENT_TYPE = "folkways:resident";

const PACK_NBT_PATH = '"neoforge:attachments"."folkways:body_state".Pack';
const PACK_QUERY_PATH = "neoforge:attachments.folkways:body_state.Pack";

export async function packHasItem(server: MinecraftServer, itemId: string) {
  return probe(
    server,
    `as @e[type=${RESIDENT_TYPE}] if data entity @s ${PACK_NBT_PATH}.Items[{Stack:{id:"${itemId}"}}]`,
  );
}

export async function blockHasItem(server: MinecraftServer, at: Vec, itemId: string) {
  return probe(server, `if items block ${commandPos(at)} container.* ${itemId}`);
}

export async function colonyHasItem(
  server: MinecraftServer, itemId: string, stores: Vec[] = [],
) {
  if (await packHasItem(server, itemId)) {
    return true;
  }
  for (const store of stores) {
    if (await blockHasItem(server, store, itemId)) {
      return true;
    }
  }
  return false;
}

export async function countInResidentPacks(server: MinecraftServer) {
  const listed: world.Entities | { entities: world.EntityJson[] } = await world
    .entities(server, { dimension: "minecraft:overworld", types: [RESIDENT_TYPE], nbtPath: PACK_QUERY_PATH, limit: 1000 })
    .catch(() => ({ entities: [] }));
  const residents = listed.entities ?? [];
  const totals: Record<string, number> = {};
  const perResident: Array<{ uuid: string; counts: Record<string, number> }> = [];
  for (const resident of residents) {
    const counts: Record<string, number> = {};
    for (const entry of (resident.nbt as any)?.Items ?? []) {
      const id = entry?.Stack?.id;
      if (typeof id !== "string") continue;
      const count = typeof entry.Stack.count === "number" ? entry.Stack.count : 1;
      counts[id] = (counts[id] ?? 0) + count;
      totals[id] = (totals[id] ?? 0) + count;
    }
    perResident.push({ uuid: resident.uuid, counts });
  }
  return { resident_count: residents.length, totals, per_resident: perResident };
}

export async function readResidentPack(server: MinecraftServer, uuid: string) {
  const held: any = await world
    .entity(server, { uuid }, { nbtPath: PACK_QUERY_PATH })
    .catch((error: unknown) => ({ $probe_error: errorSummary(error) }));
  const items = held?.nbt?.Items;
  if (!Array.isArray(items)) {
    return { read: false, carried: [] as string[], counts: {} as Record<string, number>,
      error: held?.$probe_error ?? null };
  }
  const counts: Record<string, number> = {};
  for (const entry of items) {
    const id = entry?.Stack?.id;
    if (typeof id !== "string") continue;
    counts[id] = (counts[id] ?? 0) + (typeof entry.Stack.count === "number" ? entry.Stack.count : 1);
  }
  return { read: true, carried: Object.keys(counts), counts, error: null };
}

export async function maxInAnySinglePack(server: MinecraftServer, itemId: string) {
  const packs = await countInResidentPacks(server);
  const max = packs.per_resident.reduce((best, entry) => Math.max(best, entry.counts[itemId] ?? 0), 0);
  return { max, resident_count: packs.resident_count, per_resident: packs.per_resident };
}

// A resident in the line of sight puts the crosshair on it, and clickBlock refuses to click (the press would
// hit the resident, not the block). One standing where the player is put blocks every look from there, so
// a blocked click moves to the next stance; once every stance was blocked, the world runs a moment first.
const CLICK_ATTEMPTS = 10;

// Feet offsets from the block's corner, looking down at its top face: south first (what every caller got
// before), then on the block itself, then the other three sides.
const CLICK_STANCES = [
  { x: 0.5, z: 1.5 },
  { x: 0.5, z: 0.5 },
  { x: 1.5, z: 0.5 },
  { x: -0.5, z: 0.5 },
  { x: 0.5, z: -0.5 },
];

export async function aimAndWorldLeftClick(server: MinecraftServer, client: MinecraftClient, playerName: string, block: any) {
  for (let attempt = 1; ; attempt++) {
    const stance = CLICK_STANCES[(attempt - 1) % CLICK_STANCES.length];
    try {
      return await player.clickBlock({ server, client }, block, {
        player: playerName,
        from: { x: block.x + stance.x, y: block.y + 1, z: block.z + stance.z },
      });
    } catch (error) {
      const blockedByEntity = isMinecraftError(error, "not-found") && /crosshair is not on the block .*\{type=entity\b/.test(error.message);
      if (!blockedByEntity || attempt >= CLICK_ATTEMPTS) throw error;
      if (attempt % CLICK_STANCES.length === 0) await tick.sprint(server, 20);
    }
  }
}

export type ResidentMovementSample = {
  order?: number;
  residents?: Array<{ uuid?: string; position?: { x: number; y: number; z: number } }>;
};

export function summarizeResidentMovement(samples: ResidentMovementSample[]) {
  const threshold = 0.25;
  const first = samples.find((sample) => (sample.residents?.length ?? 0) > 0);
  const startByUuid = new Map<string, any>();
  for (const resident of first?.residents ?? []) {
    if (resident.uuid && resident.position) startByUuid.set(resident.uuid, resident.position);
  }
  const perResident = [];
  for (const [uuid, start] of startByUuid) {
    let maxDistance = 0;
    let maxOrder = first?.order ?? 0;
    for (const sample of samples) {
      const current = sample.residents?.find((resident: any) => resident.uuid === uuid);
      if (!current?.position) continue;
      const travelled = distance(start, current.position);
      if (travelled > maxDistance) {
        maxDistance = travelled;
        maxOrder = sample.order ?? maxOrder;
      }
    }
    perResident.push({ uuid, start, max_distance: round(maxDistance, 3), max_order: maxOrder });
  }
  const movedResidents = perResident.filter((entry) => entry.max_distance >= threshold);
  return {
    threshold,
    moved: movedResidents.length > 0,
    moved_resident_count: movedResidents.length,
    max_distance: round(Math.max(0, ...perResident.map((entry) => entry.max_distance)), 3),
    per_resident: perResident,
  };
}

function round(value: number, digits: number) {
  const scale = 10 ** digits;
  return Math.round(value * scale) / scale;
}

export async function prepareSafeWalkway(
  server: MinecraftServer,
  origin: { x: number; y: number; z: number },
  ...points: Array<{ x: number; y: number; z: number }>
) {
  const minX = Math.min(origin.x - 7, ...points.map((p) => p.x - 3));
  const maxX = Math.max(origin.x + 7, ...points.map((p) => p.x + 3));
  const minZ = Math.min(origin.z - 4, ...points.map((p) => p.z - 3));
  const maxZ = Math.max(origin.z + 14, ...points.map((p) => p.z + 3));
  const floorY = origin.y - 1;
  await tryCommand(server, `fill ${minX} ${origin.y} ${minZ} ${maxX} ${origin.y + 6} ${maxZ} minecraft:air`);
  await tryCommand(server, `fill ${minX} ${floorY} ${minZ} ${maxX} ${floorY} ${maxZ} minecraft:smooth_stone`);
}
