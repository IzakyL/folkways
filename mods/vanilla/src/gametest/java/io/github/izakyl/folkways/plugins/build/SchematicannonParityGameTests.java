package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Placement is the schematicannon's: each case runs under the installed placement (Create's, in the test
 * environment) and under the copy used when Create is absent.
 */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SchematicannonParityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SchematicannonParityGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theTargetIsTheBlueprintAsThePrinterReadsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        eachPlacement(name -> {
            BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
            Blueprint source = new Blueprint(UUID.randomUUID(), "printed", List.of(
                new BlueprintBlock(new BlockPos(0, 0, 0), fence),
                new BlueprintBlock(new BlockPos(1, 0, 0), fence.setValue(BlockStateProperties.NORTH, true)),
                new BlueprintBlock(new BlockPos(0, 0, 2), Blocks.FARMLAND.defaultBlockState()),
                new BlueprintBlock(new BlockPos(0, 1, 2), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)),
                new BlueprintBlock(new BlockPos(2, 0, 2), Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.LIT, true)),
                new BlueprintBlock(new BlockPos(2, 1, 0), Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(WallTorchBlock.FACING, Direction.NORTH)),
                new BlueprintBlock(new BlockPos(2, 0, 0), Blocks.PISTON_HEAD.defaultBlockState())),
                BlueprintVolume.of(3, 2, 3).orElseThrow(), 0L);
            Blueprint target = source.confirm(level);
            Map<BlockPos, BlockState> states = new LinkedHashMap<>();
            target.blocks().forEach(block -> states.put(block.offset(), block.state()));
            helper.assertTrue(states.get(new BlockPos(0, 0, 0)).getValue(BlockStateProperties.EAST),
                name + ": fences join as the blueprint's own blocks meet");
            helper.assertTrue(!states.get(new BlockPos(1, 0, 0)).getValue(BlockStateProperties.NORTH),
                name + ": a join to nothing in the blueprint is dropped");
            helper.assertValueEqual(states.get(new BlockPos(0, 1, 2)).getValue(CropBlock.AGE), 0,
                name + ": crops are aged back to their start");
            helper.assertTrue(!states.get(new BlockPos(2, 0, 2)).getValue(FurnaceBlock.LIT),
                name + ": a furnace is printed cold");
            helper.assertTrue(!states.containsKey(new BlockPos(2, 1, 0)),
                name + ": a torch hanging on nothing in the blueprint is not printed");
            helper.assertTrue(!states.containsKey(new BlockPos(2, 0, 0)),
                name + ": a part no shot makes is not printed");
            helper.assertTrue(target.confirm(level) == target, name + ": a target is resolved once");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aShotIsWrittenWithoutTellingItsNeighbours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int[] row = {0};
        eachPlacement(name -> {
            BlockPos at = helper.absolutePos(new BlockPos(2, 2, 1 + 2 * row[0]++));
            level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at.west(), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
            BlockState joined = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.EAST, true);
            fire(level, at, joined);
            helper.assertValueEqual(level.getBlockState(at), joined, name + ": the shot keeps its state exactly");
            helper.assertTrue(!level.getBlockState(at.west()).getValue(BlockStateProperties.EAST),
                name + ": the fence beside it is not reshaped");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void requirementsAreTheCannons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        eachPlacement(name -> {
            SchematicPlacement.Adapter rules = SchematicPlacement.adapter();
            helper.assertValueEqual(rules.requirement(Blocks.FARMLAND.defaultBlockState(), null).needs().orElseThrow(),
                List.of(need("dirt", 1)), name + ": farmland is made from dirt");
            helper.assertValueEqual(rules.requirement(Blocks.STONE_SLAB.defaultBlockState()
                    .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE), null).needs().orElseThrow(),
                List.of(need("stone_slab", 2)), name + ": a double slab is two slabs");
            helper.assertValueEqual(rules.requirement(Blocks.TALL_GRASS.defaultBlockState(), null).needs().orElseThrow(),
                List.of(need("short_grass", 2)), name + ": tall grass is two short grass");
            helper.assertTrue(rules.requirement(Blocks.FIRE.defaultBlockState(), null).invalid(),
                name + ": a block with no item is skipped");
            BlockState banner = Blocks.WHITE_BANNER.defaultBlockState();
            helper.assertValueEqual(rules.requirement(banner, new BannerBlockEntity(at, banner)).needs().orElseThrow(),
                List.of(new Need(ItemSpec.exact(new ItemStack(Items.WHITE_BANNER)), 1)),
                name + ": a plain banner retains its exact components");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aBlueprintFiledBeforeIsResolvedOnFirstUse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        Blueprint draft = new Blueprint(UUID.randomUUID(), "pair", List.of(
            new BlueprintBlock(BlockPos.ZERO, fence), new BlueprintBlock(new BlockPos(1, 0, 0), fence)),
            BlueprintVolume.of(2, 1, 1).orElseThrow(), 0L);
        BlueprintBook book = new BlueprintBook();
        book.add(level, draft);
        Blueprint filed = book.blueprint(draft.id()).orElseThrow();
        helper.assertTrue(filed.confirmed(), "filing resolves the target");
        BlueprintBook reloaded = BlueprintBook.load(Reader.of(book.save(level.registryAccess())), level.registryAccess());
        helper.assertValueEqual(reloaded.blueprint(draft.id()).orElseThrow(), filed, "reloading keeps it as resolved");

        CompoundTag saved = book.save(level.registryAccess());
        saved.getList("blueprints", CompoundTag.TAG_COMPOUND).set(0, draft.save(level.registryAccess()));
        BlueprintBook old = BlueprintBook.load(Reader.of(saved), level.registryAccess());
        helper.assertTrue(!old.blueprint(draft.id()).orElseThrow().confirmed(), "an old blueprint loads unresolved");
        Blueprint resolved = old.target(level, draft.id()).orElseThrow();
        helper.assertTrue(resolved.confirmed() && resolved.blocks().getFirst().state()
            .getValue(BlockStateProperties.EAST), "and is resolved when first built");
        helper.succeed();
    }

    private static void eachPlacement(Consumer<String> check) {
        SchematicPlacement.Adapter installed = SchematicPlacement.adapter();
        try {
            check.accept("installed");
            SchematicPlacement.install(new VanillaSchematics());
            check.accept("without Create");
        } finally {
            SchematicPlacement.install(installed);
        }
    }

    private static void fire(ServerLevel level, BlockPos at, BlockState state) {
        Rubble.lay(level, null, Map.of(at, level.getBlockState(at)), Map.of(at, state), Set.of(at), at, Map.of(),
            ItemStack.EMPTY, ItemStack.EMPTY, false);
    }

    private static Need need(String item, long count) {
        return new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace(item)), count);
    }
}
