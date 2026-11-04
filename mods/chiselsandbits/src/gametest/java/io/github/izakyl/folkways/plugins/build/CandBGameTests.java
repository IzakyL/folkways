package io.github.izakyl.folkways.plugins.build;

import static io.github.izakyl.folkways.plugins.build.CandBTestData.sculpture;
import static io.github.izakyl.folkways.plugins.build.CandBTestData.state;
import static io.github.izakyl.folkways.plugins.build.CandBTestData.structure;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.chiselsandbits.CandBConfig;
import io.github.izakyl.folkways.chiselsandbits.CandBMaterials;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.chiselsandbits.FolkwaysChiselsAndBitsMod;
import io.github.izakyl.folkways.core.api.colony.Cadence;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.core.api.structure.Voxelizer;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.front.engine.net.ColonyOverviewPacket;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.RecipeDrawing;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysChiselsAndBitsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CandBGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CandBGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 200)
    public static void creativePrintingKeepsEveryDraftVoxel(GameTestHelper helper) {
        var level = helper.getLevel();
        var pattern = io.github.izakyl.folkways.plugins.build.draft.Patterns
            .find(ResourceLocation.parse("folkways_chiselsandbits:pavilion")).orElseThrow();
        CompoundTag settings = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof io.github.izakyl.folkways.front.api.Schema.Setting.Items items) {
                settings.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        settings.putInt("width", 11);
        settings.putInt("height", 5);
        var hint = new Hint.Zone(BlockPos.ZERO, new BlockPos(10, 0, 10));
        var drawn = Rounds.draw(pattern, hint, Chosen.load(pattern.knobs(), settings), Direction.NORTH,
            io.github.izakyl.folkways.plugins.build.draft.World.NONE).join();
        helper.assertTrue(drawn instanceof Drawn.Ready, "the large pavilion draws");
        var draft = ((Drawn.Ready) drawn).draft();
        Blueprint patterned = Rounds.blueprintOf(level, "creative-pavilion", draft).orElseThrow();
        var nativeCells = patterned.blocks().stream().map(cell -> {
            CompoundTag tag = cell.data().tag();
            tag.remove("folkways:schematic");
            return new BlueprintBlock(cell.offset(), cell.state(), BlockData.of(tag));
        }).toList();
        Blueprint source = new Blueprint(patterned.id(), patterned.name(), nativeCells, patterned.volume(), 0);
        var sample = source.blocks().stream().filter(cell -> cell.data().present()).findFirst().orElseThrow();
        var detached = (ChiseledBlockEntity) sample.data().entity(level, BlockPos.ZERO, sample.state());
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            detached.streamCodec().encode(wire, detached.payload());
            var received = detached.streamCodec().decode(wire);
            helper.assertValueEqual(received.storage().getSize(), detached.payload().storage().getSize(),
                "the network codec preserves sampled voxel resolution");
        } finally {
            wire.release();
        }
        long started = System.nanoTime();
        Blueprint confirmed = source.confirm(level);
        long confirmedAt = System.nanoTime();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 2, 2));
        SchematicPlacement.Adapter previous = SchematicPlacement.adapter();
        // Create's safe-NBT preparation drops unknown block entities. An integration's validated payload must
        // reach placement independently of that ordinary-block whitelist, without installing Create in this suite.
        SchematicPlacement.Adapter filtered = (SchematicPlacement.Adapter) Proxy.newProxyInstance(
            SchematicPlacement.Adapter.class.getClassLoader(), new Class<?>[] {SchematicPlacement.Adapter.class},
            (proxy, method, args) -> {
                if (method.getName().equals("prepare") && StructureCompat.of((BlockState) args[1]).isPresent()) {
                    return null;
                }
                if (method.isDefault()) {
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                }
                try {
                    return method.invoke(previous, args);
                } catch (java.lang.reflect.InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
        java.util.concurrent.CompletableFuture<Void> raised;
        try {
            SchematicPlacement.install(filtered);
            raised = SchematicPlacement.print(level, confirmed, anchor);
        } finally {
            SchematicPlacement.install(previous);
        }
        helper.assertTrue(!raised.isDone(), "creative raising yields to the next tick instead of blocking the caller");
        int previousReads = CandBConfig.BLUEPRINT_READS.get();
        BlockPos refusedAt = anchor;
        var before = new java.util.HashMap<BlockPos, BlockState>();
        for (BlueprintBlock cell : confirmed.blocks()) {
            before.put(cell.worldPos(refusedAt), level.getBlockState(cell.worldPos(refusedAt)));
        }
        try {
            CandBConfig.BLUEPRINT_READS.set(1);
            try {
                SchematicPlacement.print(level, confirmed, refusedAt);
                helper.fail("cached copies must not bypass the placement budget");
            } catch (StructureCompat.Failure expected) {
                helper.assertTrue(expected.getMessage().endsWith("structure_budget"), "an explicit budget refusal");
                helper.assertTrue(confirmed.blocks().stream().allMatch(cell ->
                    level.getBlockState(cell.worldPos(refusedAt)).equals(before.get(cell.worldPos(refusedAt)))),
                    "payload limits refuse before placement");
            }
        } finally {
            CandBConfig.BLUEPRINT_READS.set(previousReads);
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(raised.isDone(), "all creative slices have completed");
            raised.join();
            long printedAt = System.nanoTime();
            int partial = 0;
            for (BlueprintBlock cell : source.blocks()) {
                if (!cell.data().present()) {
                    continue;
                }
                BlockPos at = cell.worldPos(anchor);
                helper.assertValueEqual(level.getBlockState(at), cell.state(), "the printed carrier at " + at);
                helper.assertTrue(level.getBlockEntity(at) instanceof ChiseledBlockEntity,
                    "a printed payload has a server block entity at " + at);
                var actual = (ChiseledBlockEntity) level.getBlockEntity(at);
                helper.assertTrue(StructureCompat.of(cell.state()).orElseThrow()
                    .matches(actual, cell.data().payload(cell.state())), "every printed voxel matches the draft at " + at);
                helper.assertTrue(!actual.getStatistics().isEmptyBlock(), "the server carving has nonempty statistics");
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
                try {
                    actual.streamCodec().encode(buffer, actual.payload());
                    var received = actual.streamCodec().decode(buffer);
                    helper.assertValueEqual(received.storage().getSize(), actual.payload().storage().getSize(),
                        "the client receives the same voxel resolution");
                    var client = new ChiseledBlockEntity(at, cell.state());
                    client.setLevel(level);
                    client.receivePayload(received);
                    helper.assertTrue(StructureCompat.of(cell.state()).orElseThrow()
                        .matches(client, cell.data().payload(cell.state())), "the network payload keeps every draft voxel");
                } finally {
                    buffer.release();
                }
                partial++;
            }
            helper.assertTrue(partial > 100, "the regression prints a substantial multi-cell carving");
            LoggerFactory.getLogger("folkways-patterns").info(
                "CREATIVE_PRINT_ASSERT cells={} payloads={} confirmMillis={} elapsedMillis={} serverVoxelsExact=true",
                source.blocks().size(), partial, (confirmedAt - started) / 1_000_000D,
                (printedAt - confirmedAt) / 1_000_000D);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void decodedVoxelsDetermineWholeBlockMaterials(GameTestHelper helper) {
        StateEntryStorage storage = new StateEntryStorage();
        try (var batch = storage.batch()) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        storage.setBlockInformation(x, y, z, new BlockInformation(
                            (x < 4 ? Blocks.OAK_PLANKS : Blocks.STONE).defaultBlockState(), Optional.empty()));
                    }
                }
            }
        }
        // The encoder deliberately writes empty cached statistics, with recalculation required.
        var decoded = CandBStructureCodec.decode(CandBStructureCodec.encode(storage));
        var costs = CandBMaterials.of(decoded);
        helper.assertValueEqual(costs.stream().mapToInt(ItemStack::getCount).sorted().boxed().toList(),
            List.of(1, 1), "each material rounds up to a whole block");
        helper.assertTrue(!ItemSpec.exact(costs.getFirst()).equals(ItemSpec.exact(costs.getLast())), "materials remain distinct");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void blockIdentitiesSurviveSaveReloadAndDisplay(GameTestHelper helper) {
        List<ItemStack> bits = CandBMaterials.of(sculpture());
        for (ItemStack bit : bits) {
            ItemSpec exact = ItemSpec.exact(bit);
            ItemSpec loaded = ItemSpec.load(Reader.of(exact.save(helper.getLevel().registryAccess())),
                helper.getLevel().registryAccess()).orElseThrow();
            helper.assertTrue(exact.equals(loaded) && Goods.matches(bit, loaded)
                && Goods.matches(Goods.display(loaded), exact), "the material component is persisted and shown");
        }
        helper.assertTrue(!Goods.matches(bits.getFirst(), ItemSpec.exact(bits.getLast())), "wood cannot pay for stone");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void chunkRestorationKeepsStatisticsAndTheCarving(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(at, state(), Block.UPDATE_ALL);
        var first = (ChiseledBlockEntity) level.getBlockEntity(at);
        first.loadWithComponents(CandBStructureCodec.encode(sculpture()), level.registryAccess());
        CompoundTag saved = first.saveWithFullMetadata(level.registryAccess());
        helper.assertTrue(!saved.contains("folkways:schematic"), "ordinary chunk data has no schematic marker");
        first.setRemoved();
        var restored = (ChiseledBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
            at, state(), saved, level.registryAccess());
        helper.assertTrue(restored.getLevel() == null, "the chunk reads the entity before attaching its level");
        level.setBlockEntity(restored);
        helper.succeedWhen(() -> {
            helper.assertTrue(!restored.getStatistics().isEmptyBlock(), "the restored carving is not replaceable as an empty block");
            helper.assertTrue(!restored.getShape(mod.chiselsandbits.api.axissize.CollisionType.NONE_AIR).isEmpty(),
                "restored collision statistics retain the shape");
            CandBTestData.sameVoxels(helper, sculpture(), restored.payload().storage());
            restored.setChanged();
            helper.assertTrue(level.getBlockState(at).is(state().getBlock()), "a later update cannot erase the carving");
            var captured = BlueprintCapture.read(level, BoundingBox.fromCorners(at, at), pos -> true);
            var blueprint = StructureNbt.toBlueprint(captured, level.registryAccess(), UUID.randomUUID(), "restored", 0,
                StructureNbt.AirMeaning.UNCONSTRAINED, 8).orElseThrow().confirm(level);
            helper.assertTrue(!blueprint.blocks().isEmpty(), "the restored carving remains capturable and buildable");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void synchronousDetachedReadRecalculatesWhenAWorldIsAttached(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        var detached = new ChiseledBlockEntity(at, state());
        detached.loadWithComponents(CandBStructureCodec.encode(sculpture()), level.registryAccess());
        detached.setLevel(level);
        helper.assertTrue(!detached.getStatistics().isEmptyBlock(), "attaching a world completes synchronous statistics");
        helper.assertTrue(!detached.getShape(mod.chiselsandbits.api.axissize.CollisionType.NONE_AIR).isEmpty(),
            "the detached read does not leave an empty collision shape");
        detached.setRemoved();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void missingOneMaterialRestoresAllBlocksAndNeverPlaces(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        var f = new PlanningFixture(helper);
        f.worker.body().setPos(at.getX() + 2.5, at.getY(), at.getZ() + 0.5);
        List<ItemStack> bits = CandBMaterials.of(sculpture());
        ItemStack enough = bits.getFirst().copy();
        ItemSpec identity = ItemSpec.exact(enough);
        f.worker.pack().setItem(0, enough);
        ItemStack oldBits = mod.chiselsandbits.api.item.bit.IBitItemManager.getInstance().create(new BlockInformation(
            ((BlockItem) bits.getLast().getItem()).getBlock().defaultBlockState(), Optional.empty()), 4096);
        f.worker.pack().setItem(1, oldBits);
        int oldCount = f.worker.pack().getItem(1).getCount();
        Node node = node(helper, at, blueprint(helper), Set.of());
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Failed,
            "missing blocks refuse the commit");
        helper.assertTrue(level.getBlockState(at).isAir() && Goods.countIn(f.worker.pack(), identity)
            == bits.getFirst().getCount() && f.worker.pack().getItem(1).getCount() == oldCount,
            "old fragments cannot pay for material, and failure rolls back all whole blocks");
        f.worker.pack().setItem(1, bits.getLast().copy());
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Done, "the exact second material permits the shot");
        helper.assertTrue(f.worker.pack().isEmpty(), "exactly the two materials are spent");
        CandBTestData.sameVoxels(helper, sculpture(),
            ((ChiseledBlockEntity) level.getBlockEntity(at)).payload().storage());
        f.worker.body().discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void sameStateChiseledCellsAreReshapedWhenTheirVoxelsDiffer(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        level.setBlock(at, state(), Block.UPDATE_ALL);
        ChiseledBlockEntity entity = (ChiseledBlockEntity) level.getBlockEntity(at);
        StateEntryStorage other = sculpture();
        CandBStructureCodec.turn(other, Mirror.NONE, Rotation.CLOCKWISE_180);
        entity.loadWithComponents(CandBStructureCodec.encode(other), level.registryAccess());
        BuildTarget target = target(level, at, blueprint(helper));
        helper.assertTrue(!target.satisfied(level, at) && !BuildPlanner.complete(level, target),
            "equal carrier states do not hide a different carving");
        helper.assertTrue(BuildOrderView.owed(level, target, Set.of()).isEmpty(),
            "same-node material reuse costs nothing");
        var f = new PlanningFixture(helper);
        f.worker.body().setPos(at.getX() + 2.5, at.getY(), at.getZ() + 0.5);
        helper.assertTrue(Commit.run(level, node(helper, at, blueprint(helper), Set.of()),
            f.worker) instanceof Outcome.Done,
            "the shape is corrected without new materials");
        CandBTestData.sameVoxels(helper, sculpture(),
            ((ChiseledBlockEntity) level.getBlockEntity(at)).payload().storage());
        f.worker.body().discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void savedCompletionDoesNotChargeOrRebuildAfterReload(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        Blueprint blueprint = blueprint(helper);
        BlueprintBook book = new BlueprintBook();
        book.add(level, blueprint);
        BlueprintBook loaded = BlueprintBook.load(Reader.of(book.save(level.registryAccess())), level.registryAccess());
        Blueprint restored = loaded.target(level, blueprint.id()).orElseThrow();
        helper.assertTrue(restored.confirmed(), "the target survives without being resolved a second time");
        BuildTarget target = target(level, at, restored);
        helper.assertTrue(target.costs(level, target.launch(at).orElseThrow(), Set.of(at)).orElseThrow().isEmpty(),
            "the saved completed cell is not charged again even after it is broken");
        var f = new PlanningFixture(helper);
        Node completed = node(helper, at, restored, Set.of(at));
        helper.assertTrue(completed.spec().needs().isEmpty() && Commit.run(level, completed, f.worker) instanceof Outcome.Done,
            "the restored completion needs no materials");
        helper.assertTrue(level.getBlockState(at).isAir(), "completion is latched instead of rebuilding a broken cell");
        f.worker.body().discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void preIntegrationOrdersExplicitlyReconfirmTheirCosts(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        Blueprint source = blueprint(helper);
        CompoundTag saved = source.save(registries);
        saved.remove("payloadVersion");
        Blueprint old = Blueprint.load(Reader.of(saved), registries).orElseThrow();
        helper.assertTrue(!old.confirmed(), "older chiseled targets are migrated to pending confirmation");
        Blueprint resolved = old.confirm(helper.getLevel());
        helper.assertTrue(resolved.confirmed() && resolved.save(registries).getInt("payloadVersion") == 1,
            "reconfirmation writes the material-aware version");
        Blueprint plain = new Blueprint(UUID.randomUUID(), "plain", List.of(new BlueprintBlock(BlockPos.ZERO,
            Blocks.STONE.defaultBlockState())), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0).confirm(helper.getLevel());
        CompoundTag ordinary = plain.save(registries);
        ordinary.remove("payloadVersion");
        helper.assertTrue(Blueprint.load(Reader.of(ordinary), registries).orElseThrow().confirmed(), "ordinary saves remain confirmed");
        CompoundTag missing = NbtUtils.writeBlockState(state());
        missing.putString("Name", "absent:chiseled");
        Blueprint unloaded = new Blueprint(UUID.randomUUID(), "missing", List.of(), List.of(
            new UnloadedBlock(BlockPos.ZERO, missing, List.of(), source.blocks().getFirst().data())), List.of(),
            source.volume(), 0, true);
        CompoundTag absent = unloaded.save(registries);
        absent.remove("payloadVersion");
        Blueprint waiting = Blueprint.load(Reader.of(absent), registries).orElseThrow();
        helper.assertTrue(!waiting.confirmed(), "migration stays pending while the block mod is absent");
        helper.assertTrue(!Blueprint.load(Reader.of(waiting.save(registries)), registries).orElseThrow().confirmed(),
            "saving without the mod does not bypass later reconfirmation");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 40)
    public static void captureKeepsVoxelsButNeverContainerInventories(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(at, state(), Block.UPDATE_ALL);
        level.getBlockEntity(at).loadWithComponents(CandBStructureCodec.encode(sculpture()), level.registryAccess());
        level.setBlock(at.east(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        var chest = (Container) level.getBlockEntity(at.east());
        chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
        CompoundTag captured = BlueprintCapture.read(level, BoundingBox.fromCorners(at, at.east()), pos -> true);
        Blueprint read = StructureNbt.toBlueprint(captured, level.registryAccess(), UUID.randomUUID(), "captured", 0,
            StructureNbt.AirMeaning.UNCONSTRAINED, 8).orElseThrow();
        var chiseled = read.blocks().stream().filter(block -> block.state().is(state().getBlock())).findFirst().orElseThrow();
        helper.assertTrue(chiseled.data().tag().contains(StructureCompat.DESCRIPTOR), "the integration dependency travels with the file");
        CandBTestData.sameVoxels(helper, sculpture(), CandBStructureCodec.decode(chiseled.data().tag()));
        helper.assertTrue(read.blocks().stream().filter(block -> block.state().is(Blocks.CHEST))
            .noneMatch(block -> block.data().present()), "no captured inventory");
        helper.assertValueEqual(chest.getItem(0).getCount(), 5, "capture only reads the world");
        try {
            BlueprintCapture.read(level, BoundingBox.fromCorners(at, at), pos -> false);
            helper.fail("permission failure must refuse capture");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(level.getBlockEntity(at) != null, "a refused capture leaves the world alone");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void corruptAndAbsentVoxelDataCannotBecomeFreeOrders(GameTestHelper helper) {
        CompoundTag broken = new CompoundTag();
        broken.putByteArray("data", new byte[] {1, 2, 3});
        for (CompoundTag data : List.of(broken, new CompoundTag())) {
            try {
                new Blueprint(UUID.randomUUID(), "broken", List.of(new BlueprintBlock(BlockPos.ZERO, state(), BlockData.of(data))),
                    BlueprintVolume.of(1, 1, 1).orElseThrow(), 0).confirm(helper.getLevel());
                helper.fail("invalid voxel data must be refused");
            } catch (StructureCompat.Failure expected) {
                helper.assertTrue(true, "an explicit refusal, rather than an empty material list");
            }
        }
        StateEntryStorage fluid = new StateEntryStorage();
        fluid.setBlockInformation(0, 0, 0, new BlockInformation(Blocks.WATER.defaultBlockState(), Optional.empty()));
        refuses(helper, () -> CandBStructureCodec.encode(fluid));
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 40)
    public static void damagedConfirmedOrderStaysPausedAcrossTicks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        for (BlockPos floor : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, -1, 2))) {
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        CompoundTag broken = new CompoundTag();
        broken.putByteArray("data", new byte[] {1, 2, 3});
        Blueprint blueprint = new Blueprint(UUID.randomUUID(), "damaged", List.of(new BlueprintBlock(BlockPos.ZERO,
            state(), BlockData.of(broken))), List.of(), List.of(), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0, true);
        BlueprintBuildOrder order = BlueprintBuildOrder.create(blueprint.id(), "damaged", WorldPos.of(level, at),
            Optional.empty(), "", 0);
        CompoundTag book = Writer.of().children("blueprints", List.of(blueprint), value -> value.save(level.registryAccess()))
            .children("buildOrders", List.of(order), value -> value.save(level.registryAccess())).tag();
        var saved = new AtomicReference<>(Writer.of().blob("book", book).tag());
        ColonyView view = (ColonyView) Proxy.newProxyInstance(CandBGameTests.class.getClassLoader(),
            new Class<?>[] {ColonyView.class}, (proxy, method, args) -> switch (method.getName()) {
                case "level" -> level;
                case "dimension" -> level.dimension();
                case "holdings", "residents" -> List.of();
                case "blocks" -> Set.of();
                case "effectsOf" -> Effects.NONE;
                default -> throw new AssertionError(method.getName());
            });
        Colony colony = (Colony) Proxy.newProxyInstance(CandBGameTests.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                case "kept" -> saved.get();
                case "keep" -> { saved.set(((CompoundTag) args[1]).copy()); yield null; }
                case "views" -> List.of(view);
                default -> throw new AssertionError(method.getName());
            });
        BuildPresence presence = new BuildPresence(colony, level.getServer());
        helper.succeedWhen(() -> {
            presence.settle(level.getServer());
            helper.assertTrue(presence.site(order.id()).isEmpty(), "a failed opening leaves no partially planned site");
            presence.settle(level.getServer());
            helper.assertTrue(presence.filed(order.id()) && level.getBlockState(at).isAir(),
                "the damaged order stays pending instead of completing on its next tick");
            var rows = presence.board(BuildContent.PAGE.id(), view).orElseThrow().rows();
            helper.assertValueEqual(rows.getLast().detail(), Component.translatable("folkways.refusal.build.micro_data_invalid"),
                "the player sees why the order is paused");
            helper.assertValueEqual(saved.get().getCompound("book"), book, "refusal preserves the saved blueprint and order");
            Cadence.forget(colony);
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void orderCachesRefundOnCompletionAndCancellation(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(at, Blocks.STONE.defaultBlockState(), Rubble.SHOT);
        level.setBlock(at.east(), Blocks.STONE.defaultBlockState(), Rubble.SHOT);
        level.setBlock(at.east(2), state(), Rubble.SHOT);
        level.getBlockEntity(at.east(2)).loadWithComponents(payload(1).tag(), level.registryAccess());
        var cache = new Carving.Cache(() -> { });
        cache.accept(Carving.quote(level, Map.of(at, Blocks.AIR.defaultBlockState(),
            at.east(), Blocks.AIR.defaultBlockState(), at.east(2), Blocks.AIR.defaultBlockState()), pos -> BlockData.EMPTY, cache));
        Blueprint blueprint = new Blueprint(UUID.randomUUID(), "refund", List.of(new BlueprintBlock(BlockPos.ZERO,
            Blocks.STONE.defaultBlockState(), BlockData.EMPTY)), List.of(), List.of(),
            BlueprintVolume.of(1, 1, 1).orElseThrow(), 0, true);
        BlueprintBuildOrder finished = BlueprintBuildOrder.create(blueprint.id(), "finished", WorldPos.of(level, at),
            Optional.empty(), "", 0);
        BlueprintBuildOrder cancelled = BlueprintBuildOrder.create(blueprint.id(), "cancelled",
            WorldPos.of(level, at.east(4)), Optional.empty(), "", 0);
        CompoundTag book = Writer.of().children("blueprints", List.of(blueprint), value -> value.save(level.registryAccess()))
            .children("buildOrders", List.of(finished, cancelled), value -> value.save(level.registryAccess())).tag();
        var saved = new AtomicReference<>(Writer.of().blob("book", book)
            .children("carvingCaches", List.of(finished, cancelled), order -> Writer.of(cache.save())
                .uuid("order", order.id()).tag()).tag());
        ColonyView view = (ColonyView) Proxy.newProxyInstance(CandBGameTests.class.getClassLoader(),
            new Class<?>[] {ColonyView.class}, (proxy, method, args) -> switch (method.getName()) {
                case "level" -> level;
                case "dimension" -> level.dimension();
                case "holdings", "residents" -> List.of();
                case "blocks" -> Set.of();
                case "effectsOf" -> Effects.NONE;
                default -> throw new AssertionError(method.getName());
            });
        Colony colony = (Colony) Proxy.newProxyInstance(CandBGameTests.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                case "kept" -> saved.get();
                case "keep" -> { saved.set(((CompoundTag) args[1]).copy()); yield null; }
                case "views" -> List.of(view);
                default -> throw new AssertionError(method.getName());
            });
        BuildPresence presence = new BuildPresence(colony, level.getServer());
        presence.act(BuildContent.PAGE.id(), BuildAct.cancelling(cancelled.id()), Optional.empty(), view);
        helper.succeedWhen(() -> {
            presence.tick(level.getServer());
            helper.assertTrue(!presence.filed(finished.id()) && !presence.filed(cancelled.id()),
                "completed and cancelled orders both retire");
            long refunded = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(at).expandTowards(4, 0, 0).inflate(2)).stream()
                .filter(entity -> entity.getItem().is(Items.STONE)).mapToLong(entity -> entity.getItem().getCount()).sum();
            helper.assertValueEqual(refunded, 4L, "each order refunds its own two whole blocks without workers");
            var reloaded = new BuildPresence(colony, level.getServer());
            reloaded.tick(level.getServer());
            helper.assertTrue(saved.get().getList("carvingCaches", CompoundTag.TAG_COMPOUND).isEmpty(),
                "retired caches cannot charge or refund again after reload");
            Cadence.forget(colony);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void absentIntegrationRetainsTheFileAndPausesEvenConfirmedOrders(GameTestHelper helper) {
        CompoundTag data = CandBStructureCodec.encode(sculpture());
        CompoundTag descriptor = new CompoundTag();
        descriptor.putString("id", "folkways:absent_micro_blocks");
        descriptor.putInt("version", 1);
        data.put(StructureCompat.DESCRIPTOR, descriptor);
        Blueprint source = new Blueprint(UUID.randomUUID(), "absent", List.of(new BlueprintBlock(BlockPos.ZERO,
            state(), BlockData.of(data))), List.of(), List.of(), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0, true);
        Blueprint saved = Blueprint.load(Reader.of(source.save(helper.getLevel().registryAccess())),
            helper.getLevel().registryAccess()).orElseThrow();
        helper.assertValueEqual(saved.blocks().getFirst().data().tag(), data, "missing handlers do not discard payloads");
        try {
            saved.confirm(helper.getLevel());
            helper.fail("an already confirmed order still needs its integration");
        } catch (StructureCompat.Failure expected) {
            helper.assertTrue(expected.getMessage().endsWith("missing_integration"), "specific missing-integration refusal");
        }
        CompoundTag unknown = NbtUtils.writeBlockState(state());
        unknown.putString("Name", "absent:chiseled");
        Blueprint unloaded = new Blueprint(UUID.randomUUID(), "unloaded", List.of(), List.of(
            new UnloadedBlock(BlockPos.ZERO, unknown, List.of(), BlockData.of(data))), List.of(),
            BlueprintVolume.of(1, 1, 1).orElseThrow(), 0, true);
        var reloaded = Blueprint.load(Reader.of(unloaded.save(helper.getLevel().registryAccess())),
            helper.getLevel().registryAccess()).orElseThrow();
        helper.assertValueEqual(reloaded.unloaded().getFirst().data().tag(), data, "missing block mods retain all voxel data too");
        data.remove(StructureCompat.DESCRIPTOR);
        Blueprint unmarked = new Blueprint(UUID.randomUUID(), "external", List.of(), List.of(
            new UnloadedBlock(BlockPos.ZERO, unknown, List.of(), BlockData.of(data))), List.of(),
            BlueprintVolume.of(1, 1, 1).orElseThrow(), 0);
        try {
            unmarked.confirm(helper.getLevel());
            helper.fail("an external configured block also waits for its missing mod");
        } catch (StructureCompat.Failure expected) {
            helper.assertTrue(expected.getMessage().endsWith("missing_integration"), "raw imported data is not skipped for free");
        }
        data.putString(StructureCompat.DESCRIPTOR, "damaged descriptor");
        refuses(helper, () -> StructureCompat.validate(new StructurePayload(data)));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void decodeLimitsIncludeCompressedBombsMaterialsAndInvalidIndices(GameTestHelper helper) throws Exception {
        CompoundTag uncompressed = new CompoundTag();
        uncompressed.put("storage", StateEntryStorage.CODEC.encodeStart(NbtOps.INSTANCE, sculpture()).getOrThrow());
        uncompressed.putByteArray("padding", new byte[CandBConfig.DECODE_BYTES.get() + 1]);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new LZ4FrameOutputStream(packed))) {
            NbtIo.writeAnyTag(uncompressed, output);
        }
        CompoundTag versioned = new CompoundTag();
        versioned.putInt("version", 0);
        versioned.putByteArray("payload", packed.toByteArray());
        CompoundTag bomb = new CompoundTag();
        bomb.put("data", versioned);
        refuses(helper, () -> CandBStructureCodec.decode(bomb));
        try (var drawing = Voxelizer.installed().orElseThrow().drawing()) {
            refuses(helper, () -> CandBStructureCodec.decode(bomb));
        }
        int materials = CandBConfig.MATERIALS.get();
        try {
            CandBConfig.MATERIALS.set(1);
            refuses(helper, () -> CandBStructureCodec.decode(CandBStructureCodec.encode(sculpture())));
            try (var drawing = Voxelizer.installed().orElseThrow().drawing()) {
                refuses(helper, () -> CandBStructureCodec.encode(sculpture()));
            }
        } finally {
            CandBConfig.MATERIALS.set(materials);
        }
        BitSet indices = new BitSet();
        indices.set(0, 2);
        StateEntryStorage invalid = new StateEntryStorage(sculpture().palette(), indices);
        refuses(helper, () -> CandBStructureCodec.counts(invalid));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void legacyWrappersDecodeAndMigrateWithoutUsingCachedCounts(GameTestHelper helper) throws Exception {
        CompoundTag payload = new CompoundTag();
        payload.put("chiseledData", legacyStorage(sculpture()));
        payload.put("statistics", new CompoundTag());
        CompoundTag wrapped = legacyPacked(payload);
        for (int depth = 0; depth < 4; depth++) {
            CompoundTag outer = new CompoundTag();
            outer.put("data", wrapped);
            wrapped = outer;
        }
        CompoundTag data = new CompoundTag();
        data.put("data", wrapped);
        CandBTestData.sameVoxels(helper, sculpture(), CandBStructureCodec.decode(data));
        Blueprint source = new Blueprint(UUID.randomUUID(), "legacy", List.of(new BlueprintBlock(BlockPos.ZERO, state(),
            BlockData.of(data))), List.of(), List.of(), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0, true);
        CompoundTag old = source.save(helper.getLevel().registryAccess());
        old.remove("payloadVersion");
        Blueprint migrated = Blueprint.load(Reader.of(old), helper.getLevel().registryAccess()).orElseThrow().confirm(helper.getLevel());
        var entity = migrated.blocks().getFirst().data().entity(helper.getLevel(), BlockPos.ZERO, state());
        helper.assertValueEqual(new io.github.izakyl.folkways.chiselsandbits.CandBSchematicBridge().materials(entity)
            .stream().mapToLong(Need::count).sum(), 2L, "legacy orders resume with the decoded voxel price");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void legacyWrappersShareDepthAndDecompressionLimits(GameTestHelper helper) throws Exception {
        CompoundTag payload = new CompoundTag();
        payload.put("chiseledData", legacyStorage(sculpture()));
        CompoundTag tooDeep = legacyPacked(payload);
        for (int depth = 0; depth < 20; depth++) {
            CompoundTag outer = new CompoundTag();
            outer.put("data", tooDeep);
            tooDeep = outer;
        }
        CompoundTag data = new CompoundTag();
        data.put("data", tooDeep);
        refuses(helper, () -> CandBStructureCodec.decode(data));
        int maximum = CandBConfig.DECODE_BYTES.get();
        try {
            CandBConfig.DECODE_BYTES.set(16384);
            payload.putByteArray("padding", new byte[9000]);
            CompoundTag nested = legacyPacked(payload);
            nested.putByteArray("padding", new byte[9000]);
            data.put("data", legacyPacked(nested));
            refuses(helper, () -> CandBStructureCodec.decode(data));
        } finally {
            CandBConfig.DECODE_BYTES.set(maximum);
        }
        helper.succeed();
    }

    private static CompoundTag legacyPacked(CompoundTag payload) throws Exception {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new LZ4FrameOutputStream(packed))) {
            NbtIo.write(payload, output);
        }
        CompoundTag wrapper = new CompoundTag();
        wrapper.putBoolean("compressed", true);
        wrapper.putByteArray("data", packed.toByteArray());
        return wrapper;
    }

    private static CompoundTag legacyStorage(StateEntryStorage storage) {
        CompoundTag data = new CompoundTag();
        var palette = new net.minecraft.nbt.ListTag();
        for (var entry : storage.palette().paletteEntries()) {
            CompoundTag material = new CompoundTag();
            material.putString("state", mod.chiselsandbits.api.util.BlockStateSerializationUtils.serialize(
                entry.blockInformation().blockState()));
            palette.add(material);
        }
        data.put("palette", palette);
        data.putLongArray("data", storage.getRawData());
        return data;
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void repeatedPayloadsShareTheWholeBlueprintReadBudget(GameTestHelper helper) {
        CompoundTag data = CandBStructureCodec.encode(sculpture());
        CandBStructureCodec.validated(data);
        Blueprint repeated = new Blueprint(UUID.randomUUID(), "repeated", java.util.stream.IntStream.range(0, 20)
            .mapToObj(x -> new BlueprintBlock(new BlockPos(x, 0, 0), state(), BlockData.of(data))).toList(),
            BlueprintVolume.of(20, 1, 1).orElseThrow(), 0);
        int limit = CandBConfig.BLUEPRINT_READS.get();
        try {
            CandBConfig.BLUEPRINT_READS.set(4);
            helper.assertTrue(repeated.confirm(helper.getLevel()).confirmed(), "cached content needs no new decoding");
            // The cached content remains available outside the operation scope.
            CandBStructureCodec.validated(data);
        } finally {
            CandBConfig.BLUEPRINT_READS.set(limit);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void cachedPayloadsDoNotRepeatTheCumulativeDecodeWork(GameTestHelper helper) {
        var payload = new CompoundTag();
        payload.put("storage", StateEntryStorage.CODEC.encodeStart(NbtOps.INSTANCE, sculpture()).getOrThrow());
        payload.putByteArray("padding", new byte[6000]);
        CompoundTag data = new CompoundTag();
        data.put("data", currentPacked(payload));
        CandBStructureCodec.validated(data);
        int limit = CandBConfig.BLUEPRINT_BYTES.get();
        try {
            CandBConfig.BLUEPRINT_BYTES.set(16384);
            try (var processing = StructureCompat.processing()) {
                for (int cell = 0; cell < 8; cell++) {
                    CandBStructureCodec.validated(data);
                }
                helper.assertValueEqual(CandBStructureCodec.usage().reads(), 0, "no repeated decode reads");
                helper.assertValueEqual(CandBStructureCodec.usage().bytes(), 0L, "no repeated decode bytes");
            }
        } finally {
            CandBConfig.BLUEPRINT_BYTES.set(limit);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void processingTimeSpansTheWholeOperation(GameTestHelper helper) {
        CompoundTag raw = new CompoundTag();
        raw.put("storage", StateEntryStorage.CODEC.encodeStart(NbtOps.INSTANCE, sculpture()).getOrThrow());
        raw.putString("unique", UUID.randomUUID().toString());
        CompoundTag data = new CompoundTag();
        data.put("data", currentPacked(raw));
        int limit = CandBConfig.BLUEPRINT_MILLIS.get();
        try {
            CandBConfig.BLUEPRINT_MILLIS.set(10);
            refusesBudget(helper, () -> {
                try (var processing = StructureCompat.processing()) {
                    CandBStructureCodec.validated(data);
                    long until = System.nanoTime() + 25000000L;
                    while (System.nanoTime() < until) Thread.onSpinWait();
                    CandBStructureCodec.validated(data);
                }
            });
        } finally {
            CandBConfig.BLUEPRINT_MILLIS.set(limit);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void validatedStorageAndMaterialCountsAreReusedWithoutMutableAliases(GameTestHelper helper) {
        CompoundTag data = CandBStructureCodec.encode(sculpture());
        var first = CandBStructureCodec.validated(data);
        var second = CandBStructureCodec.validated(data.copy());
        helper.assertTrue(first == second, "identical payloads reuse one bounded cache entry");
        first.storage().clear();
        CandBTestData.sameVoxels(helper, sculpture(), second.storage());
        helper.assertValueEqual(second.counts().values().stream().mapToInt(Integer::intValue).sum(), 7,
            "caller mutations cannot change verified material counts");
        var bridge = new io.github.izakyl.folkways.chiselsandbits.CandBSchematicBridge();
        var entity = (ChiseledBlockEntity) bridge.detached(helper.getLevel().registryAccess(), BlockPos.ZERO, state(),
            new StructurePayload(data));
        entity.payload().storage().setBlockInformation(8, 0, 0,
            new BlockInformation(Blocks.STONE.defaultBlockState(), Optional.empty()));
        entity.setChanged();
        helper.assertValueEqual(bridge.voxels(entity).counts().values().stream().mapToInt(Integer::intValue).sum(), 8,
            "a changed entity no longer uses its earlier counts");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 40)
    public static void previewCountAndPayloadLimitsApplyBeforeMaterialQuotes(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(4, 2, 4));
        var cells = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var data = new java.util.LinkedHashMap<BlockPos, BlockData>();
        CompoundTag valid = CandBStructureCodec.encode(sculpture());
        cells.put(near, state());
        data.put(near, BlockData.of(valid));
        for (int cell = 1; cell < 30; cell++) {
            cells.put(near.east(cell), state());
            data.put(near.east(cell), BlockData.of(valid));
        }
        BuildTarget target = new BuildTarget(WorldPos.of(level, near).realm(), cells, Set.of(), List.of(), BlockPos.ZERO, data);
        int limit = CandBConfig.BLUEPRINT_READS.get();
        try {
            CandBConfig.BLUEPRINT_READS.set(10);
            try (var processing = StructureCompat.processing()) {
                var ghosts = BuildOrderView.pending(level, target, Set.of(), Set.of(), 1, near, 256 * 1024);
                helper.assertTrue(ghosts.size() == 1 && ghosts.getFirst().pos().equals(near), "only the nearest cell is quoted");
                CandBStructureCodec.validated(valid);
            }
            CandBConfig.BLUEPRINT_READS.set(1);
            try (var processing = StructureCompat.processing()) {
                helper.assertTrue(BuildOrderView.pending(level, target, Set.of(), Set.of(), 1, near, 0).isEmpty(),
                    "a payload outside the packet budget is never quoted");
                CandBStructureCodec.validated(valid);
            }
        } finally {
            CandBConfig.BLUEPRINT_READS.set(limit);
        }
        helper.succeed();
    }

    public static void checkRecipeDrawing(GameTestHelper helper, Pattern pattern, Hint hint, CompoundTag settings) {
        var snapshot = pattern.capture(helper.getLevel(), hint);
        var chosen = Chosen.load(pattern.knobs(), settings);
        var server = Rounds.draw(pattern, hint, chosen, Direction.EAST, snapshot).join();
        var client = RecipeDrawing.draw(pattern, hint, settings, Direction.EAST, snapshot).join();
        helper.assertValueEqual(client, server, "both drawing entries return the exact Draft, including payloads");
        helper.assertTrue(server instanceof Drawn.Ready, "determinism checks a successful drawing");
        Drafted recipe = new Drafted(UUID.randomUUID(), pattern.id(), hint, settings, Direction.EAST);
        helper.assertValueEqual(Drafted.load(Reader.of(recipe.save())).orElseThrow(), recipe,
            "the full recipe survives persistence");
        for (String key : List.of("settings", "facing")) {
            CompoundTag legacy = recipe.save();
            legacy.remove(key);
            helper.assertTrue(Drafted.load(Reader.of(legacy)).isEmpty(), "missing " + key + " uses payload fallback");
        }
        LoggerFactory.getLogger("folkways-patterns").info("RECIPE_DETERMINISM exact=true pattern={}", pattern.id());
    }

    private static void reportRecipePreview(GameTestHelper helper, BuildTarget target, BlockPos anchor) {
        UUID order = UUID.randomUUID();
        var hint = new Hint.Zone(anchor, anchor.offset(11, 5, 11));
        CompoundTag recipe = new Drafted(order, ResourceLocation.parse("folkways:bits_benchmark"),
            hint, new CompoundTag(), Direction.NORTH).save();
        long started = System.nanoTime();
        List<Ghost> before;
        int oldReads;
        try (var processing = StructureCompat.processing()) {
            before = BuildOrderView.pending(helper.getLevel(), target, Set.of(), Set.of(),
                2048, hint.anchor(), 256 * 1024);
            oldReads = CandBStructureCodec.usage().reads();
        }
        double oldMillis = (System.nanoTime() - started) / 1_000_000D;
        started = System.nanoTime();
        List<Ghost> after;
        int newReads;
        try (var processing = StructureCompat.processing()) {
            after = BuildOrderView.recipePending(helper.getLevel(), target, Set.of(), Set.of(),
                2048, hint.anchor(), order);
            newReads = CandBStructureCodec.usage().reads();
        }
        double newMillis = (System.nanoTime() - started) / 1_000_000D;
        for (Ghost ghost : after) {
            helper.assertValueEqual(ghost.payloadHash(), target.dataAt(ghost.pos()).payload(ghost.state()).hashCode(),
                "a saved cell fingerprint matches its reconstructed payload");
        }
        helper.assertValueEqual(newReads, 0, "recipe preview never decodes voxels");
        helper.assertTrue(after.stream().noneMatch(ghost -> ghost.payload().present()),
            "recipe ghosts carry no voxels");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
            helper.getLevel().registryAccess());
        try {
            // The former packet had six list headers and no order identifier in a Ghost.
            for (int i = 0; i < 2; i++) {
                buffer.writeVarInt(0);
            }
            buffer.writeVarInt(before.size());
            for (var ghost : before) {
                buffer.writeBlockPos(ghost.pos());
                buffer.writeVarInt(Block.getId(ghost.state()));
                buffer.writeNbt(ghost.payload().tag());
            }
            for (int i = 0; i < 3; i++) {
                buffer.writeVarInt(0);
            }
            int oldBytes = buffer.readableBytes();
            buffer.clear();
            var packet = new ColonyOverviewPacket(List.of(), List.of(), after,
                List.of(), List.of(), List.of(), List.of(recipe));
            ColonyOverviewPacket.STREAM_CODEC.encode(buffer, packet);
            int firstBytes = buffer.readableBytes();
            helper.assertValueEqual(ColonyOverviewPacket.STREAM_CODEC
                .decode(buffer), packet, "recipe preview crosses the wire intact");
            buffer.clear();
            ColonyOverviewPacket.STREAM_CODEC.encode(buffer,
                new ColonyOverviewPacket(List.of(), List.of(), after,
                    List.of(), List.of(), List.of()));
            LoggerFactory.getLogger("folkways-patterns").info(
                "RECIPE_PREVIEW oldBytes={} firstBytes={} refreshBytes={} oldReads={} newReads={}"
                    + " oldMillis={} newMillis={} oldCells={} newCells={}",
                oldBytes, firstBytes, buffer.readableBytes(), oldReads, newReads, oldMillis, newMillis,
                before.size(), after.size());
        } finally {
            buffer.release();
        }
    }

    public static void reportPatternBudgets(GameTestHelper helper, CompoundTag file) {
        var level = helper.getLevel();
        helper.assertTrue(level.getServer().isSameThread(), "blueprint budgets are measured on the server thread");
        Blueprint source = StructureNbt.toBlueprint(file, level.registryAccess(), UUID.randomUUID(), "bits-benchmark", 0,
            StructureNbt.AirMeaning.UNCONSTRAINED, BlueprintBook.maxCaptureBlocks()).orElseThrow();
        Optional<Blueprint> confirmed = measuredBudget("confirm", () -> source.confirm(level));
        helper.assertTrue(measuredBudget("quote", () -> BlueprintMaterials.describe(file,
            level.registryAccess())).isPresent(),
            "the material estimate passes the default budget");
        helper.assertTrue(confirmed.isPresent(), "confirmation passes the default budget");
        Blueprint saved = confirmed.orElseThrow();
        BlockPos anchor = helper.absolutePos(new BlockPos(64, 2, 64));
        for (int x = 0; x < source.volume().size().getX(); x++) {
            for (int z = 0; z < source.volume().size().getZ(); z++) {
                level.setBlockAndUpdate(anchor.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < source.volume().size().getY(); y++) {
                    level.setBlockAndUpdate(anchor.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        var order = BlueprintBuildOrder.create(source.id(), source.name(), WorldPos.of(level, anchor),
            Optional.empty(), "", level.getGameTime());
        BuildTarget target = BuildTarget.of(order, saved);
        if (source.volume().size().equals(new BlockPos(12, 6, 12))) {
            reportRecipePreview(helper, target, anchor);
        }
        var opened = measuredBudget("start", () -> {
            for (BlueprintBlock block : saved.blocks()) {
                StructureCompat.inspect(block.state(), new StructurePayload(block.data().tag()));
            }
            var asks = new BuildSite.Asks() {
                @Override
                public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
                }

                @Override
                public void withdraw(UUID node) {
                }
            };
            BuildSite site = new BuildSite(order.id(), asks, target, new TemporaryScaffolds(() -> { }));
            try {
                long started = System.nanoTime();
                site.begin(level, Set.of(), Set.of());
                long maximum = System.nanoTime() - started;
                int slices = 1;
                boolean finished = false;
                while (!finished) {
                    String stage = site.startingStage();
                    started = System.nanoTime();
                    finished = site.advanceStart(level);
                    long duration = System.nanoTime() - started;
                    if (duration > 20_000_000L) {
                        LoggerFactory.getLogger("folkways-patterns").info("BITS_SLOW stage={} millis={}", stage,
                            duration / 1_000_000D);
                    }
                    maximum = Math.max(maximum, duration);
                    slices++;
                }
                LoggerFactory.getLogger("folkways-patterns").info("BITS_START slices={} maxMillis={}",
                    slices, maximum / 1_000_000D);
                return site.owed();
            } finally {
                site.close();
            }
        });
        helper.assertTrue(opened.isPresent(), "the staged opening passes the default budget");
        if (source.volume().size().getX() <= 20) {
            Layering.Plan plan = Layering.of(level, target);
            var worker = new PlanningFixture(helper);
            worker.worker.body().setPos(anchor.getX() - 4.5, anchor.getY(), anchor.getZ() - 4.5);
            var cache = new Carving.Cache(() -> { });
            long blocks = 0;
            long previousBlocks = 0;
            long carved = 0;
            long placed = 0;
            long maximum = 0;
            for (Layering.Work work : plan.works()) {
                Step step = work.step().orElseThrow();
                BuildJob job = step.job(level, target, Stances.at(level, anchor.west(4)));
                BuildNode node = BuildNode.of(job, (ended, how) -> { }, new TemporaryScaffolds(() -> { }));
                previousBlocks += node.spec().needs().stream().mapToLong(Need::count).sum();
                node.cache = cache;
                node.requote(level);
                int slot = 0;
                worker.worker.pack().clearContent();
                for (Need need : node.spec().needs()) {
                    ItemStack stack = Goods.display(need.spec());
                    stack.setCount((int) need.count());
                    worker.worker.pack().setItem(slot++, stack);
                    blocks += need.count();
                }
                long started = System.nanoTime();
                Outcome outcome = Commit.run(level, node, worker.worker);
                long duration = System.nanoTime() - started;
                placed += duration;
                maximum = Math.max(maximum, duration);
                helper.assertTrue(outcome instanceof Outcome.Done,
                    "each pattern node commits with whole blocks: " + outcome);
                if (work.kind() == Layering.Kind.CARVE) {
                    carved++;
                }
            }
            helper.assertTrue(BuildPlanner.complete(level, target), "the bits pattern is built end to end");
            long expected = Carving.estimate(saved.blocks().stream().map(block -> Carving.volume(
                StructureCompat.of(block.state()).or(StructureCompat::carver).orElseThrow(),
                block.state(), block.data())).toList()).stream().mapToLong(Need::count).sum();
            helper.assertValueEqual(blocks, expected, "all nodes spend the whole-site rounded estimate");
            helper.assertValueEqual(opened.orElseThrow().values().stream().mapToLong(Long::longValue).sum(), blocks,
                "the sidebar quotes whole-site rounding instead of summing node ceilings");
            long voxels = saved.blocks().stream().mapToLong(block -> StructureCompat.of(block.state())
                .map(handler -> handler.voxels(block.data().payload(block.state())).counts().values().stream()
                    .mapToLong(Integer::longValue).sum()).orElse((long) Math.pow(Voxelizer.installed().orElseThrow().resolution(), 3))).sum();
            double units = Math.pow(Voxelizer.installed().orElseThrow().resolution(), 3);
            LoggerFactory.getLogger("folkways-patterns").info(
                "BITS_BUILD nodes={} carveNodes={} blocks={} exactBlocks={} lossPercent={} previousBlocks={}"
                    + " previousLossPercent={} placeMillis={} maxNodeMillis={}",
                plan.works().size(), carved, blocks, voxels / units, (blocks * units / voxels - 1) * 100,
                previousBlocks, (previousBlocks * units / voxels - 1) * 100,
                placed / 1_000_000D, maximum / 1_000_000D);
            worker.worker.body().discard();
        }
    }

    private static <T> Optional<T> measuredBudget(String stage, Supplier<T> operation) {
        long started = System.nanoTime();
        CandBStructureCodec.Usage usage = new CandBStructureCodec.Usage(0, 0);
        T result = null;
        String refused = "none";
        try (var processing = StructureCompat.processing()) {
            try {
                result = operation.get();
            } finally {
                usage = CandBStructureCodec.usage();
            }
        } catch (StructureCompat.Failure limited) {
            if (!limited.getMessage().equals("folkways.refusal.build.structure_budget")) {
                throw limited;
            }
            refused = limited.getMessage();
            result = null;
        }
        LoggerFactory.getLogger("folkways-patterns").info(
            "BITS_MAIN stage={} reads={} bytes={} millis={} refused={}", stage, usage.reads(), usage.bytes(),
            (System.nanoTime() - started) / 1_000_000, refused);
        return Optional.ofNullable(result);
    }

    private static CompoundTag currentPacked(CompoundTag payload) {
        try {
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new LZ4FrameOutputStream(packed))) {
                NbtIo.writeAnyTag(payload, output);
            }
            CompoundTag versioned = new CompoundTag();
            versioned.putInt("version", 0);
            versioned.putByteArray("payload", packed.toByteArray());
            return versioned;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void refusesBudget(GameTestHelper helper, Runnable action) {
        try {
            action.run();
            helper.fail("the whole-blueprint budget must refuse excessive work");
        } catch (StructureCompat.Failure expected) {
            helper.assertTrue(expected.getMessage().endsWith("structure_budget"), "an explicit whole-blueprint refusal");
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void wholeBlueprintTransformsKeepVoxelsAndTheirExactCosts(GameTestHelper helper) {
        Blueprint source = blueprint(helper);
        for (Rotation rotation : Rotation.values()) {
            for (Mirror mirror : Mirror.values()) {
                Blueprint transformed = source.transformed(rotation, mirror).orElseThrow().confirm(helper.getLevel());
                var expected = sculpture();
                CandBStructureCodec.turn(expected, mirror, rotation);
                CandBTestData.sameVoxels(helper, expected,
                    CandBStructureCodec.decode(transformed.blocks().getFirst().data().tag()));
                helper.assertValueEqual(CandBMaterials.of(expected).stream().mapToInt(ItemStack::getCount).sum(), 2,
                    "transformation preserves cost");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void thePlannerHaulsWholeMaterialsSeparately(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, f.source.cell()).orElseThrow();
        List<ItemStack> bits = CandBMaterials.of(sculpture());
        source.setItem(0, bits.getFirst().copy());
        source.setItem(1, bits.getLast().copy());
        var needs = bits.stream().map(bit -> new Need(ItemSpec.exact(bit), bit.getCount(), true)).toList();
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), PlanningFixture.Rounds.OWNER, new WorkSite.AtBlock(f.target),
            Stances.at(f.target), Workload.Once.of(1)).needs(needs).done();
        Node consuming = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) {
                helper.assertValueEqual(who.supplied().stream().mapToInt(ItemStack::getCount).sum(), 2,
                    "both whole blocks supplied");
                helper.assertTrue(who.supplied().stream().allMatch(bit -> needs.stream()
                    .anyMatch(need -> Goods.matches(bit, need.spec()))), "components survive hauling");
                return Outcome.done();
            }
        };
        var solver = new PlanningFixture.Rounds();
        try {
            Stash stash = new Stash(f.source);
            var solved = solver.solve(f.situation(new Stock(bits.stream().map(bit -> new Stock.Holding(stash, bit)).toList(),
                Map.of(stash, 25)), List.of()), f.crew, List.of(Grown.of(consuming)), List.of(), 0);
            var tour = solved.schedule().tours().get(f.worker.resident().id());
            helper.assertTrue(tour != null && tour.nodes().size() == 3, "two separate fetches precede consumption");
            for (UUID id : tour.nodes()) {
                var vertex = solved.weave().vertices().get(id);
                helper.assertTrue(Commit.run(level, vertex.node(), new Placed(f.worker, vertex.plan())) instanceof Outcome.Done,
                    "the material-aware planned step commits");
            }
            helper.assertTrue(source.isEmpty() && f.worker.pack().isEmpty(), "no bit identities merge or remain unpaid");
        } finally {
            solver.closed();
            f.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 320)
    public static void sixCarversReenterAfterOtherWorkClosesTheirPlannedFootings(GameTestHelper helper) {
        var level = helper.getLevel();
        List<net.minecraft.world.level.block.state.BlockState> materials = List.of(
            Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState());
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> cells = new java.util.LinkedHashMap<>();
        Map<BlockPos, BlockData> data = new java.util.LinkedHashMap<>();
        List<Worker> workers = new java.util.ArrayList<>();
        for (int x = 1; x <= 22; x++) {
            for (int z = 4; z <= 16; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(),
                    Rubble.SHOT);
            }
        }
        for (int i = 0; i < materials.size(); i++) {
            BlockPos cell = helper.absolutePos(new BlockPos(3 + i * 3, 7, 10));
            cells.put(cell, state());
            StateEntryStorage storage = new StateEntryStorage();
            try (var batch = storage.batch()) {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 8; y++) {
                        for (int z = 0; z < 16; z++) {
                            storage.setBlockInformation(x, y, z,
                                new BlockInformation(materials.get(i), Optional.empty()));
                        }
                    }
                }
            }
            data.put(cell, new BlockData(CandBStructureCodec.encode(storage), List.of()));
            // Initially the only nearby footing is this platform, with two clear cells below the overhang.
            level.setBlock(cell.west().below(3), Blocks.STONE.defaultBlockState(), Rubble.SHOT);
            for (int y = 0; y <= 7; y++) {
                level.setBlock(cell.west().above(y), Blocks.STONE.defaultBlockState(), Rubble.SHOT);
            }
            Worker worker = new PlanningFixture(helper).worker;
            worker.body().setPos(helper.absolutePos(new BlockPos(1, 1, 1)).getBottomCenter());
            worker.pack().setItem(0, new ItemStack(materials.get(i).getBlock(), 64));
            workers.add(worker);
        }
        var active = new java.util.LinkedHashMap<UUID, Node>();
        var endings = new java.util.LinkedHashMap<UUID, BiConsumer<UUID, Ending>>();
        var asks = new BuildSite.Asks() {
            @Override
            public Optional<List<ItemStack>> pack(Worker who) {
                return Optional.of(java.util.stream.IntStream.range(0, who.pack().getContainerSize())
                    .mapToObj(slot -> who.pack().getItem(slot).copy()).toList());
            }

            @Override
            public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
                work.nodes().forEach(node -> {
                    active.put(node.id(), node);
                    endings.put(node.id(), ended);
                });
            }

            @Override
            public void withdraw(UUID node) {
                active.remove(node);
            }
        };
        BuildTarget target = new BuildTarget(WorldPos.of(level, cells.keySet().iterator().next()).realm(), cells,
            Set.of(), List.of(), BlockPos.ZERO, data);
        BuildSite site = new BuildSite(UUID.randomUUID(), asks, target, new TemporaryScaffolds(() -> { }));
        site.start(level, Set.of());
        helper.onEachTick(() -> site.tick(level));
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(active.size(), 6, "six independent turns"))
            .thenExecute(() -> {
                for (Node stay : List.copyOf(active.values())) {
                    Workload.Within shift = (Workload.Within) stay.spec().workload();
                    Worker worker = workers.stream().filter(who -> stay.spec().needs().stream()
                        .allMatch(need -> Goods.countIn(who.pack(), need.spec()) >= need.count()))
                        .findFirst().orElseThrow();
                    var turn = shift.next(level, worker);
                    helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "each worker receives its carving");
                    Node piece = ((Workload.Within.Turn.Piece) turn).node();
                    shift.ended(level, piece, Ending.DROPPED);
                    endings.get(stay.id()).accept(stay.id(), Ending.DONE);
                    active.remove(stay.id());
                }
                // Other construction closes the old stances; the clear ground underneath still exists.
                for (BlockPos cell : cells.keySet()) {
                    level.setBlock(cell.west().below(), Blocks.STONE.defaultBlockState(), Rubble.SHOT);
                }
            }).thenExecuteAfter(210, () -> {
                helper.assertValueEqual(active.size(), 6, "all six carvings are offered again on current ground");
                for (Node stay : List.copyOf(active.values())) {
                    Workload.Within shift = (Workload.Within) stay.spec().workload();
                    Worker worker = workers.stream().filter(who -> stay.spec().needs().stream()
                        .allMatch(need -> Goods.countIn(who.pack(), need.spec()) >= need.count()))
                        .findFirst().orElseThrow();
                    var turn = shift.next(level, worker);
                    helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "the reentered worker can build");
                    Node piece = ((Workload.Within.Turn.Piece) turn).node();
                    helper.assertTrue(Commit.run(level, piece, worker) instanceof Outcome.Done, "carving commits once");
                    shift.ended(level, piece, Ending.DONE);
                    endings.get(stay.id()).accept(stay.id(), Ending.DONE);
                    active.remove(stay.id());
                }
                site.tick(level);
                helper.assertTrue(site.complete()
                    && cells.keySet().stream().allMatch(cell -> target.satisfied(level, cell)),
                    "every voxel is met and no live work remains");
                site.close();
                workers.forEach(worker -> worker.body().discard());
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void multiCellCarvingNetsRoundsAndRequotesWorldChanges(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos next = at.east();
        level.setBlock(at, Blocks.STONE.defaultBlockState(), Rubble.SHOT);
        var target = new BuildTarget(WorldPos.of(level, at).realm(), Map.of(at, state(), next, state()), Set.of(),
            List.of(),
            BlockPos.ZERO, Map.of(at, payload(4095), next, payload(4095)));
        Step step = new Step(BuildJob.Kind.CARVE, at, Map.of(at, level.getBlockState(at), next,
            level.getBlockState(next)),
            target.cells());
        var worker = new PlanningFixture(helper);
        worker.worker.body().setPos(at.getX() - 2.5, at.getY(), at.getZ() + 0.5);
        BuildNode node = BuildNode.of(step.job(level, target, Stances.at(level, at.west(2))), (ended, how) -> { },
            new TemporaryScaffolds(() -> { }));
        helper.assertValueEqual(node.spec().needs().stream().mapToLong(Need::count).sum(), 1L,
            "the original full block offsets new voxels in both cells before rounding");
        worker.worker.pack().setItem(0, new ItemStack(Items.STONE));
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Rubble.SHOT);
        helper.assertTrue(Commit.run(level, node, worker.worker) instanceof Outcome.Failed,
            "a quote cannot give away a block removed after quoting");
        helper.assertTrue(level.getBlockState(next).isAir() && worker.worker.pack().getItem(0).getCount() == 1,
            "the entire multi-cell write and payment roll back");
        node.requote(level);
        helper.assertValueEqual(node.spec().needs().stream().mapToLong(Need::count).sum(), 2L, "the retry is repriced");
        worker.worker.pack().setItem(0, new ItemStack(Items.STONE, 2));
        level.setBlock(at, Blocks.STONE.defaultBlockState(), Rubble.SHOT);
        Outcome built = Commit.run(level, node, worker.worker);
        helper.assertTrue(built instanceof Outcome.Done done && done.made().isEmpty(), "one commit carves both cells");
        helper.assertValueEqual(worker.worker.pack().getItem(0).getCount(), 1,
            "a lower current quote returns the spare block");
        helper.assertTrue(target.satisfied(level, at) && target.satisfied(level, next), "both payloads match");
        var wholeTarget = new BuildTarget(target.realm(), Map.of(at, Blocks.STONE.defaultBlockState(), next, state()),
            Set.of(), List.of(), BlockPos.ZERO, Map.of(next, payload(4095)));
        Step whole = new Step(BuildJob.Kind.CARVE, at, target.cells(), wholeTarget.cells());
        BuildNode replacement = BuildNode.of(whole.job(level, wholeTarget, Stances.at(level, at.west(2))),
            (ended, how) -> { }, new TemporaryScaffolds(() -> { }));
        replacement.cache = node.cache;
        replacement.requote(level);
        helper.assertTrue(Commit.run(level, replacement, worker.worker) instanceof Outcome.Done,
            "an existing carving can be replaced by an ordinary whole block");
        helper.assertTrue(level.getBlockState(at).is(Blocks.STONE) && worker.worker.pack().getItem(0).getCount() == 1,
            "the additional voxel uses the previous node balance");
        Step clear = new Step(BuildJob.Kind.CARVE, at, wholeTarget.cells(),
            Map.of(at, Blocks.AIR.defaultBlockState(), next, Blocks.AIR.defaultBlockState()));
        var cleared = new BuildTarget(target.realm(), clear.after());
        BuildNode demolition = BuildNode.of(clear.job(level, cleared, Stances.at(level, at.west(2))),
            (ended, how) -> { }, new TemporaryScaffolds(() -> { }));
        demolition.cache = replacement.cache;
        demolition.requote(level);
        helper.assertTrue(demolition.spec().leaves().isEmpty(), "excavation stays in the site cache");
        Outcome removed = Commit.run(level, demolition, worker.worker);
        helper.assertTrue(removed instanceof Outcome.Done done && done.made().isEmpty(),
            "demolition never puts cached material in a worker pack");
        var restoredCache = new Carving.Cache(() -> { });
        restoredCache.load(Reader.of(demolition.cache.save()));
        helper.assertValueEqual(restoredCache.save(), demolition.cache.save(), "exact balances survive reload");
        var rebuilt = BuildNode.of(step.job(level, target, Stances.at(level, at.west(2))),
            (ended, how) -> { }, new TemporaryScaffolds(() -> { }));
        rebuilt.cache = restoredCache;
        rebuilt.requote(level);
        helper.assertTrue(rebuilt.spec().needs().isEmpty(), "removed voxels offset another node after reload");
        helper.assertValueEqual(restoredCache.drain().stream().mapToLong(one -> one.count()).sum(), 2L,
            "settlement returns all whole blocks and discards only the fraction");
        helper.assertTrue(restoredCache.drain().isEmpty(), "settlement cannot refund twice");
        helper.assertTrue(level.getBlockState(at).isAir() && level.getBlockState(next).isAir(),
            "both carvings are removed");
        helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(at).expandTowards(1, 0, 0).inflate(2)).isEmpty(),
            "carving and demolition never drop carving items or fragments into the world");
        var shared = new Carving.Cache(() -> { });
        List<BuildNode> parallel = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BlockPos cell = at.east(i);
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Rubble.SHOT);
            var half = new BuildTarget(target.realm(), Map.of(cell, state()), Set.of(), List.of(),
                BlockPos.ZERO, Map.of(cell, payload(2048)));
            var piece = new Step(BuildJob.Kind.CARVE, cell, Map.of(cell, Blocks.AIR.defaultBlockState()), half.cells());
            BuildNode candidate = BuildNode.of(piece.job(level, half, Stances.at(level, at.west(2))),
                (ended, how) -> { }, new TemporaryScaffolds(() -> { }));
            candidate.cache = shared;
            parallel.add(candidate);
        }
        worker.worker.pack().setItem(0, new ItemStack(Items.STONE));
        helper.assertTrue(Commit.run(level, parallel.get(0), worker.worker) instanceof Outcome.Done,
            "first half block funds the shared cache");
        CompoundTag differentUnits = shared.save();
        CompoundTag material = differentUnits.getList("materials", CompoundTag.TAG_COMPOUND).getCompound(0);
        material.putString("count", "32");
        material.putString("units", "64");
        shared.load(Reader.of(differentUnits));
        parallel.get(1).requote(level);
        parallel.get(2).requote(level);
        CompoundTag beforeExit = shared.save();
        parallel.get(2).ended(level, Ending.DROPPED);
        helper.assertValueEqual(shared.save(), beforeExit, "quotes and worker exits do not reserve or spend voxels");
        helper.assertTrue(parallel.get(1).spec().needs().isEmpty() && parallel.get(2).spec().needs().isEmpty(),
            "two workers can receive quotes against the same available balance");
        helper.assertTrue(Commit.run(level, parallel.get(1), worker.worker) instanceof Outcome.Done,
            "one worker spends the available half block");
        CompoundTag beforeFailure = shared.save();
        helper.assertTrue(Commit.run(level, parallel.get(2), worker.worker) instanceof Outcome.Failed,
            "a stale concurrent quote cannot spend the same balance twice");
        helper.assertValueEqual(shared.save(), beforeFailure, "failure leaves the balance unchanged");
        parallel.get(2).requote(level);
        helper.assertValueEqual(parallel.get(2).spec().needs().stream().mapToLong(Need::count).sum(), 1L,
            "a concurrent retry quotes the missing whole block");
        worker.worker.pack().setItem(0, new ItemStack(Items.STONE));
        helper.assertTrue(Commit.run(level, parallel.get(2), worker.worker) instanceof Outcome.Done,
            "the refreshed concurrent quote completes without a retry loop");
        worker.worker.body().discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void carveClustersKeepGravityAndHangingDependenciesForward(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        // Choose an adjacent pair within the same horizontal cluster tile.
        if (Math.floorMod(at.getX(), Layering.CARVE_SPAN) == Layering.CARVE_SPAN - 1) {
            at = at.west();
        }
        BlockPos next = at.east();
        BlockPos cleared = at.south(4);
        level.setBlock(cleared, state(), Rubble.SHOT);
        level.getBlockEntity(cleared).loadWithComponents(payload(4096).tag(), level.registryAccess());
        level.setBlock(cleared.above(), Blocks.TORCH.defaultBlockState(), Rubble.SHOT);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> cells = new java.util.LinkedHashMap<>();
        cells.put(at, state());
        cells.put(next, state());
        cells.put(at.above(), Blocks.SAND.defaultBlockState());
        cells.put(cleared, Blocks.AIR.defaultBlockState());
        cells.put(cleared.above(), Blocks.AIR.defaultBlockState());
        BuildTarget target = new BuildTarget(WorldPos.of(level, at).realm(), cells, Set.of(), List.of(), BlockPos.ZERO,
            Map.of(at, payload(1), next, payload(1)));
        Layering.Plan plan = Layering.of(level, target);
        var cluster = plan.works().stream().filter(work -> work.kind() == Layering.Kind.CARVE
            && work.step().orElseThrow().after().containsKey(next)).findFirst().orElseThrow();
        helper.assertValueEqual(cluster.step().orElseThrow().after().size(), 2, "adjacent carvings share one node");
        var sand = plan.works().stream().filter(work -> work.focus().equals(next.west().above())).findFirst().orElseThrow();
        helper.assertTrue(plan.after().getOrDefault(sand.id(), Set.of()).contains(cluster.id()),
            "sand waits for its carve support");
        var support = plan.works().stream().filter(work -> work.focus().equals(cleared)).findFirst().orElseThrow();
        var torch = plan.works().stream().filter(work -> work.focus().equals(cleared.above())).findFirst().orElseThrow();
        helper.assertTrue(plan.after().getOrDefault(support.id(), Set.of()).contains(torch.id()),
            "hanging removal precedes clearing its support");
        Map<Integer, Long> keys = new java.util.HashMap<>();
        plan.works().forEach(work -> keys.put(work.id(), work.key()));
        plan.after().forEach((id, before) -> before.forEach(first ->
            helper.assertTrue(keys.get(first) < keys.get(id), "all dependencies point forward")));
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void uncarvableGoalClearsThenLaysAndKeepsNormalCost(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), Rubble.SHOT);
        level.setBlock(at, state(), Rubble.SHOT);
        level.getBlockEntity(at).loadWithComponents(payload(2048).tag(), level.registryAccess());
        for (var goal : List.of(Blocks.CHEST.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.TORCH.defaultBlockState())) {
            var target = new BuildTarget(WorldPos.of(level, at).realm(), Map.of(at, goal));
            Layering.Plan plan = Layering.of(level, target, Set.of());
            helper.assertTrue(plan.refused().isEmpty(), "ordinary replacement is not refused: " + goal);
            var carve = plan.works().stream().filter(work -> work.kind() == Layering.Kind.CARVE)
                .findFirst().orElseThrow();
            var lay = plan.works().stream().filter(work -> work.kind() == Layering.Kind.LAY)
                .findFirst().orElseThrow();
            helper.assertTrue(carve.step().orElseThrow().after().get(at).isAir()
                && plan.after().getOrDefault(lay.id(), Set.of()).contains(carve.id()),
                "ordinary placement waits for carving to air");
        }
        var target = new BuildTarget(WorldPos.of(level, at).realm(), Map.of(at, Blocks.TORCH.defaultBlockState()));
        var asks = new BuildSite.Asks() {
            @Override
            public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
            }

            @Override
            public void withdraw(UUID node) {
            }
        };
        var site = new BuildSite(UUID.randomUUID(), asks, target, new TemporaryScaffolds(() -> { }));
        site.start(level, Set.of());
        helper.assertValueEqual(site.owed().getOrDefault(ItemSpec.of(Items.TORCH), 0L), 1L,
            "site charges the ordinary torch");
        helper.assertValueEqual(BuildOrderView.owed(level, target, Set.of())
            .getOrDefault(ItemSpec.of(Items.TORCH), 0L), 1L, "preview charges the ordinary torch");
        var fixture = new PlanningFixture(helper);
        fixture.worker.body().setPos(at.getX() - 2.5, at.getY(), at.getZ() + 0.5);
        var carve = site.pieces().stream().filter(piece -> piece.node() instanceof CarveNode)
            .findFirst().orElseThrow();
        var expected = new Carving.Cache(() -> { });
        expected.accept(Carving.quote(level, Map.of(at, Blocks.AIR.defaultBlockState()), cell -> BlockData.EMPTY));
        helper.assertTrue(!expected.save().getList("materials", CompoundTag.TAG_COMPOUND).isEmpty(),
            "demolition quotes the standing voxels");
        helper.assertTrue(Commit.run(level, carve.node(), fixture.worker) instanceof Outcome.Done done
            && done.made().isEmpty(), "carving credits the cache without drops");
        site.ended(carve.node().id(), Ending.DONE);
        site.tick(level);
        helper.assertTrue(level.getBlockState(at).isAir() && site.met().isEmpty() && !site.complete(),
            "demolition alone does not latch the goal");
        helper.assertTrue(Layering.of(level, target, site.met()).works().stream()
            .allMatch(work -> work.kind() == Layering.Kind.LAY), "reopening between stages plans ordinary work");
        var lay = site.pieces().stream().filter(piece -> piece.work().kind() == Layering.Kind.LAY)
            .findFirst().orElseThrow();
        fixture.worker.pack().setItem(0, new ItemStack(Items.TORCH));
        helper.assertTrue(Commit.run(level, lay.node(), fixture.worker) instanceof Outcome.Done,
            "ordinary placement commits after demolition");
        site.ended(lay.node().id(), Ending.DONE);
        site.tick(level);
        helper.assertTrue(site.complete() && level.getBlockState(at).is(Blocks.TORCH)
            && level.getBlockEntity(at) == null, "supported torch replaces the chiseled block");
        helper.assertTrue(fixture.worker.pack().getItem(0).isEmpty(), "torch pays its normal cost");
        helper.assertValueEqual(site.cache.save(), expected.save(),
            "all demolished voxels remain in the order cache after ordinary placement");
        helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(at).inflate(2)).isEmpty(), "no carving drops remain");
        site.close();
        fixture.worker.body().discard();
        helper.succeed();
    }

    private static BlockData payload(int count) {
        StateEntryStorage storage = new StateEntryStorage();
        try (var batch = storage.batch()) {
            for (int index = 0; index < count; index++) {
                storage.setBlockInformation(index / 256, index / 16 % 16, index % 16,
                    new BlockInformation(Blocks.STONE.defaultBlockState(), Optional.empty()));
            }
        }
        return BlockData.of(CandBStructureCodec.encode(storage));
    }

    private static Blueprint blueprint(GameTestHelper helper) {
        return new Blueprint(UUID.randomUUID(), "chiseled", List.of(new BlueprintBlock(BlockPos.ZERO, state(),
            BlockData.of(CandBStructureCodec.encode(sculpture())))), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0)
            .confirm(helper.getLevel());
    }

    private static BuildTarget target(ServerLevel level, BlockPos at, Blueprint blueprint) {
        return new BuildTarget(WorldPos.of(level, at).realm(), Map.of(at, state()), Set.of(), List.of(), BlockPos.ZERO,
            Map.of(at, blueprint.blocks().getFirst().data()));
    }

    private static Node node(GameTestHelper helper, BlockPos at, Blueprint blueprint, Set<BlockPos> met) {
        var level = helper.getLevel();
        BuildTarget target = target(level, at, blueprint);
        Step step = new Step(BuildJob.Kind.CARVE, at, Map.of(at, level.getBlockState(at)), Map.of(at, state()));
        return BuildNode.of(step.job(level, target, Stances.at(level, at.east(2)), met), (node, how) -> { },
            new TemporaryScaffolds(() -> { }))
            .keeping(met::contains);
    }

    private static void refuses(GameTestHelper helper, Runnable read) {
        boolean refused = false;
        try { read.run(); } catch (RuntimeException expected) { refused = true; }
        helper.assertTrue(refused, "invalid or excessive voxel data is refused");
    }

    private record Placed(Worker who, Placement placement) implements Worker {
        public Resident resident() { return who.resident(); }

        public Mob body() { return who.body(); }

        public Container pack() { return who.pack(); }

        public List<Store> within() { return who.within(); }

        public ItemStack held(ToolNeed need) { return who.held(need); }

        public void spill(ItemStack stack) { who.spill(stack); }

        public Effects effects() { return who.effects(); }

        public List<Node> route() { return who.route(); }
    }
}
