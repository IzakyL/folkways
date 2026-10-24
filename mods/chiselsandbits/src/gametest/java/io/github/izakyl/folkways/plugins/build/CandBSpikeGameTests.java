package io.github.izakyl.folkways.plugins.build;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.utility.CreatePaths;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.chiselsandbits.CandBMaterials;
import io.github.izakyl.folkways.chiselsandbits.CandBSchematicBridge;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.chiselsandbits.FolkwaysChiselsAndBitsMod;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.registrars.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysChiselsAndBitsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CandBSpikeGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CandBSpikeGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void detachedDecodingAndStrictMaterialsAreSynchronous(GameTestHelper helper) {
        var storage = sculpture();
        var bridge = new CandBSchematicBridge();
        var entity = bridge.detached(helper.getLevel().registryAccess(), BlockPos.ZERO, state(),
            new StructurePayload(CandBStructureCodec.encode(storage)));
        helper.assertTrue(entity.getLevel() == null, "temporary entities never bind the real server level");
        var needs = bridge.materials(entity);
        helper.assertValueEqual(needs.stream().mapToLong(need -> need.count()).sum(), 7L, "each occupied voxel costs one bit");
        helper.assertValueEqual(needs.size(), 2, "two exact component identities");
        ItemRequirement requirement = ItemRequirement.of(state(), entity);
        helper.assertTrue(!requirement.isInvalid() && !requirement.isEmpty(), "real cannon has a valid cost");
        helper.assertTrue(requirement.getRequiredItems().stream()
            .allMatch(stack -> stack instanceof ItemRequirement.StrictNbtStackRequirement), "all bit requirements are strict");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void everyHorizontalTransformMovesTheActualVoxels(GameTestHelper helper) {
        var storage = sculpture();
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                var transformed = CandBStructureCodec.decode(CandBStructureCodec.encode(storage));
                CandBStructureCodec.turn(transformed, mirror, rotation);
                BlockPos near = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, BlockPos.ZERO);
                BlockPos far = StructureTemplate.transform(new BlockPos(15, 15, 15), mirror, rotation, BlockPos.ZERO);
                BlockPos corner = BlockPos.min(near, far);
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockPos moved = StructureTemplate.transform(new BlockPos(x, y, z), mirror, rotation,
                                BlockPos.ZERO).subtract(corner);
                            helper.assertValueEqual(transformed.getBlockInformation(moved.getX(), moved.getY(), moved.getZ()),
                                storage.getBlockInformation(x, y, z), mirror + "/" + rotation + " voxel");
                        }
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void realCannonAndResidentConsumeAndPrintTheSameBits(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var storage = sculpture();
        ChiseledBlockEntity expected = new ChiseledBlockEntity(BlockPos.ZERO, state());
        expected.receivePayload(new ChiseledBlockEntity.Payload(storage, expected.payload().mutableStatistics()));
        CompoundTag data = CandBStructureCodec.encode(storage);
        Blueprint source = new Blueprint(UUID.randomUUID(), "chiseled spike", List.of(
            new BlueprintBlock(BlockPos.ZERO, state(), BlockData.of(data))), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0);
        Blueprint resolved = source.confirm(level);
        helper.assertValueEqual(resolved.blocks().size(), 1, "temporary printer keeps chiseled cells");
        BlockPos residentPos = helper.absolutePos(new BlockPos(7, 2, 5));
        BlockPos cannonPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos printedPos = helper.absolutePos(new BlockPos(5, 2, 5));
        for (BlockPos pos : List.of(residentPos, printedPos)) {
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        var fixture = new PlanningFixture(helper, 4);
        fixture.worker.body().setPos(residentPos.getX() + 2.5, residentPos.getY(), residentPos.getZ() + 0.5);
        List<ItemStack> bits = CandBMaterials.of(storage);
        for (int i = 0; i < bits.size(); i++) fixture.worker.pack().setItem(i, bits.get(i).copy());
        BuildTarget target = new BuildTarget(WorldPos.of(level, residentPos).realm(), Map.of(residentPos, state()),
            Set.of(), List.of(), BlockPos.ZERO, Map.of(residentPos, resolved.blocks().getFirst().data()));
        Node node = BuildNode.of(Step.lay(level, new Footprint(residentPos, Map.of(residentPos, state())))
            .job(level, target, Stances.at(level, residentPos.east(2))), (done, how) -> { }, new TemporaryScaffolds(() -> { }));
        helper.assertTrue(Commit.run(level, node, fixture.worker) instanceof Outcome.Done, "resident commits its normal build node");
        helper.assertTrue(fixture.worker.pack().isEmpty(), "resident consumes exactly seven supplied bits");
        fixture.worker.body().discard();

        String file = "folkways-spike-" + UUID.randomUUID() + ".nbt";
        Path directory = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve("folkways-tests");
        Files.createDirectories(directory);
        Path path = directory.resolve(file);
        NbtIo.writeCompressed(structure(data), path);
        ItemStack schematic = SchematicItem.create(level, file, "folkways-tests");
        schematic.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        schematic.set(AllDataComponents.SCHEMATIC_ANCHOR, printedPos);
        level.setBlock(cannonPos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.parse("create:schematicannon")).defaultBlockState(), Block.UPDATE_ALL);
        var cannon = (SchematicannonBlockEntity) level.getBlockEntity(cannonPos);
        cannon.inventory.setStackInSlot(0, schematic);
        cannon.printer.loadSchematic(schematic, level, true);
        cannon.blocksToPlace = 1;
        cannon.remainingFuel = 10;
        cannon.state = SchematicannonBlockEntity.State.RUNNING;
        level.setBlock(cannonPos.west(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        var stock = (net.minecraft.world.Container) level.getBlockEntity(cannonPos.west());
        for (int i = 0; i < bits.size(); i++) stock.setItem(i, bits.get(i).copy());
        cannon.findInventories();
        cannon.neighbourCheckCooldown = 1000;
        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockEntity(printedPos) instanceof ChiseledBlockEntity,
                "real cannon projectile reaches its target: " + cannon.statusMsg + " missing=" + cannon.missingItem
                    + " inventories=" + cannon.attachedInventories.size() + " stock=" + stock.getItem(0));
            var printed = (ChiseledBlockEntity) level.getBlockEntity(printedPos);
            var resident = (ChiseledBlockEntity) level.getBlockEntity(residentPos);
            helper.assertTrue(printed.payload().storage().count().keySet().stream().anyMatch(material -> !material.isAir())
                && resident.payload().storage().count().keySet().stream().anyMatch(material -> !material.isAir()),
                "placed entities finish decoding");
            helper.assertValueEqual(CandBStructureCodec.counts(printed.payload().storage()),
                CandBStructureCodec.counts(storage), "cannon's decoded material counts");
            helper.assertValueEqual(printed.createSnapshot().createNewShapeIdentifier(),
                expected.createSnapshot().createNewShapeIdentifier(), "cannon preserves blueprint voxel coordinates");
            helper.assertValueEqual(printed.createSnapshot().createNewShapeIdentifier(),
                resident.createSnapshot().createNewShapeIdentifier(), "same voxel layout after real placement");
            helper.assertTrue(stock.getItem(0).isEmpty() && stock.getItem(1).isEmpty(),
                "cannon consumes exactly the same material bits");
            try { Files.deleteIfExists(path); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
    }

    static StateEntryStorage sculpture() {
        StateEntryStorage storage = new StateEntryStorage();
        for (int x = 0; x < 7; x++) {
            storage.setBlockInformation(x, 0, 0, new BlockInformation(
                (x < 3 ? Blocks.OAK_PLANKS : Blocks.STONE).defaultBlockState(), Optional.empty()));
        }
        return storage;
    }

    static BlockState state() {
        return ModBlocks.CHISELED_BLOCK.get().defaultBlockState();
    }

    static CompoundTag structure(CompoundTag data) {
        CompoundTag tag = new CompoundTag();
        tag.put("size", ints(1, 1, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(state()));
        tag.put("palette", palette);
        CompoundTag cell = new CompoundTag();
        cell.put("pos", ints(0, 0, 0));
        cell.putInt("state", 0);
        cell.put("nbt", data);
        ListTag blocks = new ListTag();
        blocks.add(cell);
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        return tag;
    }

    private static ListTag ints(int... values) {
        ListTag tag = new ListTag();
        for (int value : values) tag.add(IntTag.valueOf(value));
        return tag;
    }
}
