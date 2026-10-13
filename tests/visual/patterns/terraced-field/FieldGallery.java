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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
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
            overrides.forEach((key, value) -> values.put(key, value instanceof String id
                ? ItemSpec.of(ResourceLocation.parse(id)) : value));
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

    static int hill(int x, int z) {
        if (z < 0) return 3;
        double wobble = Math.sin(x * 0.23) * 1.2 + Math.sin(z * 0.5 + x * 0.1) * 0.6;
        return 3 + (int) Math.floor(Math.min(z, 44) * 0.42 + wobble + 0.5);
    }

    static int side(int x, int z) {
        double wobble = Math.sin(z * 0.3) * 0.8;
        return 3 + (int) Math.floor(Math.max(0, x - 50) * 0.3 + wobble + 0.5);
    }

    static int height(int x, int z) {
        if (x < -6) return 3;
        if (x >= 44) return side(x, z);
        return hill(x, z);
    }

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        ServerLevel level = ctx.server().overworld();
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:terraced_field"), args.string("source")).orElseThrow();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = -64; x <= 96; x++) for (int z = -40; z <= 72; z++) {
            int h = height(x, z);
            for (int y = -12; y <= 44; y++) {
                BlockState s = y > h ? air : y == h ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : y >= h - 2 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
                level.setBlock(new BlockPos(x, y, z), s, 2 | 16);
            }
        }
        for (int[] tree : new int[][] {{-20, -30}, {-58, 20}, {60, -30}, {-3, 60}, {30, 55}}) {
            int h = height(tree[0], tree[1]);
            for (int y = 1; y <= 5; y++) level.setBlock(new BlockPos(tree[0], h + y, tree[1]), Blocks.OAK_LOG.defaultBlockState(), 2);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 4; dy <= 6; dy++)
                if (Math.abs(dx) + Math.abs(dz) + Math.max(0, dy - 5) * 2 < 4 && !(dx == 0 && dz == 0 && dy < 6))
                    level.setBlock(new BlockPos(tree[0] + dx, h + dy, tree[1] + dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
        }
        List<Object> reports = new ArrayList<>();
        Object[][] fields = {
            {new BlockPos(-52, 3, -6), new BlockPos(-31, 5, 13), Direction.NORTH, Map.of(), false},
            {new BlockPos(4, 3, 0), new BlockPos(27, 20, 35), Direction.NORTH, Map.of(), true},
            {new BlockPos(52, 3, -14), new BlockPos(85, 16, 5), Direction.SOUTH,
                Map.of("wall", "minecraft:stone_bricks", "cap", "minecraft:smooth_stone", "path", "minecraft:spruce_planks",
                    "steps", "minecraft:spruce_stairs", "fence", "minecraft:oak_fence", "gate", "minecraft:oak_fence_gate"), false},
        };
        Random random = new Random(7);
        for (Object[] field : fields) {
            BlockPos a = (BlockPos) field[0];
            BlockPos b = (BlockPos) field[1];
            @SuppressWarnings("unchecked") var overrides = (Map<String, Object>) field[3];
            Drawn drawn = pattern.drawOn(new Commission(new Hint.Zone(a, b), new Knobs(pattern.knobs(), overrides),
                World.of(level), (Direction) field[2], 0L));
            if (!(drawn instanceof Drawn.Ready ready)) {
                reports.add(Map.of("zone", a.toShortString(), "refused", drawn.toString()));
                continue;
            }
            Map<BlockPos, Draft.Cell> cells = new HashMap<>();
            for (var cell : ready.draft().cells()) cells.put(ready.corner().offset(cell.offset()), cell);
            for (var e : cells.entrySet()) level.setBlock(e.getKey(), e.getValue().state(), 2 | 16);
            for (var e : cells.entrySet()) {
                BlockState now = level.getBlockState(e.getKey());
                BlockState shaped = Block.updateFromNeighbourShapes(now, level, e.getKey());
                if (shaped != now) level.setBlock(e.getKey(), shaped, 2 | 16);
            }
            List<String> dry = new ArrayList<>();
            Map<Integer, Integer> surfaces = new TreeMap<>();
            for (var e : cells.entrySet()) {
                Draft.Cell above = cells.get(e.getKey().above());
                if (!e.getValue().source().role().equals("fill") || (above != null && !above.state().isAir())) continue;
                surfaces.merge(e.getKey().getY(), 1, Integer::sum);
                boolean wet = false;
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = 0; dy <= 1; dy++) {
                    Draft.Cell near = cells.get(e.getKey().offset(dx, dy, dz));
                    wet |= near != null && near.state().is(Blocks.WATER);
                }
                if (!wet) dry.add(e.getKey().toShortString() + " above=" + (above == null ? "none" : above.source().role()));
            }
            int farmland = 0;
            if ((Boolean) field[4]) {
                for (var e : cells.entrySet()) {
                    Draft.Cell above = cells.get(e.getKey().above());
                    if (e.getValue().source().role().equals("fill") && e.getValue().state().is(Blocks.DIRT)
                        && (above == null || above.state().isAir())) {
                        level.setBlock(e.getKey(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 2);
                        level.setBlock(e.getKey().above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 4 + random.nextInt(4)), 2);
                        farmland++;
                    }
                }
            }
            reports.add(Map.of("zone", a.toShortString() + " .. " + b.toShortString(), "corner", ready.corner().toShortString(),
                "size", ready.draft().extent().size().toShortString(), "cells", ready.draft().cells().size(),
                "reports", ready.reports().toString(), "farmland", farmland, "dry", dry.size() > 12 ? dry.subList(0, 12) : dry,
                "surfaces", surfaces.toString()));
        }
        return Map.of("ok", true, "fields", reports);
    }
}
