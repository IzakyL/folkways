import { camera, defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { readFileSync } from "node:fs";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { FILM_LOOK } from "../shared/capture/shaders";
import type { VisualRun } from "../shared/capture/visual-kit";
import { DRAW_PATTERNS } from "../shared/rail/rail-drawn";
import { foundPromoColonyFast } from "./fast-setup";
import { cinematicWorld, launchPromoPair, sleepMs } from "./promo-kit";

export const COMBAT_SITE = {
  seed: "folkways-combat-river-1",
  x: -1120,
  z: -1120,
  direction: "+Z",
};
export type Settlement = Awaited<ReturnType<typeof openSettlement>>;

// Only building foundations and native draft earthworks touch the generated ground.
export async function openSettlement(scope: string, run: VisualRun) {
  const pair = await launchPromoPair(scope, {
    config: "blockwright.toml#promo-combat",
    look: { options: FILM_LOOK },
  });
  const { server, client } = pair;
  const { name } = await waitForGroundedPlayer(server);
  await world.command(server, `op ${name}`);
  await cinematicWorld(server);
  for (const command of [
    "difficulty normal",
    "gamerule doMobSpawning false",
    "gamerule doFireTick false",
    "gamerule mobGriefing true",
    "time set 10000",
  ]) {
    await world.command(server, command);
  }
  const ground = await server.run(SITE_GROUND, COMBAT_SITE);
  const o = { x: COMBAT_SITE.x, y: ground.y, z: COMBAT_SITE.z + 46 };
  const at = (x: number, y: number, z: number): Vec => ({
    x: o.x + x,
    y: o.y + y,
    z: o.z + z,
  });
  await world.command(server, `gamemode spectator ${name}`);
  await world.command(server, `tp ${name} ${o.x} ${o.y + 20} ${o.z - 40}`);
  await world.command(server, `forceload add ${o.x - 64} ${o.z - 104} ${o.x + 64} ${o.z + 160}`);
  const sources = Object.fromEntries(
    ["city_wall", "road", "arcade", "terraced_field", "dock", "arch_bridge", "materials", "grading"].map((id) => [
      `folkways_vanilla:${id}`,
      readFileSync(`mods/vanilla/src/main/resources/data/folkways_vanilla/folkways/pattern/${id}.star`, "utf8"),
    ]),
  );
  const points = (cells: number[][]) => cells.map(([x, z]) => [o.x + x, o.y, o.z + z]);
  const drawings = [
    {
      name: "curtain",
      pattern: "folkways_vanilla:city_wall",
      kind: "path",
      knobs: { spacing: 32 },
      points: points([
        [-46, 0],
        [46, 0],
        [46, -92],
        [-46, -92],
        [-46, 0],
      ]),
    },
    {
      name: "gate-road",
      pattern: "folkways_vanilla:road",
      kind: "path",
      knobs: { width: 7 },
      points: points([
        [0, 18],
        [0, -80],
      ]),
    },
    {
      name: "market-street",
      pattern: "folkways_vanilla:road",
      kind: "path",
      knobs: { width: 5 },
      points: points([
        [-36, -30],
        [36, -30],
      ]),
    },
    {
      name: "upper-street",
      pattern: "folkways_vanilla:road",
      kind: "path",
      knobs: { width: 5 },
      points: points([
        [-36, -61],
        [36, -61],
      ]),
    },
    {
      name: "market-arcade",
      pattern: "folkways_vanilla:arcade",
      kind: "path",
      knobs: { width: 5, railing: false },
      points: points([
        [-34, -20],
        [-9, -20],
      ]),
    },
    {
      name: "workshop-arcade",
      pattern: "folkways_vanilla:arcade",
      kind: "path",
      knobs: { width: 5, railing: false },
      points: points([
        [10, -39],
        [34, -39],
      ]),
    },
    {
      name: "river-dock",
      pattern: "folkways_vanilla:dock",
      kind: "path",
      points: points([
        [70, -78],
        [96, -78],
      ]),
    },
    {
      name: "river-path",
      pattern: "folkways_vanilla:road",
      kind: "path",
      knobs: { width: 3, lights: false },
      points: points([
        [52, -78],
        [70, -78],
      ]),
    },
    {
      name: "food-terraces",
      pattern: "folkways_vanilla:terraced_field",
      kind: "zone",
      points: points([
        [27, -84],
        [40, -65],
      ]),
    },
  ];
  const patterns = await server.run(DRAW_PATTERNS, { sources, drawings });
  if (!patterns.ok) {
    throw new Error(`Settlement patterns refused: ${JSON.stringify(patterns)}`);
  }
  const stage = await server.run(FURNISH_SETTLEMENT, o);
  await world.command(server, `item replace entity ${name} weapon.mainhand with folkways_core:colony_book`);
  const founded = await foundPromoColonyFast(server, name, stage.members);
  await screen.dismiss(client);
  await tick.freeze(server, false);
  await sleepMs(35000);
  await tick.freeze(server, true);
  run.note("wildlifeStaging", await server.run(MOVE_WILDLIFE, o));
  run.note("site", {
    ...COMBAT_SITE,
    ...ground,
    gate: o,
    mods: { tectonic: "3.0.28", terralith: "2.6.2", lithostitched: "1.8.0" },
  });
  run.note("settlement", { patterns, ...stage });
  run.note("staging", {
    world: "Fresh normal world; natural terrain preserved except local foundations and pattern earthworks",
    light: "Fixed afternoon and clear weather; random spawning/fire ticks disabled; native griefing enabled",
    buildings: "Instant native city-wall/road/arcade/field patterns and furnished vanilla taiga village templates",
    cast: "Initial enrollment, licences, profiles, positions and finite kits are staged; residents start with 200 HP",
    enemies: "Native hostile AI; pumpkin sunlight protection, chosen health/speed and follow range recorded per take",
    camera: "Spectator inserts with shaders; real creative commander, mode widgets and input for tactical footage",
    honesty:
      "Commands stage scenery and incident damage before recording; " +
      "resident AI performs all combat, suppression and repairs",
  });
  return {
    pair,
    server,
    client,
    name,
    o,
    at,
    colony: founded.colony_id,
    store: stage.store,
  };
}

export async function settlementCamera(scene: Settlement, eye: Vec, target: Vec, fov = 65) {
  await world.command(scene.server, `item replace entity ${scene.name} weapon.mainhand with minecraft:air`);
  await world.command(scene.server, `gamemode spectator ${scene.name}`);
  await camera.reset(scene.client);
  const look = cameraLookingAt(eye, target);
  await world.command(
    scene.server,
    `tp ${scene.name} ${target.x} ${target.y + 20} ${target.z} ${look.yaw} ${look.pitch}`,
  );
  await camera.set(scene.client, { position: eye, ...look, fov });
  await camera.hud(scene.client, false);
  await frames(scene.client, 40);
}

const SITE_GROUND = defineTask<Record<string, any>, any>({
  name: "folkways.promo.generated-site-ground",
  side: "server",
  timeoutMs: 120_000,
  source: javaTask({
    imports: ["net.minecraft.world.level.levelgen.Heightmap"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
int x = (int) a.integer("x"), z = (int) a.integer("z");
level.getChunk(x >> 4, (z + 46) >> 4);
return Map.of("y", level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z + 46),
    "numericSeed", Long.toString(level.getSeed()), "biome", level.getBiome(new net.minecraft.core.BlockPos(x, 80, z))
        .unwrapKey().orElseThrow().location().toString());`,
  }),
});

const FURNISH_SETTLEMENT = defineTask<Record<string, number>, any>({
  name: "folkways.promo.furnished-village-templates",
  side: "server",
  timeoutMs: 120_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.level.levelgen.Heightmap",
      "net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings",
      "net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor",
      "net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor",
      "net.minecraft.world.level.block.Blocks",
      "net.minecraft.world.level.block.BedBlock",
      "net.minecraft.world.level.block.state.properties.BedPart",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var origin = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var rows = List.of(
    List.of("taiga_weaponsmith_1", -34, -13), List.of("taiga_tool_smith_1", 17, -18),
    List.of("taiga_medium_house_1", -34, -54), List.of("taiga_small_house_1", -19, -52),
    List.of("taiga_small_house_2", 10, -57), List.of("taiga_small_house_3", 27, -57),
    List.of("taiga_medium_house_2", -33, -81), List.of("taiga_small_house_4", -14, -82),
    List.of("taiga_library_1", 8, -83), List.of("taiga_masons_house_1", 21, -29),
    List.of("taiga_small_house_1", -35, -40), List.of("taiga_small_house_2", -20, -41),
    List.of("taiga_small_house_3", 10, -28), List.of("taiga_small_house_5", -18, -73));
var reports = new ArrayList<Object>();
var members = new ArrayList<Object>();
for (var row : rows) {
    String name = row.getFirst().toString();
    int x = ((Number) row.get(1)).intValue(), z = ((Number) row.get(2)).intValue();
    var template = level.getStructureManager().get(ResourceLocation.parse("minecraft:village/taiga/houses/" + name))
        .orElseThrow();
    var size = template.getSize();
    int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, origin.getX() + x + size.getX() / 2,
        origin.getZ() + z + size.getZ() / 2) - 1;
    var start = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
    // Pads are confined to each building, leaving the valley and its approach untouched.
    for (var p : BlockPos.betweenClosed(start, start.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
        if (p.getY() > y) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        } else {
            level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 2);
        }
    }
    var settings = new StructurePlaceSettings().setIgnoreEntities(true)
        .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK).addProcessor(JigsawReplacementProcessor.INSTANCE);
    if (!template.placeInWorld(level, start, start, settings, level.random, 2)) {
        throw new IllegalStateException("template not placed: " + name);
    }
    reports.add(Map.of("template", name, "at", start.toShortString(), "size", size.toShortString()));
    for (var p : BlockPos.betweenClosed(start, start.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
        var state = level.getBlockState(p);
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            members.add(Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()));
        }
        if (state.is(Blocks.FARMLAND)) {
            level.setBlock(p.above(), Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7), 2);
        }
    }
}
// A small riverside postern joins the dock path before the curtain baseline is recorded.
for (var p : BlockPos.betweenClosed(origin.offset(43, 0, -79), origin.offset(49, 2, -77))) {
    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
}
// Paved workshop aprons remove only replaceable plants above their existing soil.
for (var corner : List.of(new int[] {-35, -18, -7, -14}, new int[] {8, -36, 35, -33})) {
    for (int x = corner[0]; x <= corner[2]; x++) {
        for (int z = corner[1]; z <= corner[3]; z++) {
            for (int y = 2; y >= -4; y--) {
                var p = origin.offset(x, y, z);
                if (level.getBlockState(p).is(net.minecraft.tags.BlockTags.DIRT)) {
                    level.setBlock(p, (Math.floorMod(x + z, 3) == 0 ? Blocks.GRAVEL : Blocks.DIRT_PATH)
                        .defaultBlockState(), 2);
                    for (int dy = 1; dy <= 2; dy++) {
                        if (level.getBlockState(p.above(dy)).canBeReplaced()
                            && level.getBlockState(p.above(dy)).getFluidState().isEmpty()) {
                            level.setBlock(p.above(dy), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                    break;
                }
            }
        }
    }
}
// Physical stock and market produce complete the native template streets.
var store = origin.offset(-12, 0, -27);
int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, store.getX(), store.getZ());
store = new BlockPos(store.getX(), sy, store.getZ());
level.setBlockAndUpdate(store, Blocks.BARREL.defaultBlockState());
var bin = (net.minecraft.world.Container) level.getBlockEntity(store);
bin.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COOKED_BEEF, 64));
members.add(Map.of("x", store.getX(), "y", store.getY(), "z", store.getZ()));
for (int x = -32; x <= -12; x += 4) {
    var p = origin.offset(x, 0, -20);
    p = new BlockPos(p.getX(), origin.getY(), p.getZ());
    level.setBlock(p, Blocks.BARREL.defaultBlockState(), 2);
}
for (var p : BlockPos.betweenClosed(origin.offset(26, -8, -85), origin.offset(41, 8, -64))) {
    if (level.getBlockState(p).is(Blocks.FARMLAND)) {
        level.setBlock(p.above(), Blocks.WHEAT.defaultBlockState()
            .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7), 2);
    }
}
return Map.of("members", members, "templates", reports,
    "store", Map.of("x", store.getX(), "y", store.getY(), "z", store.getZ()));`,
  }),
});

const MOVE_WILDLIFE = defineTask<Record<string, number>, any>({
  name: "folkways.promo.stage-wildlife-away-from-fire-lanes",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.world.entity.animal.Animal", "net.minecraft.world.level.levelgen.Heightmap"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
int moved = 0;
int x = (int) a.integer("x"), z = (int) a.integer("z");
level.getChunk((x - 100) >> 4, (z - 100) >> 4);
for (var entity : java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false).toList()) {
    if (entity instanceof Animal && Math.abs(entity.getX() - x) < 48
        && entity.getZ() > z + 6 && entity.getZ() < z + 165) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x - 100, z - 100);
        entity.moveTo(x - 100.5, y, z - 100.5, 0, 0);
        moved++;
    }
}
return Map.of("moved", moved, "when", "Before waves: native wildlife moved out of the battery blast corridor");`,
  }),
});

export async function settlementFeet(scene: Settlement, points: Vec[]): Promise<Vec[]> {
  return scene.server.run(READ_FEET, { points });
}

const READ_FEET = defineTask<{ points: Vec[] }, Vec[]>({
  name: "folkways.promo.read-natural-walking-footings",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.world.level.levelgen.Heightmap"],
    body: `
var level = ctx.server().overworld();
var cells = new ArrayList<Object>();
for (var raw : Args.of(ctx).list("points")) {
    var p = (Map<?, ?>) raw;
    int x = ((Number) p.get("x")).intValue(), z = ((Number) p.get("z")).intValue();
    level.getChunk(x >> 4, z >> 4);
    cells.add(Map.of("x", x, "y", level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), "z", z));
}
return cells;`,
  }),
});
