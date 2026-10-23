package io.github.izakyl.folkways.plugins.build;

import com.google.gson.JsonParser;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildingPerkGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(BuildingPerkGameTests.class); }

    @GameTest(template = "empty")
    public static void triangularOreHeightHasExactWeightsAndZeroOutsideItsRange(GameTestHelper helper) {
        var context = new WorldGenerationContext(helper.getLevel().getChunkSource().getGenerator(), helper.getLevel());
        var distribution = LocalMinerals.distribution(JsonParser.parseString("""
            {"type":"minecraft:trapezoid", "min_inclusive":{"absolute":0}, "max_inclusive":{"absolute":4}}
            """), context);
        helper.assertTrue(Math.abs(distribution.get(2) - 1.0 / 3) < 1e-10, "triangle peak must be three times an edge");
        helper.assertTrue(Math.abs(distribution.get(0) - 1.0 / 9) < 1e-10, "inclusive endpoints have nonzero probability");
        helper.assertTrue(!distribution.containsKey(5), "out-of-range mineral cannot be selected");
        helper.assertTrue(Math.abs(distribution.values().stream().mapToDouble(Double::doubleValue).sum() - 1) < 1e-10, "distribution sums to one");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void biomeOreFeaturesRespectHeightAndDoNotYieldStonePatches(GameTestHelper helper) {
        var level = helper.getLevel();
        var below = LocalMinerals.candidates(level, helper.absolutePos(BlockPos.ZERO).atY(-59), Blocks.DEEPSLATE.defaultBlockState());
        var above = LocalMinerals.candidates(level, helper.absolutePos(BlockPos.ZERO).atY(180), Blocks.STONE.defaultBlockState());
        helper.assertTrue(below.stream().anyMatch(c -> c.ore().is(Blocks.DEEPSLATE_DIAMOND_ORE)), "deep stone has locally configured diamonds");
        helper.assertTrue(above.stream().noneMatch(c -> c.ore().is(Blocks.DIAMOND_ORE) || c.ore().is(Blocks.DEEPSLATE_DIAMOND_ORE)), "diamonds cannot be found above their configured height");
        helper.assertTrue(above.stream().noneMatch(c -> c.ore().is(Blocks.DIRT) || c.ore().is(Blocks.GRAVEL)), "non-mineral patch features are excluded");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void placedStoneCanBeProspectedRepeatedlyAtTheSamePosition(GameTestHelper helper) {
        var level = helper.getLevel();
        Worker worker = worker(PersonBody.RESIDENT.get().create(level), Effects.of(List.of(
            new Effects.Part("test", Modifier.add(BuildingPerks.PROSPECTING, 1)))));
        var pos = helper.absolutePos(new BlockPos(1, 1, 1)).atY(-59);
        var stone = Blocks.DEEPSLATE.defaultBlockState();
        for (int attempt = 0; attempt < 3; attempt++) {
            level.setBlock(pos, stone, 3);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            helper.assertFalse(BuildingPerks.discoveries(level, worker, pos, stone).isEmpty(),
                "placed stone can yield minerals every time it is mined at the same position");
        }
        helper.assertTrue(BuildingPerks.discoveries(level, worker, pos, Blocks.DIRT.defaultBlockState()).isEmpty(),
            "prospecting still requires eligible stone");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void prospectingAddsActualOreLootAndThirdRankCanFindASecondPortion(GameTestHelper helper) {
        Worker worker = worker(PersonBody.RESIDENT.get().create(helper.getLevel()), Effects.of(List.of(
            new Effects.Part("test", Modifier.add(BuildingPerks.PROSPECTING, 1)),
            new Effects.Part("test", Modifier.add(BuildingPerks.SECOND_FIND, 1)))));
        var pos = helper.absolutePos(new BlockPos(2, 1, 2)).atY(-59);
        var found = BuildingPerks.discoveries(helper.getLevel(), worker, pos, Blocks.DEEPSLATE.defaultBlockState());
        helper.assertTrue(found.size() == 2, "a successful extra-find roll yields two portions of actual mineral loot");
        helper.assertTrue(found.get(0).is(found.get(1).getItem()), "both portions come from the same locally selected ore");
        helper.assertTrue(BuildingPerks.discoveries(helper.getLevel(), worker, pos, Blocks.DEEPSLATE.defaultBlockState()).size() == 2, "the same position can yield another mineral find");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void materialRefundsUseSpentBlocksAndExcludeMachines(GameTestHelper helper) {
        Worker worker = worker(PersonBody.RESIDENT.get().create(helper.getLevel()), Effects.of(List.of(
            new Effects.Part("test", Modifier.add(BuildingPerks.MATERIAL_RETURN, 1)))));
        var refund = BuildingPerks.refunded(worker, List.of(new ItemStack(Items.STONE, 3)), Blocks.STONE.defaultBlockState());
        helper.assertTrue(refund.size() == 1 && refund.getFirst().getCount() == 3, "refund is based on actual spent construction blocks");
        helper.assertTrue(BuildingPerks.refunded(worker, List.of(new ItemStack(Items.FURNACE)), Blocks.FURNACE.defaultBlockState()).isEmpty(), "functional blocks cannot generate free machines");
        helper.assertTrue(BuildingPerks.refunded(worker, List.of(new ItemStack(Items.DIAMOND)), Blocks.STONE.defaultBlockState()).isEmpty(), "unrelated inputs cannot be duplicated");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void quarryMomentumBuildsThenResetsWhenTheMaterialChanges(GameTestHelper helper) {
        Worker worker = worker(PersonBody.RESIDENT.get().create(helper.getLevel()), Effects.of(List.of(
            new Effects.Part("test", Modifier.add(BuildingPerks.QUARRY_MOMENTUM, 1)))));
        for (int i = 0; i < 8; i++) BuildingPerks.mined(worker, Blocks.STONE.defaultBlockState());
        helper.assertTrue(BuildingPerks.momentum(worker) == 2, "eight matching stone blocks reach full momentum");
        BuildingPerks.mined(worker, Blocks.DEEPSLATE.defaultBlockState());
        helper.assertTrue(BuildingPerks.momentum(worker) == 1.125, "a different material starts a fresh streak");
        BuildingPerks.interrupt(worker);
        helper.assertTrue(BuildingPerks.momentum(worker) == 1, "construction interrupts the mining streak");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void quarryWindupUsesTheNextMaterialAndConstructionClearsMomentumImmediately(GameTestHelper helper) {
        var level = helper.getLevel();
        Worker worker = worker(PersonBody.RESIDENT.get().create(level), Effects.of(List.of(
            new Effects.Part("test", Modifier.add(BuildingPerks.QUARRY_MOMENTUM, 1)))));
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var worldPos = io.github.izakyl.folkways.core.api.terms.WorldPos.of(level, pos);
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        var strip = new StripNode(new BuildJob(BuildJob.Kind.STRIP, worldPos, Stances.WHEREVER,
            java.util.Map.of(worldPos, stone), java.util.Map.of(worldPos, air)), (node, how) -> { });
        var digging = (Workload.Once) strip.spec().workload();
        level.setBlockAndUpdate(pos, stone);
        for (int i = 0; i < 8; i++) BuildingPerks.mined(worker, stone);
        helper.assertValueEqual(digging.ticksFor(worker), digging.baseTicks() / 2,
            "full quarry momentum halves actual excavation wind-up");
        level.setBlockAndUpdate(pos, Blocks.DEEPSLATE.defaultBlockState());
        helper.assertValueEqual(digging.ticksFor(worker), digging.baseTicks(),
            "the first block of a different material gets no previous streak bonus");
        helper.assertTrue(BuildingPerks.momentum(worker) == 1, "switching targets clears the old streak before any block is mined");
        for (int i = 0; i < 8; i++) BuildingPerks.mined(worker, stone);
        level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
        helper.assertValueEqual(digging.ticksFor(worker), digging.baseTicks(),
            "a non-stone target also gets no quarry bonus");
        for (int i = 0; i < 8; i++) BuildingPerks.mined(worker, stone);
        var lay = new LayNode(new BuildJob(BuildJob.Kind.LAY, worldPos, Stances.WHEREVER,
            java.util.Map.of(worldPos, air), java.util.Map.of(worldPos, stone)), (node, how) -> { });
        ((Workload.Once) lay.spec().workload()).ticksFor(worker);
        helper.assertTrue(BuildingPerks.momentum(worker) == 1, "construction clears the streak at wind-up, before its commit");
        level.setBlockAndUpdate(pos, stone);
        helper.assertValueEqual(digging.ticksFor(worker), digging.baseTicks(),
            "returning immediately to the original stone after starting construction requires a new streak");
        helper.succeed();
    }

    private static Worker worker(Body body, Effects effects) {
        Container pack = new SimpleContainer();
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body.mob(); }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { throw new AssertionError("unexpected spill"); }
            public Effects effects() { return effects; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }
}
