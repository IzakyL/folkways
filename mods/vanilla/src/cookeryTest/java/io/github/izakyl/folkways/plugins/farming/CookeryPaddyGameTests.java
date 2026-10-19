package io.github.izakyl.folkways.plugins.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryPaddyGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryPaddyGameTests.class); }

    @GameTest(template = "empty")
    public static void submergedNativeSoilsPublishTillingWithoutMutatingThePaddyDuringScanning(GameTestHelper helper) {
        for (Block soil : List.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH)) {
            try (var rig = paddy(helper, soil)) {
                Field field = field(rig);
                try {
                    List<BlockState> before = column(rig);
                    List<Node> work = scan(rig, field, 1);
                    helper.assertTrue(column(rig).equals(before), "a paddy sweep only observes its original soil, water and headroom");
                    helper.assertTrue(work.size() == 1 && work.getFirst() instanceof TillNode,
                        "submerged " + soil + " publishes exactly one hoe task before native rice can be planted");
                    helper.assertTrue(rig.pack.isEmpty(), "read-only scanning creates neither seeds nor tools");
                } finally { field.close(); }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void residentsTillSubmergedSoilAndCompleteTheNativeRiceLifecycleWithoutLosingWater(GameTestHelper helper) {
        for (Block soil : List.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH)) {
            try (var rig = paddy(helper, soil)) {
                rig.pack.setItem(0, new ItemStack(ModItems.WILD_RICE_SEED.get()));
                rig.pack.setItem(1, new ItemStack(Items.WOODEN_HOE));
                Field field = field(rig);
                try {
                    List<Node> tilling = scan(rig, field, 1);
                    helper.assertTrue(tilling.size() == 1 && tilling.getFirst() instanceof TillNode,
                        "the real field first plans the submerged soil's hoe task");
                    perform(helper, rig, tilling.getFirst());
                    helper.assertTrue(rig.level().getBlockState(rig.ground).is(Blocks.FARMLAND)
                            && rig.level().getBlockState(rig.ground.above()).equals(Blocks.WATER.defaultBlockState()),
                        "actual hoe work converts only the soil and retains the original paddy water");
                    helper.assertValueEqual(Goods.countIn(rig.pack, rig.crop.seedSpec()), 1L, "tilling does not spend the future rice seed");
                    List<Node> sowing = scan(rig, field, 2);
                    helper.assertTrue(sowing.size() == 1 && sowing.getFirst() instanceof PlantNode,
                        "a later sweep discovers native sowing only after the soil has really been tilled");
                    perform(helper, rig, sowing.getFirst());
                    parts(helper, rig, 0);
                    helper.assertValueEqual(Goods.countIn(rig.pack, rig.crop.seedSpec()), 0L, "the three-part plant spends exactly one actual wild seed");
                    rig.grow();
                    parts(helper, rig, 7);
                    List<Node> harvesting = scan(rig, field, 3);
                    helper.assertTrue(harvesting.size() == 1 && harvesting.getFirst() instanceof HarvestNode,
                        "only the native mature root owns harvesting of the full three-part plant");
                    Outcome.Done harvest = perform(helper, rig, harvesting.getFirst());
                    int panicles = harvest.made().stream().filter(stack -> stack.is(ModItems.RICE_PANICLE.get()))
                        .mapToInt(ItemStack::getCount).sum();
                    helper.assertTrue(panicles >= 2 && panicles <= 4, "one native harvest yields its real two to four rice panicles");
                    helper.assertTrue(rig.level().getBlockState(rig.ground.above()).is(Blocks.WATER)
                            && rig.level().getBlockState(rig.ground.above(2)).isAir()
                            && rig.level().getBlockState(rig.ground.above(3)).isAir(),
                        "native harvesting retains water and removes both attached parts without duplicate harvesting");
                    long seeds = Goods.countIn(rig.pack, rig.crop.seedSpec());
                    helper.assertTrue(seeds > 0, "only the native harvested wild seed supplies the next cycle");
                    List<Node> next = scan(rig, field, 4);
                    helper.assertTrue(next.size() == 1 && next.getFirst() instanceof PlantNode,
                        "the real next sweep replants without needlessly tilling the intact paddy again");
                    perform(helper, rig, next.getFirst());
                    parts(helper, rig, 0);
                    helper.assertValueEqual(Goods.countIn(rig.pack, rig.crop.seedSpec()), seeds - 1,
                        "replanting consumes one harvested seed and no injected material");
                } finally { field.close(); }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMissingHoeOrChangedPaddyCannotBeDamagedByAnAlreadyPlannedTillingTask(GameTestHelper helper) {
        for (String change : List.of("missing_hoe", "wrong_tool", "water_gone", "solid_above", "soil_changed")) {
            try (var rig = paddy(helper, Blocks.DIRT)) {
                rig.pack.setItem(0, new ItemStack(ModItems.WILD_RICE_SEED.get()));
                rig.pack.setItem(1, new ItemStack(Items.WOODEN_HOE));
                Field field = field(rig);
                try {
                    List<Node> goals = scan(rig, field, 1);
                    helper.assertTrue(goals.size() == 1 && goals.getFirst() instanceof TillNode, "the original wet dirt publishes its real task");
                    Node till = goals.getFirst();
                    helper.assertTrue(till.planned(rig.level()).isEmpty(), "the native field adopts the original paddy task");
                    switch (change) {
                        case "missing_hoe" -> rig.pack.setItem(1, ItemStack.EMPTY);
                        case "wrong_tool" -> rig.pack.setItem(1, new ItemStack(Items.WOODEN_SHOVEL));
                        case "water_gone" -> place(rig, rig.ground.above(), Blocks.AIR.defaultBlockState());
                        case "solid_above" -> place(rig, rig.ground.above(), Blocks.STONE.defaultBlockState());
                        case "soil_changed" -> place(rig, rig.ground, Blocks.STONE.defaultBlockState());
                        default -> throw new AssertionError(change);
                    }
                    List<BlockState> before = column(rig);
                    Outcome result = Commit.run(rig.level(), till, rig.worker);
                    helper.assertTrue(column(rig).equals(before), "stale " + change + " cannot till, clear, drain or replace the changed paddy");
                    helper.assertTrue(!(result instanceof Outcome.Done done) || done.made().isEmpty(), "rejected paddy work creates no drops");
                    helper.assertValueEqual(Goods.countIn(rig.pack, rig.crop.seedSpec()), 1L, "rejected tilling leaves the only real seed untouched");
                    till.ended(rig.level(), Ending.REVOKED);
                } finally { field.close(); }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unsupportedUnderwaterSoilsNeverPublishOrAcceptRiceTilling(GameTestHelper helper) {
        for (Block soil : List.of(Blocks.STONE, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.SAND)) {
            try (var rig = paddy(helper, soil)) {
                rig.pack.setItem(0, new ItemStack(Items.WOODEN_HOE));
                Field field = field(rig);
                try {
                    List<BlockState> before = column(rig);
                    helper.assertTrue(scan(rig, field, 1).isEmpty(), "the rice scan does not transform unsupported submerged " + soil);
                    var tending = new FieldChores("rejected-paddy", chore -> new TillNode(chore, Tillage.Clearing.ALL), () -> { }, new PastDay());
                    var job = new Job(WorldPos.of(rig.level(), rig.ground), List.of(rig.ground), Stances.WHEREVER, rig.ground);
                    Node stale = new TillNode(new FarmNode.Chore(UUID.randomUUID(), rig.crop, job, tending), Tillage.Clearing.ALL);
                    Commit.run(rig.level(), stale, rig.worker);
                    helper.assertTrue(column(rig).equals(before), "even a stale task preserves unsupported soil and its water");
                } finally { field.close(); }
            }
        }
        helper.succeed();
    }

    private static CookeryCropGameTests.Field paddy(GameTestHelper helper, Block soil) {
        var rig = new CookeryCropGameTests.Field(helper, ModBlocks.RICE_CROP.get(), 0);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            place(rig, rig.ground.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            for (int up = 1; up <= 3; up++) place(rig, rig.ground.offset(x, up, z), Blocks.AIR.defaultBlockState());
        }
        place(rig, rig.ground, soil.defaultBlockState());
        place(rig, rig.ground.above(), Blocks.WATER.defaultBlockState());
        rig.body.mob().setPos(rig.ground.getX() + 1.5, rig.ground.getY() + 1, rig.ground.getZ() + 0.5);
        return rig;
    }

    private static Field field(CookeryCropGameTests.Field rig) {
        return new Field(rig.crop, Tillage.Clearing.ALL, WorldPos.of(rig.level(), rig.ground), () -> { }, new PastDay());
    }

    private static List<Node> scan(CookeryCropGameTests.Field rig, Field field, long stamp) {
        ZoneView zone = new ZoneView() {
            private final UUID id = UUID.randomUUID();
            public UUID id() { return id; }
            public ResourceLocation delegation() { return FarmingContent.PLOT; }
            public ResourceKey<Level> dimension() { return rig.level().dimension(); }
            public WorldPos at(BlockPos cell) { return WorldPos.of(rig.level(), cell); }
            public Set<BlockPos> cells() { return Set.of(rig.ground); }
            public Settings settings() { throw new AssertionError("the field reads its selected crop from the sweep"); }
        };
        field.scan(rig.level(), zone, rig.crop, stamp);
        field.settle(stamp);
        List<Grown> goals = new ArrayList<>();
        field.chores().forEach(chores -> chores.goals(rig.level(), goals));
        return goals.stream().flatMap(goal -> goal.nodes().stream()).toList();
    }

    private static Outcome.Done perform(GameTestHelper helper, CookeryCropGameTests.Field rig, Node node) {
        helper.assertTrue(node.planned(rig.level()).isEmpty(), "the native field admits its own task");
        Outcome outcome = Commit.run(rig.level(), node, rig.worker);
        helper.assertTrue(outcome instanceof Outcome.Done, "the actual field task commits successfully");
        Outcome.Done done = (Outcome.Done) outcome;
        done.made().forEach(stack -> helper.assertTrue(Containers.insert(rig.pack, stack).isEmpty(), "actual native output fits the existing worker pack"));
        node.ended(rig.level(), Ending.DONE);
        return done;
    }

    private static List<BlockState> column(CookeryCropGameTests.Field rig) {
        return List.of(rig.level().getBlockState(rig.ground), rig.level().getBlockState(rig.ground.above()),
            rig.level().getBlockState(rig.ground.above(2)), rig.level().getBlockState(rig.ground.above(3)));
    }

    private static void parts(GameTestHelper helper, CookeryCropGameTests.Field rig, int age) {
        for (int part = 0; part <= 2; part++) {
            BlockState state = rig.level().getBlockState(rig.ground.above(part + 1));
            helper.assertTrue(state.is(ModBlocks.RICE_CROP.get()), "actual native placement preserves all three rice parts");
            helper.assertValueEqual(state.getValue(RiceCropBlock.LOCATION), part, "native rice has the correct part location");
            helper.assertValueEqual(state.getValue(CropBlock.AGE), age, "native growth synchronizes all rice parts");
        }
        helper.assertTrue(rig.level().getBlockState(rig.ground.above()).getValue(RiceCropBlock.WATERLOGGED), "the actual root stays waterlogged");
    }

    private static void place(CookeryCropGameTests.Field rig, BlockPos at, BlockState state) {
        rig.level().setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
