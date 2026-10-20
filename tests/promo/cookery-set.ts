import { world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

const K = "kaleidoscope_cookery:";

/** An outdoor garden kitchen, with clear aisles around every real workstation. */
export async function dressCookeryGarden(server: MinecraftServer, origin: Vec) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const set = async (x: number, z: number, y: number, block: string) =>
    world.command(server, `setblock ${commandPos(at(x, z, y))} ${block}`);
  const fill = async (x: number, z: number, y: number, xx: number, zz: number, yy: number, block: string) =>
    tryCommand(server, `fill ${commandPos(at(x, z, y))} ${commandPos(at(xx, zz, yy))} ${block}`);

  await fill(-32, -12, 0, -1, 36, 12, "minecraft:air");
  await fill(0, -12, 0, 32, 36, 12, "minecraft:air");
  // A gravel approach and a worn, bordered patio, surrounded by grass rather than a bare square platform.
  await fill(-10, 0, -1, 9, 18, -1, "minecraft:stone_bricks");
  await fill(-9, 1, -1, 8, 17, -1, "minecraft:smooth_sandstone");
  await fill(-7, 4, -1, 6, 15, -1, "minecraft:polished_andesite");
  await fill(-2, -10, -1, 2, 0, -1, "minecraft:gravel");
  for (let x = -8; x <= 7; x += 3) for (let z = 2; z <= 16; z += 3) {
    if ((x + z) % 2 === 0) await set(x, z, -1, "minecraft:andesite");
  }
  // Low plaster garden wall. The open timber lattice above it frames the herb beds and trees.
  await fill(-9, 18, 0, 9, 18, 0, "minecraft:bricks");
  await fill(-9, 18, 1, 9, 18, 1, "minecraft:smooth_sandstone");
  await fill(-9, 18, 2, 9, 18, 2, "minecraft:spruce_trapdoor[facing=north,half=bottom,open=true]");
  for (const x of [-9, -1, 8]) {
    await fill(x, 17, 0, x, 17, 4, "minecraft:stripped_spruce_log[axis=y]");
  }
  await fill(-9, 17, 5, 8, 17, 5, "minecraft:spruce_log[axis=x]");
  await fill(-9, 14, 5, 8, 14, 5, "minecraft:spruce_log[axis=x]");
  for (let x = -9; x <= 8; x += 2) {
    await fill(x, 14, 6, x, 18, 6, "minecraft:spruce_slab[type=bottom]");
  }
  for (const x of [-7, 2, 6]) await set(x, 17, 4, "minecraft:lantern[hanging=true]");
  // Continuous L-shaped counters: the native stoves will sit in the long eastern run.
  await fill(4, 7, 0, 5, 16, 0, "minecraft:bricks");
  await fill(-7, 16, 0, 5, 16, 0, "minecraft:polished_andesite");
  for (let z = 7; z <= 16; z++) await set(5, z, 1, "minecraft:smooth_stone_slab[type=bottom]");
  for (const z of [7, 9, 10, 12, 13, 15]) await set(4, z, 1, "minecraft:smooth_stone_slab[type=bottom]");
  for (let x = -7; x <= 3; x++) {
    await set(x, 16, 1, "minecraft:smooth_stone_slab[type=bottom]");
    await set(x, 15, 0, "minecraft:spruce_trapdoor[facing=south,half=bottom,open=true]");
  }
  // Preparation island, with the crafting table and the chopping board inset into its top.
  await fill(-3, 5, 0, 1, 5, 0, "minecraft:stripped_spruce_log[axis=x]");
  for (const x of [-2, -1, 1]) await set(x, 5, 1, "minecraft:smooth_stone_slab[type=bottom]");
  await set(1, 5, 1, K + "fruit_basket[facing=south]");
  // Serving counter and stores. Storage remains reachable from the central aisle.
  await fill(0, 2, 0, 3, 2, 0, "minecraft:stripped_spruce_log[axis=x]");
  for (const x of [0, 2, 3]) await set(x, 2, 1, "minecraft:spruce_slab[type=bottom]");
  await fill(-7, 3, 0, -7, 8, 0, "minecraft:spruce_planks");
  await fill(-7, 3, 2, -7, 8, 2, "minecraft:spruce_slab[type=bottom]");
  for (const z of [3, 8]) await set(-7, z, 1, "minecraft:spruce_fence");
  await set(-7, 4, 1, "minecraft:barrel[facing=east]");
  await set(-7, 6, 1, "minecraft:barrel[facing=east]");
  await set(-7, 7, 1, K + "fruit_basket");
  await set(-8, 5, 0, "minecraft:hay_block");
  await set(-8, 6, 0, "minecraft:hay_block");
  // A sink, oil crock and hanging chillies make the rear counter read as a working kitchen.
  await set(-5, 16, 1, K + "enamel_basin");
  await set(-3, 16, 1, K + "oil_pot[facing=south]");
  await set(1, 16, 1, "minecraft:flower_pot");
  for (const x of [-5, -3, 4]) {
    await set(x, 17, 4, K + "chili_ristra[is_head=true]");
    await set(x, 17, 3, K + "chili_ristra[is_head=false]");
  }
  // A little outdoor dining area, well outside the mill's three-by-three footprint.
  for (const x of [-11, -10]) {
    await set(x, 12, 0, K + "table_spruce");
    await set(x, 10, 0, K + "chair_spruce[facing=south]");
  }
  await set(-11, 12, 1, K + "fruit_basket");
  await set(-10, 12, 1, "minecraft:lantern");
  // Raised herb and vegetable beds, flower borders and a small pond soften the kitchen's edges.
  for (const [x, z] of [[-6, 21], [0, 21], [6, 21]] as const) {
    await fill(x - 2, z - 1, -1, x + 2, z + 1, -1, "minecraft:farmland[moisture=7]");
    await fill(x - 2, z - 1, 0, x + 2, z + 1, 0, "minecraft:wheat[age=7]");
    await fill(x - 2, z + 2, 0, x + 2, z + 2, 0, "minecraft:spruce_trapdoor[facing=north,open=true]");
  }
  for (const x of [-10, 9]) {
    for (let z = 2; z <= 17; z++) {
      await set(x, z, 0, "minecraft:oak_leaves[persistent=true]");
      if (z % 3 === 0) await set(x, z, 1, "minecraft:potted_fern");
    }
  }
  await fill(12, 6, -1, 16, 12, -1, "minecraft:clay");
  await fill(13, 7, -1, 15, 11, -1, "minecraft:water");
  for (const [x, z] of [[12, 6], [16, 9], [14, 12]] as const) await set(x, z, 0, "minecraft:mossy_cobblestone_slab[type=bottom]");
  // Layered, irregular tree crowns replace the empty flat-world horizon with a garden grove.
  for (const [i, [x, z]] of [[-17, 16], [-12, 27], [0, 29], [12, 26], [21, 15], [-24, 29], [25, 32]].entries()) {
    const h = 6 + i % 3;
    await fill(x, z, 0, x, z, h, "minecraft:oak_log[axis=y]");
    await fill(x - 3, z - 2, h - 1, x + 3, z + 2, h + 1, "minecraft:oak_leaves[persistent=true]");
    await fill(x - 2, z - 3, h, x + 2, z + 3, h + 2, "minecraft:oak_leaves[persistent=true]");
    await fill(x - 1, z - 1, h + 3, x + 1, z + 1, h + 3, "minecraft:oak_leaves[persistent=true]");
  }
  for (const [x, z] of [[-14, 2], [12, 2], [17, 19], [-14, 23], [5, 25]] as const) {
    await fill(x - 1, z, 0, x + 1, z + 1, 0, "minecraft:azalea_leaves[persistent=true]");
    await set(x, z, 1, "minecraft:flowering_azalea");
  }
  // The residents' beds are behind the western garden, outside the kitchen composition.
  const beds = [8, 10, 12].map(z => at(-17, z));
  for (const bed of beds) {
    await world.command(server, `setblock ${commandPos(bed)} minecraft:white_bed[part=foot,facing=west]`);
    await world.command(server, `setblock ${commandPos({ ...bed, x: bed.x - 1 })} minecraft:white_bed[part=head,facing=west]`);
  }
  return { beds, theme: "open-air garden kitchen with stove bank, prep island, pergola and pantry" };
}
