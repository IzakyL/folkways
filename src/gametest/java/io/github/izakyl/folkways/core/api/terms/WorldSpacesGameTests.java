package io.github.izakyl.folkways.core.api.terms;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import io.github.izakyl.folkways.plugins.person.walk.ResidentNodeEvaluator;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldSpacesGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WorldSpacesGameTests.class);
    }

    @GameTest(template = "empty")
    public static void structureAddressesResolveStorageAndRotatedArrival(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos storage = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(storage, Blocks.CHEST.defaultBlockState());
        UUID id = UUID.randomUUID();
        Vec3[] origin = {Vec3.atLowerCornerOf(storage.offset(20, 4, 0))};
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return id; }
            public Level level() { return level; }
            public BlockPos storageOrigin() { return storage; }
            public Vec3 toWorld(Vec3 local) { return origin[0].add(-local.z, local.y, local.x); }
            public Vec3 toLocal(Vec3 world) {
                Vec3 delta = world.subtract(origin[0]);
                return new Vec3(delta.z, delta.y, -delta.x);
            }
        };
        Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<WorldSpaces.Frame> frame(Level host, UUID key) {
                return host == level && key.equals(id) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> containing(Level host, BlockPos cell) {
                return host == level && cell.equals(storage) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> aboard(Entity entity) { return Optional.empty(); }
        });
        try {
            WorldPos address = WorldPos.of(level, storage);
            helper.assertTrue(address.realm().equals(new Realm.Frame(id)) && address.cell().equals(BlockPos.ZERO),
                "captured addresses use stable local coordinates");
            helper.assertTrue(Stores.at(level, address).isPresent(), "inventory reads must use storage coordinates");
            var mob = EntityType.VILLAGER.create(level);
            Vec3 at = WorldSpaces.world(level, address).orElseThrow();
            mob.setPos(at);
            helper.assertTrue(Reach.standingIn(mob, Stances.at(address)), "arrival uses inverse rotation");
            origin[0] = origin[0].add(5, 0, 3);
            helper.assertTrue(!Reach.standingIn(mob, Stances.at(address)), "moving the target invalidates old arrival");
            mob.setPos(WorldSpaces.world(level, address).orElseThrow());
            helper.assertTrue(Reach.standingIn(mob, Stances.at(address)), "arrival follows the new pose");
            helper.assertTrue(WorldPos.of(level, storage).equals(address), "motion must not change identity");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                address.write(buffer);
                helper.assertTrue(WorldPos.read(buffer).equals(address), "frame network round trip");
                WorldPos ordinary = WorldPos.of(Level.NETHER, new BlockPos(2, 3, 4));
                ordinary.write(buffer);
                helper.assertTrue(WorldPos.read(buffer).equals(ordinary), "dimension network round trip");
                helper.assertTrue(Stores.at(level, ordinary).isEmpty(), "never read a foreign dimension at local XYZ");
                helper.assertTrue(!Reach.standingIn(mob, Stances.at(ordinary)), "foreign dimensions cannot arrive");
            } finally {
                buffer.release();
            }
            helper.succeed();
        } finally {
            remove.run();
        }
    }

    @GameTest(template = "empty")
    public static void movingObstaclesNeverBecomeWalkingFloors(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
        AABB[] obstacle = {new AABB(cell).move(0.25, 0.2, 0.1)};
        Runnable remove = Structures.install((host, area) -> host == level && area.intersects(obstacle[0])
            ? List.of(obstacle[0]) : List.of());
        try {
            var mob = EntityType.VILLAGER.create(level);
            mob.setPos(Vec3.atBottomCenterOf(cell));
            var region = Structures.region(level, cell.offset(-3, -3, -3), cell.offset(3, 4, 3));
            var context = new PathfindingContext(region, mob);
            var evaluator = new ResidentNodeEvaluator(cell);
            helper.assertTrue(evaluator.getPathType(context, cell.getX(), cell.getY(), cell.getZ()) == PathType.BLOCKED,
                "fractional obstacle blocks the occupied cell");
            helper.assertTrue(evaluator.getPathType(context, cell.getX(), cell.getY() + 2, cell.getZ()) == PathType.BLOCKED,
                "top of a Create obstacle is not a floor");
            helper.assertTrue(!region.noCollision(new AABB(cell).deflate(0.1)),
                "collision queries must see the same synthetic obstacle as path types");
            obstacle[0] = obstacle[0].move(10, 0, 0);
            var fresh = Structures.region(level, cell.offset(-3, -3, -3), cell.offset(3, 4, 3));
            helper.assertTrue(!fresh.getBlockState(cell).is(Blocks.BARRIER), "vacated world space opens again");
            helper.succeed();
        } finally {
            remove.run();
        }
    }
}
