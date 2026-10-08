package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Extent;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import io.github.izakyl.folkways.plugins.build.draft.Piece;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftTemplateGameTests {

    private static final String TEMPLATE = "empty";
    private static final int MAX_CELLS = 4096;

    private DraftTemplateGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftTemplateGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void allThreeThingsACellCanSaySurviveTheFile(GameTestHelper helper) {
        BlockState wall = Blocks.COBBLESTONE.defaultBlockState();
        Draft draft = new Draft(Extent.of(3, 2, 1).orElseThrow(), List.of(
            new Draft.Cell(new BlockPos(0, 0, 0), wall),
            new Draft.Cell(new BlockPos(1, 0, 0), Blocks.AIR.defaultBlockState()),
            new Draft.Cell(new BlockPos(2, 1, 0), wall)));

        Blueprint read = through(helper, draft);

        helper.assertValueEqual(read.blocks().size(), 3, "every written cell comes back");
        helper.assertValueEqual(stateAt(read, 0, 0, 0), wall, "a cell holding a block");
        helper.assertValueEqual(stateAt(read, 1, 0, 0), Blocks.AIR.defaultBlockState(),
            "a cell that must end up empty");
        helper.assertTrue(stateAt(read, 2, 0, 0) == null,
            "a cell nothing covered is not in the drawing at all");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aStructureFileComesBackAsAPiece(GameTestHelper helper) {
        BlockState wall = Blocks.COBBLESTONE.defaultBlockState();
        BlockState wood = Blocks.OAK_PLANKS.defaultBlockState();
        Draft draft = new Draft(Extent.of(2, 1, 3).orElseThrow(), List.of(
            new Draft.Cell(new BlockPos(0, 0, 0), wall),
            new Draft.Cell(new BlockPos(0, 0, 1), Blocks.AIR.defaultBlockState()),
            new Draft.Cell(new BlockPos(1, 0, 2), wood)));

        Piece piece = DraftTemplate.pieceOf(DraftTemplate.of(draft,
            SharedConstants.getCurrentVersion().getDataVersion().getVersion()));

        helper.assertValueEqual(piece.size(), new Vec3i(2, 1, 3), "as big as the file says");
        helper.assertValueEqual(piece.cells().size(), 2, "air is left alone rather than laid");
        helper.assertValueEqual(piece.cells().get(new BlockPos(0, 0, 2)), wall,
            "the row nearest the file's north is the piece's front row");
        helper.assertValueEqual(piece.cells().get(new BlockPos(1, 0, 0)), wood, "and the far row its back");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void theDrawingsOwnBoxSurvivesTheFile(GameTestHelper helper) {
        Draft draft = new Draft(Extent.of(5, 4, 3).orElseThrow(), List.of(
            new Draft.Cell(new BlockPos(0, 0, 0), Blocks.COBBLESTONE.defaultBlockState())));

        helper.assertValueEqual(through(helper, draft).volume().size(),
            new Vec3i(5, 4, 3), "the declared box");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aDrawingThatOnlyClearsSurvivesTheFile(GameTestHelper helper) {
        Draft draft = new Draft(Extent.of(2, 1, 1).orElseThrow(), List.of(
            new Draft.Cell(new BlockPos(0, 0, 0), Blocks.AIR.defaultBlockState()),
            new Draft.Cell(new BlockPos(1, 0, 0), Blocks.AIR.defaultBlockState())));

        Blueprint read = through(helper, draft);
        helper.assertValueEqual(read.blocks().size(), 2, "both cleared cells come back");
        helper.assertTrue(read.blocks().stream().allMatch(cell -> cell.state().isAir()),
            "and both still say the world must end up empty here");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void jointsSurviveTheFileTheBookAndATurn(GameTestHelper helper) {
        BlockState end = Blocks.COBBLESTONE.defaultBlockState();
        BlockPos from = new BlockPos(0, 0, 0);
        BlockPos to = new BlockPos(3, 1, 2);
        Draft draft = new Draft(Extent.of(4, 2, 3).orElseThrow(), List.of(
            new Draft.Cell(from, end), new Draft.Cell(to, end)), List.of(new Joint(from, to)));

        Blueprint read = through(helper, draft);
        helper.assertValueEqual(read.joints(), List.of(new Joint(from, to)), "the joint is read back");
        Blueprint loaded = Blueprint.load(Reader.of(read.save(helper.getLevel().registryAccess())),
            helper.getLevel().registryAccess()).orElseThrow();
        helper.assertValueEqual(loaded.joints(), read.joints(), "and kept in the book");

        Blueprint turned = read.transformed(Rotation.CLOCKWISE_90, Mirror.NONE).orElseThrow();
        Joint joint = turned.joints().getFirst();
        for (BlockPos cell : List.of(joint.from(), joint.to())) {
            helper.assertTrue(turned.blocks().stream().anyMatch(block -> block.offset().equals(cell)),
                "a turned joint still ends on a turned block at " + cell);
        }
        CompoundTag stray = DraftTemplate.of(new Draft(Extent.of(4, 2, 3).orElseThrow(), List.of(
            new Draft.Cell(from, end)), List.of(new Joint(from, to))),
            SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        helper.assertTrue(StructureNbt.toBlueprint(stray, helper.getLevel().registryAccess(), UUID.randomUUID(),
            "drawn", 0L, StructureNbt.AirMeaning.EXCAVATE, MAX_CELLS).isEmpty(),
            "a joint to a cell the drawing does not hold is refused");
        helper.succeed();
    }

    private static Blueprint through(GameTestHelper helper, Draft draft) {
        CompoundTag file = DraftTemplate.of(draft,
            SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        return StructureNbt.toBlueprint(file, helper.getLevel().registryAccess(), UUID.randomUUID(),
                "drawn", 0L, StructureNbt.AirMeaning.EXCAVATE, MAX_CELLS)
            .orElseThrow(() -> new AssertionError("the file did not read back as a drawing"));
    }

    private static BlockState stateAt(Blueprint drawing, int x, int y, int z) {
        BlockPos offset = new BlockPos(x, y, z);
        return drawing.blocks().stream()
            .filter(cell -> cell.offset().equals(offset))
            .map(BlueprintBlock::state)
            .findFirst()
            .orElse(null);
    }
}
