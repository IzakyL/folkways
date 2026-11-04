package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.chiselsandbits.FolkwaysChiselsAndBitsMod;
import io.github.izakyl.folkways.core.api.structure.Voxelizer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.plugins.build.CandBGameTests;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysChiselsAndBitsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoneArchBridgeGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StoneArchBridgeGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void stoneArchBridgeDrawsInBothFrames(GameTestHelper helper) throws Exception {
        var id = ResourceLocation.parse("folkways_chiselsandbits:stone_arch_bridge");
        Pattern pattern = Patterns.find(id).orElseThrow();
        helper.assertValueEqual(pattern.detail(), "bits", "integration pattern uses bits");
        for (String language : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            var words = pattern.face().words().get(language);
            helper.assertTrue(words != null && words.containsKey("title"),
                "stone_arch_bridge has a title in " + language);
            for (var setting : pattern.knobs().settings()) {
                helper.assertTrue(words.containsKey(setting.key()), "translated knob " + setting.key());
            }
        }
        CompoundTag defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                defaults.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        for (Direction facing : List.of(Direction.NORTH, Direction.EAST)) {
            var commission = new Commission(facing == Direction.NORTH
                ? new Hint.Zone(BlockPos.ZERO, new BlockPos(2, 0, 19))
                : new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(19, 0, 0))),
                Chosen.load(pattern.knobs(), defaults), World.NONE, facing, 73);
            long began = System.nanoTime();
            Drawn drawn = Drafting.draw(pattern, commission).join();
            long millis = (System.nanoTime() - began) / 1_000_000;
            helper.assertTrue(drawn instanceof Drawn.Ready, "stone_arch_bridge draws facing " + facing + ": " + drawn);
            var cells = ((Drawn.Ready) drawn).draft().cells();
            helper.assertTrue(cells.stream().anyMatch(cell -> cell.payload().present()), "curves have partial cells");
            helper.assertTrue(cells.stream().anyMatch(cell -> !cell.payload().present()), "platform has whole cells");
            if (facing == Direction.NORTH) {
                var ready = (Drawn.Ready) drawn;
                var partial = cells.stream().filter(cell -> cell.payload().present()).toList();
                var unique = new HashSet<CompoundTag>();
                int resolution = Voxelizer.installed().orElseThrow().resolution();
                int perBlock = resolution * resolution * resolution;
                long voxels = (long) (cells.size() - partial.size()) * perBlock;
                try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
                    for (var cell : partial) {
                        unique.add(cell.payload().tag());
                        voxels += CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                            .mapToLong(Integer::longValue).sum();
                    }
                }
                var bytes = new ByteArrayOutputStream();
                var draft = ((Drawn.Ready) drawn).draft();
                var file = DraftTemplate.of(draft, 3955);
                NbtIo.writeCompressed(file, bytes);
                LoggerFactory.getLogger("folkways-patterns").info(
                    "stone_arch_bridge metrics extent={} partial={} whole={} voxels={} equivalent={} millis={}"
                        + " payloadBytes={} gzipBytes={} unique={} ratio={}",
                    draft.extent(), partial.size(), cells.size() - partial.size(), voxels, (double) voxels / perBlock,
                    millis, partial.stream().mapToLong(cell -> cell.payload().bytes()).sum(), bytes.size(),
                    unique.size(), (double) unique.size() / partial.size());
                CandBGameTests.reportPatternBudgets(helper, file);
            }
        }
        helper.succeed();
    }
}
