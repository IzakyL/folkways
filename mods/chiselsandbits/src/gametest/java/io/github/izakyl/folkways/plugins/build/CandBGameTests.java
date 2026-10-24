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
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysChiselsAndBitsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CandBGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CandBGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void decodedVoxelsOverrideTheCachedCountsWithoutRounding(GameTestHelper helper) {
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
            List.of(1024, 3072), "exact ready-made bit counts, without a container or whole-block cost");
        helper.assertTrue(!ItemSpec.exact(costs.getFirst()).equals(ItemSpec.exact(costs.getLast())), "materials remain distinct");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bitIdentitiesSurviveSaveReloadAndDisplay(GameTestHelper helper) {
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
    public static void missingOneMaterialRestoresAllBitsAndNeverPlaces(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        var f = new PlanningFixture(helper);
        f.worker.body().setPos(at.getX() + 2.5, at.getY(), at.getZ() + 0.5);
        List<ItemStack> bits = CandBMaterials.of(sculpture());
        ItemStack enough = bits.getFirst().copy();
        ItemSpec identity = ItemSpec.exact(enough);
        f.worker.pack().setItem(0, enough);
        f.worker.pack().setItem(1, new ItemStack(Items.STONE, 64));
        Node node = node(helper, at, blueprint(helper), Set.of());
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Failed, "missing bits refuse the commit");
        helper.assertTrue(level.getBlockState(at).isAir() && Goods.countIn(f.worker.pack(), identity)
            == bits.getFirst().getCount(), "no world change or partial material charge");
        f.worker.pack().setItem(1, bits.getLast().copy());
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Done, "the exact second material permits the shot");
        helper.assertTrue(f.worker.pack().isEmpty(), "exactly the two materials are spent");
        CandBTestData.sameVoxels(helper, sculpture(), ((ChiseledBlockEntity) level.getBlockEntity(at)).payload().storage());
        f.worker.body().discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 60)
    public static void sameStateChiseledCellsAreSkippedEvenWhenTheirVoxelsDiffer(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(7, 2, 7));
        level.setBlock(at, state(), Block.UPDATE_ALL);
        ChiseledBlockEntity entity = (ChiseledBlockEntity) level.getBlockEntity(at);
        StateEntryStorage other = sculpture();
        CandBStructureCodec.turn(other, Mirror.NONE, Rotation.CLOCKWISE_180);
        entity.loadWithComponents(CandBStructureCodec.encode(other), level.registryAccess());
        BuildTarget target = target(level, at, blueprint(helper));
        helper.assertTrue(target.satisfied(level, at) && BuildPlanner.complete(level, target), "the cannon's state-only skip rule");
        helper.assertTrue(BuildOrderView.owed(level, target, Set.of()).isEmpty(), "a skipped shape costs no bits");
        CandBTestData.sameVoxels(helper, other, entity.payload().storage());
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
        try {
            BuildPresence presence = new BuildPresence(colony, level.getServer());
            presence.settle(level.getServer());
            helper.assertTrue(presence.site(order.id()).isEmpty(), "a failed opening leaves no partially planned site");
            presence.settle(level.getServer());
            helper.assertTrue(presence.filed(order.id()) && level.getBlockState(at).isAir(),
                "the damaged order stays pending instead of completing on its next tick");
            var rows = presence.board(BuildContent.PAGE.id(), view).orElseThrow().rows();
            helper.assertValueEqual(rows.getLast().detail(), Component.translatable("folkways.refusal.build.micro_data_invalid"),
                "the player sees why the order is paused");
            helper.assertValueEqual(saved.get().getCompound("book"), book, "refusal preserves the saved blueprint and order");
        } finally {
            Cadence.forget(colony);
        }
        helper.succeed();
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
        int materials = CandBConfig.MATERIALS.get();
        try {
            CandBConfig.MATERIALS.set(1);
            refuses(helper, () -> CandBStructureCodec.decode(CandBStructureCodec.encode(sculpture())));
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
            .stream().mapToLong(Need::count).sum(), 7L, "legacy orders resume with the decoded voxel price");
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
    public static void wholeBlueprintTransformsKeepVoxelsAndTheirExactCosts(GameTestHelper helper) {
        Blueprint source = blueprint(helper);
        for (Rotation rotation : Rotation.values()) {
            for (Mirror mirror : Mirror.values()) {
                Blueprint transformed = source.transformed(rotation, mirror).orElseThrow().confirm(helper.getLevel());
                var expected = sculpture();
                CandBStructureCodec.turn(expected, mirror, rotation);
                CandBTestData.sameVoxels(helper, expected,
                    CandBStructureCodec.decode(transformed.blocks().getFirst().data().tag()));
                helper.assertValueEqual(CandBMaterials.of(expected).stream().mapToInt(ItemStack::getCount).sum(), 7,
                    "transformation preserves cost");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void thePlannerHaulsActualBitComponentsSeparately(GameTestHelper helper) {
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
                helper.assertValueEqual(who.supplied().stream().mapToInt(ItemStack::getCount).sum(), 7, "all seven bits supplied");
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
        return BuildNode.of(Step.lay(level, new Footprint(at, Map.of(at, state())))
            .job(level, target, Stances.at(level, at.east(2)), met), (node, how) -> { }, new TemporaryScaffolds(() -> { }))
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
