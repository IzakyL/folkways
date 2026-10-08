import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.plugins.build.draft.*;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import java.util.*;

public final class Task {
    static final class Knobs implements Settings {
        final Map<String, Object> values = new HashMap<>();
        Knobs(Schema schema, Map<?, ?> overrides) {
            for (Schema.Setting setting : schema.settings()) {
                switch (setting) {
                    case Schema.Setting.Flag flag -> values.put(flag.key(), flag.byDefault());
                    case Schema.Setting.Count count -> values.put(count.key(), count.byDefault());
                    case Schema.Setting.Choice choice -> values.put(choice.key(), choice.byDefault());
                    case Schema.Setting.Items items -> values.put(items.key(), spec(items.byDefault()));
                }
            }
            overrides.forEach((key, value) -> {
                String name = String.valueOf(key);
                Object old = values.get(name);
                if (old instanceof Boolean) values.put(name, Boolean.valueOf(String.valueOf(value)));
                else if (old instanceof Integer) values.put(name, ((Number) value).intValue());
                else if (old instanceof ResourceLocation) values.put(name, ResourceLocation.fromNamespaceAndPath("pattern", String.valueOf(value)));
                else values.put(name, ItemSpec.anyOf(((List<?>) value).stream().map(id -> ItemSpec.of(ResourceLocation.parse(String.valueOf(id)))).toList()));
            });
        }
        static ItemSpec spec(List<ItemFilter> filters) {
            List<ItemSpec> all = new ArrayList<>();
            for (ItemFilter filter : filters) all.add(filter.tag() ? ItemSpec.of(TagKey.create(Registries.ITEM, filter.id())) : ItemSpec.of(filter.id()));
            return ItemSpec.anyOf(all);
        }
        public boolean flag(String key) { return (Boolean) values.get(key); }
        public int count(String key) { return (Integer) values.get(key); }
        public ResourceLocation choice(String key) { return (ResourceLocation) values.get(key); }
        public Optional<ItemSpec> items(String key) { return Optional.ofNullable((ItemSpec) values.get(key)); }
    }

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        var level = ctx.server().overworld();
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:dock"), args.string("source")).orElseThrow();
        var reports = new ArrayList<Object>();
        for (Object each : args.list("scenes")) {
            var scene = (Map<?, ?>) each;
            int cx = ((Number) scene.get("x")).intValue();
            int bank = ((Number) scene.get("bank")).intValue();
            int dx = ((Number) scene.get("dx")).intValue();
            int reach = ((Number) scene.get("z")).intValue();
            String top = (String) scene.get("top");
            BlockState surface = top.equals("sand") ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            BlockState soil = top.equals("sand") ? Blocks.SAND.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            for (int x = cx - 12; x <= cx + 11; x++) {
                for (int z = -24; z <= 40; z++) {
                    int bed = z < 0 ? bank : z == 0 ? -1 : z == 1 ? -2 : z == 2 ? -3 : -5;
                    for (int y = -8; y <= 16; y++) {
                        BlockState state;
                        if (y <= bed) state = z < 0 && y == bank ? surface : z < 0 && y >= bank - 2 ? soil : Blocks.STONE.defaultBlockState();
                        else if (y <= 0) state = Blocks.WATER.defaultBlockState();
                        else state = Blocks.AIR.defaultBlockState();
                        level.setBlock(new BlockPos(x, y, z), state, 2 | 16);
                    }
                }
            }
            var a = new BlockPos(cx, bank + 1, -3);
            var b = new BlockPos(cx + dx, bank + 1, reach);
            var knobs = new Knobs(pattern.knobs(), (Map<?, ?>) scene.get("knobs"));
            var patched = pattern;
            if (scene.get("patch") instanceof List<?> patch) {
                String text = args.string("source");
                for (Object pair : patch) text = text.replace((String) ((List<?>) pair).get(0), (String) ((List<?>) pair).get(1));
                patched = Pattern.parse(ResourceLocation.parse("folkways:dock"), text).orElseThrow();
            }
            var drawn = patched.drawOn(new Commission(new Hint.Path(List.of(a, b)), knobs, World.of(level), Direction.NORTH, 0L));
            if (drawn instanceof Drawn.Refused refused) {
                reports.add(Map.of("x", cx, "refused", String.valueOf(refused.why()), "detail", String.valueOf(refused.detail())));
                continue;
            }
            var ready = (Drawn.Ready) drawn;
            var placed = new ArrayList<BlockPos>();
            var roles = new TreeMap<String, Integer>();
            for (var cell : ready.draft().cells()) {
                var pos = ready.corner().offset(cell.offset());
                BlockState state = cell.state();
                if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
                    state = state.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(pos).is(Fluids.WATER) && level.getFluidState(pos).isSource());
                level.setBlock(pos, state, 2 | 16);
                placed.add(pos);
                roles.merge(cell.source().role(), 1, Integer::sum);
            }
            for (var pos : placed) {
                var now = level.getBlockState(pos);
                var shaped = Block.updateFromNeighbourShapes(now, level, pos);
                if (shaped != now) level.setBlock(pos, shaped, 2 | 16);
            }
            var sections = new ArrayList<String>();
            for (Object zs : args.list("sections")) {
                int z = ((Number) zs).intValue();
                for (int y = 5; y >= -5; y--) {
                    var line = new StringBuilder("z" + z + " y" + y + " ");
                    for (int x = cx - 5; x <= cx + 5; x++) {
                        var state = level.getBlockState(new BlockPos(x, y, z));
                        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                        line.append(String.format("%-14s", id.length() > 13 ? id.substring(id.length() - 13) : id));
                    }
                    sections.add(line.toString());
                }
            }
            reports.add(Map.of("x", cx, "blocks", placed.size(), "roles", roles, "sections", sections));
        }
        return Map.of("ok", true, "scenes", reports);
    }
}
