package io.github.izakyl.folkways.vanilla.build;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.work.api.Joint;
import io.github.izakyl.folkways.core.world.api.Realm;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BuildReadGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildReadGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void jointGhostsSkipUnloadedIntermediateChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos from = new BlockPos(26000000, 64, 26000000);
        BlockPos to = from.east(512);
        level.getChunk(from);
        level.getChunk(to);
        helper.assertTrue(!level.isLoaded(from.east(256)), "the middle chunk stays unloaded");
        var track = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:track")).defaultBlockState()
            .setValue(TrackBlock.SHAPE, TrackShape.XO);
        var target = new BuildTarget(new Realm.Dimension(level.dimension()), Map.of(from, track, to, track),
            Set.of(), List.of(new Joint(from, to)), BlockPos.ZERO);
        Purity.assertPure(helper, "joint ghosts across unloaded chunks",
            () -> BuildOrderView.pending(level, target, Set.of(from, to), Set.of()));
        helper.succeed();
    }
}
