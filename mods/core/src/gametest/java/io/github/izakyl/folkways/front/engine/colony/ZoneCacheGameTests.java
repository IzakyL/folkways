package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZoneCacheGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "zone_cache_test");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ZoneCacheGameTests.class); }

    @GameTest(template = "empty")
    public static void stableZonesReuseCoordinatesAndSettingsChangesInvalidateThem(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        try {
            var front = ColonyFront.of(colony);
            var zone = front.addZone(level, OWNER, Set.of(helper.absolutePos(BlockPos.ZERO)), ColonySettings.empty()).orElseThrow();
            var first = front.zonesIn(level).getFirst();
            for (int i = 0; i < 100; i++) {
                helper.assertTrue(front.zonesIn(level).getFirst() == first, "unchanged zones reuse their entire coordinate map");
            }
            front.setDrawn(zone.id(), ColonySettings.empty().with("enabled", new ColonySettings.Value.Flag(false)));
            var changed = front.zonesIn(level).getFirst();
            helper.assertTrue(changed != first, "a setting change invalidates the cached view");
            helper.assertFalse(changed.settings().flag("enabled"), "the fresh view carries the changed setting");
            front.erase(zone.id());
            helper.assertTrue(front.zonesIn(level).isEmpty(), "erased zones leave no cached view");
        } finally { colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFrameStorageChangeRebuildsCachedCoordinates(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        UUID id = UUID.randomUUID();
        BlockPos[] origin = {helper.absolutePos(new BlockPos(1, 1, 1))};
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return id; }
            public Level level() { return level; }
            public BlockPos storageOrigin() { return origin[0]; }
            public Vec3 toWorld(Vec3 local) { return local.add(Vec3.atLowerCornerOf(origin[0])); }
            public Vec3 toLocal(Vec3 world) { return world.subtract(Vec3.atLowerCornerOf(origin[0])); }
        };
        Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<? extends WorldSpaces.Frame> frame(Level world, UUID key) {
                return world == level && key.equals(id) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<? extends WorldSpaces.Frame> containing(Level world, BlockPos pos) { return Optional.empty(); }
            public Optional<? extends WorldSpaces.Frame> aboard(Entity body) { return Optional.empty(); }
        });
        try {
            WorldPos at = new WorldPos(new Realm.Frame(id), BlockPos.ZERO);
            colony.hold(OWNER, new Held.Area(Set.of(at))).orElseThrow();
            var front = ColonyFront.of(colony);
            var first = front.zonesIn(level).getFirst();
            helper.assertTrue(front.zonesIn(level).getFirst() == first, "a stable frame needs no coordinate rebuild");
            origin[0] = origin[0].east(2);
            var moved = front.zonesIn(level).getFirst();
            helper.assertTrue(moved != first && moved.cells().contains(origin[0]), "changed storage coordinates invalidate the cache");
            helper.assertValueEqual(moved.at(origin[0]), at, "the frame address keeps its identity");
            remove.run();
            helper.assertTrue(front.zonesIn(level).isEmpty(), "an unavailable frame cannot leave stale coordinates");
        } finally { remove.run(); colony.raze(); }
        helper.succeed();
    }
}
