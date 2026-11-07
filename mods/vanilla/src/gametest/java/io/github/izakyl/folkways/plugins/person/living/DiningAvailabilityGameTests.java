package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.front.engine.Enrollments;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ZoneDrafts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiningAvailabilityGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        event.register(DiningAvailabilityGameTests.class);
    }

    @GameTest(template = "empty")
    public static void noTablesDisableDiningAndRefuseNewDrawings(GameTestHelper helper) throws Exception {
        try (var fixture = new AvailabilityFixture()) {
            var level = helper.getLevel();
            var colony = Colonies.mint(level.getServer());
            try {
                BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
                var front = ColonyFront.of(colony);
                var existing = front.addZone(level, LivingContent.DINING, Set.of(at),
                    ColonySettings.empty()).orElseThrow();
                helper.assertFalse(DiningTables.anyTable(), "empty tags and no adapters mean no tables");
                helper.assertTrue(DiningTables.sample().isEmpty(), "no table means no preview sample");
                var reason = Enrollments.delegation(LivingContent.DINING).orElseThrow()
                    .disabledReason().orElseThrow();
                helper.assertValueEqual(((TranslatableContents) reason.getContents()).getKey(),
                    "folkways.dining.requires_tables", "the picker receives the translated missing-table reason");
                helper.assertTrue(ZoneDrafts.zone(level, colony, LivingContent.DINING, at.east(), at.east(),
                    ColonySettings.empty()).isEmpty(), "the packet's server path refuses a new dining area");
                helper.assertValueEqual(front.zones().size(), 1, "a rejected drawing adds no holding");
                helper.assertTrue(front.zone(existing.id()).isPresent(), "existing dining areas remain intact");
            } finally { colony.raze(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void eitherTableTagEnablesDiningAndKeepsTablesOutOfFootings(GameTestHelper helper) throws Exception {
        try (var fixture = new AvailabilityFixture()) {
            for (String path : List.of("dining/tables", "housing/table")) {
                fixture.tagged(path);
                assertEnabled(helper);
                helper.assertTrue(DiningTables.sample().orElseThrow().is(Blocks.EMERALD_BLOCK),
                    "either live table tag supplies the preview sample");
                BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
                helper.getLevel().setBlockAndUpdate(at, Blocks.EMERALD_BLOCK.defaultBlockState());
                helper.assertTrue(DiningTables.table(helper.getLevel(), at), "either tag declares a table");
                helper.assertFalse(Footing.withFeetAt(helper.getLevel(), at.above()),
                    "tagged tabletops cannot be used as footing");
            }
            fixture.tagged(null);
            helper.assertFalse(DiningTables.anyTable(), "removing the final tagged table disables dining again");
            helper.assertTrue(DiningTables.sample().isEmpty(), "removed table tags leave no stale preview sample");
            helper.assertTrue(Enrollments.delegation(LivingContent.DINING).orElseThrow().disabledReason().isPresent(),
                "availability follows tag reloads");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void adaptersEnableDiningAndSamplesPreferLiveTags(GameTestHelper helper) throws Exception {
        try (var fixture = new AvailabilityFixture()) {
            DiningTables.register(ResourceLocation.fromNamespaceAndPath("folkways", "test_available_table"),
                new DiningTables.Table() {
                    public boolean stable() { return true; }
                    public boolean matches(ServerLevel level, BlockPos at) {
                        return level.getBlockState(at).is(Blocks.DIAMOND_BLOCK);
                    }
                    public Container inventory(ServerLevel level, BlockPos at) { return null; }
                });
            assertEnabled(helper);
            helper.assertTrue(DiningTables.sample().isEmpty(),
                "an adapter without a sample enables dining but cannot supply its Ponder scene");
            DiningTables.register(ResourceLocation.fromNamespaceAndPath("folkways", "test_table_sample"),
                new DiningTables.Table() {
                    public boolean stable() { return true; }
                    public boolean matches(ServerLevel level, BlockPos at) {
                        return level.getBlockState(at).is(Blocks.DIAMOND_BLOCK);
                    }
                    public Container inventory(ServerLevel level, BlockPos at) { return null; }
                    public Optional<BlockState> sample() {
                        return Optional.of(Blocks.DIAMOND_BLOCK.defaultBlockState());
                    }
                });
            for (String path : List.of("dining/tables", "housing/table")) {
                helper.assertTrue(DiningTables.sample().orElseThrow().is(Blocks.DIAMOND_BLOCK),
                    "the adapter supplies a sample when the tags are empty");
                fixture.tagged(path);
                helper.assertTrue(DiningTables.sample().orElseThrow().is(Blocks.EMERALD_BLOCK),
                    "live tags take precedence over an adapter's sample");
                fixture.tagged(null);
            }
            helper.assertTrue(DiningTables.sample().orElseThrow().is(Blocks.DIAMOND_BLOCK),
                "clearing tags restores the adapter sample without restarting");
        }
        helper.succeed();
    }

    private static void assertEnabled(GameTestHelper helper) {
        helper.assertTrue(DiningTables.anyTable(), "a declared table makes dining available");
        helper.assertTrue(Enrollments.delegation(LivingContent.DINING).orElseThrow().disabledReason().isEmpty(),
            "the picker enables dining");
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            BlockPos at = helper.absolutePos(new BlockPos(4, 1, 2));
            helper.assertTrue(ZoneDrafts.zone(helper.getLevel(), colony, LivingContent.DINING, at, at,
                ColonySettings.empty()).isPresent(), "the server accepts the enabled dining drawing");
        } finally { colony.raze(); }
    }

    // Each test restores the shared registries before another test or server tick can use them.
    private static final class AvailabilityFixture implements AutoCloseable {
        private final Map<ResourceLocation, DiningTables.Table> tables;
        private final Map<ResourceLocation, DiningTables.Table> saved;
        private final Map<TagKey<Block>, List<Holder<Block>>> tags = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        AvailabilityFixture() throws ReflectiveOperationException {
            var field = DiningTables.class.getDeclaredField("TABLES");
            field.setAccessible(true);
            tables = (Map<ResourceLocation, DiningTables.Table>) field.get(null);
            saved = new LinkedHashMap<>(tables);
            BuiltInRegistries.BLOCK.getTags().forEach(pair ->
                tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
            tables.clear();
            tagged(null);
            DiningTables.installKeepout();
        }

        void tagged(String chosen) {
            var changed = new LinkedHashMap<>(tags);
            for (String path : List.of("dining/tables", "housing/table")) {
                var tag = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("folkways", path));
                changed.put(tag, path.equals(chosen)
                    ? List.of(Blocks.EMERALD_BLOCK.builtInRegistryHolder()) : List.of());
            }
            BuiltInRegistries.BLOCK.bindTags(changed);
        }

        public void close() {
            tables.clear();
            tables.putAll(saved);
            BuiltInRegistries.BLOCK.bindTags(tags);
        }
    }
}
