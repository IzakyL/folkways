package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Vec3i;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftFittingGameTests {
    private DraftFittingGameTests() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftFittingGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void steppedFittingAddsFamilySlabsWithoutChangingSolidFitting(GameTestHelper helper) {
        var material = Materials.inOrder(List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("stone_bricks"))));
        var stepped = Palette.resolve(material, true).orElseThrow();
        var solid = Palette.resolve(material).orElseThrow();
        var bottom = Blocks.STONE_BRICK_SLAB.defaultBlockState();
        var top = bottom.setValue(SlabBlock.TYPE, SlabType.TOP);
        helper.assertValueEqual(stepped.nearest(Palette.maskOf(bottom)), bottom, "automatic bottom slab");
        helper.assertValueEqual(stepped.nearest(Palette.maskOf(top)), top, "automatic top slab");
        helper.assertValueEqual(stepped.nearest(Octants.FULL), Blocks.STONE_BRICKS.defaultBlockState(), "full brick");
        helper.assertValueEqual(solid.nearest(Palette.maskOf(bottom)), Blocks.STONE_BRICKS.defaultBlockState(),
            "solid mode retains its selected block");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void continuousArchProducesHalfSlabsWithOnlyAFullBlockSelected(GameTestHelper helper) {
        var pattern = DraftKit.parse("""
            accepts = ["path"]
            def draw(site):
                band = site.path.stretches[0].sized(width = 5, anchor = "center")
                return [part("deck", arched(band, band, 4), ["minecraft:smooth_stone"], fit = "stepped")]
            """);
        var draft = DraftKit.ready(helper, DraftKit.draw(pattern,
            new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(0, 0, 24)))));
        helper.assertTrue(draft.cells().stream().anyMatch(cell -> cell.state().is(Blocks.SMOOTH_STONE_SLAB)
            && cell.state().getValue(SlabBlock.TYPE) != SlabType.DOUBLE), "curved deck contains actual half slabs");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void connectedFittingBridgesDiagonalsAndHeightsInsideSupport(GameTestHelper helper) {
        var a = new BlockPos(0, 0, 0);
        var bend = new BlockPos(1, 0, 0);
        var b = new BlockPos(1, 0, 1);
        var strip = new Solid.Cells(Set.of(a.above(), b.above(2)));
        var cells = Set.copyOf(ConnectedFit.cells(strip, Map.of(a, 0, bend, 0, b, 1)).orElseThrow());
        helper.assertValueEqual(cells, Set.of(a.above(), bend.above(), bend.above(2), b.above(2)),
            "a supported elbow and taller lower column connect all cells by faces");
        helper.assertTrue(ConnectedFit.cells(strip, Map.of(a, 0, b, 1)).isEmpty(),
            "an impossible connection is refused instead of overhanging support");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void grazingVerticalEdgesDoNotBecomeHorizontalSlivers(GameTestHelper helper) {
        var palette = Palette.resolve(Materials.inOrder(List.of(
            ItemFilter.item(ResourceLocation.withDefaultNamespace("sandstone")))), true).orElseThrow();
        helper.assertTrue(palette.stepped(0x11, null, 0L).isEmpty(), "omit the grazing corner");
        helper.assertValueEqual(palette.stepped(0x0F, null, 0L).orElseThrow(),
            Blocks.SANDSTONE_SLAB.defaultBlockState(), "keep a real horizontal half slab");
        helper.assertValueEqual(palette.stepped(0x33, null, 0L).orElseThrow(),
            Blocks.SANDSTONE.defaultBlockState(), "a half-width vertical side stays vertically solid");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void adjacentCurvedMaterialsKeepBothHalvesOfTheirSharedVoxel(GameTestHelper helper) {
        var lower = new Solid.Lifted(new Solid.Cuboid(new BoundingBox(0, -1, 0, 0, -1, 0)),
            0, 0, 1, new double[] {0.5}, false);
        var upper = new Solid.Lifted(new Solid.Cuboid(new BoundingBox(0, 0, 0, 0, 0, 0)),
            0, 0, 1, new double[] {0.5}, false);
        var draft = DraftKit.laid(helper, List.of(
            new Massing.Part("arch", lower, new Skin.Fitted(List.of(
                ItemFilter.item(ResourceLocation.withDefaultNamespace("sandstone"))), Fit.STEPPED)),
            new Massing.Part("deck", upper, new Skin.Fitted(List.of(
                ItemFilter.item(ResourceLocation.withDefaultNamespace("smooth_sandstone"))), Fit.STEPPED))));
        helper.assertValueEqual(DraftKit.stateAt(draft, 0, 1, 0), Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
            "material boundary is solid, without losing the arch's lower half");
        helper.assertValueEqual(DraftKit.stateAt(draft, 0, 0, 0),
            Blocks.SANDSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), "curved underside");
        helper.assertValueEqual(DraftKit.stateAt(draft, 0, 2, 0), Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState(),
            "curved walking surface");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void railingFeetFillOnlyTheSupportingHalfSlabs(GameTestHelper helper) {
        var floor = new Solid.Lifted(new Solid.Cuboid(new BoundingBox(0, 0, 0, 2, 0, 3)),
            0, 0, 3, new double[] {0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5}, false);
        var draft = DraftKit.laid(helper, List.of(
            new Massing.Part("deck", floor, new Skin.Fitted(List.of(
                ItemFilter.item(ResourceLocation.withDefaultNamespace("smooth_sandstone"))), Fit.LAYERED)),
            new Massing.Part("rail", new Solid.Cuboid(new BoundingBox(0, 2, 0, 0, 2, 3)),
                new Skin.Fitted(Materials.inOrder(List.of(
                    ItemFilter.item(ResourceLocation.withDefaultNamespace("sandstone_wall")))),
                    Fit.CONNECTED, null, "deck"))));
        helper.assertValueEqual(DraftKit.stateAt(draft, 0, 1, 1).getValue(SlabBlock.TYPE), SlabType.DOUBLE,
            "no half-block air gap under the railing");
        helper.assertValueEqual(DraftKit.stateAt(draft, 1, 1, 1).getValue(SlabBlock.TYPE), SlabType.BOTTOM,
            "walking surface retains its half-block step");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void connectedPanelsKeepTheirSpecifiedLevelAboveSlopingSupport(GameTestHelper helper) {
        var a = BlockPos.ZERO;
        var b = new BlockPos(1, 0, 0);
        var c = new BlockPos(2, 0, 0);
        var strip = new Solid.Cuboid(new BoundingBox(0, 3, 0, 2, 3, 0));
        var cells = Set.copyOf(ConnectedFit.cells(strip, Map.of(a, 0, b, 1, c, 0)).orElseThrow());
        helper.assertValueEqual(cells, Set.of(a.above(), a.above(2), a.above(3), b.above(2), b.above(3),
            c.above(), c.above(2), c.above(3)), "level panel with supported, varying-height bottom");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void postsReplacePanelsAndStandOnLabelledEdging(GameTestHelper helper) {
        var bricks = Materials.inOrder(List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("stone_bricks"))));
        var walls = Materials.inOrder(List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("stone_brick_wall"))));
        var draft = DraftKit.laid(helper, List.of(
            new Massing.Part("edge", Set.of("deck"), new Solid.Cuboid(new BoundingBox(0, 0, 0, 4, 0, 0)),
                new Skin.Fitted(bricks, Fit.LAYERED, null), Massing.Over.ALL, "edge"),
            new Massing.Part("panel", new Solid.Cuboid(new BoundingBox(0, 2, 0, 4, 2, 0)),
                new Skin.Fitted(walls, Fit.CONNECTED, null, "deck")),
            new Massing.Part("post", Set.of(), new Solid.Cuboid(new BoundingBox(2, 3, 0, 2, 3, 0)),
                new Skin.Fitted(bricks, Fit.CONNECTED, null, "deck"),
                new Massing.Over(Massing.Over.Kind.LABELS, Set.of("panel")), "post")));
        helper.assertValueEqual(DraftKit.stateAt(draft, 2, 1, 0), Blocks.STONE_BRICKS.defaultBlockState(),
            "post replaces lower panel cell");
        helper.assertValueEqual(DraftKit.stateAt(draft, 2, 3, 0), Blocks.STONE_BRICKS.defaultBlockState(),
            "post reaches its designed top");
        helper.assertValueEqual(DraftKit.stateAt(draft, 1, 2, 0), Blocks.STONE_BRICK_WALL.defaultBlockState(),
            "adjacent panel retains its material and height");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void diagonalPostsSnapToAnAvailableSupportingColumn(GameTestHelper helper) {
        double diagonal = Math.sqrt(0.5D);
        var frame = new Frame(new Vec3(1 - diagonal, 2, 1), new Vec3(diagonal, 0, -diagonal),
            new Vec3(0, 1, 0), new Vec3(diagonal, 0, diagonal));
        var post = new Solid.Oriented(frame, new Vec3i(1, 1, 1));
        var a = BlockPos.ZERO;
        var b = new BlockPos(1, 0, 0);
        var support = Map.of(a, 0, b, 0);
        var cells = ConnectedFit.cells(post, support).orElseThrow();
        helper.assertValueEqual(cells.size(), 2, "one supported post rising to its requested height");
        helper.assertTrue(cells.contains(a.above(2)) || cells.contains(b.above(2)), "post top survives diagonal sampling");
        helper.assertTrue(cells.stream().allMatch(pos -> support.containsKey(new BlockPos(pos.getX(), 0, pos.getZ()))),
            "snapping stays on its specified support");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void connectedFitRequiresAnExplicitSupport(GameTestHelper helper) {
        var pattern = DraftKit.parse("""
            def draw(site):
                return [part("rail", site.zone, ["minecraft:stone_brick_wall"], fit = "connected")]
            """);
        var refused = DraftKit.refused(helper, DraftKit.draw(pattern, DraftKit.zone(3, 1, 3)));
        helper.assertTrue(refused.detail().contains("support"), "missing support is explained");
        helper.succeed();
    }
}
