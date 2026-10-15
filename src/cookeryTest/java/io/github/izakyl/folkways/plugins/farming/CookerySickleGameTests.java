package io.github.izakyl.folkways.plugins.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.TeaTreeBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModEnchantments;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.cookery.farming.CookerySickle;
import io.github.izakyl.folkways.plugins.cookery.farming.ResidentSickleHarvestEvent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookerySickleGameTests {
    @SubscribeEvent
    public static void declare(Declaring event) { CookerySickle.register(); }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookerySickleGameTests.class); }

    @GameTest(template = "empty")
    public static void sickleSelectionClaimsARealSharedToolButOrdinaryPickingDoesNot(GameTestHelper helper) {
        try (var field = new CookeryCropGameTests.Field(helper, ModBlocks.TOMATO_CROP.get(), 0)) {
            helper.assertTrue(field.crop.harvesting(HarvestMethods.PICK).harvestMethod().isEmpty(),
                "the default retains native ordinary picking");
            HarvestNode harvest = node(field, chores());
            helper.assertTrue(harvest.spec().tools().stream().anyMatch(tool -> tool.need().equals(CookerySickle.SICKLE)),
                "the explicit mode declares a shared sickle for runtime borrowing");
            helper.assertTrue(Commit.run(field.level(), harvest, field.worker) instanceof Outcome.Failed,
                "a real tool is required; producing a sickle does not require one");
            helper.assertTrue(!field.crop.harvesting(CookerySickle.MODE).harvestMethod().isEmpty(), "the mode is registered");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sweepThreeKeepsNeighborPlantsSafeCostsEightOnceAndHonorsCooldown(GameTestHelper helper) {
        try (var field = new CookeryCropGameTests.Field(helper, Blocks.WHEAT, 0)) {
            field.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS));
            field.sow();
            field.grow();
            BlockPos neighbor = field.ground.offset(1, 0, 0);
            field.level().setBlock(neighbor, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
            field.level().setBlock(neighbor.above(), field.level().getBlockState(field.ground.above()), Block.UPDATE_ALL);
            ItemStack sickle = new ItemStack(ModItems.SICKLE.get());
            sickle.enchant(field.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(ModEnchantments.SWEEP), 3);
            field.pack.setItem(1, sickle);
            Outcome outcome = Commit.run(field.level(), node(field, chores()), field.worker);
            helper.assertTrue(outcome instanceof Outcome.Done done
                && done.made().stream().anyMatch(stack -> stack.is(Items.WHEAT)), "real wheat loot is delivered");
            helper.assertValueEqual(field.level().getBlockState(field.ground.above()).getValue(CropBlock.AGE), 0,
                "native sickle harvesting resets the root without consuming another seed");
            helper.assertValueEqual(field.level().getBlockState(neighbor.above()).getValue(CropBlock.AGE), 7,
                "the adjacent root is outside this job's claims, even at sweep three");
            helper.assertValueEqual(sickle.getDamageValue(), 8, "native sweep-three wear occurs once");
            helper.assertTrue(Commit.run(field.level(), node(field, chores()), field.worker) instanceof Outcome.Failed failed
                && failed.why() == FarmRefusal.SICKLE_COOLDOWN, "a second invocation within ten ticks waits");
            helper.assertValueEqual(sickle.getDamageValue(), 8, "the rejected invocation does not wear the tool again");
            field.level().setBlock(neighbor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sickleRiceResetsAllThreePartsAndSettlesNativePaniclesOnce(GameTestHelper helper) {
        try (var field = new CookeryCropGameTests.Field(helper, ModBlocks.RICE_CROP.get(), 0)) {
            for (int dy = 1; dy <= 3; dy++) field.level().setBlock(field.ground.above(dy),
                dy == 1 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            field.pack.setItem(0, new ItemStack(ModItems.WILD_RICE_SEED.get()));
            field.sow();
            field.grow();
            field.pack.setItem(1, new ItemStack(ModItems.SICKLE.get()));
            Outcome.Done done = (Outcome.Done) Commit.run(field.level(), node(field, chores()), field.worker);
            int panicles = done.made().stream().filter(stack -> stack.is(ModItems.RICE_PANICLE.get()))
                .mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(panicles >= 2 && panicles <= 4, "one real mature root settles its native panicles once");
            helper.assertTrue(done.made().stream().anyMatch(stack -> stack.is(ModItems.WILD_RICE_SEED.get())),
                "native wild seeds are preserved as output rather than spent on reset");
            for (int dy = 1; dy <= 3; dy++) {
                var state = field.level().getBlockState(field.ground.above(dy));
                helper.assertTrue(state.is(ModBlocks.RICE_CROP.get()), "the complete native rice plant survives");
                helper.assertValueEqual(state.getValue(CropBlock.AGE), 0, "all parts synchronize to young rice");
                helper.assertValueEqual(state.getValue(RiceCropBlock.LOCATION), dy - 1, "native part position remains");
            }
            helper.assertValueEqual(field.pack.getItem(1).getDamageValue(), 1, "three parts cost a single use");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sickleTeaTomatoAndChiliUseWholeHarvestLootAndResetToZero(GameTestHelper helper) {
        for (Block block : List.of(ModBlocks.TEA_TREE.get(), ModBlocks.TOMATO_CROP.get(), ModBlocks.CHILI_CROP.get())) {
            try (var field = new CookeryCropGameTests.Field(helper, block, 0)) {
                boolean tea = block instanceof TeaTreeBlock;
                if (tea) field.level().setBlock(field.ground, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                field.pack.setItem(0, new ItemStack(field.crop.seed()));
                field.sow();
                if (tea) {
                    for (int i = 0; i < TeaTreeBlock.MAX_AGE; i++) ((TeaTreeBlock) block).performBonemeal(
                        field.level(), field.level().random, field.ground.above(), field.level().getBlockState(field.ground.above()));
                } else field.grow();
                field.pack.setItem(1, new ItemStack(ModItems.SICKLE.get()));
                Outcome.Done done = (Outcome.Done) Commit.run(field.level(), node(field, chores()), field.worker);
                helper.assertTrue(done.made().stream().anyMatch(stack -> stack.is(tea ? ModItems.FRESH_TEA_LEAVES.get() : field.crop.seed())),
                    "native whole-harvest loot includes its real guaranteed product; tea seeds remain probabilistic");
                helper.assertValueEqual(field.level().getBlockState(field.ground.above())
                    .getValue(tea ? TeaTreeBlock.AGE : CropBlock.AGE), 0,
                    "sickle harvest differs from the ordinary age-three/five pick");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blacklistAndUnripePlantsProduceNoLootAndNoWear(GameTestHelper helper) {
        try (var field = new CookeryCropGameTests.Field(helper, Blocks.WHEAT, 0)) {
            field.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS));
            field.sow();
            ItemStack sickle = new ItemStack(ModItems.SICKLE.get());
            field.pack.setItem(1, sickle);
            Outcome.Done young = (Outcome.Done) Commit.run(field.level(), node(field, chores()), field.worker);
            helper.assertTrue(young.made().isEmpty(), "an unripe plant is unchanged");
            field.level().setBlock(field.ground.above(), Blocks.MELON_STEM.defaultBlockState(), Block.UPDATE_ALL);
            Outcome.Done blacklisted = (Outcome.Done) new CookerySickle().harvest(field.level(), field.worker,
                Blocks.MELON_STEM, List.of(field.ground.above()));
            helper.assertTrue(blacklisted.made().isEmpty()
                && field.level().getBlockState(field.ground.above()).is(Blocks.MELON_STEM),
                "the real upstream melon-stem blacklist remains untouched");
            helper.assertValueEqual(sickle.getDamageValue(), 0, "neither operation wears the tool");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void residentEventCanCancelWithOrWithoutToolCostWithoutInventingAPlayer(GameTestHelper helper) {
        try (var field = new CookeryCropGameTests.Field(helper, Blocks.WHEAT, 0)) {
            field.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS));
            field.sow();
            field.grow();
            ItemStack sickle = new ItemStack(ModItems.SICKLE.get());
            field.pack.setItem(1, sickle);
            AtomicBoolean cost = new AtomicBoolean();
            Consumer<ResidentSickleHarvestEvent> listener = event -> {
                if (!event.getEntity().getUUID().equals(field.body.id())) return;
                helper.assertTrue(event.getEntity() == field.body.mob() && event.getSickle() == sickle,
                    "the event carries the real resident and physical tool");
                event.setCanceled(true);
                event.setCostDurability(cost.get());
            };
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                Outcome.Done first = (Outcome.Done) Commit.run(field.level(), node(field, chores()), field.worker);
                helper.assertTrue(first.made().isEmpty() && first.xp().isEmpty(), "a cancelled harvest has no products or XP");
                helper.assertValueEqual(sickle.getDamageValue(), 0, "default cancellation costs no durability");
                cost.set(true);
                Outcome.Done second = (Outcome.Done) Commit.run(field.level(), node(field, chores()), field.worker);
                helper.assertTrue(second.made().isEmpty() && second.xp().isEmpty(), "paid cancellation still grants no products or XP");
                helper.assertValueEqual(sickle.getDamageValue(), 1, "the event may explicitly cost one native use");
                helper.assertValueEqual(field.level().getBlockState(field.ground.above()).getValue(CropBlock.AGE), 7,
                    "the mature plant survives both cancelled actions");
            } finally { NeoForge.EVENT_BUS.unregister(listener); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lastDurabilityBreaksTheBorrowedPhysicalToolOnlyAfterTheHarvest(GameTestHelper helper) {
        try (var field = new CookeryCropGameTests.Field(helper, Blocks.WHEAT, 0)) {
            field.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS));
            field.sow();
            field.grow();
            ItemStack sickle = new ItemStack(ModItems.SICKLE.get());
            sickle.setDamageValue(sickle.getMaxDamage() - 1);
            field.pack.setItem(1, sickle);
            Outcome.Done done = (Outcome.Done) Commit.run(field.level(), node(field, chores()), field.worker);
            helper.assertTrue(done.made().stream().anyMatch(stack -> stack.is(Items.WHEAT)), "the final real use still harvests");
            helper.assertTrue(field.pack.getItem(1).isEmpty(), "the one physical borrowed tool breaks, without a spare copy");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawnFarmChoreCannotHarvestItsOldRoot(GameTestHelper helper) {
        try (var field = new CookeryCropGameTests.Field(helper, Blocks.WHEAT, 0)) {
            field.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS));
            field.sow();
            field.grow();
            field.pack.setItem(1, new ItemStack(ModItems.SICKLE.get()));
            FieldChores chores = chores();
            HarvestNode node = node(field, chores);
            helper.assertTrue(node.planned(field.level()).isEmpty(), "a current farm job can be planned");
            chores.close();
            helper.assertTrue(node.refusal(field.level()).orElseThrow() == FarmRefusal.GROUND_TAKEN,
                "withdrawal is an explicit refusal, not an endless wait");
            helper.assertTrue(Commit.run(field.level(), node, field.worker) instanceof Outcome.Failed,
                "an already planned action cannot revive a withdrawn plot");
            helper.assertValueEqual(field.level().getBlockState(field.ground.above()).getValue(CropBlock.AGE), 7,
                "the old mature root is untouched");
            helper.assertValueEqual(field.pack.getItem(1).getDamageValue(), 0, "no tool wear occurs on refused work");
        }
        helper.succeed();
    }

    private static FieldChores chores() {
        return new FieldChores("harvest", HarvestNode::new, () -> { }, new PastDay());
    }

    private static HarvestNode node(CookeryCropGameTests.Field field, FieldChores chores) {
        Job job = new Job(WorldPos.of(field.level(), field.ground), List.of(field.ground.above()), Stances.WHEREVER, field.ground);
        return new HarvestNode(new FarmNode.Chore(UUID.randomUUID(), field.crop.harvesting(CookerySickle.MODE), job, chores));
    }
}
