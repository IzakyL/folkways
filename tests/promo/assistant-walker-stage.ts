import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

// Let it Walk!'s demo spider on a stone apron at the edge of a meadow, a Create Big Cannons gun on its deck beside the
// pilot controls and a powder barrel behind them. Out on the meadow to the south stand two timber bunkers it is sent
// against. The spider faces south.

export type Walker = { hull: string; controls: Vec; controlsStore: Vec; muzzleStore: Vec; powerStore: Vec;
  store: Vec; deck: Vec[]; core: Vec; coreStore: Vec };

export async function clearWalkerGround(server: MinecraftServer, pad: Vec) {
  const r = (x: number, y: number, z: number) => `${pad.x + x} ${pad.y + y} ${pad.z + z}`;
  const fill = (a: number[], b: number[], block: string) =>
    tryCommand(server, `fill ${r(a[0], a[1], a[2])} ${r(b[0], b[1], b[2])} ${block}`);
  for (const z0 of [-24, 6, 36]) await fill([-24, 0, z0], [24, 16, z0 + 29], "minecraft:air");
  await fill([-24, -1, -24], [24, -1, 65], "minecraft:grass_block");
  for (const [x, z] of [[-14, 14], [16, 22], [-18, 40], [12, 48], [-6, 58], [20, 4]]) {
    await tryCommand(server, `setblock ${r(x, 0, z)} minecraft:azalea`);
  }
  // Two bunkers on the meadow to the south.
  for (const [x, z] of [[-5, 44], [7, 50]]) {
    await fill([x - 2, 0, z - 2], [x + 2, 3, z + 2], "minecraft:spruce_planks");
    await fill([x - 1, 1, z - 1], [x + 1, 3, z + 1], "minecraft:air");
    await fill([x - 2, 4, z - 2], [x + 2, 4, z + 2], "minecraft:dark_oak_slab[type=bottom]");
    await fill([x - 2, 2, z - 2], [x + 2, 2, z - 2], "minecraft:spruce_fence");
  }
}

export async function buildWalker(server: MinecraftServer, pad: Vec) {
  const built = await server.run(BUILD, pad);
  // The demo spider's feet touch the ground during assembly. Lay the apron afterward so it stays in the overworld.
  const floor = pad.y - 1;
  await tryCommand(server, `fill ${pad.x - 8} ${floor} ${pad.z - 8} ${pad.x + 8} ${floor} ${pad.z + 8} minecraft:polished_andesite`);
  return built;
}

/** Once the spider stands: the gun on its deck, the powder barrel, and where a crew stands. */
export async function armWalker(server: MinecraftServer, pad: Vec): Promise<Walker> {
  return server.run(ARM, pad);
}

const BUILD = defineTask<Vec, any>({
  name: "folkways.promo.assistant.walker-build", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "dev.letitwalk.demo.DemoWalkers"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var pad = new BlockPos((int) a.integer("x"), (int) a.integer("y") - 1, (int) a.integer("z"));
for (int cx = pad.getX() - 48 >> 4; cx <= pad.getX() + 48 >> 4; cx++)
    for (int cz = pad.getZ() - 32 >> 4; cz <= pad.getZ() + 72 >> 4; cz++) level.setChunkForced(cx, cz, true);
var built = DemoWalkers.build(level, pad.above(), Direction.SOUTH, DemoWalkers.Type.BUILT_SPIDER, pad.getY() + 1);
return Map.of("built", String.valueOf(built == null ? "" : built.getString()));`,
  }),
});

const ARM = defineTask<Vec, Walker>({
  name: "folkways.promo.assistant.walker-arm", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation", "net.minecraft.world.level.block.Block", "net.minecraft.world.level.block.Blocks",
      "net.minecraft.world.level.block.DirectionalBlock", "dev.ryanhcode.sable.Sable",
      "dev.ryanhcode.sable.companion.math.BoundingBox3d", "dev.letitwalk.walker.WalkerTracker",
      "dev.letitwalk.content.WalkerCoreBlock",
      "io.github.izakyl.folkways.core.world.api.WorldSpaces",
      "com.simibubi.create.content.kinetics.crank.HandCrankBlock",
      "rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock",
      "rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var pad = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var box = new BoundingBox3d(pad.getX() - 30, pad.getY() - 4, pad.getZ() - 30, pad.getX() + 30, pad.getY() + 40, pad.getZ() + 30);
dev.ryanhcode.sable.sublevel.SubLevel hull = null;
for (var sub : Sable.HELPER.getAllIntersecting(level, box)) if (WalkerTracker.coreOf(sub) != null) hull = sub;
if (hull == null) throw new IllegalStateException("no walker stands on the pad");
var core = WalkerTracker.coreOf(hull);
var facing = level.getBlockState(core).getValue(WalkerCoreBlock.FACING);
var controlsAt = WorldSpaces.at(level, core.relative(facing, 3));
var mountCell = controlsAt.at(controlsAt.cell().east(2));
var stored = WorldSpaces.storage(level, mountCell).orElseThrow();
java.util.function.Function<String, Block> block = name -> BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("createbigcannons", name));
var direction = Direction.EAST;
var bore = stored.above(2);
level.setBlockAndUpdate(stored, block.apply("cannon_mount").defaultBlockState().setValue(CannonMountBlock.HORIZONTAL_FACING, direction));
var crank = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "hand_crank")).defaultBlockState();
level.setBlockAndUpdate(stored.below(), crank.setValue(HandCrankBlock.FACING, Direction.DOWN));
level.setBlockAndUpdate(stored.south(), crank.setValue(HandCrankBlock.FACING, Direction.SOUTH));
// Keep the unpowered barrel connected to the Sable hull until CBC picks it up.
level.setBlockAndUpdate(stored.above(), Blocks.POLISHED_ANDESITE.defaultBlockState());
var barrel = block.apply("bronze_cannon_barrel").defaultBlockState().setValue(DirectionalBlock.FACING, direction);
var chamber = block.apply("bronze_cannon_chamber").defaultBlockState().setValue(DirectionalBlock.FACING, direction);
level.setBlockAndUpdate(bore, chamber);
level.setBlockAndUpdate(bore.east(), chamber);
level.setBlockAndUpdate(bore.east(2), barrel);
level.setBlockAndUpdate(bore.west(), block.apply("bronze_quickfiring_breech").defaultBlockState().setValue(DirectionalBlock.FACING, direction));
for (int offset = -1; offset <= 2; offset++) BigCannonBlock.onPlace(level, bore.offset(offset, 0, 0));
// The powder barrel on the deck behind the controls.
var storeCell = controlsAt.at(controlsAt.cell().relative(facing, -1).west(2));
var storeAt = WorldSpaces.storage(level, storeCell).orElseThrow();
level.setBlockAndUpdate(storeAt, Blocks.BARREL.defaultBlockState());
var deck = new ArrayList<Object>();
for (var cell : List.of(controlsAt.cell().relative(facing.getClockWise()), controlsAt.cell().relative(facing, -2))) {
    var world = WorldSpaces.world(level, controlsAt.at(cell)).orElseThrow();
    deck.add(Map.of("x", world.x, "y", world.y + 0.02, "z", world.z));
}
java.util.function.Function<BlockPos, Map<String, Object>> xyz = p -> Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
var controlsWorld = WorldSpaces.world(level, controlsAt).orElseThrow();
var controlsStore = WorldSpaces.storage(level, controlsAt).orElseThrow();
var coreWorld = WorldSpaces.world(level, WorldSpaces.at(level, core)).orElseThrow();
return Map.of("hull", hull.getUniqueId().toString(),
    "controls", Map.of("x", controlsWorld.x, "y", controlsWorld.y, "z", controlsWorld.z),
    "controlsStore", xyz.apply(controlsStore), "muzzleStore", xyz.apply(bore.relative(direction, 2)),
    "powerStore", xyz.apply(stored.west()), "store", xyz.apply(storeAt), "deck", deck,
    "core", Map.of("x", coreWorld.x, "y", coreWorld.y, "z", coreWorld.z), "coreStore", xyz.apply(core));`,
  }),
});

/** Where the walker's core stands now, and whether a shot is in flight. */
export const WALKER_STATE = defineTask<{ core: Vec }, any>({
  name: "folkways.promo.assistant.walker-state", side: "server",
  source: javaTask({
    imports: ["net.minecraft.world.phys.AABB", "io.github.izakyl.folkways.core.world.api.WorldSpaces",
      "rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile"],
    body: `
var level = ctx.server().overworld();
var c = Args.of(ctx).object("core");
var core = new net.minecraft.core.BlockPos((int) c.number("x"), (int) c.number("y"), (int) c.number("z"));
var at = WorldSpaces.world(level, WorldSpaces.at(level, core)).orElseThrow();
var shells = level.getEntitiesOfClass(AbstractBigCannonProjectile.class, new AABB(at, at).inflate(160)).size();
return Map.of("x", at.x, "y", at.y, "z", at.z, "shells", shells);`,
  }),
});

/** The Create Big Cannons entity the powered mount assembled near `at` (a storage cell on the hull), or null. */
export const GUN_ENTITY = defineTask<Vec, { uuid: string | null }>({
  name: "folkways.promo.assistant.walker-gun", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.entity.Entity",
      "net.minecraft.world.phys.AABB"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var box = new AABB(a.integer("x") - 8, a.integer("y") - 8, a.integer("z") - 8,
    a.integer("x") + 9, a.integer("y") + 9, a.integer("z") + 9);
for (Entity entity : level.getEntities((Entity) null, box,
        e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace().equals("createbigcannons"))) {
    return Map.of("uuid", entity.getUUID().toString());
}
var none = new java.util.HashMap<String, Object>();
none.put("uuid", null);
return none;`,
  }),
});
