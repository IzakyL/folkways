package io.github.izakyl.folkways.front.engine.authority;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AtBlockGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(AtBlockGameTests.class); }

    @GameTest(template = "empty")
    public static void packetReachFollowsTheVisibleRotatedStructureAndStillRejectsRemoteBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos storage = helper.absolutePos(new BlockPos(2, 1, 2));
        UUID id = UUID.randomUUID();
        Vec3[] origin = { Vec3.atLowerCornerOf(storage.offset(1000, 20, -1000)) };
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return id; }
            public Level level() { return level; }
            public BlockPos storageOrigin() { return storage; }
            public Vec3 toWorld(Vec3 local) { return origin[0].add(-local.z, local.y, local.x); }
            public Vec3 toLocal(Vec3 world) {
                Vec3 delta = world.subtract(origin[0]); return new Vec3(delta.z, delta.y, -delta.x);
            }
        };
        Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<WorldSpaces.Frame> frame(Level host, UUID key) {
                return host == level && id.equals(key) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> containing(Level host, BlockPos cell) {
                return host == level && storage.equals(cell) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> aboard(Entity entity) { return Optional.empty(); }
        });
        try {
            Vec3 visible = frame.toWorld(new Vec3(0.5, 0.5, 0.5));
            helper.assertTrue(AtBlock.nearby(level, visible.add(0, 0, 2), storage),
                "a real nearby ship block is not rejected for its distant storage address");
            helper.assertFalse(AtBlock.nearby(level, Vec3.atCenterOf(storage), storage),
                "standing near storage alone cannot authorize remote ship interaction");
            helper.assertTrue(AtBlock.nearby(level, visible.add(8, 0, 0), storage), "the eight-block reach limit remains inclusive");
            helper.assertFalse(AtBlock.nearby(level, visible.add(8.01, 0, 0), storage), "the same reach limit rejects remote visible blocks");
            origin[0] = origin[0].add(30, 0, 0);
            helper.assertFalse(AtBlock.nearby(level, visible, storage), "moving the ship invalidates an old nearby position");
            helper.assertTrue(AtBlock.nearby(level, frame.toWorld(new Vec3(0.5, 0.5, 0.5)), storage),
                "reach follows its current pose");
        } finally { remove.run(); }
        helper.assertTrue(AtBlock.nearby(level, Vec3.atCenterOf(storage).add(2, 0, 0), storage), "ordinary blocks retain world reach");
        helper.assertFalse(AtBlock.nearby(level, Vec3.atCenterOf(storage).add(9, 0, 0), storage), "ordinary remote blocks stay refused");
        helper.succeed();
    }
}
