package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
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

    @GameTest(template = "empty_large", timeoutTicks = 40)
    public static void aDoorIsOneShotThatMakesItsUpperHalf(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lower = helper.absolutePos(new BlockPos(8, 1, 8));
        level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lower.above(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        BuildTarget target = new BuildTarget(WorldPos.of(level, lower).realm(), Map.of(lower, door, lower.above(),
            door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        helper.assertValueEqual(target.launch(lower).orElseThrow().produces(), Set.of(lower, lower.above()),
            "the lower half's shot makes both halves");
        helper.assertTrue(target.launch(lower.above()).isEmpty(), "the upper half is never aimed at");
        SiteRig rig = SiteRig.start(level, target);
        helper.assertValueEqual(rig.site.pieces().size(), 1, "one piece of work");
        Worker worker = worker(helper, lower.offset(-3, 0, 0), new ItemStack(Items.OAK_DOOR));
        helper.assertTrue(Commit.run(level, rig.node(lower), worker) instanceof Outcome.Done, "the door is laid");
        rig.end(rig.node(lower), Ending.DONE);
        rig.site.tick(level);
        helper.assertTrue(level.getBlockState(lower.above()).is(Blocks.OAK_DOOR), "the upper half stands");
        helper.assertTrue(level.getBlockState(lower.above(2)).is(Blocks.STONE), "nothing above the door is touched");
        helper.assertTrue(rig.site.complete(), "both halves are done by the one shot");
        rig.site.close();
        worker.body().discard();
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
                List.of(new Need(ItemSpec.exact(new ItemStack(Items.WHITE_BANNER)), 1)), name + ": a plain banner retains its exact components");
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

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aBlockNoLongerKnownDoesNotHoldTheOrderOpen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BuildTarget target = new BuildTarget(WorldPos.of(level, at).realm(), Map.of(at, Blocks.STONE.defaultBlockState()),
            Set.of(at.east()), BlockPos.ZERO);
        helper.assertTrue(BuildPlanner.complete(level, target), "the unknown block is skipped, as the cannon skips it");
        SiteRig rig = SiteRig.start(level, target);
        helper.assertTrue(rig.site.complete(), "the order finishes");
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 40)
    public static void aFluidIsPouredAsAStepOfItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            level.setBlock(cell.relative(side), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        BuildTarget target = new BuildTarget(WorldPos.of(level, cell).realm(),
            Map.of(cell, Blocks.WATER.defaultBlockState()));
        SiteRig rig = SiteRig.start(level, target);
        helper.assertValueEqual(rig.node(cell).spec().needs(), List.of(need("water_bucket", 1).replacing()), "poured from a bucket");
        Worker worker = worker(helper, cell.offset(-3, 0, 0), new ItemStack(Items.WATER_BUCKET));
        helper.assertTrue(Commit.run(level, rig.node(cell), worker) instanceof Outcome.Done done
            && done.made().stream().anyMatch(stack -> stack.is(Items.BUCKET)), "the empty bucket comes back");
        helper.assertTrue(level.getFluidState(cell).isSource(), "the source is there");
        rig.site.close();
        worker.body().discard();
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

    private static Worker worker(GameTestHelper helper, BlockPos feet, ItemStack... carried) {
        SimpleContainer pack = new SimpleContainer(carried);
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(feet));
        return (Worker) Proxy.newProxyInstance(SchematicannonParityGameTests.class.getClassLoader(),
            new Class<?>[] {Worker.class}, (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body;
                case "pack" -> pack;
                case "resident" -> new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
                case "effects" -> Effects.NONE;
                case "placement" -> Placement.NOWHERE;
                case "held" -> ItemStack.EMPTY;
                case "within", "route", "supplied" -> List.of();
                default -> null;
            });
    }
}
