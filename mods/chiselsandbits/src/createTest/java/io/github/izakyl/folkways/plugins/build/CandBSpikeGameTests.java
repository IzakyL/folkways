package io.github.izakyl.folkways.plugins.build;

import static io.github.izakyl.folkways.plugins.build.CandBTestData.sculpture;
import static io.github.izakyl.folkways.plugins.build.CandBTestData.state;
import static io.github.izakyl.folkways.plugins.build.CandBTestData.structure;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.contraptions.StructureTransform;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
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
        if (net.neoforged.fml.ModList.get().isLoaded("create")) event.register(CandBSpikeGameTests.class);
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
    public static void voxelAndRegistryTransformsMoveTheActualVoxels(GameTestHelper helper) {
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
        var bridge = new CandBSchematicBridge();
        for (Direction.Axis axis : Direction.Axis.values()) {
            for (Rotation rotation : Rotation.values()) {
                for (Mirror mirror : Mirror.values()) {
                    var entity = bridge.detached(helper.getLevel().registryAccess(), BlockPos.ZERO, state(),
                        new StructurePayload(CandBStructureCodec.encode(storage)));
                    var transform = new StructureTransform(BlockPos.ZERO, axis, rotation, mirror);
                    transform.apply(entity);
                    var transformed = CandBSchematicBridge.checked(entity).payload().storage();
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                Vec3 moved = transform.applyWithoutOffset(new Vec3((x + 0.5) / 16,
                                    (y + 0.5) / 16, (z + 0.5) / 16)).scale(16);
                                BlockPos at = BlockPos.containing(moved);
                                helper.assertValueEqual(transformed.getBlockInformation(at.getX(), at.getY(), at.getZ()),
                                    storage.getBlockInformation(x, y, z), axis + "/" + rotation + "/" + mirror + " registry voxel");
                            }
                        }
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void realCannonAndResidentConsumeAndPrintTheSameBits(GameTestHelper helper) throws Exception {
        printPair(helper, Rotation.NONE, Mirror.NONE);
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void rotatedMirroredCannonAndResidentPrintTheSameVoxels(GameTestHelper helper) throws Exception {
        printPair(helper, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK);
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void aHundredBitsFromOneChestMatchTheResidentCharge(GameTestHelper helper) throws Exception {
        printPair(helper, Rotation.NONE, Mirror.NONE, hundredBits(), 1);
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void aHundredBitsAcrossTwoChestsMatchTheResidentCharge(GameTestHelper helper) throws Exception {
        printPair(helper, Rotation.NONE, Mirror.NONE, hundredBits(), 2);
    }

    private static mod.chiselsandbits.api.block.storage.StateEntryStorage hundredBits() {
        var storage = new mod.chiselsandbits.api.block.storage.StateEntryStorage();
        for (int bit = 0; bit < 100; bit++) storage.setBlockInformation(bit % 16, bit / 16, 0,
            new mod.chiselsandbits.api.blockinformation.BlockInformation(Blocks.STONE.defaultBlockState(), java.util.Optional.empty()));
        return storage;
    }

    private static void printPair(GameTestHelper helper, Rotation rotation, Mirror mirror) throws Exception {
        printPair(helper, rotation, mirror, sculpture(), 1);
    }

    private static void printPair(GameTestHelper helper, Rotation rotation, Mirror mirror,
            mod.chiselsandbits.api.block.storage.StateEntryStorage storage, int containers) throws Exception {
        var level = helper.getLevel();
        var turned = storage.createSnapshot();
        CandBStructureCodec.turn(turned, mirror, rotation);
        CompoundTag data = CandBStructureCodec.encode(storage);
        Blueprint source = new Blueprint(UUID.randomUUID(), "chiseled spike", List.of(
            new BlueprintBlock(BlockPos.ZERO, state(), BlockData.of(data))), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0);
        Blueprint resolved = source.transformed(rotation, mirror).orElseThrow().confirm(level);
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
        List<ItemStack> supplied = new ArrayList<>();
        for (ItemStack bit : bits) {
            int count = bit.getCount();
            while (count > 0) {
                supplied.add(bit.copyWithCount(Math.min(bit.getMaxStackSize(), count)));
                count -= supplied.getLast().getCount();
            }
        }
        int remainder = bits.size() == 1 && bits.getFirst().getCount() == 100 ? 28 : 0;
        if (remainder != 0) supplied.getLast().grow(remainder);
        for (int i = 0; i < supplied.size(); i++) fixture.worker.pack().setItem(i, supplied.get(i).copy());
        BuildTarget target = new BuildTarget(WorldPos.of(level, residentPos).realm(), Map.of(residentPos, state()),
            Set.of(), List.of(), BlockPos.ZERO, Map.of(residentPos, resolved.blocks().getFirst().data()));
        Node node = BuildNode.of(Step.lay(level, new Footprint(residentPos, Map.of(residentPos, state())))
            .job(level, target, Stances.at(level, residentPos.east(2))), (done, how) -> { }, new TemporaryScaffolds(() -> { }));
        helper.assertTrue(Commit.run(level, node, fixture.worker) instanceof Outcome.Done, "resident commits its normal build node");
        int residentRemaining = java.util.stream.IntStream.range(0, fixture.worker.pack().getContainerSize())
            .map(slot -> fixture.worker.pack().getItem(slot).getCount()).sum();
        helper.assertValueEqual(residentRemaining, remainder, "resident consumes the decoded total and leaves excess bits");
        fixture.worker.body().discard();

        String file = "folkways-spike-" + UUID.randomUUID() + ".nbt";
        Path directory = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve("folkways-tests");
        Files.createDirectories(directory);
        Path path = directory.resolve(file);
        CandBTestData.sameVoxels(helper, storage, CandBStructureCodec.decode(data), "raw source before save");
        NbtIo.writeCompressed(structure(data), path);
        ItemStack schematic = SchematicItem.create(level, file, "folkways-tests");
        schematic.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        schematic.set(AllDataComponents.SCHEMATIC_ANCHOR, printedPos);
        schematic.set(AllDataComponents.SCHEMATIC_ROTATION, rotation);
        schematic.set(AllDataComponents.SCHEMATIC_MIRROR, mirror);
        level.setBlock(cannonPos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.parse("create:schematicannon")).defaultBlockState(), Block.UPDATE_ALL);
        var cannon = (SchematicannonBlockEntity) level.getBlockEntity(cannonPos);
        cannon.inventory.setStackInSlot(0, schematic);
        helper.assertValueEqual(SchematicItem.getSettings(schematic).getRotation(), rotation, "schematic rotation setting");
        helper.assertValueEqual(SchematicItem.getSettings(schematic).getMirror(), mirror, "schematic mirror setting");
        cannon.printer.loadSchematic(schematic, level, true);
        cannon.printer.advanceCurrentPos();
        cannon.printer.handleCurrentTarget((pos, targetState, entity) -> CandBTestData.sameVoxels(helper, turned,
            ((ChiseledBlockEntity) entity).payload().storage(), "printer before launch"), (pos, entity) -> helper.fail("unexpected entity"));
        cannon.printer.loadSchematic(schematic, level, true);
        cannon.blocksToPlace = 1;
        cannon.remainingFuel = 10;
        cannon.state = SchematicannonBlockEntity.State.RUNNING;
        level.setBlock(cannonPos.west(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        var stock = (net.minecraft.world.Container) level.getBlockEntity(cannonPos.west());
        List<net.minecraft.world.Container> stocks = new ArrayList<>(List.of(stock));
        if (containers == 2) {
            level.setBlock(cannonPos.east(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            stocks.add((net.minecraft.world.Container) level.getBlockEntity(cannonPos.east()));
        }
        for (int i = 0; i < supplied.size(); i++) stocks.get(i % containers).setItem(i / containers, supplied.get(i).copy());
        cannon.findInventories();
        cannon.neighbourCheckCooldown = 1000;
        helper.succeedWhen(() -> {
            cannon.saveWithFullMetadata(level.registryAccess());
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
            CandBTestData.sameVoxels(helper, turned, printed.payload().storage(), "final cannon print");
            helper.assertValueEqual(printed.createSnapshot().createNewShapeIdentifier(),
                resident.createSnapshot().createNewShapeIdentifier(), "same voxel layout after real placement");
            int cannonRemaining = stocks.stream().mapToInt(container -> java.util.stream.IntStream
                .range(0, container.getContainerSize()).map(slot -> container.getItem(slot).getCount()).sum()).sum();
            helper.assertValueEqual(cannonRemaining, residentRemaining, "cannon consumes exactly the same material bits");
            try { Files.deleteIfExists(path); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 40)
    public static void corruptDataStopsEvenARealCreativeCrateCannon(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos target = at.offset(4, 0, 4);
        String file = "folkways-broken-" + UUID.randomUUID() + ".nbt";
        Path directory = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve("folkways-tests");
        Files.createDirectories(directory);
        Path path = directory.resolve(file);
        try {
            NbtIo.writeCompressed(structure(new CompoundTag()), path);
            ItemStack schematic = SchematicItem.create(level, file, "folkways-tests");
            schematic.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
            schematic.set(AllDataComponents.SCHEMATIC_ANCHOR, target);
            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            level.setBlock(at, registry.get(net.minecraft.resources.ResourceLocation.parse("create:schematicannon"))
                .defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at.west(), registry.get(net.minecraft.resources.ResourceLocation.parse("create:creative_crate"))
                .defaultBlockState(), Block.UPDATE_ALL);
            var cannon = (SchematicannonBlockEntity) level.getBlockEntity(at);
            cannon.inventory.setStackInSlot(0, schematic);
            cannon.state = SchematicannonBlockEntity.State.RUNNING;
            cannon.tick();
            helper.assertTrue(cannon.state == SchematicannonBlockEntity.State.STOPPED
                && cannon.statusMsg.equals("schematicErrored"), "bad data refuses the actual cannon before any shot");
            helper.assertTrue(cannon.flyingBlocks.isEmpty() && level.getBlockState(target).isAir(), "no free creative print");
        } finally {
            Files.deleteIfExists(path);
        }
        helper.succeed();
    }

}
