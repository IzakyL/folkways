import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Drafting;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.World;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

public final class Task {

    public static Object run(Context ctx) throws Exception {
        var pattern = Patterns.find(ResourceLocation.parse("folkways_chiselsandbits:gothic_cloister")).orElseThrow();
        var defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof io.github.izakyl.folkways.front.api.Schema.Setting.Items items) {
                defaults.put(items.key(), io.github.izakyl.folkways.core.api.terms.ItemSpec.of(
                    items.byDefault().getFirst().id()).save());
            }
        }
        var scenes = List.of(
            new Hint.Path(List.of(new BlockPos(0, 1, 0), new BlockPos(0, 1, 18))),
            new Hint.Path(List.of(new BlockPos(28, 1, 0), new BlockPos(46, 1, 0))),
            new Hint.Path(List.of(new BlockPos(0, 1, 36), new BlockPos(12, 1, 48), new BlockPos(24, 1, 36))),
            new Hint.Path(List.of(new BlockPos(40, 1, 30), new BlockPos(40, 4, 42), new BlockPos(40, 2, 54)))
        );
        var reports = new ArrayList<Object>();
        var level = ctx.server().overworld();
        for (int z = 30; z <= 54; z++) {
            int floor = z <= 42 ? Math.min(3, (z - 30) / 3) : Math.max(1, 3 - (z - 42) / 4);
            int height = Math.max(0, floor - 1);
            for (int x = 36; x <= 44; x++) {
                for (int y = 0; y <= height; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                }
            }
        }
        for (var scene : scenes) {
            long started = System.nanoTime();
            var drawn = Drafting.draw(pattern, new Commission(scene,
                Chosen.load(pattern.knobs(), defaults), World.NONE, Direction.SOUTH, 0)).join();
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (!(drawn instanceof Drawn.Ready ready)) {
                throw new AssertionError(drawn.toString());
            }
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
            reports.add(Map.of("ok", true, "partial", partial, "whole", ready.draft().cells().size() - partial,
                "drawMillis", millis, "payloadBytes", bytes, "gzipBytes", gzip,
                "corner", ready.corner().toShortString(), "size", ready.draft().extent().size().toString()));
        }
        return Sync.stamp(Map.of("ok", true, "scenes", reports));
    }
}
