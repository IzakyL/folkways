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
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

public final class Task {
    private static final List<BlockPos> PLACED = new ArrayList<>();

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) throws Exception {
        var level = ctx.server().overworld();
        for (BlockPos pos : PLACED) {
            level.setBlock(pos, (pos.getY() == 0 ? Blocks.SMOOTH_STONE : Blocks.AIR).defaultBlockState(), 3);
        }
        PLACED.clear();
        String name = Args.of(ctx).string("name");
        var id = ResourceLocation.parse("folkways_chiselsandbits:" + name);
        var pattern = Pattern.parse(id, Args.of(ctx).string("source")).orElseThrow(
            () -> new IllegalArgumentException("cannot parse " + name));
        if (Patterns.find(id).isEmpty()) {
            throw new AssertionError("integration namespace was not loaded");
        }
        Hint hint = switch (name) {
            case "pavilion" -> new Hint.Zone(new BlockPos(-4, 1, -4), new BlockPos(4, 1, 4));
            case "torii" -> new Hint.Zone(new BlockPos(0, 1, 0), new BlockPos(8, 1, 2));
            case "rotunda" -> new Hint.Zone(new BlockPos(0, 1, 0), new BlockPos(7, 15, 7));
            case "stone_arch_bridge" -> new Hint.Zone(new BlockPos(0, 1, 0), new BlockPos(2, 1, 19));
            case "gothic_cloister" -> new Hint.Zone(new BlockPos(0, 1, 0), new BlockPos(5, 1, 15));
            case "rampart" -> new Hint.Zone(new BlockPos(0, 1, 0), new BlockPos(4, 11, 24));
            case "curved_gallery" -> new Hint.Path(List.of(new BlockPos(-7, 1, -6), new BlockPos(-7, 1, 2),
                new BlockPos(1, 2, 2), new BlockPos(1, 2, 10)));
            default -> throw new IllegalArgumentException(name);
        };
        CompoundTag defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                defaults.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        var commission = new Commission(hint, Chosen.load(pattern.knobs(), defaults),
            World.NONE, name.equals("curved_gallery") ? Direction.NORTH : Direction.SOUTH, 73);
        long began = System.nanoTime();
        var drawn = Drafting.draw(pattern, commission).join();
        long millis = (System.nanoTime() - began) / 1_000_000;
        if (!(drawn instanceof Drawn.Ready ready)) {
            throw new AssertionError(name + " refused: " + drawn);
        }
        var draft = ready.draft();
        long partial = 0;
        long payloadBytes = 0;
        long voxels = 0;
        Map<ItemSpec, Long> materials = new HashMap<>();
        var unique = new HashSet<CompoundTag>();
        int resolution = Voxelizer.installed().orElseThrow().resolution();
        try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
            for (var cell : draft.cells()) {
                var pos = ready.corner().offset(cell.offset());
                PLACED.add(pos);
                level.setBlock(pos, cell.state(), 3);
                if (cell.payload().present()) {
                    partial++;
                    payloadBytes += cell.payload().bytes();
                    unique.add(cell.payload().tag());
                    var volume = CandBStructureCodec.validated(cell.payload().tag()).volume();
                    voxels += volume.counts().values().stream().mapToLong(Integer::longValue).sum();
                    volume.counts().forEach((material, count) -> materials.merge(material, (long) count, Long::sum));
                    var entity = StructureCompat.of(cell.state()).orElseThrow()
                        .detached(level.registryAccess(), pos, cell.state(), cell.payload());
                    level.setBlockEntity(entity);
                    entity.setChanged();
                    level.sendBlockUpdated(pos, cell.state(), cell.state(), 3);
                } else if (!cell.state().isAir()) {
                    long units = (long) resolution * resolution * resolution;
                    voxels += units;
                    materials.merge(ItemSpec.exact(new ItemStack(cell.state().getBlock().asItem())),
                        units, Long::sum);
                }
            }
        }
        var compressed = new ByteArrayOutputStream();
        NbtIo.writeCompressed(DraftTemplate.of(draft, 3955), compressed);
        var report = new LinkedHashMap<String, Object>();
        report.put("ok", true);
        report.put("grain", pattern.grain());
        report.put("extent", draft.extent().toString());
        report.put("partial", partial);
        report.put("whole", draft.cells().stream()
            .filter(cell -> !cell.payload().present() && !cell.state().isAir()).count());
        report.put("voxels", voxels);
        report.put("equivalentBlocks", (double) voxels / (resolution * resolution * resolution));
        report.put("drawMillis", millis);
        long units = (long) resolution * resolution * resolution;
        report.put("materialBlocks", materials.values().stream().mapToLong(count -> (count + units - 1) / units).sum());
        report.put("payloadBytes", payloadBytes);
        report.put("gzipBytes", compressed.size());
        report.put("uniquePayloads", unique.size());
        report.put("uniqueRatio", (double) unique.size() / partial);
        return report;
    }
}
