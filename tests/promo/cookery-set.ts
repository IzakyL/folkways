import { world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

const K = "kaleidoscope_cookery:";

/** A front-cutaway two-storey inn: working kitchen below, dining room above, lift on its right. */
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
  // The building is one compact volume viewed through its entirely open north face.
  // No scenery enters the lift builder's x=-2..2,z=-6..-2 column, even below its cabin.
  await fill(-20, -8, -1, -3, 8, -1, "minecraft:stone_bricks");
  await fill(-18, -6, -1, -3, 7, -1, pale);
  await fill(-17, -5, -1, -4, 6, -1, "minecraft:polished_andesite");
  await fill(-18, -6, 5, -3, 7, 5, "minecraft:dark_oak_planks");
  await fill(-17, -5, 5, -4, 6, 5, "minecraft:spruce_planks");
  // White side and rear walls enclose both rooms. There is no front wall or central facade pillar.
  for (const y of [0, 6]) {
    await fill(-18, -6, y, -18, 7, y + 3, plaster);
    await fill(-18, 7, y, -3, 7, y + 3, plaster);
    for (const x of [-16, -12, -8, -5]) await fill(x, 7, y + 1, x + 1, 7, y + 2, "minecraft:glass_pane");
    for (const z of [-3, 2]) await fill(-18, z, y + 1, -18, z + 2, y + 2, "minecraft:glass_pane");
  }
  // Only the outer front posts interrupt the cutaway: both levels and all their actors remain readable.
  for (const [x, z] of [[-18, -6], [-3, -6], [-18, 7], [-3, 7]] as const) {
    await fill(x, z, 0, x, z, 9, timber);
    await set(x, z, -1, "minecraft:cut_sandstone");
  }
  for (const x of [-13, -8]) await fill(x, 7, 0, x, 7, 9, timber);
  await fill(-18, 7, 4, -3, 7, 4, beam);
  await fill(-18, 7, 9, -3, 7, 9, beam);
  // The upper floor is the kitchen's ceiling. A single copper crown covers the inn, never a separate kitchen roof.
  await fill(-18, -6, 10, -3, 7, 10, "minecraft:dark_oak_planks");
  await fill(-19, -6, 11, -3, 8, 11, "minecraft:weathered_cut_copper");
  await fill(-18, -5, 12, -4, 7, 12, "minecraft:weathered_cut_copper_slab[type=bottom]");
  // A low front balcony edge leaves seated heads and hands above it; the eastern lift door is fully open.
  await fill(-17, -6, 6, -4, -6, 6, "minecraft:spruce_trapdoor[facing=north,open=true]");
  await fill(-3, -2, 6, -3, 6, 6, "minecraft:spruce_fence");
  for (const x of [-17, -4]) await set(x, -6, 7, "minecraft:lantern");
  for (const x of [-15, -10, -5]) {
    await set(x, 6, 3, "minecraft:lantern[hanging=true]");
    await set(x, 6, 8, "minecraft:lantern[hanging=true]");
  }

  const groundEntry = at(-3, -4);
  const dining = {
    tables: [-15, -11, -7].map(x => at(x, -3, 6)),
    chairs: [-15, -11, -7].map(x => at(x, -4, 6)),
    cells: [] as Vec[],
    entry: at(-3, -4, 6),
  };
  for (const x of [-15, -11, -7]) {
    await set(x, -3, 6, K + "table_spruce");
    await set(x, -4, 6, K + "chair_spruce[facing=south]");
  }
  for (let x = -16; x <= -5; x++) for (let z = -5; z <= 2; z++) {
    dining.cells.push(at(x, z, 5), at(x, z, 6));
  }
  // Small furnishings sit against the rear walls, never between the camera and the actual dining seats.
  for (const x of [-16, -10, -5]) {
    await set(x, 5, 6, "minecraft:spruce_trapdoor[facing=south,open=true]");
    await set(x, 5, 7, "minecraft:potted_fern");
  }

  // Kitchen positions are installed by cookery.take.ts. Keep the mill's full x=-13..-11,z=3..5 clear.
  // Right-hand brick supports frame the native stoves at x=-6 without hiding the cooking aisle on their west.
  await fill(-5, 1, 0, -5, 5, 0, "minecraft:bricks");
  await fill(-16, 6, 0, -5, 6, 0, "minecraft:polished_andesite");
  for (let x = -16; x <= -5; x++) await set(x, 6, 1, "minecraft:smooth_stone_slab[type=bottom]");
  await fill(-10, 0, 0, -8, 0, 0, "minecraft:stripped_spruce_log[axis=x]");
  for (const x of [-10, -8]) await set(x, 0, 1, "minecraft:smooth_stone_slab[type=bottom]");
  await set(-15, 6, 1, K + "enamel_basin");
  await set(-9, 6, 1, K + "oil_pot[facing=north]");
  await set(-5, 6, 1, K + "fruit_basket[facing=north]");
  for (const x of [-15, -10, -5]) {
    await set(x, 7, 3, K + "chili_ristra[is_head=true]");
    await set(x, 7, 2, K + "chili_ristra[is_head=false]");
  }
  // Shelves behind the four supply chests add kitchen scale without occupying their own slots or approach.
  for (const z of [-3, -1, 1, 3]) {
    await set(-17, z, 0, "minecraft:spruce_planks");
    await set(-17, z, 2, "minecraft:spruce_slab[type=bottom]");
  }

  // A low right-hand courtyard balances the tall left wing and elevator without competing with their silhouettes.
  await fill(4, -6, -1, 11, 8, -1, "minecraft:stone_bricks");
  await fill(5, -5, -1, 10, 7, -1, pale);
  await fill(5, -3, -2, 9, 1, -1, "minecraft:cut_sandstone");
  await fill(6, -2, -1, 8, 0, -1, "minecraft:water");
  await set(7, -1, 0, "minecraft:lily_pad");
  // This garden shade is airy and only three blocks high; no new upper storey appears beside the lift.
  for (const x of [5, 10]) for (const z of [4, 7]) await fill(x, z, 0, x, z, 2, "minecraft:stripped_spruce_log[axis=y]");
  for (const z of [4, 6, 7]) await fill(5, z, 3, 10, z, 3, "minecraft:spruce_slab[type=bottom]");
  for (const x of [6, 9]) await set(x, 7, 2, "minecraft:lantern[hanging=true]");
  // A pale entry court and flower boxes stay low enough to reveal both levels from the north.
  await fill(-20, -12, -1, 11, -8, -1, "minecraft:stone_bricks");
  await fill(-19, -11, -1, 10, -9, -1, pale);
  await fill(-2, -14, -1, 2, -12, -1, "minecraft:gravel");
  for (const [x, z, length] of [[-20, -11, 5], [11, -6, 5], [11, 4, 5]] as const) {
    await fill(x, z, 0, x, z + length - 1, 0, "minecraft:azalea_leaves[persistent=true]");
    await set(x, z + 1, 1, "minecraft:potted_poppy");
  }
  for (const [x, z] of [[-19, -12], [10, -9], [4, 7]] as const) await set(x, z, 0, "minecraft:lantern");

  // All trees are behind the house or far outside its silhouette. Layered foliage replaces foreground obstructions.
  for (const [x, z, h] of [[-27, 12, 11], [-24, 30, 13], [-17, 19, 12], [-7, 18, 14], [4, 17, 12], [15, 10, 11]] as const) {
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
  for (const [x, z] of [[-17, 13], [-9, 13], [1, 13]] as const) {
    await fill(x - 2, z - 1, -1, x + 2, z + 1, -1, "minecraft:farmland[moisture=7]");
    await fill(x - 2, z - 1, 0, x + 2, z + 1, 0, "minecraft:wheat[age=7]");
  }
  // Founding beds move out of the new kitchen volume, behind the western garden.
  const beds = [12, 14, 16, 18, 20, 22, 24, 26].map(z => at(-24, z));
  for (const bed of beds) {
    await world.command(server, `setblock ${commandPos(bed)} minecraft:white_bed[part=foot,facing=west]`);
    await world.command(server, `setblock ${commandPos({ ...bed, x: bed.x - 1 })} minecraft:white_bed[part=head,facing=west]`);
  }
  return { beds, dining, groundEntry, theme: "front-cutaway two-storey white-plaster inn: open kitchen below the dining balcony, with dark timber, green copper and a courtyard lift" };
}
