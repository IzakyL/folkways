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
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class Task {
    static final class Knobs implements Settings {
        final Map<String, Object> values = new HashMap<>();
        Knobs(Schema schema, Map<String, Object> overrides) {
            Map<String, ResourceLocation> choices = new HashMap<>();
            for (Schema.Setting setting : schema.settings()) {
                switch (setting) {
                    case Schema.Setting.Flag flag -> values.put(flag.key(), flag.byDefault());
                    case Schema.Setting.Count count -> values.put(count.key(), count.byDefault());
                    case Schema.Setting.Choice choice -> {
                        values.put(choice.key(), choice.byDefault());
                        choices.put(choice.key(), choice.byDefault());
                    }
                    case Schema.Setting.Items items -> values.put(items.key(), spec(items.byDefault()));
                }
            }
            overrides.forEach((key, value) -> values.put(key, !(value instanceof String word) ? value
                : choices.containsKey(key) ? ResourceLocation.fromNamespaceAndPath(choices.get(key).getNamespace(), word)
                : ItemSpec.of(ResourceLocation.parse(word))));
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

    static final int SURFACE = 90;
    static final int BOTTOM = 0;
    static final int X0 = -80, X1 = 80, Z0 = -44, Z1 = 44;
    static final int MOST_ROUNDS = 80;
    static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, ResourceLocation.parse("c:ores"));

    static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return (h ^ h >>> 15) & 0x7fffffff;
    }

    static BlockState rock(int x, int y, int z) {
        if (y == SURFACE) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (y > SURFACE - 4) return Blocks.DIRT.defaultBlockState();
        if (y == SURFACE - 10 && x >= 24 && x <= 48 && z >= -12 && z <= 12) return Blocks.GRAVEL.defaultBlockState();
        int cell = hash(Math.floorDiv(x, 3), Math.floorDiv(y, 3), Math.floorDiv(z, 3));
        if (cell % 40 == 0 && hash(x, y, z) % 3 != 0) {
            if (y < 40) return Blocks.IRON_ORE.defaultBlockState();
            return cell % 3 == 0 ? Blocks.COPPER_ORE.defaultBlockState() : Blocks.COAL_ORE.defaultBlockState();
        }
        double n = Math.sin(x * 0.21 + y * 0.13) + Math.sin(z * 0.17 - y * 0.11) + Math.sin((x + z) * 0.09 + y * 0.05);
        if (n > 1.9) return Blocks.ANDESITE.defaultBlockState();
        if (n < -2.0) return Blocks.GRANITE.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    static int[] box() {
        return new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    }

    static void grow(int[] box, BlockPos at) {
        box[0] = Math.min(box[0], at.getX());
        box[1] = Math.min(box[1], at.getY());
        box[2] = Math.min(box[2], at.getZ());
        box[3] = Math.max(box[3], at.getX());
        box[4] = Math.max(box[4], at.getY());
        box[5] = Math.max(box[5], at.getZ());
    }

    static Map<String, Object> dig(ServerLevel level, Pattern pattern, String name, int cx, int cz,
            Map<String, Object> knobs) {
        Settings settings = new Knobs(pattern.knobs(), knobs);
        Hint hint = new Hint.Zone(new BlockPos(cx - 1, SURFACE + 1, cz - 1), new BlockPos(cx + 1, SURFACE + 1, cz + 1));
        Growth growth = Growth.first(hint);
        List<Map<String, Object>> rounds = new ArrayList<>();
        Map<String, Integer> roles = new TreeMap<>();
        int[] dug = box();
        int[] mouth = box();
        int ore = 0;
        String end = "ran out of rounds";
        for (int round = 0; round < MOST_ROUNDS; round++) {
            Round answered = pattern.growOn(new Commission(hint, settings, World.of(level), Direction.NORTH, 7L, growth));
            if (answered instanceof Round.Ended) {
                end = "ended";
                break;
            }
            if (answered instanceof Round.Stuck stuck) {
                end = "stuck: " + stuck.refused().why() + " " + stuck.refused().detail();
                break;
            }
            Round.Grew grew = (Round.Grew) answered;
            Drawn.Ready ready = grew.ready();
            int[] reach = box();
            int opened = 0;
            for (Draft.Cell cell : ready.draft().cells()) {
                BlockPos at = ready.corner().offset(cell.offset());
                roles.merge(cell.source().role(), 1, Integer::sum);
                grow(reach, at);
                if (round == 0) grow(mouth, at);
                if (cell.state().isAir()) {
                    opened++;
                    grow(dug, at);
                    if (level.getBlockState(at).is(ORES)) ore++;
                }
                level.setBlock(at, cell.state(), 2);
            }
            rounds.add(Map.of("cells", ready.draft().cells().size(), "opened", opened, "reach", reach));
            growth = growth.next(grew.kept(), ready.corner(), ready.draft(), hint.anchor());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("name", name);
        report.put("center", new int[] {cx, SURFACE, cz});
        report.put("knobs", knobs.toString());
        report.put("end", end);
        report.put("rounds", rounds.size());
        report.put("mouth", mouth);
        report.put("dug", dug);
        report.put("ore", ore);
        report.put("roles", roles);
        report.put("each", rounds);
        return report;
    }

    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:mine"), args.string("source")).orElseThrow();
        ServerLevel level = ctx.server().overworld();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {
            for (int y = BOTTOM; y <= SURFACE; y++) level.setBlock(new BlockPos(x, y, z), rock(x, y, z), 2);
            for (int y = SURFACE + 1; y <= SURFACE + 24; y++) level.setBlock(new BlockPos(x, y, z), air, 2);
        }
        List<Object> mines = new ArrayList<>();
        mines.add(dig(level, pattern, "iron", -36, 0, Map.of()));
        mines.add(dig(level, pattern, "copper", 36, 0, Map.of("level", "copper", "branch", 8, "spacing", 2,
            "pairs", 4, "timber", "minecraft:oak_log", "planks", "minecraft:oak_planks",
            "stairs", "minecraft:oak_stairs", "rail", "minecraft:oak_fence", "gate", "minecraft:oak_fence_gate")));
        boolean ok = mines.stream().allMatch(m -> "ended".equals(((Map<?, ?>) m).get("end"))
            && (Integer) ((Map<?, ?>) m).get("rounds") > 3);
        return Map.of("ok", ok, "surface", SURFACE, "rock", new int[] {X0, BOTTOM, Z0, X1, SURFACE, Z1}, "mines", mines);
    }
}
