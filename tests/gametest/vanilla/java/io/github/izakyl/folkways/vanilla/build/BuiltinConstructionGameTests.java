package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.build.draft.Commission;
import io.github.izakyl.folkways.vanilla.build.draft.DraftTemplate;
import io.github.izakyl.folkways.vanilla.build.draft.Drawn;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Pattern;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import io.github.izakyl.folkways.vanilla.build.draft.World;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises shipped scripts through the same NBT handoff and construction commits as real commissions. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BuiltinConstructionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuiltinConstructionGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledArcadeBuildsItsRoofAndHangingLights(GameTestHelper helper) {
        build(helper, "arcade", Map.of("width", 3, "pavilions", false), false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledShedArcadeBuildsAnAlternativeRoof(GameTestHelper helper) {
        build(helper, "arcade", Map.of("width", 3, "pavilions", false, "roof", id("shed")), false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledCityWallBuildsTowersAndStairs(GameTestHelper helper) {
        build(helper, "city_wall", Map.of("height", 5, "thickness", 3, "spacing", 0, "gate", false), false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledRoadBuildsLightsAndPaving(GameTestHelper helper) {
        build(helper, "road", Map.of("width", 3, "lights", true, "spacing", 6), false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledBridgeBuildsArchAndParapets(GameTestHelper helper) {
        build(helper, "arch_bridge", Map.of("width", 3), false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledDockBuildsSubmergedPilesLadderAndShelter(GameTestHelper helper) {
        build(helper, "dock", Map.of("width", 3, "shelter", true), true, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledQuarryExcavatesItsRampAndPit(GameTestHelper helper) {
        build(helper, "quarry", Map.of("depth", 3), false, true);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledTerraceBuildsIrrigationAndFences(GameTestHelper helper) {
        build(helper, "terraced_field", Map.of("rise", 1, "spacing", 8, "fenced", true), false, true);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void bundledRailwayBuildsItsBedAndTracks(GameTestHelper helper) {
        build(helper, "railway", Map.of(), false, false);
    }

    private static void build(GameTestHelper helper, String name, Map<String, Object> overrides,
            boolean lake, boolean zone) {
        var level = helper.getLevel();
        for (int x = name.equals("city_wall") ? -12 : 0; x <= (name.equals("city_wall") ? 35 : 23); x++) {
            for (int z = name.equals("city_wall") ? -12 : 0; z <= (name.equals("city_wall") ? 35 : 23); z++) {
                int top = lake && z >= 6 ? 2 : name.equals("terraced_field") ? 6 + Math.max(0, (17 - z) / 4) : 6;
                for (int y = 1; y <= 18; y++) {
                    level.setBlock(at(helper, x, y, z), y <= top ? Blocks.STONE.defaultBlockState()
                        : lake && y <= 5 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", name)).orElseThrow();
        Hint hint = zone ? new Hint.Zone(at(helper, 6, 7, 6), at(helper, 15, 7, 17))
            : new Hint.Path(List.of(at(helper, 10, 7, 3), at(helper, 10, 7, 19)));
        Drawn drawn = pattern.drawOn(new Commission(hint, new Defaults(pattern.knobs(), overrides),
            World.of(level), Direction.NORTH, 42L));
        helper.assertTrue(drawn instanceof Drawn.Ready, name + " can draw on the fixture: " + drawn);
        Drawn.Ready ready = (Drawn.Ready) drawn;
        Blueprint blueprint = StructureNbt.toBlueprint(DraftTemplate.of(ready.draft(), 0), level.registryAccess(),
            UUID.randomUUID(), name, level.getGameTime(), StructureNbt.AirMeaning.EXCAVATE, 100000).orElseThrow();
        BuildTarget target = BuildTarget.of(BlueprintBuildOrder.create(blueprint.id(), name,
            WorldPos.of(level, ready.corner()), Optional.empty(), "", level.getGameTime()), blueprint.confirm(level));
        SiteRig rig = SiteRig.start(level, target);
        helper.assertTrue(rig.site.refusals().isEmpty(), name + " planning refuses no cells: " + rig.site.refusals()
            .entrySet().stream().limit(8).toList());
        SimpleContainer pack = new SimpleContainer(128);
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(at(helper, 1, 7, 1)));
        Worker worker = TestWorker.of(body, new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig")), pack)
            .held(need -> new ItemStack(Items.DIAMOND_PICKAXE)).spill(stack -> { });
        int[] commits = {0};
        Map<BlockPos, Outcome> failures = new HashMap<>();
        helper.onEachTick(() -> {
            if (rig.site.complete()) { return; }
            for (int round = 0; round < 32; round++) {
                boolean progress = false;
                for (BuildSite.Piece piece : rig.site.pieces()) {
                    if (!rig.site.before(piece).isEmpty()) { continue; }
                    pack.clearContent();
                    int slot = 0;
                    for (var need : piece.action().spec().needs()) {
                        var item = Goods.members(need.spec()).stream().findFirst().orElseThrow();
                        long left = need.count();
                        while (left > 0) {
                            int count = (int) Math.min(left, item.getDefaultMaxStackSize());
                            pack.setItem(slot++, new ItemStack(item, count));
                            left -= count;
                        }
                    }
                    Outcome outcome = Commit.run(level, piece.action(), worker);
                    if (outcome instanceof Outcome.Done) {
                        rig.end(piece.action(), Ending.DONE);
                        commits[0]++;
                        progress = true;
                    } else {
                        failures.put(piece.work().focus(), outcome);
                    }
                }
                rig.site.tick(level);
                if (!progress || rig.site.complete()) { break; }
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.site.complete(),
                name + " finishes; commits=" + commits[0] + " left=" + rig.site.pieces().size()
                    + " refusals=" + rig.site.refusals().entrySet().stream().limit(8).toList()
                    + " failed=" + failures.entrySet().stream().limit(8).toList()))
            .thenExecuteAfter(20, () -> {
                List<String> wrong = new ArrayList<>();
                target.cells().forEach((pos, wanted) -> {
                    // Completion is latched before neighbour/fluid simulation changes derived properties.
                    // Still require every constructed block to remain present and every source to retain its fluid.
                    if (!wanted.isAir() && (wanted.liquid()
                            ? wanted.getFluidState().isSource() && !level.getFluidState(pos).getType()
                                .isSame(wanted.getFluidState().getType())
                            : !level.getBlockState(pos).is(wanted.getBlock()))) {
                        wrong.add(helper.relativePos(pos)
                            .toShortString() + " expected=" + wanted + " actual=" + level.getBlockState(pos));
                    }
                });
                helper.assertTrue(wrong.isEmpty(), name + " constructed blocks survive neighbor/fluid ticks; wrong="
                    + wrong.size() + " " + wrong.stream().limit(12).toList());
                helper.assertTrue(rig.site.complete(), name + " stays complete after neighbour/fluid ticks");
                helper.assertValueEqual(rig.site.metCells().size(), target.cells().size(),
                    name + " retains every completed target cell");
                rig.site.close();
                body.discard();
            }).thenSucceed();
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        // The GameTest template has an enclosing barrier roof. Keep this terrain wholly above it.
        return helper.absolutePos(new BlockPos(x, y + 40, z));
    }

    private static ResourceLocation id(String local) {
        return ResourceLocation.fromNamespaceAndPath("pattern", local);
    }

    private record Defaults(Map<String, Object> values) implements Settings {
        private Defaults(Schema schema, Map<String, Object> overrides) {
            this(new HashMap<>());
            for (Schema.Setting setting : schema.settings()) {
                switch (setting) {
                    case Schema.Setting.Flag flag -> values.put(flag.key(), flag.byDefault());
                    case Schema.Setting.Count count -> values.put(count.key(), count.byDefault());
                    case Schema.Setting.Choice choice -> values.put(choice.key(), choice.byDefault());
                    case Schema.Setting.Items items -> values.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                        .map(filter -> filter.tag() ? ItemSpec.of(TagKey.create(Registries.ITEM, filter.id()))
                            : ItemSpec.of(filter.id())).toList()));
                }
            }
            values.putAll(overrides);
        }
        public boolean flag(String key) { return (Boolean) values.get(key); }
        public int count(String key) { return (Integer) values.get(key); }
        public ResourceLocation choice(String key) { return (ResourceLocation) values.get(key); }
        public Optional<ItemSpec> items(String key) { return Optional.ofNullable((ItemSpec) values.get(key)); }
    }
}
