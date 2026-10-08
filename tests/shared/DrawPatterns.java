import io.github.izakyl.folkways.plugins.build.draft.*;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import java.util.*;
import dev.blockwright.api.Context;
import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Fail;
import blockwright.minecraft.core.Sync;

// Draws patterns from their .star sources at marked zones or paths and lays the cells at once.
// args: sources {id: text}, drawings [{pattern, kind, points [[x,y,z]..], knobs {key: value}, seed,
// lay (false only drafts), collect [role..] (answers those roles' cells), blueprint (answers every cell and state)}],
// keep [[x, z]..] columns where the guarded roles may not stand, guard [role..].
public final class Task {
    static final class Knobs implements Settings {
        final Map<String, Object> values = new HashMap<>();

        Knobs(Schema schema, Map<?, ?> given) {
            for (Schema.Setting setting : schema.settings()) {
                Object set = given.get(setting.key());
                switch (setting) {
                    case Schema.Setting.Flag flag -> values.put(flag.key(), set == null ? flag.byDefault() : (Boolean) set);
                    case Schema.Setting.Count count -> values.put(count.key(), set == null ? count.byDefault()
                        : Math.max(count.min(), Math.min(count.max(), ((Number) set).intValue())));
                    case Schema.Setting.Choice choice -> values.put(choice.key(), set == null ? choice.byDefault()
                        : choice.options().stream().filter(o -> o.getPath().equals(set) || o.toString().equals(set))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException(choice.key() + " has no option " + set)));
                    case Schema.Setting.Items items -> {
                        List<ItemSpec> specs = new ArrayList<>();
                        if (set == null) {
                            for (ItemFilter f : items.byDefault())
                                specs.add(f.tag() ? ItemSpec.of(TagKey.create(Registries.ITEM, f.id())) : ItemSpec.of(f.id()));
                        } else {
                            for (Object id : (List<?>) set) specs.add(ItemSpec.of(ResourceLocation.parse((String) id)));
                        }
                        values.put(items.key(), ItemSpec.anyOf(specs));
                    }
                }
            }
        }

        public boolean flag(String key) { return (Boolean) values.get(key); }
        public int count(String key) { return (Integer) values.get(key); }
        public ResourceLocation choice(String key) { return (ResourceLocation) values.get(key); }
        public Optional<ItemSpec> items(String key) { return Optional.ofNullable((ItemSpec) values.get(key)); }
    }

    static BlockPos pos(Object triple) {
        var p = (List<?>) triple;
        return new BlockPos(((Number) p.get(0)).intValue(), ((Number) p.get(1)).intValue(), ((Number) p.get(2)).intValue());
    }

    public static Object run(Context ctx) throws Exception {
        // ok is false when any drawing was refused; each refusal is in its report, and callers read them.
        Args args = Args.of(ctx);
        ServerLevel level = ctx.server().overworld();
        Map<ResourceLocation, String> sources = new HashMap<>();
        Map<String, String> given = args.stringMap("sources");
        if (given == null) throw Fail.invalid("args.sources is required ({id: .star text})");
        given.forEach((id, text) -> sources.put(ResourceLocation.parse(id), text));
        Map<ResourceLocation, Pattern> patterns = new HashMap<>();
        Set<Long> keep = new HashSet<>();
        List<Object> columns = args.list("keep");
        for (Object column : columns == null ? List.of() : columns) {
            var c = (List<?>) column;
            keep.add(BlockPos.asLong(((Number) c.get(0)).intValue(), 0, ((Number) c.get(1)).intValue()));
        }
        Set<String> guard = new HashSet<>();
        List<String> guarded = args.strings("guard");
        if (guarded != null) guard.addAll(guarded);
        List<Object> drawings = args.list("drawings");
        if (drawings == null) throw Fail.invalid("args.drawings is required (an array of drawings)");

        List<Object> reports = new ArrayList<>();
        int laid = 0;
        for (Object one : drawings) {
            @SuppressWarnings("unchecked") var drawing = (Map<String, Object>) one;
            var id = ResourceLocation.parse((String) drawing.get("pattern"));
            Pattern pattern = patterns.computeIfAbsent(id, key -> Pattern.parse(key, sources.get(key),
                lib -> Optional.ofNullable(sources.get(lib))).orElseThrow());
            List<BlockPos> points = new ArrayList<>();
            for (Object p : (List<?>) drawing.get("points")) points.add(pos(p));
            Hint hint = "zone".equals(drawing.get("kind")) ? new Hint.Zone(points.get(0), points.get(1)) : new Hint.Path(points);
            var knobs = new Knobs(pattern.knobs(), (Map<?, ?>) drawing.getOrDefault("knobs", Map.of()));
            long seed = ((Number) drawing.getOrDefault("seed", 7)).longValue();
            Drawn drawn = pattern.drawOn(new Commission(hint, knobs, World.of(level), Direction.NORTH, seed));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("name", drawing.containsKey("name") ? drawing.get("name") : id.getPath());
            if (!(drawn instanceof Drawn.Ready ready)) {
                var refused = (Drawn.Refused) drawn;
                report.put("refused", refused.why() + " " + refused.detail());
                reports.add(report);
                continue;
            }
            boolean lay = !Boolean.FALSE.equals(drawing.get("lay"));
            boolean blueprint = Boolean.TRUE.equals(drawing.get("blueprint"));
            Set<String> collect = new HashSet<>();
            for (Object role : (List<?>) drawing.getOrDefault("collect", List.of())) collect.add((String) role);
            Map<String, List<Integer>> collected = new TreeMap<>();
            List<Object> plan = new ArrayList<>();
            int cells = 0, dropped = 0;
            int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (var cell : ready.draft().cells()) {
                BlockPos at = ready.corner().offset(cell.offset());
                if (guard.contains(cell.source().role()) && keep.contains(BlockPos.asLong(at.getX(), 0, at.getZ()))) {
                    dropped++;
                    continue;
                }
                min[0] = Math.min(min[0], at.getX()); min[1] = Math.min(min[1], at.getY()); min[2] = Math.min(min[2], at.getZ());
                max[0] = Math.max(max[0], at.getX()); max[1] = Math.max(max[1], at.getY()); max[2] = Math.max(max[2], at.getZ());
                if (collect.contains(cell.source().role()))
                    collected.computeIfAbsent(cell.source().role(), r -> new ArrayList<>()).addAll(List.of(at.getX(), at.getY(), at.getZ()));
                if (blueprint) plan.add(List.of(at.getX(), at.getY(), at.getZ(), BlockStateParser.serialize(cell.state())));
                if (lay) level.setBlock(at, cell.state(), 2 | 16);
                cells++;
            }
            if (lay) laid += cells;
            report.put("cells", cells);
            if (cells > 0) report.put("bounds", List.of(min[0], min[1], min[2], max[0], max[1], max[2]));
            if (!collected.isEmpty()) report.put("roles", collected);
            if (blueprint) report.put("blueprint", plan);
            if (dropped > 0) report.put("dropped", dropped);
            reports.add(report);
        }
        return Sync.stamp(Map.of("ok", reports.stream().noneMatch(r -> ((Map<?, ?>) r).containsKey("refused")),
            "laid", laid, "drawings", reports));
    }
}
