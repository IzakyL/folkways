package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.chiselsandbits.FolkwaysChiselsAndBitsMod;
import io.github.izakyl.folkways.core.api.structure.Voxelizer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
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
public final class CandBGalleryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CandBGalleryGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void curvedGalleryDrawsPathAndRotatedDefaults(GameTestHelper helper) throws Exception {
        var pattern = Patterns.find(ResourceLocation.parse("folkways_chiselsandbits:curved_gallery")).orElseThrow();
        helper.assertValueEqual(pattern.detail(), "bits", "integration detail");
        for (String language : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            var words = pattern.face().words().get(language);
            helper.assertTrue(words != null && words.containsKey("title"), "translated title " + language);
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
            var commission = new Commission(new Hint.Zone(BlockPos.ZERO, new BlockPos(2, 0, 12)),
                Chosen.load(pattern.knobs(), defaults), World.NONE, facing, 73);
            long began = System.nanoTime();
            Drawn drawn = Drafting.draw(pattern, commission).join();
            long millis = (System.nanoTime() - began) / 1_000_000;
            helper.assertTrue(drawn instanceof Drawn.Ready, "gallery draws " + facing + ": " + drawn);
            var draft = ((Drawn.Ready) drawn).draft();
            var cells = draft.cells();
            var partial = cells.stream().filter(cell -> cell.payload().present()).toList();
            helper.assertTrue(!partial.isEmpty(), "curves contain partial cells");
            helper.assertTrue(cells.size() > partial.size(), "paving contains whole cells");
            var unique = new HashSet<CompoundTag>();
            long voxels = (long) (cells.size() - partial.size()) * 4096;
            try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
                for (var cell : partial) {
                    unique.add(cell.payload().tag());
                    voxels += CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                        .mapToLong(Integer::longValue).sum();
                }
            }
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(DraftTemplate.of(draft, 3955), bytes);
            LoggerFactory.getLogger("folkways-patterns").info(
                "curved_gallery metrics facing={} extent={} partial={} whole={} voxels={} equivalent={} millis={}"
                    + " payloadBytes={} gzipBytes={} unique={} ratio={}",
                facing, draft.extent(), partial.size(), cells.size() - partial.size(), voxels, voxels / 4096.0,
                millis, partial.stream().mapToLong(cell -> cell.payload().bytes()).sum(), bytes.size(),
                unique.size(), (double) unique.size() / partial.size());
        }
        var path = new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(0, 0, 6), new BlockPos(6, 1, 6)));
        var drawn = Drafting.draw(pattern, new Commission(path, Chosen.load(pattern.knobs(), defaults),
            World.NONE, Direction.NORTH, 73)).join();
        helper.assertTrue(drawn instanceof Drawn.Ready, "rising turning path draws: " + drawn);
        var frame = Frame.over(new BoundingBox(0, 0, 0, 5, 5, 8), Direction.SOUTH, false);
        var skin = new Solid.ProfiledSweep(frame, List.of(new Vec3(3, 0, 1), new Vec3(3, 1, 7)),
            4, 3, 1, 0.25, 0.2);
        var point = frame.at(3, 4.55, 4);
        helper.assertTrue(skin.holds(point.x, point.y, point.z), "rounded ridge follows ramp elevation");
        point = frame.at(3.9, 4.55, 4);
        helper.assertTrue(!skin.holds(point.x, point.y, point.z), "roof changes within a block");
        helper.succeed();
    }
}
