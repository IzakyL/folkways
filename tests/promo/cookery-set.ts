import { world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

const K = "kaleidoscope_cookery:";

/** A compact front-cutaway inn: dense kitchen work below, camera-facing diners above, lift to the right. */
export async function dressCookeryGarden(server: MinecraftServer, origin: Vec) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const set = async (x: number, z: number, y: number, block: string) =>
    world.command(server, `setblock ${commandPos(at(x, z, y))} ${block}`);
  const fill = async (x: number, z: number, y: number, xx: number, zz: number, yy: number, block: string) =>
    tryCommand(server, `fill ${commandPos(at(x, z, y))} ${commandPos(at(xx, zz, yy))} ${block}`);
  const plaster = "minecraft:white_terracotta";
  const pale = "minecraft:smooth_sandstone";
  const timber = "minecraft:stripped_dark_oak_log[axis=y]";
  const beam = "minecraft:stripped_dark_oak_log[axis=x]";

  await fill(-32, -14, 0, -1, 36, 16, "minecraft:air");
  await fill(0, -14, 0, 32, 36, 16, "minecraft:air");
  // The shallower room brings the working counters into the front opening. The lift column is untouched.
  await fill(-20, -8, -1, -3, 4, -1, "minecraft:stone_bricks");
  await fill(-18, -6, -1, -3, 3, -1, pale);
  await fill(-17, -5, -1, -4, 2, -1, "minecraft:polished_andesite");
  await fill(-18, -6, 5, -3, 3, 5, "minecraft:dark_oak_planks");
  await fill(-17, -5, 5, -4, 2, 5, "minecraft:spruce_planks");
  for (const y of [0, 6]) {
    await fill(-18, -6, y, -18, 3, y + 3, plaster);
    await fill(-18, 3, y, -3, 3, y + 3, plaster);
    for (const x of [-16, -12, -8, -5]) await fill(x, 3, y + 1, x + 1, 3, y + 2, "minecraft:glass_pane");
    await fill(-18, -3, y + 1, -18, 0, y + 2, "minecraft:glass_pane");
  }
  for (const [x, z] of [[-18, -6], [-3, -6], [-18, 3], [-3, 3]] as const) {
    await fill(x, z, 0, x, z, 9, timber);
    await set(x, z, -1, "minecraft:cut_sandstone");
  }
  for (const x of [-13, -8]) await fill(x, 3, 0, x, 3, 9, timber);
  await fill(-18, 3, 4, -3, 3, 4, beam);
  await fill(-18, 3, 9, -3, 3, 9, beam);
  await fill(-18, -6, 10, -3, 3, 10, "minecraft:dark_oak_planks");
  await fill(-19, -6, 11, -3, 4, 11, "minecraft:weathered_cut_copper");
  await fill(-18, -5, 12, -4, 3, 12, "minecraft:weathered_cut_copper_slab[type=bottom]");
  // No rail or ornament crosses the north dining opening: the food, faces and hands must all be visible.
  await fill(-3, -2, 6, -3, 2, 6, "minecraft:spruce_fence");
  for (const x of [-17, -4]) await set(x, -6, 9, "minecraft:lantern[hanging=true]");
  // Lamps hang high under the real upper floor, lighting the chefs without covering their faces.
  for (const x of [-13, -9, -5]) await set(x, -2, 4, "minecraft:lantern[hanging=true]");
  for (const x of [-15, -10, -5]) await set(x, 2, 9, "minecraft:lantern[hanging=true]");

  const groundEntry = at(-3, -4);
  const dining = {
    tables: [] as Vec[],
    chairs: [] as Vec[],
    cells: [] as Vec[],
    entry: at(-3, -4, 6),
  };
  // One joined banquet table, with fourteen inward-facing chairs and a clear aisle all around.
  for (let x = -15; x <= -7; x++) for (const z of [-3, -2]) {
    await set(x, z, 6, K + "table_spruce");
    dining.tables.push(at(x, z, 6));
  }
  const chair = async (x: number, z: number, facing: string) => {
    await set(x, z, 6, K + `chair_spruce[facing=${facing}]`);
    dining.chairs.push(at(x, z, 6));
  };
  for (const x of [-15, -13, -11, -9, -7]) {
    await chair(x, -4, "south");
    await chair(x, -1, "north");
  }
  for (const z of [-3, -2]) {
    await chair(-16, z, "east");
    await chair(-6, z, "west");
  }
  for (let x = -17; x <= -5; x++) for (let z = -5; z <= 1; z++) {
    dining.cells.push(at(x, z, 5), at(x, z, 6));
  }
  for (const x of [-16, -10, -5]) await set(x, 2, 6, "minecraft:potted_fern");

  // Six real appliances will be installed at x=-12,-11,-9,-8,-6,-5,z=-4 by the take.
  // Leave both operator aisles z=-5 and z=-3 open; the gaps become small worktop spaces.
  await fill(-12, -4, 0, -5, -4, 0, "minecraft:bricks");
  for (const x of [-10, -7]) await set(x, -4, 1, "minecraft:smooth_stone_slab[type=bottom]");
  // The rear preparation counter avoids the mill's entire x=-15..-13,z=0..2 footprint.
  await fill(-12, 2, 0, -5, 2, 0, "minecraft:polished_andesite");
  await fill(-11, 0, 0, -9, 0, 0, "minecraft:stripped_spruce_log[axis=x]");
  for (const x of [-11, -9]) await set(x, 0, 1, "minecraft:smooth_stone_slab[type=bottom]");
  await set(-12, 2, 1, K + "enamel_basin");
  await set(-11, 2, 1, K + "fruit_basket[facing=north]");
  await set(-9, 2, 1, K + "oil_pot[facing=north]");
  // These are declared native display dishes, distinct from the real order and dining deliveries.
  const displayFood = [
    { at: at(-8, 2, 1), block: K + "dongpo_pork[facing=north,bites=0,quality=0]" },
    { at: at(-6, 2, 1), block: K + "baozi_plate[facing=north,servings=5]" },
  ];
  for (const dish of displayFood) await world.command(server, `setblock ${commandPos(dish.at)} ${dish.block}`);
  await set(-10, 2, 2, K + "kitchenware_racks[facing=north]");
  for (const x of [-16, -7, -4]) {
    await set(x, 3, 3, K + "chili_ristra[is_head=true]");
    await set(x, 3, 2, K + "chili_ristra[is_head=false]");
  }
  // Supplies remain on the left at x=-17,z=-4,-2,0,2. Shelves sit two blocks above each real chest.
  for (const z of [-4, -2, 0, 2]) await set(-17, z, 2, "minecraft:spruce_slab[type=bottom]");

  // Keep the farmer's right courtyard open: two real fields x=6..11,z=-6..-3 and z=1..4,
  // the central pond x=6..8,z=-2..0, and tool/produce barrels at x=5,z=-5/3 belong to cookery-work.ts.
  await fill(4, -7, -1, 12, 5, -1, "minecraft:stone_bricks");
  await fill(5, -6, -1, 11, 4, -1, pale);
  await fill(5, -3, -2, 9, 1, -1, "minecraft:cut_sandstone");
  await fill(6, -2, -1, 8, 0, -1, "minecraft:water");
  await set(7, -1, 0, "minecraft:lily_pad");
  // The open forecourt has low food stalls in the centre, livestock to the west and a
  // fishing pond to the east. Nothing tall interrupts the kitchen or restaurant sightline.
  await fill(-28, -14, -1, 13, -7, -1, "minecraft:stone_bricks");
  await fill(-27, -13, -1, 12, -8, -1, pale);
  await fill(-17, -11, -1, -4, -7, -1, "minecraft:polished_andesite");
  await fill(-16, -10, -1, -5, -7, -1, "minecraft:polished_diorite");
  await fill(-18, -14, -1, 3, -12, -1, "minecraft:gravel");
  // Little paving insets separate the chopping, tea and roasting bays without walls.
  for (const x of [-14, -10, -6]) await fill(x - 1, -9, -1, x + 1, -7, -1, "minecraft:smooth_sandstone");
  // A compact left woodshed and grain reserve make the kitchen's supply side read as a working inn.
  await fill(-23, -5, -1, -20, 2, -1, "minecraft:stone_bricks");
  for (const [x, z] of [[-23, -5], [-20, -5], [-23, 2], [-20, 2]] as const) {
    await fill(x, z, 0, x, z, 2, "minecraft:stripped_spruce_log[axis=y]");
  }
  await fill(-23, -5, 3, -20, 2, 3, "minecraft:spruce_slab[type=bottom]");
  await fill(-23, 0, 0, -21, 1, 0, "minecraft:spruce_log[axis=z]");
  for (const x of [-23, -22]) await set(x, -3, 0, "minecraft:hay_block");
  await set(-21, -3, 0, "minecraft:barrel[facing=up]");
  await set(-21, 1, 2, "minecraft:lantern[hanging=true]");
  for (const [x, z] of [[-28, -13], [12, -13], [12, 5]] as const) await set(x, z, 0, "minecraft:lantern");

  // All trees are behind the house or far outside its silhouette. Layered foliage replaces foreground obstructions.
  for (const [x, z, h] of [[-31, 6, 13], [-34, 21, 14], [-27, 12, 11], [-24, 30, 13],
    [-17, 19, 12], [-7, 18, 14], [4, 17, 12], [15, 10, 11], [18, 7, 12], [22, 18, 15]] as const) {
    await fill(x, z, 0, x, z, h, "minecraft:spruce_log[axis=y]");
    for (let layer = 0; layer < 7; layer++) {
      const radius = layer < 3 ? 2 : layer < 5 ? 1 : 0;
      const y = h - 5 + layer;
      await fill(x - radius, z - radius, y, x + radius, z + radius, y, "minecraft:spruce_leaves[persistent=true]");
      if (radius > 0) for (const dx of [-radius, radius]) for (const dz of [-radius, radius]) await set(x + dx, z + dz, y, "minecraft:air");
    }
    await fill(x, z, 0, x, z, h - 1, "minecraft:spruce_log[axis=y]");
  }
  await fill(-21, 10, -1, 11, 10, 0, "minecraft:mossy_stone_bricks");
  for (const [x, z] of [[-28, 2], [-26, 4], [13, 6], [16, 7]] as const) {
    await set(x, z, 0, "minecraft:flowering_azalea");
    await set(x + 1, z, 0, "minecraft:fern");
  }
  for (const [x, z] of [[-17, 13], [-9, 13], [1, 13]] as const) {
    await fill(x - 2, z - 1, -1, x + 2, z + 1, -1, "minecraft:farmland[moisture=7]");
    await fill(x - 2, z - 1, 0, x + 2, z + 1, 0, "minecraft:wheat[age=7]");
  }
  // A small enclosed bunkhouse sits behind the inn; its real beds stay out of the foreground.
  await fill(-18, 7, -1, -3, 12, -1, "minecraft:stone_bricks");
  await fill(-17, 8, 0, -4, 11, 2, "minecraft:air");
  await fill(-18, 7, 0, -18, 12, 2, plaster);
  await fill(-3, 7, 0, -3, 12, 2, plaster);
  await fill(-18, 7, 0, -3, 7, 2, plaster);
  await fill(-18, 12, 0, -3, 12, 2, plaster);
  await fill(-18, 7, 3, -3, 12, 3, "minecraft:dark_oak_planks");
  await fill(-18, 9, 0, -18, 9, 1, "minecraft:air");
  await set(-10, 10, 2, "minecraft:lantern[hanging=true]");
  const beds = [-15, -13, -11, -9, -7, -5, -3].flatMap(x => [at(x - 1, 8), at(x - 1, 11)]);
  beds.push(at(-4, 9));
  for (const bed of beds) {
    await world.command(server, `setblock ${commandPos(bed)} minecraft:white_bed[part=foot,facing=west]`);
    await world.command(server, `setblock ${commandPos({ ...bed, x: bed.x - 1 })} minecraft:white_bed[part=head,facing=west]`);
  }
  return { beds, dining, groundEntry, displayFood, theme: "front-cutaway two-storey white-plaster inn: open kitchen below the dining balcony, with dark timber, green copper and a courtyard lift" };
}
