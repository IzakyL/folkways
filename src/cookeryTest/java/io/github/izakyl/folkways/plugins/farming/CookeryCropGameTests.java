package io.github.izakyl.folkways.plugins.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
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
public final class CookeryCropGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryCropGameTests.class);
    }

    @GameTest(template = "empty")
    public static void lettuceCanBeSownGrownHarvestedAndSownAgain(GameTestHelper helper) {
        cycle(helper, ModBlocks.LETTUCE_CROP.get(), ModItems.LETTUCE_SEED.get(), ModItems.LETTUCE.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chiliCanBeSownGrownHarvestedAndSownAgain(GameTestHelper helper) {
        cycle(helper, ModBlocks.CHILI_CROP.get(), ModItems.CHILI_SEED.get(), ModItems.RED_CHILI.get());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cookeryCropsCanBeReplantedByTheFarmingPerk(GameTestHelper helper) {
        for (Block block : List.of(ModBlocks.LETTUCE_CROP.get(), ModBlocks.CHILI_CROP.get())) {
            try (Field field = new Field(helper, block, 1)) {
                field.pack.setItem(0, new ItemStack(field.crop.seed()));
                field.sow();
                field.grow();
                Outcome.Done done = field.harvest();
                helper.assertTrue(field.level().getBlockState(field.ground.above()).is(block),
                    "the perk replants the real crop after harvest");
                helper.assertFalse(field.crop.ripe(field.level(), field.ground.above()),
                    "replanting leaves a young crop");
                helper.assertTrue(done.made().stream().anyMatch(stack -> !Goods.matches(stack, field.crop.seedSpec())),
                    "the food yield remains after a seed is reserved for replanting");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingCookerySeedsCannotCreateCrops(GameTestHelper helper) {
        for (Block block : List.of(ModBlocks.LETTUCE_CROP.get(), ModBlocks.CHILI_CROP.get())) {
            try (Field field = new Field(helper, block, 0)) {
                helper.assertTrue(Commit.run(field.level(), field.plant(), field.worker) instanceof Outcome.Failed,
                    "the core refuses sowing without real seed goods");
                helper.assertTrue(field.level().getBlockState(field.ground.above()).isAir(),
                    "no free crop is created");
            }
        }
        helper.succeed();
    }

    private static void cycle(GameTestHelper helper, Block block, Item seed, Item food) {
        try (Field field = new Field(helper, block, 0)) {
            helper.assertTrue(Crop.sowable().contains(BuiltInRegistries.BLOCK.getKey(block)),
                "the real crop appears in the existing farming selector");
            helper.assertTrue(field.crop.seed() == seed, "cloning the crop resolves its real seed");
            helper.assertTrue(field.crop.needsFarmland(), "cookery crops use farmland");
            helper.assertTrue(field.crop.sowableAt(field.level(), List.of(field.ground), Set.of()),
                "the bare field can be sown");
            field.pack.setItem(0, new ItemStack(seed, 2));
            field.sow();
            helper.assertValueEqual((int) Goods.countIn(field.pack, field.crop.seedSpec()), 1,
                "sowing spends one real seed");
            helper.assertFalse(field.crop.ripe(field.level(), field.ground.above()), "young crops are not ripe");
            field.grow();
            helper.assertTrue(field.crop.harvestFrom(field.level(), field.ground).equals(List.of(field.ground.above())),
                "the mature real crop is recognized for harvesting");
            Outcome.Done done = field.harvest();
            if (block == ModBlocks.CHILI_CROP.get()) {
                helper.assertTrue(done.made().size() == 1 && (done.made().getFirst().is(ModItems.RED_CHILI.get())
                    || done.made().getFirst().is(ModItems.GREEN_CHILI.get())), "native picking yields exactly one red or green chili");
                helper.assertValueEqual(field.level().getBlockState(field.ground.above()).getValue(CropBlock.AGE), 5,
                    "native picking preserves the plant at age five");
                helper.assertValueEqual((int) Goods.countIn(field.pack, field.crop.seedSpec()), 1,
                    "picking spends no seed on the surviving plant");
                field.grow();
                helper.assertTrue(field.harvest().made().size() == 1, "the surviving plant can be picked again");
                return;
            }
            helper.assertTrue(done.made().stream().anyMatch(stack -> stack.is(food) && stack.getCount() > 0),
                "real loot yields cookery food");
            helper.assertTrue(done.made().stream().anyMatch(stack -> stack.is(seed) && stack.getCount() > 0),
                "real loot yields seeds for the next sowing");
            helper.assertTrue(field.level().getBlockState(field.ground.above()).isAir(), "harvesting clears the crop");
            done.made().forEach(stack -> helper.assertTrue(Containers.insert(field.pack, stack).isEmpty(),
                "the field's outputs fit in its worker's pack"));
            long before = Goods.countIn(field.pack, field.crop.seedSpec());
            field.sow();
            helper.assertTrue(field.level().getBlockState(field.ground.above()).is(block), "the next cycle starts");
            helper.assertValueEqual((int) Goods.countIn(field.pack, field.crop.seedSpec()), (int) before - 1,
                "the next sowing also spends a seed");
        }
    }

    static final class Field implements AutoCloseable {
        final GameTestHelper helper;
        final BlockPos ground;
        final Body body;
        final Crop crop;
        final SimpleContainer pack = new SimpleContainer(8);
        final Worker worker;

        Field(GameTestHelper helper, Block block, int replant) {
            this.helper = helper;
            ground = helper.absolutePos(new BlockPos(2, 1, 2));
            level().setBlock(ground, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_CLIENTS);
            level().setBlock(ground.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            body = PersonBody.RESIDENT.get().create(level());
            body.mob().setPos(ground.getCenter());
            crop = Crop.of(level(), ground.above(), BuiltInRegistries.BLOCK.getKey(block)).orElseThrow();
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body.mob(); }
                public Container pack() { return pack; }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { throw new AssertionError("the eight-slot pack has room"); }
                public int rankOf(String perk) { return FarmingContent.REPLANT.equals(perk) ? replant : 0; }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        net.minecraft.server.level.ServerLevel level() {
            return helper.getLevel();
        }

        PlantNode plant() {
            Job job = new Job(WorldPos.of(level(), ground), List.of(ground), Stances.WHEREVER, ground);
            return new PlantNode(new FarmNode.Chore(UUID.randomUUID(), crop, job,
                new FieldChores("plant", PlantNode::new, () -> { }, new PastDay())));
        }

        void sow() {
            helper.assertTrue(Commit.run(level(), plant(), worker) instanceof Outcome.Done, "sowing commits");
        }

        void grow() {
            CropBlock block = (CropBlock) crop.block();
            for (int i = 0; i < 8 && !crop.ripe(level(), ground.above()); i++) {
                block.performBonemeal(level(), level().getRandom(), ground.above(), level().getBlockState(ground.above()));
            }
            helper.assertTrue(crop.ripe(level(), ground.above()), "actual bonemeal growth reaches maturity");
        }

        Outcome.Done harvest() {
            Job job = new Job(WorldPos.of(level(), ground), List.of(ground.above()), Stances.WHEREVER, ground);
            HarvestNode node = new HarvestNode(new FarmNode.Chore(UUID.randomUUID(), crop, job,
                new FieldChores("harvest", HarvestNode::new, () -> { }, new PastDay())));
            Outcome outcome = Commit.run(level(), node, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "harvesting commits");
            return (Outcome.Done) outcome;
        }

        @Override
        public void close() {
            body.mob().discard();
            level().setBlock(ground.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
