package io.github.izakyl.folkways.create.build;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.foundation.utility.CreatePaths;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.build.Blueprint;
import io.github.izakyl.folkways.plugins.build.BlueprintBlock;
import io.github.izakyl.folkways.plugins.build.BlueprintBuildOrder;
import io.github.izakyl.folkways.plugins.build.StructureNbt;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Extent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
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

/** Compares the confirmed build order's world positions with Create's deployed schematic printer. */
@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateSchematicPlacementGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CreateSchematicPlacementGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deployedSchematicSiteMatchesCreateForEveryRotationAndMirror(GameTestHelper helper)
            throws IOException {
        compare(helper, new Vec3i(5, 3, 4));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deployedSchematicWithDifferentParityKeepsItsPosition(GameTestHelper helper)
            throws IOException {
        compare(helper, new Vec3i(4, 2, 7));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deployedSingleBlockWideSchematicKeepsItsPosition(GameTestHelper helper)
            throws IOException {
        compare(helper, new Vec3i(1, 2, 4));
        helper.succeed();
    }

    private static void compare(GameTestHelper helper, Vec3i size) throws IOException {
        var level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(10, 1, 10));
        // Empty margins belong to the template volume, even though the printer contains only these blocks.
        int x = size.getX() == 1 ? 0 : 1;
        CompoundTag file = DraftTemplate.of(new Draft(new Extent(size), List.of(
            new Draft.Cell(new BlockPos(x, 0, 1), Blocks.STONE.defaultBlockState()),
            new Draft.Cell(new BlockPos(x, 1, 1), Blocks.FURNACE.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)),
            new Draft.Cell(new BlockPos(x, 0, size.getZ() - 2), Blocks.OAK_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X)))),
            SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        String owner = "folkways-gametest-" + UUID.randomUUID();
        Path directory = CreatePaths.UPLOADED_SCHEMATICS_DIR.resolve(owner);
        Path path = directory.resolve("placement.nbt");
        Files.createDirectories(directory);
        try {
            NbtIo.writeCompressed(file, path);
            for (Rotation rotation : Rotation.values()) {
                for (Mirror mirror : Mirror.values()) {
                    String label = size + " / " + rotation + " / " + mirror;
                    ItemStack schematic = new ItemStack(BuiltInRegistries.ITEM.get(
                        ResourceLocation.fromNamespaceAndPath("create", "schematic")));
                    schematic.set(AllDataComponents.SCHEMATIC_OWNER, owner);
                    schematic.set(AllDataComponents.SCHEMATIC_FILE, path.getFileName().toString());
                    schematic.set(AllDataComponents.SCHEMATIC_BOUNDS, size);
                    schematic.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
                    schematic.set(AllDataComponents.SCHEMATIC_ANCHOR, anchor);
                    schematic.set(AllDataComponents.SCHEMATIC_ROTATION, rotation);
                    schematic.set(AllDataComponents.SCHEMATIC_MIRROR, mirror);
                    SchematicPrinter printer = new SchematicPrinter();
                    printer.loadSchematic(schematic, level, true);
                    helper.assertTrue(printer.isLoaded() && !printer.isErrored(), label + ": Create loads the fixture");
                    Map<BlockPos, BlockState> expected = new LinkedHashMap<>();
                    while (printer.advanceCurrentPos()) {
                        printer.handleCurrentTarget((pos, state, entity) -> {
                            if (!state.isAir()) {
                                expected.put(pos.immutable(), state);
                            }
                        }, (pos, entity) -> { });
                    }
                    helper.assertValueEqual(expected.size(), 3, label + ": Create prints every fixture block");

                    BlockPos corner = CreateSchematicPlacement.corner(anchor, size, SchematicItem.getSettings(schematic));
                    Blueprint source = StructureNbt.toBlueprint(file, level.registryAccess(), UUID.randomUUID(),
                        "placement", level.getGameTime(), StructureNbt.AirMeaning.UNCONSTRAINED, 100).orElseThrow();
                    Blueprint confirmed = source.transformed(rotation, mirror).orElseThrow().confirm(level);
                    BlueprintBuildOrder order = BlueprintBuildOrder.create(confirmed.id(), "placement",
                        WorldPos.of(level, corner), Optional.empty(), "", level.getGameTime());
                    // BuildTarget.of uses precisely these world positions for the site's cells.
                    Map<BlockPos, BlockState> target = new LinkedHashMap<>();
                    for (BlueprintBlock block : confirmed.blocks()) {
                        target.put(block.worldPos(order.anchor().cell()), block.state());
                    }
                    helper.assertValueEqual(target, expected, label + ": the site's block positions and states equal Create's");
                }
            }
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }
}
