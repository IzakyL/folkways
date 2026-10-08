package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftCityWallGameTests {

    private static final int GROUND = 64;

    private DraftCityWallGameTests() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftCityWallGameTests.class);
    }

    private static Pattern wall() {
        String path = "/data/folkways/" + Patterns.FOLDER + "/city_wall.star";
        try (InputStream in = DraftCityWallGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("city_wall.star is not bundled");
            }
            return DraftKit.parse("city_wall", new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.of());
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static DraftKit.Filled defaults(Pattern pattern) {
        return DraftKit.Filled.defaulting(pattern.knobs());
    }

    private static Drawn draw(Pattern pattern, DraftKit.Filled settings, BlockPos... points) {
        return pattern.drawOn(new Commission(new Hint.Path(List.of(points)), settings, new DraftKit.Ground(GROUND),
            Direction.NORTH, 0L));
    }

    private static BlockPos on(int x, int z) {
        return new BlockPos(x, GROUND + 1, z);
    }

    private static Set<String> roles(Draft draft) {
        return draft.cells().stream().map(cell -> cell.source().role()).collect(Collectors.toSet());
    }

    private static void distinct(GameTestHelper helper, Drawn.Ready ready) {
        Set<BlockPos> seen = new HashSet<>();
        for (Draft.Cell cell : ready.draft().cells()) {
            helper.assertTrue(seen.add(ready.corner().offset(cell.offset())), "each cell is laid once");
        }
    }

    private static boolean filled(Drawn.Ready ready, BlockPos at) {
        return ready.draft().cells().stream().anyMatch(cell -> ready.corner().offset(cell.offset()).equals(at)
            && !cell.state().isAir());
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void cityWallParsesWithReplaceableMaterials(GameTestHelper helper) {
        Pattern pattern = wall();
        helper.assertTrue(pattern.accepts().equals(Set.of(Hint.PATH)), "a city wall is marked as a path");
        Set<String> keys = pattern.knobs().settings().stream().map(Schema.Setting::key).collect(Collectors.toSet());
        helper.assertTrue(keys.containsAll(Set.of("height", "thickness", "spacing", "gate", "wall", "base", "walkway",
            "trim", "timber", "panel", "roof")), "every material and size is a knob: " + keys);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void straightCityWallRaisesAGatehouseOverAnOpenPassage(GameTestHelper helper) {
        Pattern pattern = wall();
        Drawn drawn = draw(pattern, defaults(pattern), on(0, 0), on(80, 0));
        DraftKit.ready(helper, drawn);
        Drawn.Ready ready = (Drawn.Ready) drawn;
        distinct(helper, ready);
        Set<String> roles = roles(ready.draft());
        helper.assertTrue(roles.containsAll(Set.of("wall", "plinth", "walk", "parapet", "battlement", "gate",
            "voussoir", "hall", "post", "roof", "ramp")), "gatehouse, towers and stairs are all drawn: " + roles);
        for (int y = GROUND + 1; y <= GROUND + 5; y++) {
            helper.assertFalse(filled(ready, new BlockPos(40, y, 0)), "the gate passage is open at y " + y);
        }
        helper.assertTrue(filled(ready, new BlockPos(40, GROUND + 9, 0)), "the gate carries its platform, above the curtain");
        helper.assertTrue(filled(ready, new BlockPos(14, GROUND + 7, 0)), "the curtain reaches walkway height");
        helper.assertTrue(ready.draft().cells().stream().anyMatch(cell -> cell.state().is(Blocks.DEEPSLATE_TILE_SLAB)),
            "the gate tower roof is laid in half slabs");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void corneredCityWallStandsATowerOnTheCorner(GameTestHelper helper) {
        Pattern pattern = wall();
        Drawn drawn = draw(pattern, defaults(pattern).count("spacing", 0), on(0, 0), on(40, 0), on(40, 40));
        Drawn.Ready ready = (Drawn.Ready) drawn;
        DraftKit.ready(helper, drawn);
        distinct(helper, ready);
        for (int x = 36; x <= 44; x++) {
            helper.assertTrue(filled(ready, new BlockPos(x, GROUND + 7, -4)), "the corner tower is square at x " + x);
        }
        helper.assertTrue(filled(ready, new BlockPos(40, GROUND + 7, 20)), "the second stretch is walled");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void cityWallWithoutGateStillDrawsItsTowers(GameTestHelper helper) {
        Pattern pattern = wall();
        DraftKit.Filled settings = defaults(pattern);
        settings.flag("gate", false);
        Drawn drawn = draw(pattern, settings, on(0, 0), on(30, 0));
        DraftKit.ready(helper, drawn);
        Set<String> roles = roles(((Drawn.Ready) drawn).draft());
        helper.assertFalse(roles.contains("gate"), "no gate is drawn when it is switched off");
        helper.assertTrue(roles.contains("battlement"), "the towers still stand");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void cityWallRefusesPathsItCannotBuild(GameTestHelper helper) {
        Pattern pattern = wall();
        DraftKit.Filled settings = defaults(pattern);
        helper.assertTrue(DraftKit.refused(helper, draw(pattern, settings, on(0, 0), on(8, 0))).detail()
            .contains("城墙"), "a stretch shorter than two towers is refused");
        helper.assertTrue(DraftKit.refused(helper, draw(pattern, settings, on(0, 0), on(20, 0))).detail()
            .contains("gatehouse"), "a wall too short for its gate is refused");
        helper.assertTrue(DraftKit.refused(helper, draw(pattern, settings, on(0, 0), on(40, 0), on(0, 5))).detail()
            .contains("sharply"), "a path doubling back is refused");
        helper.assertTrue(DraftKit.refused(helper, draw(pattern, settings, on(0, 0), on(130, 0))).detail()
            .contains("120"), "an overlong wall is refused");
        helper.assertValueEqual(DraftKit.refused(helper, pattern.drawOn(new Commission(DraftKit.zone(20, 4, 20),
            settings, new DraftKit.Ground(GROUND), Direction.NORTH, 0L))).why(), DraftRefusal.WRONG_MARK,
            "a zone is not a wall line");
        helper.assertTrue(DraftKit.refused(helper, pattern.drawOn(new Commission(new Hint.Path(List.of(on(0, 0),
            on(60, 0))), settings, World.NONE, Direction.NORTH, 0L))).detail().contains("ground"),
            "a wall with no ground under it is refused");
        helper.succeed();
    }
}
