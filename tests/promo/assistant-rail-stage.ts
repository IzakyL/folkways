import { defineTask, javaTask, player, reflect, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

// A Create trunk line runs north and south along the valley at x = o.x. A new station stands east of it, its siding
// running east and west along z = o.z, its west end SIDING_WEST blocks short of the trunk: nothing joins the two.
// Joining them takes a real route: west off the siding, a turn, and a turnout on the running line, the new track
// curving into the trunk at a junction.

export const SIDING_WEST = 20;
const SIDING = 16;
const NORTH = 52;
const SOUTH = 34;
export const STATION_NAME = "麦田站";

export type RailSite = { sidingEnd: Vec; station: Vec; straight: Vec[]; trunk: { x: number; y: number; z0: number; z1: number } };

export function railLayout(o: Vec): RailSite {
  const straight: Vec[] = [];
  for (let x = o.x + 11; x < o.x + SIDING_WEST; x++) straight.push({ x, y: o.y, z: o.z });
  return { sidingEnd: { x: o.x + SIDING_WEST, y: o.y, z: o.z }, station: { x: o.x + SIDING_WEST + 8, y: o.y, z: o.z + 1 },
    straight, trunk: { x: o.x, y: o.y, z0: o.z - NORTH, z1: o.z + SOUTH } };
}

export async function buildRail(server: MinecraftServer, client: MinecraftClient, playerName: string, o: Vec) {
  const r = (x: number, y: number, z: number) => `${o.x + x} ${o.y + y} ${o.z + z}`;
  const fill = (a: number[], b: number[], block: string) =>
    tryCommand(server, `fill ${r(a[0], a[1], a[2])} ${r(b[0], b[1], b[2])} ${block}`);
  const set = (a: number[], block: string) => tryCommand(server, `setblock ${r(a[0], a[1], a[2])} ${block}`);
  const east = SIDING_WEST + SIDING;
  for (const [z0, z1] of [[-NORTH - 4, -20], [-19, 10], [11, SOUTH + 4]]) {
    await fill([-18, 0, z0], [east + 6, 12, z1], "minecraft:air");
    await fill([-18, -1, z0], [east + 6, -1, z1], "minecraft:grass_block");
  }
  // The trunk on its ballast and bed, as the railway pattern lays it, running out of the picture both ways.
  await fill([-1, -1, -NORTH], [1, -1, SOUTH], "minecraft:cobblestone");
  await fill([0, -1, -NORTH], [0, -1, SOUTH], "minecraft:tuff");
  await fill([0, 0, -NORTH], [0, 0, SOUTH], "create:track[shape=zo,turn=false,waterlogged=false]");
  // The station's siding, east and west, with a buffer stop at its far end.
  await fill([SIDING_WEST, -1, -1], [east, -1, 1], "minecraft:cobblestone");
  await fill([SIDING_WEST, -1, 0], [east, -1, 0], "minecraft:tuff");
  await fill([SIDING_WEST, 0, 0], [east, 0, 0], "create:track[shape=xo,turn=false,waterlogged=false]");
  await set([east + 1, 0, 0], "minecraft:stone_brick_wall");
  await set([east + 1, 1, 0], "minecraft:redstone_lamp[lit=true]");
  // The platform south of the siding, its canopy and a few lamps.
  await fill([SIDING_WEST + 1, 0, 2], [east - 1, 0, 5], "minecraft:smooth_stone_slab[type=top]");
  await fill([SIDING_WEST + 1, 0, 2], [east - 1, 0, 2], "minecraft:polished_andesite");
  for (const x of [SIDING_WEST + 2, SIDING_WEST + 8, SIDING_WEST + 14]) {
    await fill([x, 1, 5], [x, 3, 5], "minecraft:stripped_spruce_log");
    await set([x, 4, 5], "minecraft:lantern[hanging=false]");
  }
  await fill([SIDING_WEST + 1, 4, 3], [east - 1, 4, 5], "minecraft:spruce_slab[type=bottom]");
  for (const x of [SIDING_WEST + 4, SIDING_WEST + 11]) await set([x, 1, 4], "minecraft:spruce_stairs[facing=south]");
  // Fields away from the new route, so the valley reads as farmland the railway serves.
  await fill([6, -1, -44], [16, -1, -22], "minecraft:farmland[moisture=7]");
  await fill([6, 0, -44], [16, 0, -22], "minecraft:wheat[age=7]");
  await fill([-15, -1, -40], [-5, -1, -14], "minecraft:farmland[moisture=7]");
  await fill([-15, 0, -40], [-5, 0, -14], "minecraft:wheat[age=6]");
  await fill([-15, -1, 4], [-5, -1, 24], "minecraft:farmland[moisture=7]");
  await fill([-15, 0, 4], [-5, 0, 24], "minecraft:wheat[age=7]");
  for (const [x, z] of [[-8, -8], [22, -12], [30, -8], [-12, 28], [26, 14], [-6, -48], [12, -50], [34, -14]]) {
    await set([x, 0, z], "minecraft:azalea");
  }
  const station = { x: o.x + SIDING_WEST + 8, y: o.y, z: o.z + 1 };
  let placed: string | undefined;
  for (let attempt = 0; attempt < 4 && placed !== "create:track_station"; attempt++) {
    await tryCommand(server, `gamemode creative ${playerName}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with create:track_station`);
    await tryCommand(server, `tp ${playerName} ${station.x + 0.5} ${station.y} ${station.z + 1.5} 180 30`);
    await tick.sprint(server, 10 + attempt * 30);
    if (attempt > 0) await new Promise(done => setTimeout(done, 1500 * attempt));
    await player.useBlock(client, { x: station.x, y: o.y, z: o.z }, { face: "up" }).catch(() => null);
    await tick.sprint(server, 5);
    await player.useBlock(client, { ...station, y: station.y - 1 }, { face: "up" }).catch(() => null);
    await tick.sprint(server, 10);
    placed = await server.run(BLOCK_ID, station);
  }
  await tryCommand(server, `clear ${playerName} create:track_station`);
  const renamed = await reflect.setField(server, { target: { kind: "blockentity", position: station },
    path: "edgePoint.getEdgePoint()", field: "name", value: STATION_NAME }).catch((e: any) => ({ error: String(e) }));
  return { station: placed, renamed };
}

const BLOCK_ID = defineTask<Vec, string>({
  name: "folkways.promo.assistant.block-id", side: "server",
  source: javaTask({ imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries"], body: `
var a = Args.of(ctx);
var state = ctx.server().overworld().getBlockState(new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z")));
return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();` }),
});

export type RailState = { track: number; trackAbove: number; built: number; curves: number;
  junction: { x: number; y: number; z: number } | null; trunkBroken: number };

/**
 * How much of the new straight stands as track, what is built around it, the curves laid near the new route, and the
 * trunk block a curve leaves from (the junction). A trunk cell without track counts as broken.
 */
export const RAIL_STATE = defineTask<{ cells: Vec[]; trunk: RailSite["trunk"]; around: { min: Vec; max: Vec } }, RailState>({
  name: "folkways.promo.assistant.rail-state", side: "server",
  source: javaTask({ imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries",
    "com.simibubi.create.content.trains.track.TrackBlockEntity"], body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
int track = 0, trackAbove = 0, built = 0;
for (var raw : a.list("cells")) {
    var c = (Map<?, ?>) raw;
    var pos = new BlockPos(((Number) c.get("x")).intValue(), ((Number) c.get("y")).intValue(), ((Number) c.get("z")).intValue());
    if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath().equals("track")) track++;
    if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.above()).getBlock()).getPath().equals("track")) trackAbove++;
    for (int dy = -1; dy <= 0; dy++) for (int dz = -1; dz <= 1; dz++) {
        var id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.offset(0, dy, dz)).getBlock()).getPath();
        if (!id.equals("air") && !id.equals("grass_block") && !id.equals("dirt")) built++;
    }
}
var t = a.object("trunk");
int tx = (int) t.integer("x"), ty = (int) t.integer("y");
Map<String, Object> junction = null;
int broken = 0;
for (int z = (int) t.integer("z0"); z <= (int) t.integer("z1"); z++) {
    var pos = new BlockPos(tx, ty, z);
    if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath().equals("track")) broken++;
    if (junction == null && level.getBlockEntity(pos) instanceof TrackBlockEntity be && !be.getConnections().isEmpty()) {
        junction = Map.of("x", tx, "y", ty, "z", z);
    }
}
var box = a.object("around");
var min = box.object("min"); var max = box.object("max");
int curves = 0;
for (var pos : BlockPos.betweenClosed((int) min.integer("x"), (int) min.integer("y"), (int) min.integer("z"),
        (int) max.integer("x"), (int) max.integer("y"), (int) max.integer("z"))) {
    if (level.getBlockEntity(pos) instanceof TrackBlockEntity be) curves += be.getConnections().size();
}
var out = new LinkedHashMap<String, Object>();
out.put("track", track); out.put("trackAbove", trackAbove); out.put("built", built); out.put("curves", curves);
out.put("junction", junction); out.put("trunkBroken", broken);
return out;` }),
});

/**
 * Commissions the railway straight to the colony, as the assistant would, so the stage and the finished-track shot
 * can be worked on without asking the model.
 */
export const PREDEFINE_RAIL = defineTask<{ colony: string; player: string; points: Vec[]; read?: boolean }, any>({
  name: "folkways.promo.assistant.rail-predefine", side: "server", timeoutMs: 60_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.colony.api.Colonies",
    "io.github.izakyl.folkways.core.front.api.operation.Commissioning", "net.minecraft.core.BlockPos",
    "net.minecraft.core.Direction", "net.minecraft.nbt.CompoundTag", "net.minecraft.resources.ResourceLocation"], body: `
var a = Args.of(ctx);
if (a.has("read")) return Map.of("result", String.valueOf(System.getProperties().get("folkways.promo.rail-predefine")));
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var player = ctx.server().getPlayerList().getPlayerByName(a.string("player"));
var points = new ArrayList<BlockPos>();
for (var raw : a.list("points")) {
    var c = (Map<?, ?>) raw;
    points.add(new BlockPos(((Number) c.get("x")).intValue(), ((Number) c.get("y")).intValue(), ((Number) c.get("z")).intValue()));
}
var source = Commissioning.source().orElseThrow();
var request = new Commissioning.Request(ResourceLocation.parse("folkways_vanilla:railway"), points.getFirst(), points.getLast(),
    Direction.NORTH, "none", Optional.of(List.copyOf(points)), new CompoundTag());
System.getProperties().put("folkways.promo.rail-predefine", "pending");
source.draw(level, colony, request).thenCompose(drawing -> {
    if (!(drawing instanceof Commissioning.Drawing.Ready ready)) {
        System.getProperties().put("folkways.promo.rail-predefine", "refused " + drawing);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    System.getProperties().put("folkways.promo.rail-predefine", "drawn blocks=" + ready.blocks() + " joints=" + ready.joints()
        + " rail=" + ready.railConnections());
    return source.commission(player, level, colony, request).thenAccept(result -> System.getProperties().put(
        "folkways.promo.rail-predefine", "filed=" + result.filed() + " " + result.message().getString() + " joints=" + ready.joints()
        + " rail=" + ready.railConnections()));
}).exceptionally(failure -> { System.getProperties().put("folkways.promo.rail-predefine", "failed " + failure); return null; });
return Map.of("started", true);` }),
});

/** The colony's build orders, with the cell each is anchored at (where its card is opened from). */
export const BUILD_ORDERS = defineTask<{ colony: string }, Array<{ id: string; name: string; anchor: Vec; marks: Vec[]; progress: string }>>({
  name: "folkways.promo.assistant.build-orders", side: "server",
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.colony.api.Colonies",
    "io.github.izakyl.folkways.core.front.api.operation.Commissioning"], body: `
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
var out = new ArrayList<Object>();
for (var order : Commissioning.source().orElseThrow().orders(colony)) {
    var at = order.anchor().cell();
    out.add(Map.of("id", order.id().toString(), "name", order.name(), "anchor", Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ()),
        "marks", order.marks().stream().map(m -> Map.of("x", m.getX(), "y", m.getY(), "z", m.getZ())).toList(),
        "progress", String.valueOf(order.progress())));
}
return out;` }),
});
