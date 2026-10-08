import { errorToString } from "@izakyl/blockwright-client";
import { player, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import {
  waitForGroundedPlayer,
  type Vec,
} from "../e2e/colony-founding";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import {
  buildPen,
  countAnimalsInPen,
  createPastureZoneViaPanel,
  pastureZoneFailure,
  penCenter,
  penLayout,
  type Pen,
} from "../e2e/pasture-tools";
import { colonyRegistry, configureZone, createMarkedZone, markCorner, setBookGesture, zoneCount } from "../e2e/zone-tools";
import { drainActionBar, FIRST_CORNER, ZONE_MARKED } from "../e2e/zone-receipt";
import type { ZoneEdit } from "../e2e/zone-tools";
import {
  blobCells,
  cellBounds,
  edgeCells,
  fillCells,
  nearestCells,
  pickCells,
  pruneCells,
  type Blob,
  type Cell,
} from "./ground";
import { commandPos, frames, safeAction, tryCommand, type CommandOutcome } from "../shared/bw-helpers";
import { runColonyTask } from "../e2e/colony-tasks";
import { GOLEM_CAST, PATROL_ROUTES } from "../shared/integration-tasks";

export type Patch = { minX: number; maxX: number; minZ: number; maxZ: number };

export type Sight = {
  name: string;
  aim: Vec;
  presence: Patch;
  crew: number;
  posts: Vec[];
  cue: (server: any, layout: SiteLayout, held: Held[]) => Promise<unknown>;
};

export type Held = { sight: string; uuid: string; at: Vec };

export type Track = {
  start: Vec;
  stop: Vec;
  yaw: number;
  pitch: number;
  speed: number;
  sights: Sight[];
  rate: number;
};

export type FarmPlot = {
  name: string;
  zone: { min: Vec; max: Vec };
  crop: string;
};

export type PenPlan = {
  name: string;
  pen: Pen;
  animal: "sheep" | "cow" | "pig" | "chicken";
  target: number;
  heads: number;
};

export type Demand = { at: Vec; item: string; count: number };

export type SiteLayout = {
  origin: Vec;
  ground: number;
  bounds: { min: Vec; max: Vec };
  standBlock: Vec;
  lane: { min: Vec; max: Vec };
  quarters: { bedFeet: Vec[]; foodChest: Vec; toolChest: Vec };
  farm: {
    plots: FarmPlot[];
    tilth: Vec[];
    fallow: Vec[];
    standing: Array<{ block: string; cells: Vec[] }>;
    springs: Vec[];
    verge: Vec[];
    cane: { channel: Vec[]; stools: Vec[] };
    grove: { trunks: Vec[]; feature: string };
  };
  pasture: {
    pens: PenPlan[];
    paddock: { grass: Vec[]; fence: Vec[]; grazed: Vec[]; strays: Vec[] };
    hay: Vec[];
    dairy: Demand;
    nests: Vec[];
  };
  fishery: { pond: Vec[]; shore: Vec[]; bed: Vec[]; zones: Array<{ min: Vec; max: Vec }> };
  camp: {
    clearing: Vec[];
    grit: Vec[];
    fires: Vec[];
    stations: Array<{ at: Vec; block: string }>;
    demands: Demand[];
    bins: Vec[];
    spill: Vec[];
    logs: Vec[];
    golems: Vec[];
  };
  stores: Store[];
  patrol: Vec[][];
  scenery: Array<{ at: Vec; feature: string }>;
  track: Track;
};

// A chest kept for nothing in particular, one in sight of each place of work: what a crew puts away or fetches
// goes there and back in frame, not to the quarters behind the camera.
export type Store = { name: string; at: Vec; items: Array<[string, number]>; double?: boolean };

// Every chest the colony keeps, once each: several orders share one of the camp's bins.
export function memberChests(layout: SiteLayout): Vec[] {
  const seen = new Set<string>();
  return [
    layout.quarters.foodChest,
    layout.quarters.toolChest,
    layout.pasture.dairy.at,
    ...layout.camp.demands.map((demand) => demand.at),
    ...layout.stores.map((store) => store.at),
  ].filter((at) => {
    const key = `${at.x},${at.y},${at.z}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

// The chests laid as double chests, each named by its west half as the colony names it; the east half is at x + 1.
export function doubleChests(layout: SiteLayout): Vec[] {
  return [...layout.camp.bins, ...layout.stores.filter((store) => store.double).map((store) => store.at)];
}

export function demands(layout: SiteLayout): Demand[] {
  return [...layout.camp.demands, layout.pasture.dairy];
}

export type Settlement = {
  layout: SiteLayout;
  colonyId: string;
  residents: number;
  evidence: any;
};

const COOK_SPILL = ["minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken"];
const FOOD = "minecraft:bread";
const COOK_OUTPUT = "minecraft:cooked_beef";
const COOK_INPUT = "minecraft:beef";
const ROAST_OUTPUT = "minecraft:cooked_porkchop";
const ROAST_INPUT = "minecraft:porkchop";
// The colony's fuel list starts at coal and charcoal: without it in the camp store nothing is ever cooked.
const FUEL = "minecraft:coal";
// What the camp's bins are ordered full of, each with what one of it is cut from and how many one input makes.
// An order is one item in one container, so each kind is an order of its own, and a hand of its own at the benches.
// Each is made in one step from what the camp store holds: the stone ones at a stonecutter.
const CARPENTRY: Array<[string, string, number]> = [
  ["minecraft:oak_planks", "minecraft:oak_log", 4],
  ["minecraft:birch_planks", "minecraft:birch_log", 4],
  ["minecraft:spruce_planks", "minecraft:spruce_log", 4],
];
const MASONRY_INPUT = "minecraft:stone";
const MASONRY: Array<[string, string, number]> = [
  ["minecraft:stone_bricks", MASONRY_INPUT, 1],
  ["minecraft:chiseled_stone_bricks", MASONRY_INPUT, 1],
  ["minecraft:stone_brick_stairs", MASONRY_INPUT, 1],
  ["minecraft:stone_brick_wall", MASONRY_INPUT, 1],
];
const DAIRY_OUTPUT = "minecraft:milk_bucket";
// A cooker takes the whole order in one load and gives nothing back until all of it is cooked: a stack of beef
// sits in the smoker for minutes. A few pieces come out, and are carried over, while the camera is there.
const COOK_DEMAND_COUNT = 4;
// An order is worked by one hand at a time, 64 at a go. Planks and bricks come off the benches fast: three stacks
// keep each crafter at it through the shot.
const CRAFT_DEMAND_COUNT = 192;
const DAIRY_COUNT = 8;

type CrewKit = {
  trade: string;
  sight: string;
  hands: number;
  licences: string[];
  hold: string;
  items: Array<[string, number]>;
  post: (layout: SiteLayout) => Vec;
};

const posts = {
  field: (layout: SiteLayout) => ({ ...layout.farm.plots[0].zone.min, y: layout.origin.y }),
  grove: (layout: SiteLayout) => ({
    ...layout.farm.grove.trunks[0],
    y: layout.origin.y,
    z: layout.farm.grove.trunks[0].z + 2,
  }),
  pasture: (layout: SiteLayout) => ({
    x: layout.pasture.pens[1].pen.min.x,
    y: layout.origin.y,
    z: layout.pasture.pens[1].pen.min.z + 4,
  }),
  fishery: (layout: SiteLayout) => ({
    ...layout.fishery.zones[0].min,
    y: layout.origin.y,
    z: layout.fishery.zones[0].min.z + FISH_POST_BACK,
  }),
  camp: (layout: SiteLayout) => ({ ...layout.camp.fires[1], z: layout.camp.fires[1].z + 3 }),
};

const FISHERS = 3;
const FISH_ZONE_GAP = 4;

const CREW_KITS: CrewKit[] = [
  {
    trade: "field",
    sight: "farm",
    hands: 4,
    licences: ["farming"],
    hold: "minecraft:wooden_hoe",
    items: [["minecraft:wooden_hoe", 1], ["minecraft:wheat_seeds", 64], ["minecraft:carrot", 64]],
    post: posts.field,
  },
  {
    trade: "grove",
    sight: "farm",
    hands: 2,
    licences: ["farming"],
    hold: "minecraft:iron_axe",
    items: [["minecraft:wooden_hoe", 1], ["minecraft:iron_axe", 1], ["minecraft:oak_sapling", 16]],
    post: posts.grove,
  },
  {
    trade: "pasture",
    sight: "pasture",
    hands: 5,
    licences: ["herding"],
    hold: "minecraft:shears",
    items: [["minecraft:shears", 1], ["minecraft:wheat", 64], ["minecraft:wheat_seeds", 64], ["minecraft:bucket", 3]],
    post: posts.pasture,
  },
  {
    trade: "fishery",
    sight: "fishery",
    hands: FISHERS,
    licences: ["fishing"],
    hold: "minecraft:fishing_rod",
    items: [["minecraft:fishing_rod", 1]],
    post: posts.fishery,
  },
  {
    trade: "camp",
    sight: "camp",
    hands: 6,
    licences: ["crafting", "hauling"],
    hold: COOK_INPUT,
    // No food in their packs: what an idle hand has left over it puts away in the nearest store that keeps nothing
    // else, which for the camp is the fishery's, out of frame.
    items: [],
    post: posts.camp,
  },
];

function crewAt(sight: string) {
  const hands = CREW_KITS.filter((kit) => kit.sight === sight).reduce((sum, kit) => sum + kit.hands, 0);
  if (hands === 0) {
    throw new Error(`No kit in CREW_KITS is sent to ${sight}`);
  }
  return hands;
}

export function crewTotal() {
  return CREW_KITS.reduce((sum, kit) => sum + kit.hands, 0);
}

const KIT_SLOT = 8;
const KIT_CAPACITY = 12 - KIT_SLOT;

const LANE_HEIGHT = 7;
const LANE_PILLAR_STRIDE = 6;
const WALL_DEPTH = 30;
const WALL_HEIGHT = 20;
const WALL_BLOCK = "minecraft:stone_bricks";
const SIDE_MARGIN = 40;
const PLOT_RAGGED = 0.9;
const PLOT_WOBBLE = 4;
const FARM_FALLOW_Z = -12;
const FISH_POST_BACK = 7;
const BIN_ROW = -10;
const PATROL_Z = -8;
// A plain oak's leaves come down to the ground and hide its trunk and whoever fells it; a fancy oak holds its crown
// up on a bare trunk, and its many logs keep the feller at it for several seconds.
const GROVE_TREE = "minecraft:fancy_oak";
export const TRACK_SPEED = 6;

const BANDS: Record<string, { from: number; to: number }> = {
  farm: { from: -14, to: 12 },
  pasture: { from: 13, to: 35 },
  fishery: { from: 36, to: 58 },
  camp: { from: 59, to: 81 },
};

export function siteLayout(origin: Vec): SiteLayout {
  const ground = origin.y - 1;
  const x = (offset: number) => origin.x + offset;
  const z = (offset: number) => origin.z + offset;
  const at = (dx: number, dz: number, y = origin.y): Vec => ({ x: x(dx), y, z: z(dz) });
  const band = (name: string): Patch => ({
    minX: x(BANDS[name].from),
    maxX: x(BANDS[name].to),
    minZ: z(-27),
    maxZ: z(-5),
  });
  const box = (minDx: number, minDz: number, maxDx: number, maxDz: number) => ({
    min: at(minDx, minDz, ground),
    max: at(maxDx, maxDz, ground),
  });
  const layer = (cells: Cell[], y: number): Vec[] => cells.map((cell) => ({ x: x(cell.x), y, z: z(cell.z) }));
  const shape = (blobs: Blob[], seed: number) =>
    pruneCells(blobCells(blobs, { seed, ragged: PLOT_RAGGED, wobble: PLOT_WOBBLE }));
  const held = (cells: Cell[]) => new Set(cells.map(cellKey));
  const without = (cells: Cell[], taken: Set<string>) => cells.filter((cell) => !taken.has(cellKey(cell)));
  const window = (cells: Cell[], w: { minX: number; maxX: number; minZ: number; maxZ: number }) =>
    cells.filter((cell) => cell.x >= w.minX && cell.x <= w.maxX && cell.z >= w.minZ && cell.z <= w.maxZ);

  const bedFeet: Vec[] = [];
  for (let row = 0; row < 10; row++) {
    for (let i = 0; i < 4; i++) bedFeet.push(at(26 + i * 3, 4 + row * 2));
  }

  const wheatField = window(
    shape(
      [
        { x: -7, z: -13.5, rx: 6, rz: 2.8 },
        { x: -2, z: -12.5, rx: 5, rz: 2.2 },
        { x: -9, z: -12, rx: 3.5, rz: 2 },
      ],
      11,
    ),
    { minX: -13, maxX: 1, minZ: -16, maxZ: -10 },
  );
  const carrotField = window(
    shape([{ x: 7, z: -13.5, rx: 4.5, rz: 2.6 }, { x: 9.5, z: -12, rx: 3, rz: 2 }], 41),
    { minX: 3, maxX: 12, minZ: -16, maxZ: -10 },
  );
  const wheatBox = cellBounds(wheatField);
  const carrotBox = cellBounds(carrotField);

  const springs = [
    ...nearestCells(wheatField, { x: -10, z: -15 }, 3),
    ...nearestCells(wheatField, { x: -1, z: -13 }, 2),
    ...nearestCells(carrotField, { x: 8, z: -15 }, 2),
  ];
  const wet = held(springs);
  const cropCells = without([...wheatField, ...carrotField], wet);
  const fallow = cropCells.filter((cell) => cell.z >= FARM_FALLOW_Z);
  const tilth = cropCells.filter((cell) => cell.z < FARM_FALLOW_Z);
  const wheatTilth = tilth.filter((cell) => cell.x <= wheatBox.maxX);
  const carrotTilth = tilth.filter((cell) => cell.x > wheatBox.maxX);
  const ripeWheat = pickCells(wheatTilth, 0.4, 21, 5);
  const greenWheat = pickCells(without(wheatTilth, held(ripeWheat)), 0.6, 22, 4);
  const sproutWheat = pickCells(without(wheatTilth, held([...ripeWheat, ...greenWheat])), 0.5, 23, 3);
  const ripeCarrot = pickCells(carrotTilth, 0.45, 24, 4);
  const greenCarrot = pickCells(without(carrotTilth, held(ripeCarrot)), 0.6, 25, 3);

  const caneRow = -17;
  const caneChannel: Cell[] = [];
  const caneStools: Cell[] = [];
  for (let dx = -12; dx <= -3; dx++) {
    caneChannel.push({ x: dx, z: caneRow - 1 });
    caneStools.push({ x: dx, z: caneRow });
  }
  const caneBox = cellBounds(caneStools);

  const groveTrunks = [
    { x: -12, z: -22 },
    { x: -7, z: -21 },
    { x: -2, z: -22 },
    { x: 3, z: -21 },
    { x: 8, z: -22 },
  ];
  const groveBox = { minX: -14, maxX: 10, minZ: -23, maxZ: -19 };

  const plots: FarmPlot[] = [
    { name: "wheat", zone: box(wheatBox.minX, wheatBox.minZ, wheatBox.maxX, wheatBox.maxZ), crop: "minecraft:wheat" },
    { name: "carrot", zone: box(carrotBox.minX, carrotBox.minZ, carrotBox.maxX, carrotBox.maxZ), crop: "minecraft:carrots" },
    { name: "cane", zone: box(caneBox.minX, caneBox.minZ, caneBox.maxX, caneBox.maxZ), crop: "minecraft:sugar_cane" },
    { name: "grove", zone: box(groveBox.minX, groveBox.minZ, groveBox.maxX, groveBox.maxZ), crop: "minecraft:oak_sapling" },
  ];

  const planted = held([...wheatField, ...carrotField]);
  const verge: Cell[] = [];
  for (const rect of [wheatBox, carrotBox]) {
    for (let cz = rect.minZ; cz <= rect.maxZ; cz++) {
      for (let cx = rect.minX; cx <= rect.maxX; cx++) {
        const cell = { x: cx, z: cz };
        if (planted.has(cellKey(cell))) continue;
        verge.push(cell);
      }
    }
  }

  const pens: PenPlan[] = [
    { name: "sheep", pen: penLayout({ x: x(16), y: origin.y, z: z(-12) }, 0, 3), animal: "sheep", target: 6, heads: 3 },
    { name: "cow", pen: penLayout({ x: x(22), y: origin.y, z: z(-12) }, 0, 3), animal: "cow", target: 5, heads: 3 },
    { name: "chicken", pen: penLayout({ x: x(28), y: origin.y, z: z(-12) }, 0, 3), animal: "chicken", target: 6, heads: 3 },
  ];
  const paddock = shape(
    [
      { x: 24, z: -20, rx: 8, rz: 2.6 },
      { x: 17, z: -19, rx: 3.5, rz: 2.2 },
      { x: 31, z: -19, rx: 3.5, rz: 2.4 },
    ],
    5,
  );
  const inPaddock = held(paddock);
  const grazed = shape([{ x: 20, z: -19, rx: 3, rz: 1.8 }, { x: 29, z: -20, rx: 2.5, rz: 1.6 }], 31).filter((cell) =>
    inPaddock.has(cellKey(cell)),
  );

  const pond = without(
    shape(
      [
        { x: 41, z: -19.5, rx: 6, rz: 3.6 },
        { x: 47, z: -18, rx: 5, rz: 3 },
        { x: 53, z: -19.5, rx: 5, rz: 3.4 },
        { x: 49.5, z: -21.5, rx: 4, rz: 2 },
      ],
      3,
    ),
    held([
      ...blobCells([{ x: 43, z: -20, rx: 2, rz: 1.3 }], { seed: 13, ragged: PLOT_RAGGED, wobble: 3 }),
      ...blobCells([{ x: 52, z: -20.5, rx: 1.8, rz: 1.2 }], { seed: 17, ragged: PLOT_RAGGED, wobble: 3 }),
    ]),
  );
  const water = held(pond);
  const southOfPond = (cell: Cell) => !pond.some((one) => one.x === cell.x && one.z > cell.z);
  const banks = edgeCells(pond)
    .filter((cell) => water.has(cellKey({ x: cell.x, z: cell.z - 1 })))
    .filter(southOfPond)
    .filter((cell) => !fishBoxCells(cell).some((cell2) => water.has(cellKey(cell2))))
    .sort((a, b) => a.x - b.x);
  const stands = spreadBanks(banks, FISHERS, FISH_ZONE_GAP);
  const fishZones = stands.map((bank) => box(bank.x - 1, bank.z, bank.x + 1, bank.z + 1));
  const midBank = stands[Math.floor(stands.length / 2)];

  const clearing = shape(
    [
      { x: 70, z: -15, rx: 9, rz: 4.5 },
      { x: 63, z: -13, rx: 4.5, rz: 3 },
      { x: 77, z: -14, rx: 4.5, rz: 3 },
    ],
    8,
  );
  const inCamp = held(clearing);
  const grit = shape([{ x: 65, z: -14, rx: 3.5, rz: 2.5 }, { x: 74, z: -16, rx: 3, rz: 2 }], 17).filter((cell) =>
    inCamp.has(cellKey(cell)),
  );
  const spot = (dx: number, dz: number) => nearestCells(clearing, { x: dx, z: dz }, 1)[0];
  const fires = [spot(62, -13), spot(67, -16), spot(72, -12)];
  const stations = [
    { at: spot(75, -15), block: "minecraft:crafting_table" },
    { at: spot(78, -13), block: "minecraft:stonecutter" },
    { at: spot(60, -16), block: "minecraft:furnace[facing=south,lit=false]" },
    { at: spot(64, -18), block: "minecraft:smoker[facing=south,lit=false]" },
    { at: spot(70, -18), block: "minecraft:smithing_table" },
    // A second table and cutter, so more hands than one per bench have their own place to work in frame.
    { at: spot(65, -16), block: "minecraft:crafting_table" },
    { at: spot(74, -10), block: "minecraft:stonecutter" },
  ];
  // Double chests in one even row along the near edge of the camp, a block apart: the larder, the carpentry bin,
  // the camp store in the middle, the masonry bin.
  const [larder, carpentry, campStore, masonry] = [63, 66, 69, 72].map((dx) => at(dx, BIN_ROW));
  const spill = [spot(61, -18), spot(69, -19), spot(79, -16)];
  const logs = [spot(68, -11), spot(73, -18)];
  const golemSpots = [spot(64, -14), spot(70, -15), spot(74, -13)];
  const stores: Store[] = [
    { name: "farm", at: at(-6, -9), items: [[FOOD, 16]] },
    { name: "pasture", at: at(25, -7), items: [[FOOD, 16]] },
    { name: "fishery", at: at(45, -9), items: [[FOOD, 16]] },
    {
      name: "camp",
      at: campStore,
      double: true,
      items: [
        [FOOD, 16],
        [COOK_INPUT, 16],
        [ROAST_INPUT, 16],
        [FUEL, 16],
        ...[...CARPENTRY, ...MASONRY].flatMap(([, input, yields]) => stacks(input, CRAFT_DEMAND_COUNT / yields)),
      ],
    },
  ];
  // Walked end to end and back by a patrolling golem each, along the near edge of the work so the camera keeps them.
  const patrol = [
    [at(-12, PATROL_Z), at(10, PATROL_Z)],
    [at(15, PATROL_Z), at(34, PATROL_Z)],
    [at(37, PATROL_Z), at(57, PATROL_Z)],
    [at(60, PATROL_Z), at(80, PATROL_Z)],
  ];

  const farmAim = at(-2, -14);
  const sights: Sight[] = [
    {
      name: "farm",
      aim: farmAim,
      presence: band("farm"),
      crew: crewAt("farm"),
      posts: [
        ...spreadCells(layer(verge, origin.y), farmAim, 4, 3),
        ...nearestTo(layer(groveTrunks, origin.y), farmAim, 2).map((trunk) => ({
          x: trunk.x + 1,
          y: origin.y,
          z: trunk.z + 2,
        })),
      ],
      cue: cueFarm,
    },
    {
      name: "pasture",
      aim: at(22, -11),
      presence: band("pasture"),
      crew: crewAt("pasture"),
      posts: [
        ...pens.map((plan) => ({ x: plan.pen.min.x + 1, y: origin.y, z: plan.pen.min.z + 2 })),
        at(33, -9),
        at(34, -12),
      ],
      cue: cuePasture,
    },
    {
      name: "fishery",
      aim: at(midBank.x, midBank.z + 1),
      presence: band("fishery"),
      crew: crewAt("fishery"),
      posts: stands.map((bank) => at(bank.x, bank.z)),
      cue: cueFishery,
    },
    {
      name: "camp",
      aim: at(70, -14),
      presence: band("camp"),
      crew: crewAt("camp"),
      posts: [...stations.map((one) => at(one.at.x, one.at.z + 1)), at(fires[1].x, fires[1].z + 1)],
      cue: cueCamp,
    },
  ];

  const layout: SiteLayout = {
    origin,
    ground,
    bounds: { min: at(-3 - SIDE_MARGIN, -WALL_DEPTH, ground), max: at(68 + SIDE_MARGIN, 28, ground) },
    standBlock: at(30, -4),
    lane: { min: at(-20, 0, origin.y), max: at(80, 2, origin.y + LANE_HEIGHT - 1) },
    farm: {
      plots,
      tilth: layer(tilth, ground),
      fallow: layer(fallow, ground),
      standing: [
        { block: "minecraft:wheat[age=7]", cells: layer(ripeWheat, origin.y) },
        { block: "minecraft:wheat[age=5]", cells: layer(greenWheat, origin.y) },
        { block: "minecraft:wheat[age=2]", cells: layer(sproutWheat, origin.y) },
        { block: "minecraft:carrots[age=7]", cells: layer(ripeCarrot, origin.y) },
        { block: "minecraft:carrots[age=4]", cells: layer(greenCarrot, origin.y) },
      ],
      springs: layer(springs, ground),
      verge: layer(verge, origin.y),
      cane: { channel: layer(caneChannel, ground), stools: layer(caneStools, origin.y) },
      grove: { trunks: layer(groveTrunks, origin.y), feature: GROVE_TREE },
    },
    pasture: {
      pens,
      paddock: {
        grass: layer(paddock, ground),
        fence: layer(edgeCells(paddock), origin.y),
        grazed: layer(grazed, ground),
        strays: layer(
          [
            ...nearestCells(paddock, { x: 17, z: -19 }, 2),
            ...nearestCells(paddock, { x: 24, z: -21 }, 2),
            ...nearestCells(paddock, { x: 31, z: -19 }, 2),
          ],
          origin.y,
        ),
      },
      hay: [at(33, -12, ground), at(33, -11, ground)],
      dairy: { at: at(33, -10), item: DAIRY_OUTPUT, count: DAIRY_COUNT },
      nests: [at(27, -9), at(30, -9), at(31, -11)],
    },
    fishery: {
      pond: layer(pond, ground),
      shore: layer(pickCells(edgeCells(pond), 0.4, 7, 5), ground),
      bed: layer(pickCells(pond, 0.55, 9, 4), ground - 2),
      zones: fishZones,
    },
    camp: {
      clearing: layer(clearing, ground),
      grit: layer(grit, ground),
      fires: fires.map((cell) => at(cell.x, cell.z)),
      stations: stations.map((one) => ({ at: at(one.at.x, one.at.z), block: one.block })),
      demands: [
        { at: larder, item: COOK_OUTPUT, count: COOK_DEMAND_COUNT },
        ...CARPENTRY.map(([item]) => ({ at: carpentry, item, count: CRAFT_DEMAND_COUNT })),
        ...MASONRY.map(([item]) => ({ at: masonry, item, count: CRAFT_DEMAND_COUNT })),
        { at: larder, item: ROAST_OUTPUT, count: COOK_DEMAND_COUNT },
      ],
      bins: [larder, carpentry, masonry],
      spill: spill.map((cell) => at(cell.x, cell.z)),
      logs: logs.map((cell) => at(cell.x, cell.z)),
      golems: golemSpots.map((cell) => at(cell.x, cell.z)),
    },
    stores,
    patrol,
    scenery: scenery(at),
    quarters: { bedFeet, foodChest: at(46, 6), toolChest: at(46, 9) },
    track: {
      start: at(-3, 0, origin.y + LANE_HEIGHT),
      stop: at(68, 0, origin.y + LANE_HEIGHT),
      yaw: 180,
      pitch: 20,
      speed: TRACK_SPEED,
      sights,
      rate: 20,
    },
  };
  assertDisjointPlots(layout);
  return layout;
}

function stacks(item: string, count: number): Array<[string, number]> {
  const out: Array<[string, number]> = [];
  for (let left = count; left > 0; left -= 64) out.push([item, Math.min(64, left)]);
  return out;
}

function nearestTo(cells: Vec[], to: Vec, want: number): Vec[] {
  return [...cells].sort((a, b) => step(a, to) - step(b, to)).slice(0, want);
}

function spreadCells(cells: Vec[], to: Vec, want: number, gap: number): Vec[] {
  const picked: Vec[] = [];
  for (const cell of [...cells].sort((a, b) => step(a, to) - step(b, to))) {
    if (picked.length >= want) break;
    if (picked.every((one) => step(one, cell) >= gap)) picked.push(cell);
  }
  return picked;
}

function spreadBanks(banks: Cell[], want: number, gap: number): Cell[] {
  if (banks.length === 0) {
    throw new Error("No standable fishing spot on the south shore of this pond");
  }
  const west = banks[0].x;
  const span = banks[banks.length - 1].x - west;
  const picked: Cell[] = [];
  for (let i = 0; i < want; i++) {
    const aim = want === 1 ? west + span / 2 : west + (span * i) / (want - 1);
    const bank = [...banks].sort((a, b) => Math.abs(a.x - aim) - Math.abs(b.x - aim))[0];
    if (picked.every((one) => Math.abs(one.x - bank.x) >= gap)) picked.push(bank);
  }
  if (picked.length === 0) {
    throw new Error("No standable fishing spot on the south shore of this pond");
  }
  return picked;
}

function assertDisjointPlots(layout: SiteLayout) {
  const plots = layout.farm.plots;
  for (let i = 0; i < plots.length; i++) {
    for (let j = i + 1; j < plots.length; j++) {
      const a = plots[i].zone;
      const b = plots[j].zone;
      const overlap = a.min.x <= b.max.x && b.min.x <= a.max.x && a.min.z <= b.max.z && b.min.z <= a.max.z;
      if (overlap) {
        throw new Error(`Farm plots ${plots[i].name} and ${plots[j].name} overlap`);
      }
    }
  }
  const bands = Object.entries(BANDS).sort((a, b) => a[1].from - b[1].from);
  for (let i = 1; i < bands.length; i++) {
    if (bands[i][1].from <= bands[i - 1][1].to) {
      throw new Error(`Bands ${bands[i - 1][0]} and ${bands[i][0]} overlap`);
    }
  }
  for (const kit of CREW_KITS) {
    if (kit.items.length > KIT_CAPACITY) {
      throw new Error(`The ${kit.trade} kit has ${kit.items.length} items, but the inventory only holds ${KIT_CAPACITY}`);
    }
    if (!layout.track.sights.some((sight) => sight.name === kit.sight)) {
      throw new Error(`The ${kit.trade} kit is sent to ${kit.sight}, but the blocking has no such place`);
    }
  }
}

function markCells(layout: SiteLayout): Vec[] {
  const cells: Vec[] = [];
  for (const plot of layout.farm.plots) {
    cells.push(plot.zone.min, plot.zone.max);
  }
  for (const zone of layout.fishery.zones) {
    for (let x = zone.min.x; x <= zone.max.x; x++) {
      for (let z = zone.min.z; z <= zone.max.z; z++) cells.push({ x, y: layout.origin.y, z });
    }
  }
  for (const plan of layout.pasture.pens) {
    for (let x = plan.pen.min.x; x <= plan.pen.max.x; x++) {
      for (let z = plan.pen.min.z; z <= plan.pen.max.z; z++) cells.push({ x, y: layout.origin.y, z });
    }
  }
  return cells.map((cell) => ({ ...cell, y: layout.origin.y }));
}

function fishBoxCells(bank: Cell): Cell[] {
  const cells: Cell[] = [];
  for (let dx = -1; dx <= 1; dx++) for (let dz = 0; dz <= 1; dz++) cells.push({ x: bank.x + dx, z: bank.z + dz });
  return cells;
}

function cellKey(cell: Cell) {
  return `${cell.x},${cell.z}`;
}

function scenery(at: (dx: number, dz: number, y?: number) => Vec): Array<{ at: Vec; feature: string }> {
  const placed: Array<{ at: Vec; feature: string }> = [];
  const trees = ["minecraft:oak", "minecraft:birch", "minecraft:spruce", "minecraft:fancy_oak", "minecraft:pine"];
  for (let i = 0; i < 28; i++) {
    const dx = -34 + i * 5 + ((i * 7) % 4) - 2;
    if (dx >= BANDS.farm.from && dx <= BANDS.farm.to) continue;
    placed.push({ at: at(dx, -27 + ((i * 5) % 2)), feature: trees[i % trees.length] });
  }
  for (const [dx, dz] of [
    [-24, -14], [-23, -22], [14, -12], [35, -11], [36, -22], [56, -11], [58, -21], [82, -12], [84, -20],
  ]) {
    placed.push({ at: at(dx, dz), feature: "minecraft:patch_grass" });
  }
  for (const [dx, dz] of [[14, -8], [37, -15], [57, -16], [83, -9]]) {
    placed.push({ at: at(dx, dz), feature: "minecraft:flower_plain" });
  }
  return placed;
}

export async function settleClient(server: any, client: any) {
  await tick.sprint(server, 20);
  await safeAction(() => frames(client, 20));
}

export type Note = (key: string, value: unknown) => void;

export type Staging = { residents: number; note?: Note };

export async function stageSettlement(
  server: any,
  client: any,
  playerName: string,
  options: Staging,
): Promise<Settlement> {
  const evidence: any = { steps: [] };
  try {
    return await layScene(server, client, playerName, options, evidence);
  } catch (error) {
    options.note?.("staging_failed", { error: errorToString(error), evidence });
    throw error;
  }
}

async function layScene(
  server: any,
  client: any,
  playerName: string,
  options: Staging,
  evidence: any,
): Promise<Settlement> {
  const grounded = await waitForGroundedPlayer(server);
  const origin: Vec = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
  const layout = siteLayout(origin);
  evidence.spawn = grounded;
  evidence.biome = await world.biome(server, origin).catch(() => null);

  await stageGround(server, layout);
  evidence.planted = await stageStructures(server, layout);

  await world.command(server, `tp ${playerName} ${layout.standBlock.x + 0.5} ${layout.standBlock.y} ${layout.standBlock.z + 0.5} 180 20`);
  await tick.sprint(server, 10);

  await tryCommand(server, `give ${playerName} folkways:colony_book`);
  await world.command(server, `item replace entity ${playerName} weapon.mainhand with folkways:colony_book`);
  await tick.sprint(server, 5);
  const founded = await foundPromoColonyFast(server, playerName, [
    ...layout.quarters.bedFeet,
    ...memberChests(layout),
    ...layout.camp.stations.map(one => one.at),
  ]);
  evidence.steps.push(founded);

  for (const plot of layout.farm.plots) {
    await settleClient(server, client);
    await assertZoneRowClickable(server, `farm plot ${plot.name}`);
    evidence.steps.push(await markZone(server, client, playerName, layout, plot.zone, "farm",
      [{ key: "crop", option: plot.crop }]));
  }
  for (const plan of layout.pasture.pens) {
    await settleClient(server, client);
    const index = await assertZoneRowClickable(server, `${plan.name} pen`);
    const pasture = await createPastureZoneViaPanel(server, client, playerName, layout.standBlock, plan.pen, {
      animal: plan.animal,
      target: plan.target,
      index,
    });
    evidence.steps.push(pasture);
    if (!pasture.created) {
      throw new Error(`The ${plan.name} pen was not created: ${pastureZoneFailure(pasture, plan.animal, plan.target)}`);
    }
  }
  for (const zone of layout.fishery.zones) {
    await settleClient(server, client);
    evidence.steps.push(await markZone(server, client, playerName, layout, zone, "fish", []));
  }

  await stockChests(server, layout, options.residents);
  const admitted = await admitPromoSettlersFast(server, playerName, founded.colony_id, options.residents);
  const residents = admitted.residents;
  evidence.steps.push(admitted);
  evidence.residents = residents;

  await safeAction(() => screen.dismiss(client));
  evidence.demands = await setMaintainDemandsFast(server, playerName, demands(layout));
  evidence.steps.push(evidence.demands);

  evidence.outfit = await outfitCrew(server, layout);

  await tryCommand(server, "time set noon");
  await tick.sprint(server, 40);
  return { layout, colonyId: founded.colony_id, residents, evidence };
}

export async function armWork(server: any, layout: SiteLayout) {
  const armed: any = {};
  armed.farm = await cueFarm(server, layout);
  armed.camp = await cueCamp(server, layout, []);

  armed.pens = await stockPens(server, layout);
  armed.strays = await stockPaddock(server, layout);
  await tick.sprint(server, ARM_SETTLE_TICKS);
  return armed;
}

const ARM_SETTLE_TICKS = 10;

async function cueFarm(server: any, layout: SiteLayout) {
  const fallow = layout.farm.fallow;
  await fillCells(server, fallow.map((cell) => ({ ...cell, y: layout.origin.y })), "minecraft:air");
  const cued: any = { tilled_back: await fillCells(server, fallow, "minecraft:grass_block") };
  for (const stage of layout.farm.standing) {
    if (!stage.block.includes("age=7")) continue;
    cued[stage.block] = await fillCells(server, stage.cells, stage.block);
  }
  cued.cane = await plantCane(server, layout);
  cued.trees = await Promise.all(
    layout.farm.grove.trunks.map((trunk) =>
      tryCommand(server, `place feature ${layout.farm.grove.feature} ${commandPos(trunk)}`)),
  );
  return cued;
}

async function cuePasture(server: any, layout: SiteLayout) {
  const cued: any = {};
  const sheep = layout.pasture.pens.find((plan) => plan.animal === "sheep");
  if (sheep) {
    const centre = penCenter(sheep.pen, layout.origin.y);
    cued.shorn = await tryCommand(
      server,
      `execute as @e[type=minecraft:sheep,x=${centre.x},y=${centre.y},z=${centre.z},distance=..6] ` +
        `run data merge entity @s {Sheared:0b}`,
    );
  }
  cued.eggs = await Promise.all(
    layout.pasture.nests.map((nest) =>
      tryCommand(server, `summon minecraft:item ${nest.x + 0.5} ${nest.y} ${nest.z + 0.5} {Item:{id:"minecraft:egg",count:1}}`)),
  );
  return cued;
}

async function plantCane(server: any, layout: SiteLayout) {
  const filled: unknown[] = [];
  for (let level = 0; level < 3; level++) {
    filled.push(
      await fillCells(server, layout.farm.cane.stools.map((cell) => ({ ...cell, y: cell.y + level })), "minecraft:sugar_cane"),
    );
  }
  return filled;
}

async function cueFishery() {
  return { armed: false };
}

async function cueCamp(server: any, layout: SiteLayout, held: Held[]) {
  const cued: any = {};
  cued.emptied = await Promise.all(
    layout.camp.demands.map((demand) =>
      tryCommand(server, `data modify block ${commandPos(demand.at)} Items set value []`)),
  );
  cued.fires = await Promise.all(
    layout.camp.fires.map((fire) => tryCommand(server, `data modify block ${commandPos(fire)} Items set value []`)),
  );
  cued.drops = await Promise.all(
    COOK_SPILL.map((id, i) => {
      const at = layout.camp.spill[i % layout.camp.spill.length];
      return tryCommand(server, `summon minecraft:item ${at.x + 0.5} ${at.y} ${at.z + 0.5} {Item:{id:"${id}",count:1}}`);
    }),
  );
  if (held.length > 0) {
    cued.eater = held[0].uuid;
    cued.hunger = { staged: false, why: "hunger lives in the colony save; there is no command for it" };
  }
  return cued;
}

export type Standing = { uuid: string; at: Vec };

export async function residentPositions(server: any): Promise<Standing[]> {
  const result = await world.entities(server, {
    dimension: "minecraft:overworld",
    limit: 1000,
    types: ["folkways:resident"],
  });
  const standing: Standing[] = result.entities
    .filter((entity: any) => /folkways:resident$/.test(String(entity.type ?? "")) && entity.uuid && entity.position)
    .map((entity: any) => ({ uuid: String(entity.uuid), at: entity.position as Vec }));
  return standing;
}

export function inPatch(position: Vec, patch: Patch) {
  return position.x >= patch.minX && position.x <= patch.maxX + 1 && position.z >= patch.minZ && position.z <= patch.maxZ + 1;
}

export function census(standing: Standing[], sights: Sight[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const sight of sights) {
    counts[sight.name] = standing.filter((one) => inPatch(one.at, sight.presence)).length;
  }
  return counts;
}

const POSE_YAW = [-35, 35, -110, 110, -70, 70];
const PLACE_SETTLE_TICKS = 10;

// Each sight is staffed by the hands outfitted for it, in kit order, so a sight's posts listed in that order put
// each trade where its work is: the field hands on the verge, the grove hands by the trunks.
export async function holdWorkers(
  server: any,
  layout: SiteLayout,
  handed: Outfitted[],
  { settleTicks = PLACE_SETTLE_TICKS }: { settleTicks?: number } = {},
): Promise<{ held: Held[]; waited_ticks: number; best: Record<string, number>; benched: string[] }> {
  const sights = layout.track.sights;
  const standing = await residentPositions(server);
  const staged = new Set<string>();
  const held: Held[] = [];
  const best: Record<string, number> = {};

  for (const sight of sights) {
    const posts = fanOut(sight.posts, sight.crew);
    const crew = handed.filter((one) => kitOf(one.trade).sight === sight.name);
    if (crew.length < sight.crew) {
      throw new Error(
        `${sight.name} needs ${sight.crew} residents, but only ${crew.length} were outfitted for it` +
          ` (${standing.length} total; the resident count comes from crewTotal())`,
      );
    }
    for (let i = 0; i < crew.length; i++) {
      staged.add(crew[i].uuid);
      await place(server, crew[i].uuid, posts[i], POSE_YAW[i % POSE_YAW.length], kitOf(crew[i].trade).hold);
      held.push({ sight: sight.name, uuid: crew[i].uuid, at: posts[i] });
    }
    best[sight.name] = crew.length;
  }

  await tick.sprint(server, settleTicks);
  const benched = await benchIdle(server, layout, standing, staged);
  return { held, waited_ticks: settleTicks, best, benched };
}

function fanOut(posts: Vec[], want: number): Vec[] {
  const out: Vec[] = [];
  const taken = new Set<string>();
  for (let i = 0; out.length < want && i < posts.length * (want + 2); i++) {
    const base = posts[i % posts.length];
    const cell = { ...base, x: base.x + Math.floor(i / posts.length) };
    const key = `${cell.x},${cell.z}`;
    if (taken.has(key)) continue;
    taken.add(key);
    out.push(cell);
  }
  if (out.length < want) {
    throw new Error(`Only ${out.length} standing spots found here, need ${want} (${posts.length} hardcoded spots)`);
  }
  return out;
}

async function place(server: any, uuid: string, at: Vec, yaw: number, holdItem: string) {
  await world.command(server, `tp ${uuid} ${at.x + 0.5} ${at.y} ${at.z + 0.5} ${yaw} 0`);
  await freeze(server, uuid, holdItem);
}

async function benchIdle(server: any, layout: SiteLayout, standing: Standing[], staged: Set<string>) {
  const benched: string[] = [];
  const feet = layout.quarters.bedFeet;
  for (const one of standing) {
    if (staged.has(one.uuid)) continue;
    const home = feet[benched.length % feet.length];
    await tryCommand(server, `tp ${one.uuid} ${home.x + 0.5} ${home.y} ${home.z + 0.5}`);
    await tryCommand(server, `data merge entity ${one.uuid} {NoAI:1b}`);
    benched.push(one.uuid);
  }
  return benched;
}

function step(from: Vec, to: Vec) {
  return Math.hypot(from.x - to.x, from.z - to.z);
}

async function freeze(server: any, uuid: string, holdItem: string) {
  await world.command(server, `data merge entity ${uuid} {NoAI:1b}`);
  await tryCommand(server, `item replace entity ${uuid} weapon.mainhand with ${holdItem}`);
}

export async function castGolems(server: any, layout: SiteLayout, playerName: string, colony: string) {
  const spots = layout.camp.golems;
  const cast = await runColonyTask(server, GOLEM_CAST, {
    player: playerName,
    colony_id: colony,
    origin: layout.origin,
    freeze_humans: false,
    kinds: spots.map(() => "humanoid"),
    positions: spots,
    trades: ["crafting", "hauling"],
  });
  return cast;
}

// Metal and dog golems guard barehanded: one is cast at the start of each route, licensed to patrol and nothing else.
const PATROL_KINDS = ["metal", "dog", "metal", "dog"];

export async function castPatrol(server: any, layout: SiteLayout, playerName: string, colony: string) {
  const routes = await runColonyTask(server, PATROL_ROUTES, { colony_id: colony, routes: layout.patrol });
  const cast = await runColonyTask(server, GOLEM_CAST, {
    player: playerName,
    colony_id: colony,
    origin: layout.origin,
    freeze_humans: false,
    kinds: layout.patrol.map((_, i) => PATROL_KINDS[i % PATROL_KINDS.length]),
    positions: layout.patrol.map((route) => route[0]),
    trades: ["patrolling"],
  });
  return { routes: routes.routes, uuids: cast.uuids };
}

export async function releaseSight(server: any, layout: SiteLayout, sight: Sight, held: Held[]) {
  const mine = held.filter((one) => one.sight === sight.name);
  const cued = await sight.cue(server, layout, mine).catch((error: unknown) => ({ error: String(error) }));
  const thawed: unknown[] = [];
  for (const one of mine) {
    thawed.push(await tryCommand(server, `data merge entity ${one.uuid} {NoAI:0b}`));
  }
  return { sight: sight.name, cued, released: mine.length, thawed };
}

async function stageGround(server: any, layout: SiteLayout) {
  const { min, max } = layout.bounds;
  const origin = layout.origin;
  await world.command(server, `forceload add ${min.x} ${min.z} ${max.x} ${max.z}`);

  await fillVolume(server, { ...min, y: origin.y - 4 }, { ...max, y: origin.y - 2 }, "minecraft:dirt");
  await fillVolume(server, { ...min, y: layout.ground }, { ...max, y: layout.ground }, "minecraft:grass_block");
  await fillVolume(server, { ...min, y: origin.y }, { ...max, y: origin.y + WALL_HEIGHT + 3 }, "minecraft:air");

  for (const [a, b] of [
    [{ x: min.x, z: min.z }, { x: max.x, z: min.z }],
    [{ x: min.x, z: max.z }, { x: max.x, z: max.z }],
    [{ x: min.x, z: min.z }, { x: min.x, z: max.z }],
    [{ x: max.x, z: min.z }, { x: max.x, z: max.z }],
  ]) {
    await fillVolume(server, { x: a.x, y: origin.y, z: a.z }, { x: b.x, y: origin.y + WALL_HEIGHT - 1, z: b.z }, WALL_BLOCK);
  }

  const deck = { ...layout.lane.max };
  await fillVolume(server, { ...layout.lane.min, y: deck.y }, deck, "minecraft:smooth_stone");
  for (let x = layout.lane.min.x; x <= layout.lane.max.x; x += LANE_PILLAR_STRIDE) {
    await fillVolume(
      server,
      { x, y: layout.lane.min.y, z: layout.lane.min.z },
      { x, y: deck.y - 1, z: layout.lane.max.z },
      "minecraft:smooth_stone",
    );
  }
}

async function stageStructures(server: any, layout: SiteLayout) {
  const origin = layout.origin;

  for (const foot of layout.quarters.bedFeet) {
    await world.command(server, `setblock ${commandPos(foot)} minecraft:red_bed[part=foot,facing=east]`);
    await world.command(server, `setblock ${commandPos({ x: foot.x + 1, y: foot.y, z: foot.z })} minecraft:red_bed[part=head,facing=east]`);
  }

  await fillCells(server, layout.farm.tilth, "minecraft:farmland");
  await fillCells(server, layout.farm.fallow, "minecraft:grass_block");
  await fillCells(server, layout.farm.springs, "minecraft:water");
  for (const stage of layout.farm.standing) {
    await fillCells(server, stage.cells, stage.block);
  }
  await fillCells(server, layout.farm.verge, "minecraft:short_grass");

  await fillCells(server, layout.farm.cane.channel, "minecraft:water");

  const grove: Record<string, number> = {};
  for (const trunk of layout.farm.grove.trunks) {
    const result = await tryCommand(server, `place feature ${layout.farm.grove.feature} ${commandPos(trunk)}`);
    grove[layout.farm.grove.feature] = (grove[layout.farm.grove.feature] ?? 0) + (succeeded(result) ? 1 : 0);
  }

  for (const plan of layout.pasture.pens) {
    await buildPen(server, plan.pen);
  }
  await fillCells(server, layout.pasture.paddock.grazed, "minecraft:coarse_dirt");
  await fillCells(server, layout.pasture.paddock.fence, "minecraft:oak_fence");
  for (const bale of layout.pasture.hay) {
    await world.command(server, `setblock ${commandPos({ ...bale, y: origin.y })} minecraft:hay_block`);
  }

  await fillCells(server, layout.fishery.shore, "minecraft:sand");
  await fillCells(server, layout.fishery.bed, "minecraft:gravel");
  await fillCells(server, layout.fishery.pond, "minecraft:water");
  await fillCells(server, layout.fishery.pond.map((cell) => ({ ...cell, y: cell.y - 1 })), "minecraft:water");

  await fillCells(server, layout.camp.clearing, "minecraft:coarse_dirt");
  await fillCells(server, layout.camp.grit, "minecraft:gravel");
  for (const fire of layout.camp.fires) {
    await world.command(server, `setblock ${commandPos(fire)} minecraft:campfire[lit=true,facing=south]`);
  }
  for (const station of layout.camp.stations) {
    await world.command(server, `setblock ${commandPos(station.at)} ${station.block}`);
  }
  for (const log of layout.camp.logs) {
    await world.command(server, `setblock ${commandPos(log)} minecraft:oak_log[axis=x]`);
  }

  const doubled = new Set(doubleChests(layout).map(commandPos));
  for (const chest of memberChests(layout)) {
    if (!doubled.has(commandPos(chest))) {
      await world.command(server, `setblock ${commandPos(chest)} minecraft:chest[facing=south]{Items:[]}`);
      continue;
    }
    // Facing south, the west half joins east as the right half.
    await world.command(server, `setblock ${commandPos(chest)} minecraft:chest[facing=south,type=right]{Items:[]}`);
    await world.command(server, `setblock ${commandPos({ ...chest, x: chest.x + 1 })} minecraft:chest[facing=south,type=left]{Items:[]}`);
  }

  const planted: Record<string, number> = { ...grove };
  for (const one of layout.scenery) {
    const result = await tryCommand(server, `place feature ${one.feature} ${commandPos(one.at)}`);
    planted[one.feature] = (planted[one.feature] ?? 0) + (succeeded(result) ? 1 : 0);
  }

  await fillCells(server, markCells(layout), "minecraft:air");

  await tick.sprint(server, 5);
  return planted;
}

export type Outfitted = { trade: string; uuid: string };

function kitOf(trade: string): CrewKit {
  const kit = CREW_KITS.find((one) => one.trade === trade);
  if (!kit) {
    throw new Error(`No kit in CREW_KITS for the ${trade} trade`);
  }
  return kit;
}

async function outfitCrew(server: any, layout: SiteLayout) {
  const crew = await residentPositions(server);
  const handed: Outfitted[] = [];
  let next = 0;
  for (const kit of CREW_KITS) {
    const post = kit.post(layout);
    for (let hand = 0; hand < kit.hands && next < crew.length; hand++, next++) {
      const uuid = crew[next].uuid;
      for (let i = 0; i < kit.items.length; i++) {
        const [item, count] = kit.items[i];
        await world.command(
          server,
          `data modify entity ${uuid} "neoforge:attachments"."folkways:body_state".Pack.Items append value ` +
            `{Slot:${KIT_SLOT + i}b,Stack:{id:"${item}",count:${count}}}`,
        );
      }
      await tryCommand(server, `tp ${uuid} ${post.x + 0.5 + hand} ${post.y} ${post.z + 0.5}`);
      handed.push({ trade: kit.trade, uuid });
    }
  }
  const licensed = await licenseCrewFast(
    server,
    handed.map((one) => ({ uuid: one.uuid, trades: kitOf(one.trade).licences })),
  );
  await tick.sprint(server, 5);
  return { crew: crew.length, handed, wanted: crewTotal(), licensed: licensed.licensed };
}

async function stockChests(server: any, layout: SiteLayout, residents: number) {
  const food = Math.max(2, Math.ceil((residents * 16) / 64));
  let slot = 0;
  for (let i = 0; i < food; i++) {
    await world.command(server, `item replace block ${commandPos(layout.quarters.foodChest)} container.${slot++} with ${FOOD} 64`);
  }

  slot = 0;
  for (const [tool, count] of [
    ["minecraft:wooden_hoe", 2],
    ["minecraft:shears", 1],
    ["minecraft:fishing_rod", 1],
    ["minecraft:stone_pickaxe", 1],
    ["minecraft:stone_axe", 1],
    ["minecraft:stone_shovel", 1],
  ] as Array<[string, number]>) {
    for (let i = 0; i < count; i++) {
      await world.command(server, `item replace block ${commandPos(layout.quarters.toolChest)} container.${slot++} with ${tool} 1`);
    }
  }

  for (const store of layout.stores) {
    for (let i = 0; i < store.items.length; i++) {
      const [item, count] = store.items[i];
      await world.command(server, `item replace block ${commandPos(store.at)} container.${i} with ${item} ${count}`);
    }
  }

  await tick.sprint(server, 5);
}

async function stockPens(server: any, layout: SiteLayout) {
  const filled: any[] = [];
  for (const plan of layout.pasture.pens) {
    const type = new RegExp(`${plan.animal}$`);
    const centre = penCenter(plan.pen, layout.origin.y);
    const before = await countAnimalsInPen(server, plan.pen, type);
    for (let i = before; i < plan.heads; i++) {
      await world.command(server, `summon minecraft:${plan.animal} ${centre.x} ${centre.y} ${centre.z} {Age:0}`);
    }
    filled.push({ pen: plan.name, before, after: await countAnimalsInPen(server, plan.pen, type) });
  }
  await tick.sprint(server, 5);
  return filled;
}

async function stockPaddock(server: any, layout: SiteLayout) {
  const spots = layout.pasture.paddock.strays;
  const kinds = layout.pasture.pens.map((plan) => plan.animal);
  for (let i = 0; i < spots.length; i++) {
    const spot = spots[i];
    await tryCommand(server, `summon minecraft:${kinds[i % kinds.length]} ${spot.x + 0.5} ${spot.y} ${spot.z + 0.5}`);
  }
  await tick.sprint(server, 5);
  return { summoned: spots.length };
}

const ZONE_ROWS_CLICKABLE = 9;

async function assertZoneRowClickable(server: any, what: string): Promise<number> {
  const before = await zoneCount(server, await colonyRegistry(server));
  if (before + 1 > ZONE_ROWS_CLICKABLE) {
    throw new Error(
      `${what} would be zone #${before + 1}, but on the Zones page only the first ${ZONE_ROWS_CLICKABLE} rows have their centers `
        + "inside the scroll area, and row order is random by UUID. Adding more is betting it does not land outside the viewport. Either build the zones "
        + "that do not need the settings panel last, or teach zone-tools to scroll a row into view before clicking.",
    );
  }
  return before;
}

export async function markZone(
  server: any,
  client: any,
  playerName: string,
  layout: SiteLayout,
  box: { min: Vec; max: Vec },
  kind: "farm" | "fish",
  edits: ZoneEdit[],
) {
  const evidence: any = { kind, box_min: box.min, box_max: box.max, edits };
  evidence.mode = await setBookGesture(server, client, playerName, "box");
  await drainActionBar(client);
  evidence.corner_1 = await markCorner(server, client, box.min, FIRST_CORNER,
    () => safeAction(() => player.clickBlock({ server, client }, box.min, { player: playerName })));
  evidence.corner_2 = await markCorner(server, client, box.max, ZONE_MARKED,
    () => safeAction(() => player.clickBlock({ server, client }, box.max, { player: playerName })));
  await tick.sprint(server, 5);
  evidence.create = await createMarkedZone(server, client, playerName, layout.standBlock, kind);
  if (edits.length > 0) {
    evidence.configure = await configureZone(server, client, playerName, layout.standBlock,
      { min: box.min, max: box.max }, edits);
  }
  await screen.dismiss(client);
  return evidence;
}

async function fillVolume(server: any, min: Vec, max: Vec, block: string) {
  const area = (Math.abs(max.x - min.x) + 1) * (Math.abs(max.z - min.z) + 1);
  const step = Math.max(1, Math.floor(FILL_LIMIT / area));
  const low = Math.min(min.y, max.y);
  const high = Math.max(min.y, max.y);
  let last: CommandOutcome | undefined;
  for (let y = low; y <= high; y += step) {
    const command = `fill ${commandPos({ ...min, y })} ${commandPos({ ...max, y: Math.min(y + step - 1, high) })} ${block}`;
    last = await tryCommand(server, command);
    if (succeeded(last)) continue;
    if (/No blocks were filled/.test((last?.output ?? []).join(" "))) continue;
    throw new Error(`${command} failed: ${JSON.stringify(last)}`);
  }
  return last;
}

const FILL_LIMIT = 32768;

function succeeded(result: CommandOutcome): boolean {
  return result.status === "ok" && result.success;
}
