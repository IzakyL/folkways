import { expect } from "@playwright/test";
import { reflect, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { entityTypeMatches, type Vec } from "../e2e/colony-founding";
import { readDoing } from "../e2e/resident-probe";
import type { VisualRun } from "./visual-kit";
import { occluderIn } from "./occlusion";

export type ResidentState = {
  uuid: string;
  position: Vec;
  doing: string;
  look: string;
  raw?: unknown;
};

const RESIDENT_TYPE = "folkways:resident";

export async function residentStates(server: MinecraftServer): Promise<ResidentState[]> {
  const listed = await world
    .entities(server, { dimension: "minecraft:overworld", types: [RESIDENT_TYPE], limit: 200 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  const result: ResidentState[] = [];
  for (const entity of listed.entities ?? []) {
    if (!entity?.uuid || !entityTypeMatches(entity.type, RESIDENT_TYPE)) continue;
    const state = await stateOf(server, entity.uuid);
    if (!state) continue;
    result.push({ ...state, raw: result.length === 0 ? state.raw : undefined });
  }
  return result;
}

const MAX_SHUTTER_ATTEMPTS = 8;

export function lookPortraits(run: VisualRun, server: MinecraftServer, client: MinecraftClient, options: { prefix: string; want?: number }) {
  const want = options.want ?? 1;
  const taken = new Map<string, { doing: string; look: string }>();
  const shutter: Array<{ uuid: string; doing: string; attempt: number; verdict: string; detail?: string }> = [];
  const attempts = new Map<string, number>();
  let raw: unknown;

  const sample = async () => {
    if (taken.size >= want) return;
    const residents = await residentStates(server);
    raw ??= residents[0]?.raw;
    for (const resident of residents) {
      if (taken.size >= want) return;
      if (taken.has(resident.uuid)) continue;
      if (await shoot(resident)) {
        taken.set(resident.uuid, { doing: resident.doing, look: resident.look });
      }
    }
  };

  const shoot = async (target: ResidentState) => {
    const attempt = (attempts.get(target.uuid) ?? 0) + 1;
    const log = (verdict: string, detail?: string) =>
      shutter.push({ uuid: target.uuid.slice(0, 8), doing: target.doing, attempt, verdict, detail });
    if (attempt > MAX_SHUTTER_ATTEMPTS) {
      return false;
    }

    const now = await stateOf(server, target.uuid);
    if (!now) {
      log("gone-before-aim");
      return false;
    }
    const camera = portraitCamera(now.position);
    const crowd = await residentPositions(server);
    const blockedBefore = occluderIn(crowd, camera.position, headOf(now.position), target.uuid);
    if (blockedBefore) {
      log("occluded-before-aim", blockedBefore);
      return false;
    }

    attempts.set(target.uuid, attempt);
    let detail: string | undefined;
    const gates = await run.shot(client, `${options.prefix}-${target.uuid.slice(0, 8)}`, {
      subject:
        `Resident close-up: ${target.uuid.slice(0, 8)} drew ${now.look || "(unreadable)"}. ` +
        `Compare against its PNG in assets: sleeve, trouser and hat overlays should be there, and slim entries should have arms one pixel thinner`,
      worldState: {
        uuid: target.uuid,
        look: now.look,
        doing: now.doing,
        position: now.position,
        attempt,
        verifiedAtShutter: true,
        crowd: crowd.map((other) => ({
          uuid: other.uuid.slice(0, 8),
          position: other.position,
          fromCamera: round2(distance(camera.position, other.position)),
        })),
      },
      camera,
      verify: async () => {
        const after = await stateOf(server, target.uuid);
        if (!after) {
          detail = "gone";
          return false;
        }
        const blocked = occluderIn(await residentPositions(server), camera.position, headOf(after.position), target.uuid);
        if (blocked) {
          detail = `occluded-by=${blocked}`;
          return false;
        }
        return true;
      },
    });
    log(gates ? "committed" : "discarded", detail);
    return gates !== null;
  };

  const crowdShot = async () => {
    const residents = await residentStates(server);
    if (residents.length === 0) return;
    const centre = residents[0].position;
    const eye = { x: centre.x - 6, y: centre.y + 4, z: centre.z - 6 };
    await run.shot(client, `${options.prefix}-crowd`, {
      subject: "Resident looks: a group standing together should have drawn different outfits",
      worldState: {
        looks: residents.map((c) => ({ uuid: c.uuid.slice(0, 8), look: c.look, doing: c.doing })),
        distinct: new Set(residents.map((c) => c.look)).size,
      },
      camera: { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, centre), fov: 70 },
    });
  };

  const assertPortraits = () => {
    expect(
      taken.size,
      `Did not get ${want} unobstructed resident close-ups. Taken=${JSON.stringify([...taken])}; ` +
        `shutter log=${JSON.stringify(shutter)}. All occluded/discarded means the crowd in front of the camera never cleared; ` +
        "do not hand in an obstructed shot.",
    ).toBeGreaterThanOrEqual(want);

    const blank = [...taken].filter(([, shot]) => shot.look === "").map(([uuid]) => uuid.slice(0, 8));
    expect(blank, `These residents read back an empty look: ${blank.join(", ")}. The pin did not land, or reflection decoding is broken.`)
      .toEqual([]);
  };

  return {
    sample,
    crowdShot,
    assertPortraits,
    summary: () => ({
      taken: [...taken],
      distinct: new Set([...taken.values()].map((shot) => shot.look)).size,
      shutter,
      raw,
    }),
  };
}

function portraitCamera(position: Vec | undefined) {
  const at = position ?? { x: 0, y: 0, z: 0 };
  const eye = { x: at.x - 3.5, y: at.y + 2.5, z: at.z - 3.5 };
  return { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, headOf(at)), fov: 70 };
}

function headOf(position: Vec): Vec {
  return { x: position.x, y: position.y + 1.2, z: position.z };
}

async function residentPositions(server: MinecraftServer): Promise<Array<{ uuid: string; position: Vec }>> {
  const listed = await world
    .entities(server, { dimension: "minecraft:overworld", types: [RESIDENT_TYPE], limit: 200 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  return (listed.entities ?? [])
    .filter((entity: any) => entity?.uuid && entity?.position)
    .map((entity: any) => ({ uuid: entity.uuid as string, position: entity.position as Vec }));
}

function distance(a: Vec, b: Vec): number {
  return Math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2);
}

const round2 = (value: number) => Math.round(value * 100) / 100;

async function stateOf(server: MinecraftServer, uuid: string): Promise<ResidentState | null> {
  const entity = await world.entity(server, { uuid }).catch(() => null);
  const position = entity?.entity?.position;
  if (!position) {
    return null;
  }
  let doing: string | null = null;
  let doingError: string | undefined;
  try {
    doing = await readDoing(server, uuid);
  } catch (error) {
    doingError = String(error).slice(0, 200);
  }
  const look = await readOptional(server, uuid, "lookId");
  return {
    uuid,
    position,
    doing: doingError ? "(probe broken)" : (doing ?? "idle"),
    look: lookIdIn(look.value),
    raw: { doing, doing_error: doingError, look: look.value },
  };
}

function lookIdIn(tree: unknown): string {
  const ids = [...JSON.stringify(tree).matchAll(/folkways:([a-z0-9_.-]+)/g)].map((m) => m[1]);
  return ids.find((id) => id !== "resident_look") ?? "";
}

async function readOptional(server: MinecraftServer, uuid: string, method: string): Promise<{ ok: boolean; value: unknown }> {
  try {
    return {
      ok: true,
      value: await reflect.invoke(server, {
        target: { kind: "entity", uuid },
        method,
        returnType: "java.util.Optional",
        limits: { maxDepth: 5 },
      }),
    };
  } catch (error) {
    return { ok: false, value: String(error) };
  }
}
