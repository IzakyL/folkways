package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StructureNbtGameTests {

    private static final String TEMPLATE = "empty";
    private static final int MAX_CELLS = 512;

    private StructureNbtGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StructureNbtGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void flowingBlueprintCellsKeepTheirUnconstrainedMeaning(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var flowing = Blocks.WATER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL, 1);
        Blueprint resolved = new Blueprint(UUID.randomUUID(), "flow", java.util.List.of(
            new BlueprintBlock(BlockPos.ZERO, flowing)), BlueprintVolume.of(1, 1, 1).orElseThrow(), 0L).confirm(level);
        helper.assertTrue(resolved.blocks().isEmpty(), "resolution excludes flow cells from construction targets");
        BuildTarget target = new BuildTarget(io.github.izakyl.folkways.core.world.api.WorldPos.of(level, pos).realm(),
            java.util.Map.of());
        SiteRig rig = SiteRig.start(level, target);
        helper.assertTrue(rig.site.complete(), "an unconstrained flow cell needs no construction");
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
        rig.site.tick(level);
        helper.assertTrue(rig.site.complete() && rig.site.pieces().isEmpty(),
            "later fluid simulation cannot introduce work for an unconstrained cell");
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void whatAirMeansIsTheImporterSChoice(GameTestHelper helper) {
        CompoundTag file = fileOf(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());

        Blueprint asCreateWrote = StructureNbt.toBlueprint(file, helper.getLevel().registryAccess(),
            UUID.randomUUID(), "create", 0L, StructureNbt.AirMeaning.UNCONSTRAINED, MAX_CELLS).orElseThrow();
        helper.assertValueEqual(asCreateWrote.blocks().size(), 1,
            "air read as unconstrained should leave one cell");

        Blueprint asVanillaWrote = StructureNbt.toBlueprint(file, helper.getLevel().registryAccess(),
            UUID.randomUUID(), "vanilla", 0L, StructureNbt.AirMeaning.EXCAVATE, MAX_CELLS).orElseThrow();
        helper.assertValueEqual(asVanillaWrote.blocks().size(), 2,
            "air read as excavate should keep the air cell as a clear");
        helper.assertTrue(asVanillaWrote.blocks().stream().anyMatch(cell -> cell.state().isAir()),
            "the excavated cell must be the air-valued one");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void structureVoidIsNeverACell(GameTestHelper helper) {
        CompoundTag file = fileOf(Blocks.STONE.defaultBlockState(),
            Blocks.STRUCTURE_VOID.defaultBlockState());
        for (StructureNbt.AirMeaning meaning : StructureNbt.AirMeaning.values()) {
            Blueprint blueprint = StructureNbt.toBlueprint(file, helper.getLevel().registryAccess(),
                UUID.randomUUID(), "void", 0L, meaning, MAX_CELLS).orElseThrow();
            helper.assertValueEqual(blueprint.blocks().size(), 1,
                "cells kept under " + meaning);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void somethingThatIsNotAStructureIsRefused(GameTestHelper helper) {
        CompoundTag notAStructure = new CompoundTag();
        notAStructure.putString("hello", "world");
        Optional<Blueprint> read = StructureNbt.toBlueprint(notAStructure,
            helper.getLevel().registryAccess(), UUID.randomUUID(), "junk", 0L,
            StructureNbt.AirMeaning.UNCONSTRAINED, MAX_CELLS);
        helper.assertTrue(read.isEmpty(), "arbitrary NBT must not read as a blueprint");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aVolumeNobodyCouldWalkRefusesTheFile(GameTestHelper helper) {
        CompoundTag file = fileOf(Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        declareSize(file, 2000, 2000, 2000);
        helper.assertTrue(read(helper, file).isEmpty(),
            "a file may not declare a volume larger than a placement could walk");

        CompoundTag longAxis = fileOf(Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        declareSize(longAxis, BlueprintVolume.MAX_EDGE + 1, 1, 1);
        helper.assertTrue(read(helper, longAxis).isEmpty(),
            "one axis past the edge cap is refused even where the volume would fit");

        CompoundTag fits = fileOf(Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        declareSize(fits, 8, 4, 8);
        helper.assertTrue(read(helper, fits).orElseThrow().volume().size().equals(new Vec3i(8, 4, 8)),
            "a volume inside the caps is the blueprint's own");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aCellOutsideTheDeclaredVolumeRefusesTheFile(GameTestHelper helper) {
        CompoundTag file = fileOf(Blocks.STONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        declareSize(file, 1, 1, 1);
        helper.assertTrue(read(helper, file).isEmpty(),
            "the second cell sits at x=1, outside the 1x1x1 the file claims");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aBlockFromAnUnloadedModIsKeptAndNeverReadAsAir(GameTestHelper helper) {
        CompoundTag missing = new CompoundTag();
        missing.putString("Name", "absentmod:crystal");
        CompoundTag file = fileOf(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        file.getList("palette", 10).set(1, missing);

        Blueprint blueprint = StructureNbt.toBlueprint(file, helper.getLevel().registryAccess(), UUID.randomUUID(),
            "missing", 0L, StructureNbt.AirMeaning.EXCAVATE, MAX_CELLS).orElseThrow();
        helper.assertValueEqual(blueprint.blocks().size(), 1, "only the stone is a block");
        helper.assertTrue(blueprint.blocks().stream().noneMatch(cell -> cell.state().isAir()),
            "the missing block is not a cell to clear");
        helper.assertValueEqual(blueprint.unloaded().size(), 1, "it is kept aside");
        helper.assertValueEqual(blueprint.unloaded().getFirst().block(), "absentmod:crystal", "under its own name");

        Blueprint reloaded = Blueprint.load(Reader.of(blueprint.save(helper.getLevel().registryAccess())),
            helper.getLevel().registryAccess()).orElseThrow();
        helper.assertValueEqual(reloaded.unloaded().getFirst().state(), missing, "a reload keeps it as written");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void anUnloadedBlockComesBackTurnedOnceItsModIsThere(GameTestHelper helper) {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        Blueprint written = new Blueprint(UUID.randomUUID(), "turned",
            List.of(new BlueprintBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState())),
            List.of(new UnloadedBlock(new BlockPos(1, 0, 0), NbtUtils.writeBlockState(stairs), List.of())),
            BlueprintVolume.of(2, 1, 1).orElseThrow(), 0L);
        Blueprint turned = written.transformed(Rotation.CLOCKWISE_90, Mirror.NONE).orElseThrow();
        helper.assertValueEqual(turned.unloaded().getFirst().turns().size(), 1, "the turn is remembered");

        Blueprint loaded = Blueprint.load(Reader.of(turned.save(helper.getLevel().registryAccess())),
            helper.getLevel().registryAccess()).orElseThrow();
        helper.assertTrue(loaded.unloaded().isEmpty(), "a block that can be read is a block again");
        helper.assertTrue(loaded.blocks().stream().anyMatch(cell -> cell.state()
                .equals(stairs.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST))),
            "facing the way the blueprint was turned");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void blockEntityConfigurationSurvivesImportSaveAndTurning(GameTestHelper helper) {
        CompoundTag file = fileOf(Blocks.OAK_SIGN.defaultBlockState(), Blocks.STONE.defaultBlockState());
        CompoundTag configuration = new CompoundTag();
        configuration.putString("CustomName", "{\"text\":\"Workshop\"}");
        file.getList("blocks", 10).getCompound(0).put("nbt", configuration);
        Blueprint imported = read(helper, file).orElseThrow();
        Blueprint turned = imported.transformed(Rotation.CLOCKWISE_90, Mirror.FRONT_BACK).orElseThrow();
        Blueprint reloaded = Blueprint.load(Reader.of(turned.save(helper.getLevel().registryAccess())),
            helper.getLevel().registryAccess()).orElseThrow();
        BlueprintBlock sign = reloaded.blocks().stream().filter(cell -> cell.state().is(Blocks.OAK_SIGN))
            .findFirst().orElseThrow();
        helper.assertValueEqual(sign.data().tag(), configuration, "block entity data survives the complete pipeline");
        helper.assertValueEqual(sign.data().turns().size(), 1, "the configuration remembers its transformation");
        configuration.putString("CustomName", "changed source");
        helper.assertTrue(!sign.data().tag().equals(configuration), "blueprints own their configuration data");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void trustedConfigurationIsPlaced(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos destination = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlockAndUpdate(source.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(destination.below(), Blocks.STONE.defaultBlockState());
        BlockState state = Blocks.OAK_SIGN.defaultBlockState();
        level.setBlockAndUpdate(source, state);
        net.minecraft.world.level.block.entity.SignBlockEntity sign =
            (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(source);
        sign.setText(sign.getFrontText().setMessage(0, net.minecraft.network.chat.Component.literal("Workshop")), true);
        BlockData data = BlockData.of(sign.saveWithFullMetadata(level.registryAccess()));
        fire(level, destination, state, data);
        net.minecraft.world.level.block.entity.SignBlockEntity placed =
            (net.minecraft.world.level.block.entity.SignBlockEntity) level.getBlockEntity(destination);
        helper.assertValueEqual(placed.getFrontText().getMessage(0, false).getString(), "Workshop",
            "schematic placement restores sign text");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void containerContentsAreNotCreatedFromBlueprintNbt(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos destination = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockState state = Blocks.CHEST.defaultBlockState();
        level.setBlockAndUpdate(source, state);
        net.minecraft.world.level.block.entity.ChestBlockEntity chest =
            (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(source);
        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 32));
        BlockData data = BlockData.of(chest.saveWithFullMetadata(level.registryAccess()));
        fire(level, destination, state, data);
        net.minecraft.world.Container placed = (net.minecraft.world.Container) level.getBlockEntity(destination);
        helper.assertTrue(placed.isEmpty(), "schematic configuration must not copy inventory contents");
        helper.succeed();
    }

    private static void fire(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState state,
            BlockData data) {
        SchematicPlacement.adapter().fire(level, SchematicPlacement.Launch.of(pos, java.util.Set.of(pos)),
            cell -> state, cell -> data, net.minecraft.world.item.ItemStack.EMPTY);
    }

    private static Optional<Blueprint> read(GameTestHelper helper, CompoundTag file) {
        return StructureNbt.toBlueprint(file, helper.getLevel().registryAccess(),
            UUID.randomUUID(), "sized", 0L, StructureNbt.AirMeaning.UNCONSTRAINED, MAX_CELLS);
    }

    private static void declareSize(CompoundTag file, int x, int y, int z) {
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(x));
        size.add(IntTag.valueOf(y));
        size.add(IntTag.valueOf(z));
        file.put("size", size);
    }

    private static CompoundTag fileOf(BlockState first, BlockState second) {
        CompoundTag tag = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(2));
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        tag.put("size", size);

        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(first));
        palette.add(NbtUtils.writeBlockState(second));
        tag.put("palette", palette);

        ListTag blocks = new ListTag();
        blocks.add(cellAt(0, 0, 0, 0));
        blocks.add(cellAt(1, 0, 0, 1));
        tag.put("blocks", blocks);
        return tag;
    }

    private static CompoundTag cellAt(int x, int y, int z, int state) {
        CompoundTag cell = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        cell.put("pos", pos);
        cell.putInt("state", state);
        return cell;
    }
}
