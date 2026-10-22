import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

const K = "kaleidoscope_cookery:";
const T = "kaleidoscope_tavern:";

/** A compact front-cutaway inn: dense kitchen work below, camera-facing diners above, lift beside the dining room. */
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
  // A stepped hip roof gives the inn a recognisable silhouette without covering the cutaway.
  // Each half-block eave projects beyond the next tier; the low amber ridge warms the green copper.
  for (let tier = 0; tier < 5; tier++) {
    await fill(-19 + tier, -7 + tier, 11 + tier, -2 - tier, 4 - tier, 11 + tier,
      "minecraft:weathered_cut_copper_slab[type=bottom]");
    await fill(-18 + tier, -6 + tier, 11 + tier, -3 - tier, 3 - tier, 11 + tier,
      "minecraft:weathered_cut_copper");
  }
  await fill(-14, -2, 16, -7, -1, 16, "minecraft:dark_oak_slab[type=bottom]");
  // A rear chimney and pale stone accents suggest a lived-in inn, while keeping the cooks in view.
  await fill(-16, 2, 12, -15, 3, 16, "minecraft:bricks");
  await fill(-17, 1, 17, -14, 4, 17, "minecraft:brick_slab[type=bottom]");
  for (const x of [-17, -4]) {
    await fill(x, 3, 0, x, 3, 2, "minecraft:cut_sandstone");
    await set(x, 2, 3, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]");
  }
  // No rail or ornament crosses the north dining opening: the food, faces and hands must all be visible.
  // A two-block glass boundary meets the tall rear wall without a low rail to climb onto.
  await fill(-3, -2, 6, -3, 2, 7, "minecraft:glass_pane");
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
  // Leave x=-6..-3 clear for the elevator's three-block waiting area.
  // Three small table groups leave two broad service aisles. Diners and each delivered dish
  // remain readable from the north camera; every group has its own inward-facing seats.
  for (const left of [-16, -12, -8]) {
    for (const x of [left, left + 1]) for (const z of [-3, -2]) {
      await set(x, z, 6, K + "table_spruce");
      dining.tables.push(at(x, z, 6));
    }
    for (const [x, z, facing] of [
      [left, -4, "south"], [left + 1, -4, "south"],
      [left, -1, "north"], [left + 1, -1, "north"],
    ] as const) {
      await set(x, z, 6, K + `chair_spruce[facing=${facing}]`);
      dining.chairs.push(at(x, z, 6));
    }
  }
  for (let x = -17; x <= -5; x++) for (let z = -5; z <= 1; z++) {
    dining.cells.push(at(x, z, 5), at(x, z, 6));
  }
  for (const x of [-16, -10, -5]) await set(x, 2, 6, "minecraft:potted_fern");

  // Six real appliances will be installed at x=-12,-11,-9,-8,-6,-5,z=-4 by the take.
  // Leave both operator aisles z=-5 and z=-3 open; x=-10 holds the third skillet, x=-7 stays a worktop.
  await fill(-12, -4, 0, -5, -4, 0, "minecraft:bricks");
  await set(-7, -4, 1, "minecraft:smooth_stone_slab[type=bottom]");
  // The rear preparation counter leaves the central kitchen aisle open; the mill is in the courtyard.
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

  // A native grape garden replaces the vegetable beds and pond. Suspended fruit leaves
  // two blocks of headroom; a three-block central aisle joins both open ends of the rows.
  await fill(4, -7, -1, 12, 5, -1, "minecraft:stone_bricks");
  await fill(5, -6, -1, 11, 4, -1, pale);
  const vineyard = {
    vines: [] as Vec[], grapes: [] as Array<{ at: Vec; age: number }>, posts: [] as Vec[],
    barrels: [at(5, -7), at(11, -7)], lamps: [at(6, -6, 4), at(10, 4, 4)],
    aisle: { min: at(7, -6), max: at(9, 4, 1) },
    crop: T + "grape_crop", vine: T + "grapevine_trellis", maximumCropAge: 5,
  };
  for (const x of [6, 10]) {
    for (const z of [-6, 4]) {
      await set(x, z, -1, "minecraft:rooted_dirt");
      for (let y = 0; y <= 2; y++) {
        await set(x, z, y, T + "grapevine_trellis[type=single,age=3,waterlogged=false]");
        vineyard.posts.push(at(x, z, y));
      }
      await set(x, z, 3, T + "grapevine_trellis[type=cross_north_south,age=3,waterlogged=false]");
      vineyard.vines.push(at(x, z, 3));
    }
    for (let z = -5; z <= 3; z++) {
      await set(x, z, 3, T + "grapevine_trellis[type=north_south,age=3,waterlogged=false]");
      const age = 3 + ((z + 5 + (x === 10 ? 1 : 0)) % 3);
      await set(x, z, 2, T + `grape_crop[age=${age}]`);
      vineyard.vines.push(at(x, z, 3));
      vineyard.grapes.push({ at: at(x, z, 2), age });
    }
  }
  for (const barrel of vineyard.barrels) await world.command(server,
    `setblock ${commandPos(barrel)} minecraft:barrel[facing=up]{Items:[]}`);
  for (const lamp of vineyard.lamps) await world.command(server, `setblock ${commandPos(lamp)} minecraft:lantern`);
  // The open forecourt keeps low food stalls and terrace tables in front of the inn.
  // The grape garden sits beside it, away from the kitchen and elevator sightline.
  await fill(-28, -14, -1, 13, -7, -1, "minecraft:stone_bricks");
  await fill(-27, -13, -1, 12, -8, -1, pale);
  await fill(-17, -11, -1, -4, -7, -1, "minecraft:polished_andesite");
  await fill(-16, -10, -1, -5, -7, -1, "minecraft:polished_diorite");
  await fill(-18, -14, -1, 3, -12, -1, "minecraft:gravel");
  // A village street continues beyond both edges of the locked camera. Low borders
  // keep the approaches legible without hiding cooks or the courtyard work.
  await fill(-65, -18, -1, 55, -15, -1, "minecraft:stone_bricks");
  await fill(-65, -17, -1, 55, -16, -1, "minecraft:gravel");
  await fill(-18, -14, -1, 18, -12, -1, pale);
  for (const [from, to] of [[-65, -29], [14, 55]] as const) {
    await fill(from, -14, 0, to, -14, 0, "minecraft:spruce_fence");
  }
  for (const x of [-49, -36, 24, 39, 52]) {
    // Solid three-block posts have no low wall cap reachable from the nearby planting.
    await fill(x, -18, 0, x, -18, 2, "minecraft:stripped_spruce_log[axis=y]");
    await set(x, -18, 3, "minecraft:lantern");
    await set(x - 1, -19, 0, "minecraft:flowering_azalea");
  }
  // A second dining area sits between the working stalls and the village street.
  // Leave z=-14 as a continuous approach and z=-10 as the cooks' independent working aisle.
  const courtyardDining = {
    tables: [] as Vec[], chairs: [] as Vec[], cells: [] as Vec[], entry: at(-3, -12),
  };
  for (const left of [-15, -11, -7]) {
    await fill(left - 1, -13, -1, left + 2, -11, -1, "minecraft:polished_diorite");
    for (const x of [left, left + 1]) {
      await set(x, -12, 0, K + "table_spruce");
      courtyardDining.tables.push(at(x, -12));
      for (const [z, facing] of [[-13, "south"], [-11, "north"]] as const) {
        await set(x, z, 0, K + `chair_spruce[facing=${facing}]`);
        courtyardDining.chairs.push(at(x, z));
      }
    }
  }
  for (let x = -17; x <= -4; x++) for (let z = -14; z <= -11; z++) {
    courtyardDining.cells.push(at(x, z, -1), at(x, z));
  }
  // Low flower pots flank the terrace; no awning, hedge or lamp hides the food or people's faces.
  for (const x of [-17, -4]) {
    await set(x, -12, 0, "minecraft:cut_sandstone");
    await set(x, -12, 1, "minecraft:potted_red_tulip");
  }
  // Little paving insets separate the chopping, tea and roasting bays without walls.
  for (const x of [-14, -10, -6]) await fill(x - 1, -9, -1, x + 1, -7, -1, "minecraft:smooth_sandstone");
  // A compact left woodshed and empty barrel reserve serve the kitchen and winery.
  await fill(-23, -5, -1, -20, 2, -1, "minecraft:stone_bricks");
  for (const [x, z] of [[-23, -5], [-20, -5], [-23, 2], [-20, 2]] as const) {
    await fill(x, z, 0, x, z, 2, "minecraft:stripped_spruce_log[axis=y]");
  }
  await fill(-23, -5, 3, -20, 2, 3, "minecraft:spruce_slab[type=bottom]");
  await fill(-23, 0, 0, -21, 1, 0, "minecraft:spruce_log[axis=z]");
  for (const x of [-23, -22]) await set(x, -3, 0, "minecraft:barrel[facing=north]{Items:[]}");
  await set(-21, -3, 0, "minecraft:barrel[facing=up]");
  await set(-21, 1, 2, "minecraft:lantern[hanging=true]");
  for (const [x, z] of [[-30, -12], [12, -13], [12, 5]] as const) await set(x, z, 0, "minecraft:lantern");

  // Layer a woodland edge behind the set: low spreading crowns in front, taller spruce
  // further back. The dense lower foliage removes the exposed trunks and empty flat horizon.
  const trees = [
    [-31, 6, 12], [-27, 12, 11], [-34, 16, 15], [-24, 22, 14], [-17, 21, 12],
    [-10, 20, 13], [-3, 22, 15], [4, 19, 12], [12, 15, 12], [18, 7, 12],
    [22, 14, 15], [-36, 28, 15], [-29, 32, 13], [-20, 31, 15], [-12, 32, 14],
    [-4, 31, 15], [5, 30, 13], [14, 28, 15], [23, 27, 14], [29, 18, 13],
  ];
  for (const [index, [x, z, h]] of trees.entries()) {
    const broad = index % 3 === 1;
    const leaves = broad ? "minecraft:oak_leaves[persistent=true]" : "minecraft:spruce_leaves[persistent=true]";
    await fill(x - 2, z - 2, -1, x + 2, z + 2, -1, "minecraft:grass_block");
    await fill(x, z, 0, x, z, h - 1, broad ? "minecraft:oak_log[axis=y]" : "minecraft:spruce_log[axis=y]");
    const radii = broad ? [2, 3, 3, 2, 2, 1] : [3, 3, 2, 2, 2, 1, 1, 0];
    for (const [layer, radius] of radii.entries()) {
      const y = h - radii.length + layer + 1;
      await fill(x - radius, z - radius, y, x + radius, z + radius, y, leaves);
      if (radius > 0) for (const dx of [-radius, radius]) for (const dz of [-radius, radius]) {
        await set(x + dx, z + dz, y, "minecraft:air");
      }
    }
    await set(x - 2, z - 1, 0, "minecraft:fern");
    await set(x + 1, z + 2, 0, index % 2 ? "minecraft:flowering_azalea" : "minecraft:azalea");
  }
  // Planting beds and low boundary stone give the working yard a finished garden edge.
  for (const [x, z] of [[-29, 3], [-25, 5], [14, 7], [18, 5]] as const) {
    await fill(x - 1, z - 1, -1, x + 1, z + 1, -1, "minecraft:moss_block");
    await set(x, z, 0, "minecraft:flowering_azalea");
    await set(x - 1, z, 0, "minecraft:oxeye_daisy");
    await set(x + 1, z + 1, 0, "minecraft:fern");
  }
  await fill(-21, 10, -1, 11, 10, 0, "minecraft:mossy_stone_bricks");
  for (const [x, z] of [[-28, 2], [-26, 4], [13, 6], [16, 7]] as const) {
    await set(x, z, 0, "minecraft:flowering_azalea");
    await set(x + 1, z, 0, "minecraft:fern");
  }
  // A small enclosed bunkhouse sits behind the inn; its real beds stay out of the foreground.
  await fill(-18, 7, -1, -3, 42, -1, "minecraft:stone_bricks");
  await fill(-17, 8, 0, -4, 41, 2, "minecraft:air");
  await fill(-18, 7, 0, -18, 42, 2, plaster);
  await fill(-3, 7, 0, -3, 42, 2, plaster);
  await fill(-18, 7, 0, -3, 7, 2, plaster);
  await fill(-18, 42, 0, -3, 42, 2, plaster);
  await fill(-18, 7, 3, -3, 42, 3, "minecraft:dark_oak_planks");
  await fill(-18, 9, 0, -18, 9, 1, "minecraft:air");
  await set(-10, 10, 2, "minecraft:lantern[hanging=true]");
  const beds = [-15, -13, -11, -9, -7, -5, -3].flatMap(x => [at(x - 1, 8), at(x - 1, 11), at(x - 1, 13), at(x - 1, 16), at(x - 1, 19), at(x - 1, 22), at(x - 1, 25), at(x - 1, 28), at(x - 1, 31), at(x - 1, 34), at(x - 1, 37), at(x - 1, 40)]);
  for (const bed of beds) {
    await world.command(server, `setblock ${commandPos(bed)} minecraft:white_bed[part=foot,facing=west]`);
    await world.command(server, `setblock ${commandPos({ ...bed, x: bed.x - 1 })} minecraft:white_bed[part=head,facing=west]`);
  }
  const diningAreas = [{ id: "balcony", ...dining }, { id: "courtyard", ...courtyardDining }];
  const vineyardChecked = await server.run(CHECK_VINEYARD, vineyard);
  return { beds, dining, diningAreas, groundEntry, displayFood, vineyard: { ...vineyard, checked: vineyardChecked },
    theme: "warm timber-and-plaster tavern with a layered copper hip roof: open kitchen below three balcony table groups, three street terrace tables, a courtyard lift and native grape trellises" };
}

const CHECK_VINEYARD = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.vineyard-check", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapeCropBlock",
    "com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapevineTrellisBlock",
    "net.minecraft.core.BlockPos",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
int mature = 0;
for (var raw : args.list("grapes")) {
    var grape = Args.of((Map) raw, "grape");
    var pos = grape.blockPos("at");
    var state = level.getBlockState(pos);
    if (!(state.getBlock() instanceof GrapeCropBlock crop) || !state.canSurvive(level, pos))
        throw new IllegalStateException("Unsupported native vineyard fruit at " + pos);
    var above = level.getBlockState(pos.above());
    if (!(above.getBlock() instanceof GrapevineTrellisBlock vine) || !vine.isMaxAge(above))
        throw new IllegalStateException("Vineyard fruit needs a mature native trellis at " + pos.above());
    if (crop.isMaxAge(state)) mature++;
}
var aisle = args.requireObject("aisle");
var min = aisle.blockPos("min");
var max = aisle.blockPos("max");
for (var pos : BlockPos.betweenClosed(min, max))
    if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Blocked vineyard aisle at " + pos);
int daylight = level.getMaxLocalRawBrightness(min);
if (daylight < 8) throw new IllegalStateException("The vineyard aisle must remain lit");
return Sync.stamp(Map.of("supportedClusters", args.list("grapes").size(), "matureClusters", mature,
    "clearAisleWidth", 3, "clearAisleHeight", 2, "daylight", daylight, "initialFinishedDrinks", 0));
` }),
});
