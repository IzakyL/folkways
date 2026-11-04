import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Drafting;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.World;
import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.structure.Voxelizer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

public final class Task {
    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) throws Exception {
        var level = ctx.server().overworld();
        var id = ResourceLocation.parse("folkways_chiselsandbits:rampart");
        var pattern = Pattern.parse(id, Args.of(ctx).string("source")).orElseThrow();
        if (Patterns.find(id).isEmpty()) {
            throw new AssertionError("integration namespace was not loaded");
        }
        int baseY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        for (int x = -96; x <= 96; x++) {
            for (int z = -96; z <= 96; z++) {
                level.setBlock(new BlockPos(x, baseY - 1, z), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
            }
        }
        var hint = new Hint.Path(List.of(new BlockPos(0, baseY, -12), new BlockPos(0, baseY, 12),
            new BlockPos(12, baseY, 18)));
        CompoundTag defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                defaults.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        var commission = new Commission(hint, Chosen.load(pattern.knobs(), defaults),
            World.NONE, Direction.NORTH, 73);
        long began = System.nanoTime();
        var drawn = Drafting.draw(pattern, commission).join();
        long millis = (System.nanoTime() - began) / 1_000_000;
        if (!(drawn instanceof Drawn.Ready ready)) {
            throw new AssertionError("rampart refused: " + drawn);
        }
        var draft = ready.draft();
        long partial = 0;
        long payloadBytes = 0;
        long voxels = 0;
        var unique = new HashSet<CompoundTag>();
        int resolution = Voxelizer.installed().orElseThrow().resolution();
        try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
            for (var cell : draft.cells()) {
                var pos = ready.corner().offset(cell.offset());
                level.setBlock(pos, cell.state(), 3);
                if (cell.payload().present()) {
                    partial++;
                    payloadBytes += cell.payload().bytes();
                    unique.add(cell.payload().tag());
                    voxels += CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                        .mapToLong(Integer::longValue).sum();
                    var entity = StructureCompat.of(cell.state()).orElseThrow()
                        .detached(level.registryAccess(), pos, cell.state(), cell.payload());
                    level.setBlockEntity(entity);
                    entity.setChanged();
                    level.sendBlockUpdated(pos, cell.state(), cell.state(), 3);
                } else {
                    voxels += (long) resolution * resolution * resolution;
                }
            }
        }
        var compressed = new ByteArrayOutputStream();
        NbtIo.writeCompressed(DraftTemplate.of(draft, 3955), compressed);
        var report = new LinkedHashMap<String, Object>();
        report.put("ok", true);
        report.put("baseY", baseY);
        report.put("extent", draft.extent().toString());
        report.put("partial", partial);
        report.put("whole", draft.cells().size() - partial);
        report.put("voxels", voxels);
        report.put("equivalentBlocks", (double) voxels / (resolution * resolution * resolution));
        report.put("drawMillis", millis);
        report.put("payloadBytes", payloadBytes);
        report.put("gzipBytes", compressed.size());
        report.put("uniquePayloads", unique.size());
        report.put("uniqueRatio", (double) unique.size() / partial);
        return report;
    }
}
