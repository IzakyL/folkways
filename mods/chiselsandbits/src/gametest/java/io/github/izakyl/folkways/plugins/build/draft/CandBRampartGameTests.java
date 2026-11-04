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
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysChiselsAndBitsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CandBRampartGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CandBRampartGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void rampartDrawsInBothFrames(GameTestHelper helper) throws Exception {
        var id = ResourceLocation.parse("folkways_chiselsandbits:rampart");
        Pattern pattern = Patterns.find(id).orElseThrow();
        helper.assertValueEqual(pattern.detail(), "bits", "integration pattern uses bits");
        for (String language : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            var words = pattern.face().words().get(language);
            helper.assertTrue(words != null && words.containsKey("title"), "rampart has a title in " + language);
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
            var commission = new Commission(new Hint.Path(List.of(BlockPos.ZERO, facing == Direction.NORTH
                ? new BlockPos(0, 0, 24) : new BlockPos(24, 0, 0))),
                Chosen.load(pattern.knobs(), defaults), World.NONE, facing, 73);
            long began = System.nanoTime();
            Drawn drawn = Drafting.draw(pattern, commission).join();
            long millis = (System.nanoTime() - began) / 1_000_000;
            helper.assertTrue(drawn instanceof Drawn.Ready, "rampart draws facing " + facing + ": " + drawn);
            var cells = ((Drawn.Ready) drawn).draft().cells();
            helper.assertTrue(cells.stream().anyMatch(cell -> cell.payload().present()), "curves have partial cells");
            helper.assertTrue(cells.stream().anyMatch(cell -> !cell.payload().present()), "solid core has whole cells");
            try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
                for (int along : List.of(0, 12, 24)) {
                    for (int y : List.of(5, 6)) {
                        BlockPos pos = facing == Direction.NORTH ? new BlockPos(0, y, along)
                            : new BlockPos(along, y, 0);
                        BlockPos offset = pos.subtract(((Drawn.Ready) drawn).corner());
                        var cell = cells.stream().filter(one -> one.offset().equals(offset)).findFirst();
                        helper.assertTrue(cell.isEmpty() || cell.get().state().isAir() || cell.get().payload().present()
                            && CandBStructureCodec.decode(cell.get().payload().tag())
                                .getBlockInformation(8, 8, 8).isAir(), "wall walk headroom at " + pos);
                    }
                }
            }
            if (facing == Direction.NORTH) {
                var ready = (Drawn.Ready) drawn;
                CandBGameTests.reportPatternBudgets(helper, DraftTemplate.of(ready.draft(), 3955));
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
                NbtIo.writeCompressed(DraftTemplate.of(draft, 3955), bytes);
                LoggerFactory.getLogger("folkways-patterns").info(
                    "rampart metrics extent={} partial={} whole={} voxels={} equivalent={} millis={}"
                        + " payloadBytes={} gzipBytes={} unique={} ratio={}",
                    draft.extent(), partial.size(), cells.size() - partial.size(), voxels, (double) voxels / perBlock,
                    millis, partial.stream().mapToLong(cell -> cell.payload().bytes()).sum(), bytes.size(),
                    unique.size(), (double) unique.size() / partial.size());
            }
        }
        defaults.putInt("spacing", 12);
        Drawn closeTowers = Drafting.draw(pattern, new Commission(
            new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(0, 0, 24))),
            Chosen.load(pattern.knobs(), defaults), World.NONE, Direction.NORTH, 73)).join();
        helper.assertTrue(closeTowers instanceof Drawn.Ready, "minimum tower spacing draws");
        var closeReady = (Drawn.Ready) closeTowers;
        BlockPos gate = new BlockPos(0, 1, 12).subtract(closeReady.corner());
        helper.assertTrue(closeReady.draft().cells().stream().noneMatch(cell -> cell.offset().equals(gate)
            && !cell.state().isAir()), "the gate reserves its bay when tower spacing is reduced");
        var frame = Frame.over(new BoundingBox(0, 0, 0, 4, 4, 4),
            Direction.NORTH, false);
        var batter = new Solid.TaperedBox(frame, new Vec3i(5, 5, 5), 1, 0);
        var low = frame.at(0.5, 0.25, 2.5);
        var high = frame.at(0.5, 4.75, 2.5);
        helper.assertTrue(batter.holds(low.x, low.y, low.z), "batter retains its broad foot");
        helper.assertTrue(!batter.holds(high.x, high.y, high.z), "batter continuously retreats at the crown");
        helper.succeed();
    }
}
