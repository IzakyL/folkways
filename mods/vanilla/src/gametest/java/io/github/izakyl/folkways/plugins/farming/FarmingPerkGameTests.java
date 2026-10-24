package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FarmingPerkGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(FarmingPerkGameTests.class); }

    @GameTest(template = "empty")
    public static void chainHarvestStaysInsideTheSurveyedFieldAndSkipsReservedJobs(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        Crop crop = Crop.of(level, base, ResourceLocation.withDefaultNamespace("wheat")).orElseThrow();
        Job first = job(level, base), next = job(level, base.east()), outside = job(level, base.east(2));
        FieldChores chores = new FieldChores("test", HarvestNode::new, () -> { }, new PastDay());
        chores.settle(crop, List.of(first, next), java.util.Set.of(first.cell(), next.cell()));
        helper.assertValueEqual(chores.neighbors(first, 7).size(), 2, "only surveyed neighboring crops join");
        chores.planned(new FarmNode.Chore(java.util.UUID.randomUUID(), crop, next, chores));
        helper.assertValueEqual(chores.neighbors(first, 7).size(), 1, "another worker's crop stays reserved");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void careDoesNotStackAndSurvivesSaving(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        CropCare care = CropCare.of(level);
        var state = Blocks.WHEAT.defaultBlockState();
        care.forget(at);
        care.tend(at, state, .2);
        care.tend(at, state, .6);
        var saved = care.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
        var reloaded = CropCare.load(saved).save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
        var record = reloaded.getList("crops", net.minecraft.nbt.Tag.TAG_COMPOUND).stream()
            .map(tag -> (net.minecraft.nbt.CompoundTag) tag).filter(tag -> tag.getLong("pos") == at.asLong())
            .findFirst().orElseThrow();
        helper.assertTrue(record.getDouble("bonus") == .2, "repeated care cannot stack or replace the treatment");
        care.forget(at);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualHarvestChainsProducesMoreFoodAndReplants(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        for (int x = 0; x < 3; x++) {
            level.setBlock(at.east(x).below(), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(at.east(x), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 3);
        }
        Crop crop = Crop.of(level, at, ResourceLocation.withDefaultNamespace("wheat")).orElseThrow();
        Job first = job(level, at), next = job(level, at.east());
        FieldChores chores = new FieldChores("test", HarvestNode::new, () -> { }, new PastDay());
        chores.settle(crop, List.of(first, next), java.util.Set.of(first.cell(), next.cell()));
        var node = new HarvestNode(new FarmNode.Chore(java.util.UUID.randomUUID(), crop, first, chores));
        var body = io.github.izakyl.folkways.plugins.person.PersonBody.RESIDENT.get().create(level);
        var effects = io.github.izakyl.folkways.effect.Effects.of(List.of(
            new io.github.izakyl.folkways.effect.Effects.Part("chain", io.github.izakyl.folkways.effect.Modifier.add(FarmingContent.CHAIN, 7)),
            new io.github.izakyl.folkways.effect.Effects.Part("bonus", io.github.izakyl.folkways.effect.Modifier.add(FarmingContent.HARVEST_BONUS, 1)),
            new io.github.izakyl.folkways.effect.Effects.Part("seed", io.github.izakyl.folkways.effect.Modifier.add(FarmingContent.SEED_SAVING, 1)),
            new io.github.izakyl.folkways.effect.Effects.Part("replant", io.github.izakyl.folkways.effect.Modifier.grant(FarmingContent.REPLANTS))));
        var worker = (io.github.izakyl.folkways.core.api.work.Worker) java.lang.reflect.Proxy.newProxyInstance(
            io.github.izakyl.folkways.core.api.work.Worker.class.getClassLoader(),
            new Class<?>[] {io.github.izakyl.folkways.core.api.work.Worker.class}, (proxy, method, args) -> switch(method.getName()) {
                case "body" -> body.mob();
                case "effects" -> effects;
                default -> throw new AssertionError(method.getName());
            });
        var outcome = node.work(level, worker, first);
        helper.assertTrue(outcome instanceof io.github.izakyl.folkways.core.api.work.Outcome.Done, "harvest succeeds");
        var done = (io.github.izakyl.folkways.core.api.work.Outcome.Done) outcome;
        int wheat = done.made().stream().filter(stack -> stack.is(net.minecraft.world.item.Items.WHEAT))
            .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
        helper.assertValueEqual(wheat, 4, "both wheat crops yield double their primary food product");
        helper.assertValueEqual(level.getBlockState(at).getValue(CropBlock.AGE), 0, "first crop replanted");
        helper.assertValueEqual(level.getBlockState(at.east()).getValue(CropBlock.AGE), 0, "neighbor replanted");
        helper.assertValueEqual(level.getBlockState(at.east(2)).getValue(CropBlock.AGE), 7, "outside crop untouched");
        helper.succeed();
    }

    private static Job job(net.minecraft.server.level.ServerLevel level, BlockPos crop) {
        return new Job(WorldPos.of(level, crop.below()), List.of(crop), Stances.WHEREVER, crop.below());
    }
}
