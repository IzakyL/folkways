package io.github.izakyl.folkways.create.build;

import com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.vanilla.build.BlockData;
import io.github.izakyl.folkways.vanilla.build.Costs;
import io.github.izakyl.folkways.vanilla.build.SchematicPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_create")
@PrefixGameTestTemplate(false)
public final class CreateBuildCostsGameTests {
    private static final List<Block> FIXTURES = new ArrayList<>();

    @SubscribeEvent
    public static void registerBlocks(RegisterEvent event) {
        event.register(Registries.BLOCK, registry -> {
            List<Function<BlockEntity, ItemRequirement>> requirements = List.of(
                entity -> new ItemRequirement(ItemRequirement.ItemUseType.CONSUME,
                    List.of(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.REDSTONE, 2),
                        new ItemStack(Items.IRON_INGOT, 4))),
                entity -> new ItemRequirement(ItemRequirement.ItemUseType.CONSUME, Items.IRON_INGOT),
                entity -> new ItemRequirement(ItemRequirement.ItemUseType.DAMAGE, Items.FLINT_AND_STEEL),
                entity -> new ItemRequirement(new ItemRequirement.StrictNbtStackRequirement(
                    new ItemStack(Items.WHITE_BANNER), ItemRequirement.ItemUseType.CONSUME)),
                entity -> ItemRequirement.INVALID,
                entity -> new ItemRequirement(ItemRequirement.ItemUseType.CONSUME,
                    new ItemStack(Items.IRON_INGOT, entity == null ? 1 : entity.getPersistentData().getInt("cost"))),
                entity -> new ItemRequirement(new ItemRequirement.StrictNbtStackRequirement(
                    componentStack("stone"), ItemRequirement.ItemUseType.CONSUME)));
            for (int index = 0; index < requirements.size(); index++) {
                Block block = new AddonBlock(requirements.get(index));
                registry.register(ResourceLocation.fromNamespaceAndPath(FolkwaysCreateMod.ID,
                    "test_build_cost_" + index), block);
                FIXTURES.add(block);
            }
        });
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CreateBuildCostsGameTests.class);
    }

    private record Translation(String what, int fixture, BlockEntity entity, Predicate<Costs.Quote> holds) { }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 10)
    public static void addonSchematicRequirementsTranslateAsTheCannonReadsThem(GameTestHelper helper) {
        BlockEntity configured = new BannerBlockEntity(BlockPos.ZERO, Blocks.WHITE_BANNER.defaultBlockState());
        configured.getPersistentData().putInt("cost", 6);
        List<Translation> rows = List.of(
            new Translation("addon requirements retain every material and count", 0, null,
                quote -> count(quote, "iron_ingot") == 7 && count(quote, "redstone") == 2),
            new Translation("a tool worn down cannot be represented, so the placement is refused rather than free",
                2, null, quote -> quote.needs().isEmpty() && !quote.invalid()),
            new Translation("an exact item that is a plain item is asked for as it is", 3, null,
                quote -> quote.needs().orElseThrow().getFirst().spec().item().orElseThrow().getPath()
                    .equals("white_banner")),
            new Translation("an invalid requirement is skipped, as the cannon skips it", 4, null, Costs.Quote::invalid),
            new Translation("without block entity data the schematic requirement takes its default", 5, null,
                quote -> quote.needs().orElseThrow().getFirst().count() == 1),
            new Translation("the schematic requirement sees blueprint block entity configuration", 5, configured,
                quote -> quote.needs().orElseThrow().getFirst().count() == 6));
        for (Translation row : rows) {
            helper.assertTrue(row.holds().test(SchematicPlacement.adapter()
                .requirement(FIXTURES.get(row.fixture()).defaultBlockState(), row.entity())), row.what());
        }
        helper.succeed();
    }

    private static long count(Costs.Quote quote, String item) {
        return quote.needs().orElseThrow().stream()
            .filter(need -> need.spec().item().orElseThrow().getPath().equals(item)).mapToLong(Need::count).sum();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 10)
    public static void aPairedAddonPlacementOnlyPaysOnce(GameTestHelper helper) {
        Block block = FIXTURES.get(1);
        BlockState lower = block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        var launch = SchematicPlacement.adapter().launch(pos -> pos.equals(BlockPos.ZERO) ? lower
            : pos.equals(BlockPos.ZERO.above()) ? upper : Blocks.AIR.defaultBlockState(), BlockPos.ZERO).orElseThrow();
        helper.assertTrue(launch.produces().contains(BlockPos.ZERO.above()) && launch.charged().equals(List.of(BlockPos.ZERO)),
            "the upper half is made by the lower half's shot and charges nothing more");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 20)
    public static void aBeltIsShotWholeFromItsLastSegment(GameTestHelper helper) {
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 2));
        Block beltBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "belt"));
        BlockState belt = beltBlock.defaultBlockState()
            .setValue(BeltBlock.HORIZONTAL_FACING, Direction.EAST).setValue(BeltBlock.SLOPE, BeltSlope.HORIZONTAL);
        Map<BlockPos, BlockState> target =
            Map.of(
            start,
            belt.setValue(BeltBlock.PART, BeltPart.START),
            start.east(),
            belt.setValue(BeltBlock.PART, BeltPart.MIDDLE),
            start.east(2),
            belt.setValue(BeltBlock.PART, BeltPart.END));
        Function<BlockPos, BlockState> states = pos -> target.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        var rules = SchematicPlacement.adapter();
        var shot = rules.launch(states, start.east(2)).orElseThrow();
        helper.assertTrue(shot.produces().equals(target.keySet()), "the last segment's shot makes the whole belt");
        helper.assertTrue(shot.charged().equals(List.of(start.east(2), start)), "both ends are charged, as the cannon shoots both");
        helper.assertTrue(rules.launch(states, start).isEmpty() && rules.launch(states, start.east()).isEmpty(),
            "no other segment is shot by itself");
        for (int x = 0; x < 3; x++) {
            helper.getLevel().setBlock(start.east(x).below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        rules.fire(helper.getLevel(), shot, states, pos -> BlockData.EMPTY, ItemStack.EMPTY);
        for (BlockPos cell : target.keySet()) {
            helper.assertTrue(helper.getLevel().getBlockState(cell).getBlock() instanceof BeltBlock,
                "every segment of the belt stands");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 10)
    public static void strictAddonComponentsOnlyAcceptTheMatchingMaterial(GameTestHelper helper) {
        var need =
            SchematicPlacement.adapter()
            .requirement(FIXTURES.get(6).defaultBlockState(), null)
            .needs()
            .orElseThrow()
            .getFirst();
        ItemStack matching = componentStack("stone");
        ItemStack other = componentStack("wood");
        var inventory = new SimpleContainer(other, matching);
        helper.assertTrue(
            need.count() == 4
            && need.spec().components().orElseThrow().exact()
            && Goods.countIn(inventory, need.spec()) == 4,
            "the placement adapter retains strict material identity and count");
        var fixture = new PlanningFixture(helper);
        var spec =
            NodeSpec.of(
            UUID.randomUUID(),
            ResourceLocation.withDefaultNamespace("test"),
            new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target),
            Workload.Once.of(1))
            .needs(need)
            .done();
        var placement =
            new Node() {
                public NodeSpec spec() {
                    return spec;
                }

                public Outcome commit(ServerLevel level, Worker who) {
                    helper.assertTrue(
                        ItemStack.isSameItemSameComponents(who.supplied().getFirst(), componentStack("stone")),
                        "build commit receives the required material stack");
                    return Outcome.done();
                }
            };
        fixture.worker.pack().setItem(0, other.copy());
        helper.assertTrue(
            Commit.run(helper.getLevel(), placement, fixture.worker) instanceof Outcome.Failed,
            "a different material cannot satisfy the build cost");
        fixture.worker.pack().setItem(1, matching.copy());
        helper.assertTrue(
            Commit.run(helper.getLevel(), placement, fixture.worker) instanceof Outcome.Done
            && fixture.worker.pack().getItem(0).getCount() == 4,
            "only the matching material is consumed");
        matching.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        helper.assertTrue(
            !Goods.matches(matching, need.spec()),
            "even additional components fail the cannon's strict requirement");
        helper.succeed();
    }

    private static ItemStack componentStack(String material) {
        ItemStack stack = new ItemStack(Items.PAPER, 4);
        var data = new CompoundTag();
        data.putString("material", material);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }

    private static final class AddonBlock extends Block implements SpecialBlockItemRequirement {
        private final Function<BlockEntity, ItemRequirement> requirement;

        private AddonBlock(Function<BlockEntity, ItemRequirement> requirement) {
            super(BlockBehaviour.Properties.of());
            this.requirement = requirement;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.DOUBLE_BLOCK_HALF);
        }

        @Override
        public ItemRequirement getRequiredItems(BlockState state, BlockEntity entity) {
            return requirement.apply(entity);
        }
    }
}
