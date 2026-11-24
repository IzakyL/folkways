import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

// Let it Walk!'s demo spider on a meadow, facing south, with an arm the player built at its nose: a hinge, a
// three-block upper arm, an elbow hinge and, hanging from it, three Create drills side by side, each spun by a creative
// motor above it. The four-high ridge runs farther than the arm can reach from one stand. The three drills sweep in a vertical
// plane, so the spider walks along the cut and sidesteps to reach the other side. Flat ground flanks the ridge,
// and the lower shoulder gives the hull clearance on the return pass.

export type DrillWalker = { hull: string; head: string; controls: Vec; controlsStore: Vec;
  deck: Vec[]; pantry: Vec; core: Vec; coreStore: Vec; drillStores: Vec[] };

// The last two rows are outside the pick and give the take standing blocks to check.
export const DIG_BOX = { low: { x: -1, y: 0, z: 6 }, high: { x: 3, y: 3, z: 22 } };
const RIDGE = { low: DIG_BOX.low, high: { ...DIG_BOX.high, z: DIG_BOX.high.z + 2 } };
const CREST_EDGE = 1;
const CAP_START = DIG_BOX.high.z;

export async function clearDrillGround(server: MinecraftServer, pad: Vec) {
  const r = (x: number, y: number, z: number) => `${pad.x + x} ${pad.y + y} ${pad.z + z}`;
  const fill = (a: number[], b: number[], block: string) =>
    tryCommand(server, `fill ${r(a[0], a[1], a[2])} ${r(b[0], b[1], b[2])} ${block}`);
  for (const z0 of [-24, 6, 36]) await fill([-24, 0, z0], [24, 16, z0 + 29], "minecraft:air");
  await fill([-24, -1, -24], [24, -1, 65], "minecraft:grass_block");
  for (const [x, z] of [[-14, 14], [16, 22], [-18, 40], [12, 44], [-9, 52], [20, 4]]) {
    await tryCommand(server, `setblock ${r(x, 0, z)} minecraft:azalea`);
  }
}

/** The spider with its arm, put together and assembled on the pad. */
export async function buildDrillWalker(server: MinecraftServer, pad: Vec) {
  return server.run(BUILD, pad);
}

/** Build the original calibration ridge for replay, then widen and raise it for the tactical shot. */
export async function raiseRidge(server: MinecraftServer, pad: Vec, calibrating = false) {
  const r = (v: { x: number; y: number; z: number }) => `${pad.x + v.x} ${pad.y + v.y} ${pad.z + v.z}`;
  const { low, high } = RIDGE;
  const fill = (from: Vec, to: Vec, block: string) =>
    tryCommand(server, `fill ${r(from)} ${r(to)} minecraft:${block}`);
  const base = { ...high, x: calibrating ? CREST_EDGE : high.x, y: high.y - 1 };
  await fill(low, { ...base, y: base.y - 1 }, "dirt");
  await fill({ ...low, y: base.y }, base, "grass_block");
  if (calibrating) {
    return;
  }
  // Leave the pick corners and the uncut end in place; shape the rest into uneven terraces.
  for (const [from, to] of [
    [{ x: -1, y: 2, z: 6 }, { x: 0, y: 2, z: 8 }],
    [{ x: 2, y: 1, z: 6 }, { x: 3, y: 2, z: 10 }],
    [{ x: 3, y: 0, z: 11 }, { x: 3, y: 2, z: 13 }],
    [{ x: 2, y: 2, z: 16 }, { x: 3, y: 2, z: 18 }],
  ]) {
    await fill(from, to, "air");
    if (from.y > 0) {
      await fill({ ...from, y: from.y - 1 }, { ...to, y: from.y - 1 }, "grass_block");
    }
  }
  // Keep a low shoulder on the side under the hull. The full-height end makes the far top corner pickable.
  for (const [from, to] of [
    [{ ...low, z: low.z + 4 }, { ...high, x: CREST_EDGE, z: high.z - 4 }],
    [{ ...low, x: CREST_EDGE + 1, z: CAP_START }, high],
  ]) {
    await fill({ ...from, y: base.y }, { ...to, y: base.y }, "dirt");
    await fill({ ...from, y: high.y }, to, "grass_block");
  }
}

/** Once the spider stands: where its controls, deck, core and drills are. */
export async function findDrillWalker(server: MinecraftServer, pad: Vec): Promise<DrillWalker> {
  return server.run(FIND, pad);
}

const BUILD = defineTask<Vec, any>({
  name: "folkways.promo.assistant.drill-build", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation", "net.minecraft.world.level.block.Block",
      "net.minecraft.world.level.block.state.properties.BlockStateProperties", "dev.letitwalk.ModContent",
      "dev.letitwalk.content.LegJointBlock", "dev.letitwalk.content.LegJointBlockEntity", "dev.letitwalk.demo.DemoWalkers",
      "net.minecraft.world.level.block.Blocks", "net.minecraft.world.level.block.entity.BarrelBlockEntity",
      "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items", "dev.letitwalk.walker.WalkerAssembler",
      "com.simibubi.create.content.kinetics.base.DirectionalKineticBlock",
      "com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var at = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
for (int cx = at.getX() - 48 >> 4; cx <= at.getX() + 48 >> 4; cx++)
    for (int cz = at.getZ() - 32 >> 4; cz <= at.getZ() + 72 >> 4; cz++) level.setChunkForced(cx, cz, true);
var core = DemoWalkers.place(level, at, Direction.SOUTH, DemoWalkers.Type.BUILT_SPIDER, at.getY());
java.util.function.Function<String, Block> create = name -> BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", name));
var casing = ModContent.KLAXIUM_CASING.get().defaultBlockState();
// The arm: a hinge at the nose, a three-block upper arm, an elbow hinge and the drill head hanging below it.
var shoulder = at.above(6).south(5);
level.setBlock(shoulder, ModContent.LEG_HINGE.get().defaultBlockState().setValue(LegJointBlock.FACING, Direction.SOUTH), 3);
level.setBlock(shoulder.south(), casing, 3);
level.setBlock(shoulder.south(2), casing, 3);
var elbow = shoulder.south(3);
level.setBlock(elbow, ModContent.LEG_HINGE.get().defaultBlockState().setValue(LegJointBlock.FACING, Direction.DOWN), 3);
// At rest the forearm folds back under the upper arm, so the elbow can open all the way.
if (level.getBlockEntity(elbow) instanceof LegJointBlockEntity joint) joint.setRestOffset(60);
level.setBlock(elbow.below(), casing, 3);
for (int side = -1; side <= 1; side++) {
    var motor = elbow.below(2).east(side);
    level.setBlock(motor, create.apply("creative_motor").defaultBlockState().setValue(DirectionalKineticBlock.FACING, Direction.DOWN), 3);
    if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity spinning) spinning.generatedSpeed.setValue(128);
    level.setBlock(elbow.below(3).east(side), create.apply("mechanical_drill").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN), 3);
}
// The crew stays on the hull, so its food travels with it.
var pantry = core.south(2).west(2);
level.setBlock(pantry, Blocks.BARREL.defaultBlockState(), 3);
if (level.getBlockEntity(pantry) instanceof BarrelBlockEntity barrel) {
    barrel.setItem(0, new ItemStack(Items.BREAD, 64));
}
var built = WalkerAssembler.assemble(level, core);
return Map.of("built", String.valueOf(built == null ? "" : built.getString()));`,
  }),
});

const FIND = defineTask<Vec, DrillWalker>({
  name: "folkways.promo.assistant.drill-find", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "dev.ryanhcode.sable.Sable", "dev.ryanhcode.sable.companion.math.BoundingBox3d",
      "dev.letitwalk.walker.WalkerTracker", "dev.letitwalk.content.WalkerCoreBlock",
      "com.simibubi.create.content.kinetics.drill.DrillBlockEntity", "io.github.izakyl.folkways.core.world.api.WorldSpaces"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var pad = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var box = new BoundingBox3d(pad.getX() - 30, pad.getY() - 4, pad.getZ() - 30, pad.getX() + 30, pad.getY() + 40, pad.getZ() + 30);
dev.ryanhcode.sable.sublevel.SubLevel hull = null;
var drillStores = new ArrayList<Object>();
String head = "";
for (var sub : Sable.HELPER.getAllIntersecting(level, box)) {
    if (WalkerTracker.coreOf(sub) != null) hull = sub;
    var plot = sub.getPlot().getBoundingBox();
    for (var cell : BlockPos.betweenClosed(plot.minX(), plot.minY(), plot.minZ(), plot.maxX(), plot.maxY(), plot.maxZ())) {
        if (level.getBlockEntity(cell) instanceof DrillBlockEntity) {
            var frame = sub.getUniqueId().toString();
            if (!head.isEmpty() && !head.equals(frame)) {
                throw new IllegalStateException("the drills span more than one sublevel");
            }
            head = frame;
            drillStores.add(Map.of("x", cell.getX(), "y", cell.getY(), "z", cell.getZ()));
        }
    }
}
if (hull == null) throw new IllegalStateException("no walker stands on the pad");
var core = WalkerTracker.coreOf(hull);
var facing = level.getBlockState(core).getValue(WalkerCoreBlock.FACING);
var controlsAt = WorldSpaces.at(level, core.relative(facing, 3));
var deck = new ArrayList<Object>();
for (var cell : List.of(controlsAt.cell().relative(facing.getClockWise()), controlsAt.cell().relative(facing, -2))) {
    var world = WorldSpaces.world(level, controlsAt.at(cell)).orElseThrow();
    deck.add(Map.of("x", world.x, "y", world.y + 0.02, "z", world.z));
}
java.util.function.Function<BlockPos, Map<String, Object>> xyz = p -> Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
var controlsWorld = WorldSpaces.world(level, controlsAt).orElseThrow();
var controlsStore = WorldSpaces.storage(level, controlsAt).orElseThrow();
var coreWorld = WorldSpaces.world(level, WorldSpaces.at(level, core)).orElseThrow();
return Map.of("hull", hull.getUniqueId().toString(), "head", head,
    "controls", Map.of("x", controlsWorld.x, "y", controlsWorld.y, "z", controlsWorld.z),
    "controlsStore", xyz.apply(controlsStore), "deck", deck, "pantry", xyz.apply(core.relative(facing, 2).west(2)),
    "core", Map.of("x", coreWorld.x, "y", coreWorld.y, "z", coreWorld.z), "coreStore", xyz.apply(core),
    "drillStores", drillStores);`,
  }),
});

/**
 * The ridge cell by cell, inside the box from `low` to `high` (relative to the pad) or outside it, each counted as dug
 * or standing, and the ground under and around the ridge counted as dug where it is gone; `dug` lists the cells gone
 * inside the box. Also samples the carrier and the dig routine to track repositioning between passes.
 */
export const DIG_STATE = defineTask<{ pad: Vec; low: Vec; high: Vec; coreStore: Vec; colony: string }, any>({
  name: "folkways.promo.assistant.drill-state", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "io.github.izakyl.folkways.core.world.api.WorldSpaces",
      "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.facility.api.DefinedFacilities",
      "com.google.gson.JsonObject"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var p = a.object("pad");
var pad = new BlockPos((int) p.number("x"), (int) p.number("y"), (int) p.number("z"));
var lo = a.object("low");
var hi = a.object("high");
int[] b = {(int) lo.number("x"), (int) lo.number("y"), (int) lo.number("z"), (int) hi.number("x"), (int) hi.number("y"),
    (int) hi.number("z")};
int insideDug = 0, insideStanding = 0, outsideDug = 0, groundDug = 0;
var stray = new ArrayList<Object>();
var dug = new ArrayList<Object>();
for (int x = ${RIDGE.low.x}; x <= ${RIDGE.high.x}; x++) for (int y = ${RIDGE.low.y}; y <= ${RIDGE.high.y}; y++)
    for (int z = ${RIDGE.low.z}; z <= ${RIDGE.high.z}; z++) {
        if (y == ${RIDGE.high.y} && x > ${CREST_EDGE} && z < ${CAP_START}) {
            continue;
        }
        boolean in = x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
        boolean air = level.getBlockState(pad.offset(x, y, z)).isAir();
        if (in && air) { insideDug++; dug.add(List.of(x, y, z)); } else if (in) insideStanding++; else if (air) { outsideDug++; stray.add(List.of(x, y, z)); }
    }
for (int x = ${RIDGE.low.x - 3}; x <= ${RIDGE.high.x + 3}; x++) for (int z = ${RIDGE.low.z - 3}; z <= ${RIDGE.high.z + 2}; z++) {
    if (level.getBlockState(pad.offset(x, -1, z)).isAir()) { groundDug++; stray.add(List.of(x, -1, z)); }
}
var core = a.object("coreStore");
var coreCell = new BlockPos((int) core.number("x"), (int) core.number("y"), (int) core.number("z"));
var at = WorldSpaces.world(level, WorldSpaces.at(level, coreCell)).orElseThrow();
var out = new LinkedHashMap<String, Object>(Map.of("core", List.of(at.x, at.y, at.z), "insideDug", insideDug,
    "insideStanding", insideStanding, "outsideDug", outsideDug, "groundDug", groundDug, "stray", stray, "dug", dug));
Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).flatMap(DefinedFacilities::of).ifPresent(services -> {
    for (var script : services.activeScripts("drill_arm").values()) {
        if (!"dig".equals(script.get("routine"))) {
            continue;
        }
        out.put("step", script.get("step"));
        if (script.get("state") instanceof JsonObject state && state.get("sweep") instanceof JsonObject sweep) {
            out.put("sweepWorked", sweep.getAsJsonArray("worked").size());
            if (sweep.has("stands_tried")) {
                out.put("sweepDestinations", sweep.getAsJsonArray("stands_tried").toString());
            }
        }
    }
    services.recent("drill_arm").filter(result -> result.routine().equals("dig")).ifPresent(result ->
        out.put("result", Map.of("ending", result.ending(), "reason", result.reason().orElse(""))));
});
return out;`,
  }),
});

/** Clicks an exact world point in the tactical view, such as the middle of a block's face, so that face is the one hit. */
export const PROJECT_POINT = defineTask<{ x: number; y: number; z: number }, { x: number; y: number }>({
  name: "folkways.promo.assistant.drill-project", side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.Optional", "net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3"],
    body: `
var a = Args.of(ctx);
var mc = Minecraft.getInstance();
var type = Class.forName("io.github.izakyl.folkways.core.command.client.CombatProjection");
var constructor = type.getDeclaredConstructor(Minecraft.class);
constructor.setAccessible(true);
var projection = constructor.newInstance(mc);
var point = type.getDeclaredMethod("point", Vec3.class);
point.setAccessible(true);
var pixel = ((Optional<?>) point.invoke(projection, new Vec3(a.number("x"), a.number("y"), a.number("z")))).orElseThrow();
var x = pixel.getClass().getDeclaredMethod("x");
x.setAccessible(true);
var y = pixel.getClass().getDeclaredMethod("y");
y.setAccessible(true);
return Map.of("x", ((Number) x.invoke(pixel)).doubleValue(), "y", ((Number) y.invoke(pixel)).doubleValue());`,
  }),
});
