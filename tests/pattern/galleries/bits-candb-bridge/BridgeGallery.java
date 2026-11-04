import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.core.api.structure.Voxelizer;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Drafting;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.World;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

public final class Task {

    public static Object run(Context ctx) throws Exception {
        var pattern = Patterns.find(ResourceLocation.parse("folkways_chiselsandbits:stone_arch_bridge")).orElseThrow();
        var defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof io.github.izakyl.folkways.front.api.Schema.Setting.Items items) {
                defaults.put(items.key(), io.github.izakyl.folkways.core.api.terms.ItemSpec.of(
                    items.byDefault().getFirst().id()).save());
            }
        }
        long started = System.nanoTime();
        var drawn = Drafting.draw(pattern, new Commission(
            new Hint.Zone(new BlockPos(0, 1, 0), new BlockPos(2, 1, 19)),
            Chosen.load(pattern.knobs(), defaults), World.NONE, Direction.SOUTH, 0)).join();
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (!(drawn instanceof Drawn.Ready ready)) {
            throw new AssertionError(drawn.toString());
        }
        var level = ctx.server().overworld();
        for (var cell : ready.draft().cells()) {
            var pos = ready.corner().offset(cell.offset());
            level.setBlock(pos, cell.state(), 3);
            if (cell.payload().present()) {
                var entity = level.getBlockEntity(pos);
                if (entity == null) {
                    throw new AssertionError("missing carving at " + pos);
                }
                entity.loadWithComponents(cell.payload().tag(), level.registryAccess());
                entity.setChanged();
                level.sendBlockUpdated(pos, cell.state(), cell.state(), 3);
            }
        }
        long partial = ready.draft().cells().stream().filter(cell -> cell.payload().present()).count();
        long bytes = ready.draft().cells().stream().mapToLong(cell -> cell.payload().bytes()).sum();
        int gzip;
        try (var output = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(DraftTemplate.of(ready.draft(), 3955), output);
            gzip = output.size();
        }
        var unique = new HashSet<CompoundTag>();
        long voxels = (ready.draft().cells().size() - partial) * 4096;
        try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
            for (var cell : ready.draft().cells()) {
                if (cell.payload().present()) {
                    unique.add(cell.payload().tag());
                    voxels += CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                        .mapToLong(Integer::longValue).sum();
                }
            }
        }
        return Sync.stamp(Map.ofEntries(
            Map.entry("ok", true), Map.entry("partial", partial),
            Map.entry("whole", ready.draft().cells().size() - partial),
            Map.entry("drawMillis", millis), Map.entry("payloadBytes", bytes), Map.entry("gzipBytes", gzip),
            Map.entry("voxels", voxels), Map.entry("equivalent", voxels / 4096.0),
            Map.entry("unique", unique.size()), Map.entry("ratio", unique.size() / (double) partial),
            Map.entry("corner", ready.corner().toShortString()),
            Map.entry("size", ready.draft().extent().size().toString())));
    }
}
