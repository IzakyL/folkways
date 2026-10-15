package io.github.izakyl.folkways.plugins.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.TeaTreeBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.cookery.farming.CookeryCrops;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryCropLifecycleGameTests {
    @SubscribeEvent
    public static void declare(Declaring event) {
        CookeryCrops.register();
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryCropLifecycleGameTests.class);
    }

    @GameTest(template = "empty")
    public static void wildRiceBuildsThreeNativePartsAndItsOwnDropsStartTheNextCycle(GameTestHelper helper) {
        try (var field = rice(helper, 0)) {
            field.pack.setItem(0, new ItemStack(ModItems.WILD_RICE_SEED.get()));
            field.sow();
            parts(helper, field, 0);
            helper.assertTrue(field.pack.isEmpty(), "one wild seed creates one complete plant");
            field.grow();
            parts(helper, field, 7);
            helper.assertValueEqual(field.crop.canopyOf(field.level(), List.of(field.ground.above())).size(), 2,
                "the root also claims its two attached parts against duplicate harvest jobs");
            Outcome.Done harvest = field.harvest();
            int panicles = harvest.made().stream().filter(stack -> stack.is(ModItems.RICE_PANICLE.get()))
                .mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(panicles >= 2 && panicles <= 4, "one mature root gives its real two to four panicles");
            helper.assertTrue(harvest.made().stream().anyMatch(stack -> stack.is(ModItems.WILD_RICE_SEED.get())),
                "the native loot supplies wild seeds for another cycle");
            helper.assertTrue(field.level().getBlockState(field.ground.above()).is(Blocks.WATER),
                "cutting preserves the paddy water");
            helper.assertTrue(field.level().getBlockState(field.ground.above(2)).isAir()
                && field.level().getBlockState(field.ground.above(3)).isAir(), "the upper parts leave no duplicate drops");
            harvest.made().forEach(stack -> helper.assertTrue(Containers.insert(field.pack, stack).isEmpty(), "loot fits"));
            long seeds = Goods.countIn(field.pack, field.crop.seedSpec());
            field.sow();
            parts(helper, field, 0);
            helper.assertValueEqual(Goods.countIn(field.pack, field.crop.seedSpec()), seeds - 1,
                "the next complete plant spends exactly one harvested wild seed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ricePlacementRefusesDryOrObstructedPaddiesAndRefundsTheSeed(GameTestHelper helper) {
        try (var field = rice(helper, 0)) {
            field.pack.setItem(0, new ItemStack(ModItems.WILD_RICE_SEED.get()));
            field.level().setBlock(field.ground.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(Commit.run(field.level(), field.plant(), field.worker) instanceof Outcome.Failed,
                "a dry field refuses placement");
            field.level().setBlock(field.ground.above(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            field.level().setBlock(field.ground.above(3), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(Commit.run(field.level(), field.plant(), field.worker) instanceof Outcome.Failed,
                "an obstructed third part refuses placement");
            helper.assertValueEqual(Goods.countIn(field.pack, field.crop.seedSpec()), 1L,
                "neither failed placement spends or duplicates the one seed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void riceReplantingPerkUsesNativePlacementAndOneHarvestedWildSeed(GameTestHelper helper) {
        try (var field = rice(helper, 1)) {
            field.pack.setItem(0, new ItemStack(ModItems.WILD_RICE_SEED.get()));
            field.sow();
            field.grow();
            field.harvest();
            parts(helper, field, 0);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void tomatoAndTeaPickingPreserveTheirNativeRegrowthStages(GameTestHelper helper) {
        for (Block block : List.of(ModBlocks.TOMATO_CROP.get(), ModBlocks.TEA_TREE.get())) {
            try (var field = new CookeryCropGameTests.Field(helper, block, 1)) {
                boolean tea = block == ModBlocks.TEA_TREE.get();
                if (tea) field.level().setBlock(field.ground, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                field.pack.setItem(0, new ItemStack(field.crop.seed()));
                field.sow();
                if (tea) {
                    for (int i = 0; i < TeaTreeBlock.MAX_AGE; i++) ((TeaTreeBlock) block).performBonemeal(
                        field.level(), field.level().getRandom(), field.ground.above(), field.level().getBlockState(field.ground.above()));
                } else field.grow();
                Outcome.Done picked = field.harvest();
                helper.assertValueEqual(picked.made().size(), 1, "one native fruit or leaf is picked");
                helper.assertTrue(picked.made().getFirst().is(tea ? ModItems.FRESH_TEA_LEAVES.get() : ModItems.TOMATO.get()),
                    "the actual crop's native product is delivered");
                helper.assertValueEqual(field.level().getBlockState(field.ground.above())
                    .getValue(tea ? TeaTreeBlock.AGE : CropBlock.AGE), tea ? 3 : 5,
                    "the living plant retains its exact native regrowth age");
                helper.assertTrue(field.pack.isEmpty(), "picking does not need another seed or create seed output");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void edibleRiceCanPlantWhenTheSowingPlanSelectsItAsFallback(GameTestHelper helper) {
        try (var field = rice(helper, 0)) {
            Crop fallback = field.crop.using(ModItems.RICE_SEED.get());
            field.pack.setItem(0, new ItemStack(ModItems.RICE_SEED.get()));
            var job = new Job(io.github.izakyl.folkways.core.api.terms.WorldPos.of(field.level(), field.ground),
                List.of(field.ground), io.github.izakyl.folkways.core.api.work.Stances.WHEREVER, field.ground);
            var node = new PlantNode(new FarmNode.Chore(java.util.UUID.randomUUID(), fallback, job,
                new FieldChores("plant", PlantNode::new, () -> { }, new io.github.izakyl.folkways.front.api.PastDay())));
            helper.assertTrue(Commit.run(field.level(), node, field.worker) instanceof Outcome.Done,
                "the real edible rice item can sow through its native BlockItem placement");
            helper.assertTrue(field.pack.isEmpty(), "the actual fallback grain is spent once");
            parts(helper, field, 0);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void interruptedNativePlacementRestoresThePaddyBeforeTheCoreRefundsItsSeed(GameTestHelper helper) {
        try (var field = rice(helper, 0)) {
            field.pack.setItem(0, new ItemStack(ModItems.WILD_RICE_SEED.get()));
            java.util.function.Consumer<net.neoforged.neoforge.event.VanillaGameEvent> listener = event -> {
                if (event.getVanillaEvent().equals(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE)) {
                    throw new IllegalStateException("test: native rice was placed before its event failed");
                }
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                false, net.neoforged.neoforge.event.VanillaGameEvent.class, listener);
            boolean threw = false;
            try {
                Commit.run(field.level(), field.plant(), field.worker);
            } catch (IllegalStateException expected) {
                threw = true;
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            }
            helper.assertTrue(threw, "the test interrupts the actual native placement event");
            helper.assertValueEqual(Goods.countIn(field.pack, field.crop.seedSpec()), 1L,
                "the core returns exactly the original seed");
            helper.assertTrue(field.level().getBlockState(field.ground.above()).is(Blocks.WATER)
                && field.level().getBlockState(field.ground.above(2)).isAir()
                && field.level().getBlockState(field.ground.above(3)).isAir(),
                "a refunded placement leaves no free plant or damaged paddy");
        }
        helper.succeed();
    }

    private static CookeryCropGameTests.Field rice(GameTestHelper helper, int replant) {
        var field = new CookeryCropGameTests.Field(helper, ModBlocks.RICE_CROP.get(), replant);
        for (int up = 1; up <= 3; up++) field.level().setBlock(field.ground.above(up),
            up == 1 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return field;
    }

    private static void parts(GameTestHelper helper, CookeryCropGameTests.Field field, int age) {
        for (int part = 0; part <= 2; part++) {
            var state = field.level().getBlockState(field.ground.above(part + 1));
            helper.assertTrue(state.is(ModBlocks.RICE_CROP.get()), "native placement creates every rice part");
            helper.assertValueEqual(state.getValue(RiceCropBlock.LOCATION), part, "the part has its native location");
            helper.assertValueEqual(state.getValue(CropBlock.AGE), age, "native growth keeps all parts synchronized");
        }
        helper.assertTrue(field.level().getBlockState(field.ground.above()).getValue(RiceCropBlock.WATERLOGGED),
            "the native bottom stays waterlogged");
    }
}
