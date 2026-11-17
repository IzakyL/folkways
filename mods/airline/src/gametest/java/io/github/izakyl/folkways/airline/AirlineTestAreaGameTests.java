package io.github.izakyl.folkways.airline;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysAirlineMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_airline")
@PrefixGameTestTemplate(false)
public final class AirlineTestAreaGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AirlineTestAreaGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel")
    public static void failedAreaReleasesOnlyItsOwnResources(GameTestHelper helper) {
        var own = AirlineTestArea.of(helper);
        var failedInfo = new GameTestInfo(helper.testInfo.getTestFunction(), helper.testInfo.getRotation(),
            helper.getLevel(), helper.testInfo.retryOptions());
        var failed = AirlineTestArea.of(new GameTestHelper(failedInfo));
        own.onClose(failed::close);
        BlockPos here = own.pos(0, 100, 0);
        BlockPos there = failed.pos(0, 100, 0);
        helper.assertTrue(here.distSqr(there) > 4.0 * AirportScan.VALIDATION_RADIUS * AirportScan.VALIDATION_RADIUS,
            "concurrent regions include two complete surveys and clearance");
        helper.assertTrue(!own.name("Shared").equals(failed.name("Shared")), "same suffix gets a unique prefix");
        helper.assertValueEqual(failed.name(""), "", "unnamed-airport assertions retain their meaning");
        var ownChunk = new ChunkPos(here);
        var failedChunk = new ChunkPos(there);
        own.force(ownChunk.x, ownChunk.z);
        failed.force(failedChunk.x, failedChunk.z);
        var level = helper.getLevel();
        level.setBlockAndUpdate(here, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
        level.setBlockAndUpdate(there, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
        Airports.register(level, here);
        Airports.register(level, there);
        Airports.rename(level, here, own.name("Shared"));
        Airports.rename(level, there, failed.name("Shared"));
        Airports.request(level, there);
        failed.onClose(() -> level.setBlockAndUpdate(there, Blocks.AIR.defaultBlockState()));
        failedInfo.fail(new IllegalStateException("deliberate fixture setup failure"));
        AirlineTestArea.finish(failedInfo);
        helper.assertTrue(Airports.at(level, there).isEmpty() && !Airports.scanning(level, there),
            "failure unregisters the tower and cancels only its pending survey");
        helper.assertTrue(!level.getForcedChunks().contains(failedChunk.toLong()), "failure releases acquired ticket");
        helper.assertTrue(level.getForcedChunks().contains(ownChunk.toLong()), "sibling ticket remains forced");
        helper.assertTrue(Airports.byName(level, own.name("Shared")).isPresent(), "sibling airport remains registered");
        failed.close();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel")
    public static void areaPreservesPreexistingForcedTicket(GameTestHelper helper) {
        var own = AirlineTestArea.of(helper);
        var childInfo = new GameTestInfo(helper.testInfo.getTestFunction(), helper.testInfo.getRotation(),
            helper.getLevel(), helper.testInfo.retryOptions());
        var child = AirlineTestArea.of(new GameTestHelper(childInfo));
        own.onClose(child::close);
        var chunk = new ChunkPos(child.pos(0, 100, 0));
        // Temporarily close the child so this external ticket is not attributed to it by the test-only observer.
        child.close();
        var level = helper.getLevel();
        level.setChunkForced(chunk.x, chunk.z, true);
        own.onClose(() -> level.setChunkForced(chunk.x, chunk.z, false));
        child = AirlineTestArea.of(new GameTestHelper(childInfo));
        own.onClose(child::close);
        child.force(chunk.x, chunk.z);
        child.close();
        helper.assertTrue(level.getForcedChunks().contains(chunk.toLong()), "an existing ticket is never released");
        helper.succeed();
    }
}
