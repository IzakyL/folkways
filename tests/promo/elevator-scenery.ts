import { world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import type { Vec } from "../shared/colony/colony-founding";

/** A cutaway hillside colony. Origin is the lowest cabin deck, not residents' feet.
 * The moving cabin's x=0..4,z=0..4 column stays completely clear at every height.
 * Each inhabited terrace is isolated: the elevator is its only vertical connection.
 */
export async function buildElevatorVillage(server: MinecraftServer, origin: Vec) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const fill = async (a: number[], b: number[], state: string) => {
    const command = `fill ${origin.x + a[0]} ${origin.y + a[1]} ${origin.z + a[2]} ${origin.x + b[0]} ${origin.y + b[1]} ${origin.z + b[2]} ${state}`;
    const result = await world.tryCommand(server, command);
    if (!result.success && !result.output.some(line => /No blocks were filled/.test(line))) {
      throw new Error(`Scenery command failed: ${command}: ${JSON.stringify(result)}`);
    }
    return result;
  };
  const block = (x: number, y: number, z: number, state: string) => fill([x, y, z], [x, y, z], state);
  const pale = "minecraft:smooth_sandstone";
  const trim = "minecraft:cut_sandstone";
  const timber = "minecraft:stripped_spruce_log[axis=y]";
  const floor = "minecraft:spruce_planks";
  const copper = "minecraft:weathered_cut_copper";

  // A pale stone plaza, inset paths, and a low reflecting pool form the foreground.
  await fill([-25, -2, -18], [23, -1, 16], "minecraft:dirt");
  await fill([-25, 0, -18], [23, 0, 16], "minecraft:grass_block");
  await fill([-18, 0, -8], [17, 0, -4], "minecraft:smooth_sandstone");
  await fill([-1, 0, -17], [3, 0, -5], "minecraft:gravel");
  // Full stone bottom and perimeter retain the courtyard pond.
  await fill([8, -1, -14], [16, 0, -8], trim);
  await fill([9, 0, -13], [15, 0, -9], "minecraft:water");
  await block(12, 1, -11, "minecraft:lily_pad");
  await block(14, 1, -10, "minecraft:lily_pad");
  // Raised beds frame the courtyard without filling the cabin or the arrivals.
  for (const [x, z] of [[-21, -8], [-21, 2], [19, -5], [19, 5]]) {
    await fill([x - 1, 0, z - 2], [x + 1, 0, z + 2], trim);
    await fill([x - 1, 1, z - 2], [x + 1, 1, z + 2], "minecraft:flowering_azalea_leaves[persistent=true]");
    await block(x, 2, z, "minecraft:lantern");
  }

  for (const h of [0, 10, 20]) {
    // Continuous landings in front of the shaft and broad balconies on both wings.
    await fill([-17, h, -5], [-2, h, 8], pale);
    await fill([-1, h, -5], [6, h, -1], pale);
    await fill([7, h, -5], [15, h, 8], pale);
    await fill([-16, h, -3], [-3, h, 7], floor);
    await fill([8, h, -3], [14, h, 6], floor);
    await fill([-17, h, -6], [15, h, -6], trim);
    // Ground-floor paths stay open; only elevated balconies need guardrails.
    if (h > 0) {
      await fill([-17, h + 1, -6], [15, h + 1, -6], "minecraft:spruce_fence");
      // The opening toward the cabin spans its full width; no rail crosses it.
      for (const edge of [-17, 15]) await fill([edge, h + 1, -5], [edge, h + 1, 8], "minecraft:spruce_fence");
      await fill([-16, h + 1, 8], [-2, h + 1, 8], "minecraft:spruce_fence");
      await fill([7, h + 1, 8], [14, h + 1, 8], "minecraft:spruce_fence");
      for (const edge of [-2, 7]) await fill([edge, h + 1, 0], [edge, h + 1, 7], "minecraft:spruce_fence");
    }
    // Narrow ribs under each terrace make the separated decks read as architecture.
    for (const x of [-17, -9, -2, 7, 15]) {
      if (h > 0) await fill([x, h - 2, -4], [x, h - 1, 7], "minecraft:dark_oak_planks");
    }
    if (h > 0) {
      // Headers meet the outer upright frames and every under-deck rib.
      for (const z of [-4, 7]) await fill([-18, h - 1, z], [16, h - 1, z], "minecraft:stripped_spruce_log[axis=x]");
    }
    for (const x of [-16, -9, 14]) {
      await block(x, h + (h === 0 ? 1 : 2), -6, "minecraft:lantern");
      await block(x, h + 1, -5, "minecraft:potted_fern");
    }

    // Open-front rooms: white plaster, exposed dark beams, and tall rear windows.
    await fill([-16, h + 1, 7], [-3, h + 5, 7], "minecraft:white_terracotta");
    await fill([-16, h + 1, 0], [-16, h + 5, 6], pale);
    for (const x of [-16, -10, -3]) await fill([x, h + 1, 7], [x, h + 5, 7], timber);
    for (const x of [-14, -7]) {
      await fill([x, h + 2, 7], [x + 2, h + 4, 7], "minecraft:glass_pane");
      await fill([x, h + 1, 6], [x + 2, h + 1, 6], "minecraft:spruce_trapdoor[facing=north,half=top]");
    }
    // Full-depth ceiling beams meet the plaster wall directly, without a half-block seam.
    await fill([-16, h + 5, 0], [-3, h + 5, 7], "minecraft:dark_oak_planks");
    for (const x of [-15, -4]) await fill([x, h + 1, 0], [x, h + 4, 0], timber);
    await fill([-16, h + 4, 0], [-3, h + 4, 0], "minecraft:stripped_spruce_log[axis=x]");
    await fill([-17, h + 6, -1], [-2, h + 6, 8], copper);
    await fill([-16, h + 7, 0], [-3, h + 7, 7], "minecraft:weathered_cut_copper_slab[type=bottom]");
    for (const x of [-15, -5]) await block(x, h + 4, 4, "minecraft:lantern[hanging=true]");

    if (h === 0) {
      // Workshop benches and stocked shelves.
      for (const x of [-14, -12, -10]) await block(x, h + 1, 5, "minecraft:crafting_table");
      await block(-6, h + 1, 5, "minecraft:smithing_table");
      await block(-4, h + 1, 5, "minecraft:stonecutter[facing=north]");
      await fill([-15, h + 1, 1], [-15, h + 2, 3], "minecraft:barrel[facing=east]");
    } else if (h === 10) {
      // Dining room: two small tables, with a kitchen visible behind them.
      for (const x of [-13, -7]) {
        await fill([x, h + 1, 2], [x + 1, h + 1, 3], "minecraft:spruce_slab[type=top]");
        await block(x - 1, h + 1, 2, "minecraft:spruce_stairs[facing=east]");
        await block(x + 2, h + 1, 3, "minecraft:spruce_stairs[facing=west]");
        await block(x, h + 2, 2, "minecraft:potted_poppy");
      }
      await fill([-14, h + 1, 6], [-11, h + 1, 6], "minecraft:smoker[facing=north]");
      await fill([-8, h + 1, 6], [-4, h + 1, 6], "minecraft:barrel[facing=up]");
    } else {
      // Reading room and a greenery-lined upper terrace.
      await fill([-15, h + 1, 6], [-11, h + 3, 6], "minecraft:bookshelf");
      await block(-7, h + 1, 4, "minecraft:lectern[facing=north]");
      await fill([-11, h + 1, 1], [-7, h + 1, 1], "minecraft:red_carpet");
      await block(-12, h + 1, 2, "minecraft:spruce_stairs[facing=east]");
    }

    // Small right-hand pergolas have airy roofs and never cover the central shaft.
    for (const x of [8, 14]) {
      for (const z of [0, 6]) await fill([x, h + 1, z], [x, h + 4, z], timber);
    }
    for (const z of [0, 2, 4, 6]) await fill([8, h + 4, z], [14, h + 4, z], "minecraft:spruce_slab[type=bottom]");
    await fill([8, h + 5, 5], [14, h + 5, 6], "minecraft:flowering_azalea_leaves[persistent=true]");
    await block(11, h + 3, 4, "minecraft:lantern[hanging=true]");
    await fill([10, h + 1, 5], [12, h + 1, 5], "minecraft:spruce_stairs[facing=north]");
    await block(9, h + 1, 1, "minecraft:potted_azalea_bush");
    await block(13, h + 1, 1, "minecraft:potted_flowering_azalea_bush");
    for (const x of [-17, 15]) {
      await fill([x, h + 1, 3], [x, h + 2, 5], "minecraft:oak_leaves[persistent=true]");
    }
  }

  // Tall outer pillars tie the terraces together; the lift remains the vertical focal point.
  for (const x of [-18, 16]) {
    for (const z of [-4, 7]) {
      await fill([x, 0, z], [x, 25, z], timber);
      await fill([x, 0, z - 1], [x, 2, z + 1], trim);
    }
  }
  // The background orchard provides a soft, natural silhouette behind the cutaway.
  for (const [x, z, height] of [[-25, 12, 9], [-22, 19, 11], [-12, 19, 8], [0, 19, 10], [13, 20, 11], [23, 13, 9], [25, 1, 8]]) {
    await fill([x, 1, z], [x, height, z], "minecraft:oak_log[axis=y]");
    await fill([x - 2, height - 3, z - 2], [x + 2, height, z + 2], "minecraft:oak_leaves[persistent=true]");
    await fill([x - 1, height + 1, z - 1], [x + 1, height + 2, z + 1], "minecraft:oak_leaves[persistent=true]");
  }
  // A small irrigated wheat garden gives the courtyard a working colony foreground.
  await fill([-25, 1, -15], [-19, 2, -6], "minecraft:air");
  await fill([-25, 0, -15], [-19, 0, -6], "minecraft:farmland[moisture=7]");
  await fill([-22, -1, -15], [-22, -1, -6], trim);
  for (const z of [-15, -6]) await block(-22, 0, z, trim);
  await fill([-22, 0, -14], [-22, 0, -7], "minecraft:water");
  for (const x of [-24, -23, -21, -20]) {
    await fill([x, 1, -14], [x, 1, -7], "minecraft:wheat[age=7]");
  }
  await block(-25, 1, -15, "minecraft:lantern");
  await block(-19, 1, -6, "minecraft:lantern");

  // Layered conifers frame both edges. Leaves end before z=28, where the founding beds sit.
  for (const [x, z, height] of [[-28, 20, 19], [-24, 23, 16], [-29, 12, 17], [22, 21, 20], [24, 14, 16], [16, 24, 17]]) {
    await block(x, 0, z, "minecraft:grass_block");
    await fill([x, 1, z], [x, height, z], "minecraft:spruce_log[axis=y]");
    for (let layer = 0; layer < 9; layer++) {
      const radius = layer > 5 ? (layer > 7 ? 0 : 1) : (layer % 2 === 0 ? 3 : 2);
      const y = height - 7 + layer;
      await fill([x - radius, y, z - radius], [x + radius, y, z + radius], "minecraft:spruce_leaves[persistent=true]");
    }
    await fill([x, 1, z], [x, height - 1, z], "minecraft:spruce_log[axis=y]");
  }
  for (const [x, z, height] of [[-16, 23, 14], [-7, 24, 12], [3, 23, 15], [11, 24, 13]]) {
    await block(x, 0, z, "minecraft:grass_block");
    await fill([x, 1, z], [x, height, z], "minecraft:oak_log[axis=y]");
    await fill([x - 2, height - 3, z - 2], [x + 2, height, z + 2], "minecraft:oak_leaves[persistent=true]");
    await fill([x - 1, height + 1, z - 1], [x + 1, height + 1, z + 1], "minecraft:oak_leaves[persistent=true]");
  }

  // Rear timber guides and a small copper canopy make the pulley part of the building.
  for (const x of [-1, 5]) await fill([x, 0, 4], [x, 27, 4], timber);
  // Clear only the shaft after landscaping, including the lowest deck which the cabin supplies.
  await fill([0, 0, 0], [4, 28, 4], "minecraft:air");
  // Compact dark timber machinery cabinet hides the creative motor at (1,27,2).
  // The open bay at x=2 leaves the pulley's north face and suspended rope visible.
  // Every enclosure block is above the highest cabin roof (y=24).
  await fill([0, 26, 1], [1, 26, 3], "minecraft:dark_oak_planks");
  await fill([0, 27, 1], [0, 27, 3], "minecraft:dark_oak_planks");
  for (const z of [1, 3]) await block(1, 27, z, "minecraft:dark_oak_planks");
  await fill([-1, 28, 0], [5, 28, 4], copper);
  await fill([0, 29, 1], [4, 29, 3], "minecraft:weathered_cut_copper_slab[type=bottom]");
  return {
    landings: [0, 10, 20].map(h => at(2, h + 1, -3)),
    roomCenters: [0, 10, 20].map(h => at(-8, h + 1, 2)),
    camera: at(28, 16, -36),
    lookAt: at(1, 12, 1),
  };
}
