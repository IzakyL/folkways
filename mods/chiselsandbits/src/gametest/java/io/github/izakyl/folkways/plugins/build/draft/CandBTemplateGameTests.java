package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.chiselsandbits.FolkwaysChiselsAndBitsMod;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.plugins.build.CandBTestData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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
public final class CandBTemplateGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CandBTemplateGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void templateImportStampFitAndExportKeepVoxelCoordinates(GameTestHelper helper) {
        Piece piece = piece();
        Scope scope = Scope.over(new BoundingBox(0, 0, 0, 0, 0, 0));
        Draft draft = fitted(helper, piece, scope);
        helper.assertValueEqual(draft.cells().size(), 1, "one template cell");
        CandBTestData.sameVoxels(helper, CandBTestData.sculpture(),
            CandBStructureCodec.decode(draft.cells().getFirst().payload().tag()));
        CompoundTag exported = DraftTemplate.of(draft, 3955);
        var written = exported.getList("blocks", CompoundTag.TAG_COMPOUND).getCompound(0).getCompound("nbt");
        CandBTestData.sameVoxels(helper, CandBTestData.sculpture(), CandBStructureCodec.decode(written));
        CompoundTag absent = CandBTestData.structure(CandBStructureCodec.encode(CandBTestData.sculpture()));
        absent.getList("palette", CompoundTag.TAG_COMPOUND).getCompound(0).putString("Name", "absent:carving");
        boolean refused = false;
        try { DraftTemplate.pieceOf(absent); } catch (io.github.izakyl.folkways.core.api.structure.StructureCompat.Failure expected) {
            refused = true;
        }
        helper.assertTrue(refused, "an external configured template waits for its missing block mod");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void tiledAndStretchedTemplatesCopyIndependentPayloads(GameTestHelper helper) {
        Piece piece = piece();
        Piece stretched = new Piece(piece.cells(), piece.size(), List.of(Set.of(0), Set.of(), Set.of()), piece.payloads())
            .stretchedTo(new int[] {3, 1, 1});
        Piece tiled = piece.tiledTo(new int[] {2, 1, 2});
        for (Piece copied : List.of(stretched, tiled)) {
            Draft draft = fitted(helper, copied, Scope.over(new BoundingBox(0, 0, 0,
                copied.size().getX() - 1, copied.size().getY() - 1, copied.size().getZ() - 1)));
            helper.assertValueEqual(draft.cells().size(), copied.cells().size(), "every copied cell reaches the drawing");
            for (Draft.Cell cell : draft.cells()) {
                CandBTestData.sameVoxels(helper, CandBTestData.sculpture(), CandBStructureCodec.decode(cell.payload().tag()));
            }
            CompoundTag mutable = draft.cells().getFirst().payload().tag();
            mutable.remove("data");
            helper.assertTrue(draft.cells().stream().allMatch(cell -> cell.payload().tag().contains("data")),
                "one returned tag cannot mutate any copy of a piece");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void templatePayloadsFollowAllHorizontalFramesAndMirrors(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (boolean mirrored : List.of(false, true)) {
                Frame frame = Frame.over(new BoundingBox(0, 0, 0, 0, 0, 0), facing, mirrored);
                Scope scope = new Scope(frame, new Vec3i(1, 1, 1), "", "", mirrored);
                Draft draft = fitted(helper, piece(), scope);
                var expected = CandBTestData.sculpture();
                Rotation rotation = switch (facing) {
                    case EAST -> Rotation.CLOCKWISE_90;
                    case SOUTH -> Rotation.CLOCKWISE_180;
                    case WEST -> Rotation.COUNTERCLOCKWISE_90;
                    default -> Rotation.NONE;
                };
                CandBStructureCodec.turn(expected, mirrored ? Mirror.FRONT_BACK : Mirror.NONE, rotation);
                CandBTestData.sameVoxels(helper, expected, CandBStructureCodec.decode(draft.cells().getFirst().payload().tag()));
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void actualTemplateVocabularyPreservesPayloadsThroughFit(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("local", "test_chiseled_template");
        Templates.adoptOwn(Map.of(id, CandBTestData.structure(CandBStructureCodec.encode(CandBTestData.sculpture()))));
        try {
            Pattern pattern = Pattern.parse(ResourceLocation.fromNamespaceAndPath("folkways", "test_chiseled_stamp"), """
                def draw(site):
                    return [stamp("carving", template("local:test_chiseled_template", stretch = {"x": [0]}), site.zone, fit = "stretch")]
                """).orElseThrow();
            Drawn drawn = pattern.drawOn(new Commission(new Hint.Zone(BlockPos.ZERO, new BlockPos(2, 0, 0)),
                Chosen.load(pattern.knobs(), new CompoundTag()), World.NONE, Direction.NORTH, 0));
            helper.assertTrue(drawn instanceof Drawn.Ready, "the Starlark template/stamp vocabulary accepts the payload: " + drawn);
            Draft draft = ((Drawn.Ready) drawn).draft();
            helper.assertValueEqual(draft.cells().size(), 3, "the template stretches to all three cells");
            for (Draft.Cell cell : draft.cells()) {
                CandBTestData.sameVoxels(helper, CandBTestData.sculpture(), CandBStructureCodec.decode(cell.payload().tag()));
            }
        } finally {
            Templates.adoptOwn(Map.of());
        }
        helper.succeed();
    }

    private static Piece piece() {
        return DraftTemplate.pieceOf(CandBTestData.structure(CandBStructureCodec.encode(CandBTestData.sculpture())));
    }

    private static Draft fitted(GameTestHelper helper, Piece piece, Scope scope) {
        Map<BlockPos, StructurePayload> payloads = piece.payloadsInto(scope, new int[3]);
        var cells = piece.laidInto(scope, new int[3]);
        var part = new Massing.Part("carving", new Solid.Cells(cells.keySet()), new Skin.Stamped(cells, List.of(), payloads));
        var lattice = Lattice.of(new Massing(List.of(part))).orElseThrow();
        Drawn drawn = Fitter.fit(lattice, BlockPos.ZERO, 0);
        helper.assertTrue(drawn instanceof Drawn.Ready, "the payload is fitted");
        return ((Drawn.Ready) drawn).draft();
    }
}
