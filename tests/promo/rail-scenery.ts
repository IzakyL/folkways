import { world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { tryCommand } from "../shared/bw-helpers";
import type { Box, Vec } from "../e2e/rail-fixture";

export const PROMO_TRAIN_BOUNDS: Box = { min: { x: -2, y: 1, z: 3 }, max: { x: 2, y: 6, z: 11 } };
// The decorated shell and its station clearances are sized for this many seats,
// independently of how many residents are cast for the shot.
export const PROMO_TRAIN_SEATS = 6;

// The line runs four blocks under the street. Riders board from anywhere within two blocks of the station's
// height, so the street never counts as standing room: the only way onto the train is down the stairs to the platform.
export const CUT_DEPTH = 4;

const CAR_FRONT = 3;
const CAR_BACK = 11;
// Two pairs of Create's sliding train doors a side, which open at each station. The conductor seat behind the first
// pair is clicked straight through them (the line's clickThrough), and riders are seated without walking in.
const DOORS = [6, 7, 9, 10];
const BODY = "minecraft:white_concrete";
const WINDOW = "create:framed_glass";
const LINE = "minecraft:red_concrete";
const ROOF_EDGE = "minecraft:smooth_quartz_stairs";
// Four blocks wide and longer than the car, so six riders have room to mill about instead of queueing in single file.
// It reaches past the car's tail rather than its nose: a rider left waiting beyond the nose, out of reach of every
// door, never boards.
const PLATFORM = { from: 2, to: 14, near: 3, far: 6 };
const WALL = PLATFORM.far + 1;
const STAIRS = [9, 10];
const TUNNEL = 10;
// Beta's stop leaves the car four blocks further along than Alpha's.
const BETA_SHIFT = 2;

type Filler = (a: number[], b: number[], state: string) => Promise<unknown>;

function filler(server: MinecraftServer, x: number, z: number, must = true): Filler {
  return (a, b, state) => {
    const command = `fill ${x + a[0]} ${a[1]} ${z + a[2]} ${x + b[0]} ${b[1]} ${z + b[2]} ${state}`;
    return must ? world.command(server, command) : tryCommand(server, command);
  };
}

export async function decoratePromoTrain(server: MinecraftServer, origin: Vec) {
  const { x, y, z } = origin;
  const block = async (dx: number, dy: number, dz: number, state: string) =>
    world.command(server, `setblock ${x + dx} ${y + dy} ${z + dz} ${state}`);
  const fill = async (a: number[], b: number[], state: string) =>
    world.command(server, `fill ${x + a[0]} ${y + a[1]} ${z + a[2]} ${x + b[0]} ${y + b[1]} ${z + b[2]} ${state}`);

  // Create's railway casing for the underframe, in place of the plank floor.
  await fill([-2, 1, CAR_FRONT], [2, 1, CAR_BACK], "create:railway_casing replace minecraft:oak_planks");
  // Stainless sides, a band of framed windows, and the line's colour running round the car above the doors.
  for (const side of [-2, 2]) {
    await fill([side, 2, CAR_FRONT], [side, 2, CAR_BACK], BODY);
    await fill([side, 3, CAR_FRONT], [side, 3, CAR_BACK], WINDOW);
    await fill([side, 4, CAR_FRONT], [side, 4, CAR_BACK], LINE);
    // The pillar between the two pairs of doors, so each pair reads as one doorway.
    await block(side, 3, DOORS[1] + 1, BODY);
    // Each pair hinged on its outer edges, so the leaves part from the middle.
    const facing = side > 0 ? "west" : "east";
    for (const [i, dz] of DOORS.entries()) {
      const hinge = (i % 2 === 0) === (side < 0) ? "left" : "right";
      for (const half of ["lower", "upper"]) {
        await block(side, half === "lower" ? 2 : 3, dz, `create:train_door[facing=${facing},half=${half},hinge=${hinge},open=false]`);
      }
    }
  }
  // Both ends are cabs: headlights either side of the coupling, a dark windscreen and a lit destination board.
  for (const dx of [-1, 1]) await block(dx, 2, CAR_FRONT, "minecraft:sea_lantern");
  await fill([-1, 3, CAR_FRONT], [1, 3, CAR_FRONT], "minecraft:black_stained_glass");
  // The seats in the back row sit against the tail, so its windscreen is the middle pane alone.
  await block(0, 2, CAR_BACK, BODY);
  await block(0, 3, CAR_BACK, "minecraft:black_stained_glass");
  for (const dz of [CAR_FRONT, CAR_BACK]) {
    await fill([-1, 4, dz], [1, 4, dz], "minecraft:black_concrete");
    await block(0, 4, dz, "minecraft:redstone_lamp[lit=true]");
  }
  // A roof rounded off at its edges, with the air-conditioning units and a vent along its crown.
  await fill([-1, 5, CAR_FRONT], [1, 5, CAR_BACK], "minecraft:light_gray_concrete");
  await fill([-2, 5, CAR_FRONT], [-2, 5, CAR_BACK], `${ROOF_EDGE}[facing=east]`);
  await fill([2, 5, CAR_FRONT], [2, 5, CAR_BACK], `${ROOF_EDGE}[facing=west]`);
  await fill([-1, 5, CAR_FRONT], [1, 5, CAR_FRONT], `${ROOF_EDGE}[facing=south]`);
  await fill([-1, 5, CAR_BACK], [1, 5, CAR_BACK], `${ROOF_EDGE}[facing=north]`);
  for (const dz of [CAR_FRONT + 1, CAR_BACK - 2]) {
    await fill([-1, 6, dz], [1, 6, dz + 1], "create:industrial_iron_block");
  }
  await fill([0, 6, CAR_FRONT + 3], [0, 6, CAR_BACK - 3], "create:train_trapdoor[half=bottom,facing=north]");
  // A ceiling light at the front, and a grab pole by the back doors.
  await block(0, 4, 4, "minecraft:end_rod[facing=down]");
  await fill([0, 2, 10], [0, 4, 10], "create:metal_girder[axis=y]");
  // The hand-over is made standing on the platform, so Alpha's platform is dug before the train is.
  await digPlatform(server, origin);
}

async function digPlatform(server: MinecraftServer, stop: Vec) {
  const { x, y, z } = stop;
  const fill = filler(server, x, z);
  const { from, to, near, far } = PLATFORM;
  await fill([near, y + 2, from], [far, y + CUT_DEPTH - 1, to], "minecraft:air");
  await fill([near, y + 1, from], [near, y + 1, to], "minecraft:yellow_concrete");
  await fill([near + 1, y + 1, from], [far, y + 1, to], "minecraft:polished_andesite");
}

// Idle residents wander while the train is built; a railing keeps them out of the open cut until it is filled in.
export async function fenceTheCut(server: MinecraftServer, origin: Vec, lineLength: number) {
  const { x, y, z } = origin;
  const street = y + CUT_DEPTH;
  const fill = filler(server, x, 0, false);
  const [north, south] = [z - lineLength - 9, z + CAR_BACK + 1];
  for (const side of [-3, 3]) await fill([side, street, north], [side, street, south], "minecraft:oak_fence");
  for (const end of [north, south]) await fill([-2, street, end], [2, street, end], "minecraft:oak_fence");
}

export async function buildSubwayStation(server: MinecraftServer, origin: Vec, stationB: Vec) {
  const { x, y } = origin;
  const beta = stationB.z + BETA_SHIFT;
  await buildStop(server, origin, -1, "ALPHA");
  await digPlatform(server, { x, y, z: beta });
  await buildStop(server, { x, y, z: beta }, 1, "BETA");
  // Only the dead end past Beta is filled in: blocks along the running line stall the train, so between the
  // tunnels the cut stays open behind its railings. The car overhangs the station by four blocks when it stops
  // there, and a conductor whose head is in the fill suffocates in the ten seconds the train waits.
  const buried = await bury(server, origin, stationB.z - 9, stationB.z - 6);
  return { alpha: origin.z, beta, buried };
}

async function buildStop(server: MinecraftServer, stop: Vec, towards: -1 | 1, name: string) {
  const { x, y, z } = stop;
  const street = y + CUT_DEPTH;
  const fill = filler(server, x, z);
  const soft = filler(server, x, z, false);
  const { from, to } = PLATFORM;

  for (const [a, b] of [[-2, -1], [1, 2]]) {
    await soft([a, y, from - 5], [b, y, to + 5], "minecraft:gravel replace minecraft:air");
  }
  await soft([PLATFORM.near, street, from], [PLATFORM.far, street, to], "minecraft:air replace minecraft:oak_fence");
  await fill([WALL, y + 1, from - 1], [WALL, street - 1, to + 1], "minecraft:stone_bricks");
  for (const end of [from - 1, to + 1]) await fill([PLATFORM.near, y + 1, end], [PLATFORM.far, street - 1, end], "minecraft:stone_bricks");
  await fill([-3, y + 1, from - 5], [-3, street - 1, to + 5], "minecraft:stone_bricks");
  await soft([WALL, street, from - 1], [WALL, street, to + 1], "minecraft:oak_fence replace minecraft:air");
  // The far end railed as well: a rider who has a reason to leave will find any step up onto its wall.
  await soft([PLATFORM.near, street, from - 1], [PLATFORM.far, street, from - 1], "minecraft:oak_fence replace minecraft:air");
  await soft([-3, street, from - 5], [-3, street, to + 5], "minecraft:oak_fence replace minecraft:air");
  for (const dz of STAIRS) {
    await fill([WALL, street - 2, dz], [WALL, street - 2, dz], "minecraft:stone_brick_stairs[facing=east]");
    await fill([WALL, street - 1, dz], [WALL, street, dz], "minecraft:air");
    await fill([WALL + 1, street - 1, dz], [WALL + 1, street - 1, dz], "minecraft:stone_brick_stairs[facing=east]");
  }
  for (const dz of [STAIRS[0] - 1, STAIRS[STAIRS.length - 1] + 1]) {
    await soft([WALL + 1, street, dz], [WALL + 2, street, dz], "minecraft:oak_fence replace minecraft:air");
  }
  for (let dz = from; dz <= to; dz += 3) {
    if (!STAIRS.includes(dz)) await soft([WALL, street + 1, dz], [WALL, street + 1, dz], "minecraft:lantern replace minecraft:air");
  }
  await tryCommand(server,
    `setblock ${x + PLATFORM.far} ${street - 1} ${z + from + 1} minecraft:oak_wall_sign[facing=west]{front_text:{messages:['""','"${name}"','""','""']}}`);

  const portal = towards < 0 ? from - 6 : to + 3;
  const far = portal + towards * TUNNEL;
  const [near, away] = towards < 0 ? [far, portal] : [portal, far];
  for (let h = 0; h <= 4; h++) {
    await fill([-(7 - h), street + h, near], [7 - h, street + h, away], "minecraft:grass_block");
  }
  await fill([-4, y + 1, portal], [4, street + 4, portal], "minecraft:stone_bricks");
  await fill([-3, y + 1, near], [3, y + 7, away], "minecraft:stone_bricks");
  await fill([-2, y + 1, near], [2, y + 6, away], "minecraft:air");
  for (let dz = near + 2; dz < away; dz += 4) {
    for (const dx of [-3, 3]) await fill([dx, y + 3, dz], [dx, y + 3, dz], "minecraft:glowstone");
  }
}

// Invisible barriers across the top of Alpha's stairs keep the riders milling about the platform until the train
// comes in for them.
export async function holdOnPlatform(server: MinecraftServer, origin: Vec) {
  const { x, y, z } = origin;
  const street = y + CUT_DEPTH;
  const results = [];
  for (const dz of STAIRS) {
    results.push(await tryCommand(server, `fill ${x + WALL} ${street - 1} ${z + dz} ${x + WALL} ${street} ${z + dz} minecraft:barrier`));
  }
  return results.every((one: any) => one?.success !== false);
}

// The spots along Alpha's platform the riders are set down on, spread along its length and across its width.
export function platformSpots(origin: Vec, count: number): Vec[] {
  const { from, to, near, far } = PLATFORM;
  const spots: Vec[] = [];
  for (let i = 0; i < count; i++) {
    spots.push({
      x: origin.x + near + 1 + (i * 2) % (far - near),
      y: origin.y + 2,
      z: origin.z + from + 3 + Math.floor(i * (to - from - 3) / count),
    });
  }
  return spots;
}

// Where the goods wait on Alpha's platform, against its back wall near the far end from the camera, and where they
// are wanted, stacked two high along the back wall of Beta's. Neither stands in a corner, where it would be a step up
// out of the cut.
export const CHEST_SLOTS = 27;

// As many chests as the stacks of goods need, stacked at the head of Alpha's platform.
export function supplyChests(origin: Vec, stacks: number): Vec[] {
  const chests: Vec[] = [];
  for (let i = 0; i < Math.ceil(stacks / CHEST_SLOTS); i++) {
    chests.push({ x: origin.x + PLATFORM.far, y: origin.y + 2 + i, z: origin.z + PLATFORM.from + 1 });
  }
  return chests;
}

export function deliveryChests(origin: Vec, stationB: Vec, count: number): Vec[] {
  const beta = stationB.z + BETA_SHIFT;
  // A row runs the length of the platform, and rows stack up the wall.
  const perRow = PLATFORM.to - PLATFORM.from;
  const chests: Vec[] = [];
  for (let i = 0; i < count; i++) {
    chests.push({
      x: origin.x + PLATFORM.far, y: origin.y + 2 + Math.floor(i / perRow), z: beta + PLATFORM.from + 1 + i % perRow,
    });
  }
  return chests;
}

// Fills the cut back in so nobody falls four blocks into it.
async function bury(server: MinecraftServer, origin: Vec, fromZ: number, toZ: number) {
  const { x, y } = origin;
  const street = y + CUT_DEPTH;
  const fill = filler(server, x, 0, false);
  const results = [];
  for (const [a, b, low] of [[-2, -1, y], [0, 0, y + 1], [1, 2, y]]) {
    results.push(await fill([a, low, fromZ], [b, street - 2, toZ], "minecraft:dirt replace minecraft:air"));
  }
  results.push(await fill([-2, street - 1, fromZ], [2, street - 1, toZ], "minecraft:grass_block replace minecraft:air"));
  return { fromZ, toZ, ok: results.every((one: any) => one?.success !== false) };
}
