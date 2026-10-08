import { errorSummary } from "@izakyl/blockwright-client";
import { screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { type Vec } from "./colony-founding";
import { FIRST_CORNER, ZONE_MARKED } from "./zone-receipt";
import {
  aimAndLeftClick,
  colonyRegistry,
  configureZone,
  createMarkedZone,
  markCorner,
  setBookGesture,
  zoneChoiceSettingAt,
  zoneCount,
  zoneCountSettingAt,
  zoneKindAt,
  ZONE_KIND_ID,
} from "./zone-tools";
import { commandPos, tryCommand } from "../shared/bw-helpers";

/** Whether an `execute` condition holds now (throws when the game cannot evaluate it). */
export function probe(server: MinecraftServer, condition: string): Promise<boolean> {
  return world.probe(server, condition);
}

async function mustCommand(server: MinecraftServer, command: string) {
  return world.command(server, command);
}
export { blockHasItem } from "./world-ui";

export const PASTURE_ANIMALS = { cow: 0, sheep: 0, pig: 0, chicken: 0 } as const;

export type Pen = {
  min: Vec;
  max: Vec;
  standInside: Vec;
  fences: Vec[];
  gate: Vec;
};

export function penLayout(origin: Vec, zOffset = 8, size = 2): Pen {
  const ground = origin.y - 1;
  const min: Vec = { x: origin.x, y: ground, z: origin.z + zOffset };
  const max: Vec = { x: origin.x + size - 1, y: ground, z: origin.z + zOffset + size - 1 };
  const gate: Vec = { x: min.x - 1, y: origin.y, z: min.z };

  const fences: Vec[] = [];
  for (let x = min.x - 1; x <= max.x + 1; x++) {
    for (let z = min.z - 1; z <= max.z + 1; z++) {
      const inside = x >= min.x && x <= max.x && z >= min.z && z <= max.z;
      if (inside) continue;
      if (x === gate.x && z === gate.z) continue;
      fences.push({ x, y: origin.y, z });
    }
  }
  const standInside: Vec = { x: min.x + size / 2, y: origin.y, z: min.z + size / 2 };
  return { min, max, standInside, fences, gate };
}

export function penCenter(pen: Pen, y: number): Vec {
  return { x: (pen.min.x + pen.max.x + 1) / 2, y, z: (pen.min.z + pen.max.z + 1) / 2 };
}

export async function buildPen(server: MinecraftServer, pen: Pen) {
  for (const fence of pen.fences) {
    await mustCommand(server, `setblock ${commandPos(fence)} minecraft:oak_fence`);
  }
  await mustCommand(server, `setblock ${commandPos(pen.gate)} minecraft:oak_fence_gate[facing=east,open=false]`);
  await tick.sprint(server, 3);
}

export async function createPastureZoneViaPanel(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
  pen: Pen,
  options: { animal: keyof typeof PASTURE_ANIMALS; target: number; index?: number },
) {
  const animalId = `minecraft:${options.animal}`;
  const index = options.index ?? 0;
  const evidence: any = { pen_min: pen.min, pen_max: pen.max, animal: animalId, target: options.target };
  try {
    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");

    evidence.corner_1 = await markCorner(server, client, pen.min, FIRST_CORNER,
      () => aimAndLeftClick(server, client, playerName, pen.standInside, pen.min));
    evidence.corner_2 = await markCorner(server, client, pen.max, ZONE_MARKED,
      () => aimAndLeftClick(server, client, playerName, pen.standInside, pen.max));
    await tick.sprint(server, 5);

    evidence.create = await createMarkedZone(server, client, playerName, standBlock, "pasture");
    evidence.configure = await configureZone(server, client, playerName, standBlock,
      { min: pen.min, max: pen.max },
      [
        { key: "animal", option: animalId },
        { key: "target", count: options.target },
      ]);

    const registry = await colonyRegistry(server);
    evidence.zone_count = await zoneCount(server, registry);
    evidence.zone_kind = await zoneKindAt(server, registry, index);
    evidence.zone_animal = await zoneChoiceSettingAt(server, registry, "animal", index);
    evidence.zone_target = await zoneCountSettingAt(server, registry, "target", index);
    evidence.created =
      evidence.zone_kind === ZONE_KIND_ID.pasture
      && evidence.zone_animal === animalId
      && evidence.zone_target === options.target;
  } catch (error) {
    evidence.created = false;
    evidence.error = errorSummary(error);
  } finally {
    await screen.dismiss(client);
  }
  return evidence;
}

export function pastureZoneFailure(zone: any, animal: string, target: number) {
  if (zone.error) {
    return `creating the pasture threw: ${JSON.stringify(zone.error)}`;
  }
  return `the colony's zone does not match: kind=${zone.zone_kind} (want ${ZONE_KIND_ID.pasture}), `
    + `animal=${zone.zone_animal} (want minecraft:${animal}), target=${zone.zone_target} (want ${target}); `
    + `${zone.zone_count} zones in total right now`;
}


export async function countAnimalsInPen(server: MinecraftServer, pen: Pen, typePattern: RegExp) {
  const pad = 1.0;
  const result = await world.entities(server, { dimension: "minecraft:overworld", limit: 1000 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  return result.entities.filter((entity) => {
    if (!typePattern.test(String(entity.type ?? ""))) return false;
    const p = entity.position;
    if (!p) return false;
    return (
      p.x >= pen.min.x - pad &&
      p.x < pen.max.x + 1 + pad &&
      p.z >= pen.min.z - pad &&
      p.z < pen.max.z + 1 + pad &&
      p.y >= pen.min.y &&
      p.y < pen.max.y + 5
    );
  }).length;
}

export async function purgeLooseItem(server: MinecraftServer, playerName: string, itemId: string) {
  const cleared = await tryCommand(server, `clear ${playerName} ${itemId}`);
  const killed = await tryCommand(server, `kill @e[type=minecraft:item,nbt={Item:{id:"${itemId}"}}]`);
  await tick.sprint(server, 5);
  return { cleared: cleared.status, killed: killed.status };
}

export async function looseItemExists(server: MinecraftServer, itemId: string) {
  return probe(server, `if entity @e[type=minecraft:item,nbt={Item:{id:"${itemId}"}}]`);
}

const PERKS_IN = (inner: string) =>
  `{"neoforge:attachments":{"folkways:body_state":{Perks:${inner}}}}`;

export async function grantPerkToAllResidents(server: MinecraftServer, perksNbt: string) {
  return mustCommand(
    server,
    `execute as @e[type=folkways:resident] run data merge entity @s ${PERKS_IN(perksNbt)}`,
  );
}

export async function anyResidentEarnedPerkXp(server: MinecraftServer) {
  return probe(
    server,
    `as @e[type=folkways:resident] unless entity @s[nbt=${PERKS_IN("{global:{xp:0}}")}]`,
  );
}
