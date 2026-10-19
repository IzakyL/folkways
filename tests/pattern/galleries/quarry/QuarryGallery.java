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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class Task {
    static final class Knobs implements Settings {
        final Map<String, Object> values = new HashMap<>();
        Knobs(Schema schema, Map<String, Object> overrides) {
            for (Schema.Setting setting : schema.settings()) {
                switch (setting) {
                    case Schema.Setting.Flag flag -> values.put(flag.key(), flag.byDefault());
                    case Schema.Setting.Count count -> values.put(count.key(), count.byDefault());
                    case Schema.Setting.Choice choice -> values.put(choice.key(), choice.byDefault());
                    case Schema.Setting.Items items -> values.put(items.key(), spec(items.byDefault()));
                }
            }
            values.putAll(overrides);
        }
        static ItemSpec spec(List<ItemFilter> filters) {
            List<ItemSpec> any = new ArrayList<>();
            for (ItemFilter f : filters) any.add(f.tag() ? ItemSpec.of(TagKey.create(Registries.ITEM, f.id())) : ItemSpec.of(f.id()));
            return ItemSpec.anyOf(any);
        }
        public boolean flag(String key) { return values.get(key) instanceof Boolean b && b; }
        public int count(String key) { return values.get(key) instanceof Integer i ? i : 0; }
        public ResourceLocation choice(String key) { return (ResourceLocation) values.get(key); }
        public Optional<ItemSpec> items(String key) { return Optional.ofNullable((ItemSpec) values.get(key)); }
    }

    static final int GROUND = 3;

    // Flat ground to the west; east of x = 8 a hill climbs a block every two, so a quarry framed across its foot
    // cuts the slope back to a face where the hill stands above the zone's edge.
    static int height(int x, int z) {
        if (x < 8) return GROUND;
        double wobble = Math.sin(z * 0.31) * 0.7;
        return GROUND + (int) Math.floor(Math.min(x - 8, 40) * 0.5 + wobble + 0.5);
    }

    static BlockState rock(int x, int y, int z, int h) {
        if (y == h) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (y >= h - 2) return Blocks.DIRT.defaultBlockState();
        double n = Math.sin(x * 0.27 + y * 0.19) + Math.sin(z * 0.23 - y * 0.15);
        if (n > 1.5) return Blocks.ANDESITE.defaultBlockState();
        if (n < -1.6) return Blocks.GRANITE.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        ServerLevel level = ctx.server().overworld();
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:quarry"), args.string("source")).orElseThrow();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = -56; x <= 56; x++) for (int z = -32; z <= 32; z++) {
            int h = height(x, z);
            for (int y = -24; y <= 40; y++) {
                level.setBlock(new BlockPos(x, y, z), y > h ? air : rock(x, y, z, h), 2 | 16);
            }
        }
        List<Object> reports = new ArrayList<>();
        Object[][] quarries = {
            {"flat", new BlockPos(-40, GROUND, -10), new BlockPos(-21, GROUND, 9), Map.of()},
            {"hillside", new BlockPos(4, GROUND, -12), new BlockPos(27, GROUND, 11), Map.of("depth", 6)},
        };
        boolean ok = true;
        for (Object[] quarry : quarries) {
            BlockPos a = (BlockPos) quarry[1];
            BlockPos b = (BlockPos) quarry[2];
            @SuppressWarnings("unchecked") var overrides = (Map<String, Object>) quarry[3];
            Drawn drawn = pattern.drawOn(new Commission(new Hint.Zone(a, b), new Knobs(pattern.knobs(), overrides),
                World.of(level), Direction.NORTH, 0L));
            if (!(drawn instanceof Drawn.Ready ready)) {
                ok = false;
                reports.add(Map.of("name", quarry[0], "refused", drawn.toString()));
                continue;
            }
            int opened = 0;
            for (var cell : ready.draft().cells()) {
                BlockPos at = ready.corner().offset(cell.offset());
                if (cell.state().isAir() && !level.getBlockState(at).isAir()) opened++;
                level.setBlock(at, cell.state(), 2 | 16);
            }
            ok &= opened > 0;
            reports.add(Map.of("name", quarry[0], "zone", a.toShortString() + " .. " + b.toShortString(),
                "cells", ready.draft().cells().size(), "opened", opened, "reports", ready.reports().toString()));
        }
        return Map.of("ok", ok, "ground", GROUND, "quarries", reports);
    }
}
