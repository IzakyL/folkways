import dev.blockwright.api.Context;
import blockwright.minecraft.core.Args;
import io.github.izakyl.folkways.airline.AirlineContent;
import io.github.izakyl.folkways.airline.Airports;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.vanilla.build.StructureNbt;
import io.github.izakyl.folkways.vanilla.build.draft.Commission;
import io.github.izakyl.folkways.vanilla.build.draft.DraftTemplate;
import io.github.izakyl.folkways.vanilla.build.draft.Drafting;
import io.github.izakyl.folkways.vanilla.build.draft.Drawn;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

// The promo airport, drafted from Airline's own patterns exactly as the colony book would draw them and pasted in,
// laid out like an international field seen from the air: two parallel east-west runways (09L/27R to the north,
// 09R/27L to the south, staggered), each with a full-length parallel taxiway, its entries and two rapid-exit
// taxiways; between the parallel taxiways the terminal apron of six stands with the terminal and its jet bridges
// behind them, two cross-field connectors and a helipad area; a short diagonal crosswind runway (04/22) to the east;
// and the control tower with its glass cab south of everything, scanning north over the field with its back. The terminal, bridges
// and cab are glass, slabs and bars only, so none of them stands in the tower's line of sight. Offsets are from the
// origin's ground cell.
public final class Task {
    record Defaults(Map<String, Object> values) implements Settings {
        public boolean flag(String key) { return (Boolean) values.get(key); }
        public int count(String key) { return (Integer) values.get(key); }
        public ResourceLocation choice(String key) { return (ResourceLocation) values.get(key); }
        public Optional<ItemSpec> items(String key) { return Optional.ofNullable((ItemSpec) values.get(key)); }
    }

    static ServerLevel level;
    static BlockPos o;
    static final Map<String, Object> report = new LinkedHashMap<>();

    static BlockPos at(int x, int z) { return o.offset(x, 0, z); }

    static Hint.Path path(int... xz) {
        var points = new ArrayList<BlockPos>();
        for (int i = 0; i < xz.length; i += 2) points.add(at(xz[i], xz[i + 1]));
        return new Hint.Path(points);
    }

    static ResourceLocation option(String local) { return ResourceLocation.fromNamespaceAndPath("pattern", local); }

    static void draw(String name, Hint hint, Map<String, Object> overrides) {
        var pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath("folkways_airline", name)).orElseThrow();
        Map<String, Object> values = new HashMap<>();
        for (Schema.Setting setting : pattern.knobs().settings()) {
            switch (setting) {
                case Schema.Setting.Flag flag -> values.put(flag.key(), flag.byDefault());
                case Schema.Setting.Count count -> values.put(count.key(), count.byDefault());
                case Schema.Setting.Choice choice -> values.put(choice.key(), choice.byDefault());
                case Schema.Setting.Items items -> values.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> filter.tag() ? ItemSpec.of(TagKey.create(Registries.ITEM, filter.id()))
                        : ItemSpec.of(filter.id())).toList()));
            }
        }
        values.putAll(overrides);
        Drawn drawn = Drafting.draw(pattern, new Commission(hint, new Defaults(values), pattern.capture(level, hint),
            Direction.NORTH, 42L)).join();
        if (!(drawn instanceof Drawn.Ready ready)) {
            throw new IllegalStateException(name + " " + hint + " refused: " + drawn);
        }
        var blueprint = StructureNbt.toBlueprint(DraftTemplate.of(ready.draft(), 0), level.registryAccess(),
            UUID.randomUUID(), name, level.getGameTime(), StructureNbt.AirMeaning.EXCAVATE, 262144).orElseThrow()
            .confirm(level);
        for (var cell : blueprint.blocks()) level.setBlock(cell.worldPos(ready.corner()), cell.state(), 2);
        report.merge(name, 1, (a, b) -> (Integer) a + (Integer) b);
    }

    static void pad(int x, int z) {
        draw("helipad", new Hint.Zone(at(x - 7, z - 7), at(x + 7, z + 7)),
            Map.of("size", 15, "heading", option("ns"), "circle", true));
    }

    static void taxiway(int... xz) { draw("taxiway", path(xz), Map.of("width", 9)); }

    static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    static void put(int x, int y, int z, BlockState state) { level.setBlock(at(x, z).above(y), state, 2); }

    static boolean grass(int x, int z) { return level.getBlockState(at(x, z)).is(Blocks.GRASS_BLOCK); }

    /** Lights set flush into the grass: they read from the air without standing in any approach. */
    static void light(int x, int z) {
        if (grass(x, z)) put(x, 0, z, Blocks.SEA_LANTERN.defaultBlockState());
    }

    /** An east-west runway's approach lights (a centre row with two crossbars) and edge lights along both sides. */
    static void lights(int west, int east, int z, int half) {
        for (int end : new int[] {west, east}) {
            int out = end == west ? -1 : 1;
            for (int d = 4; d <= 60; d += 4) light(end + out * d, z);
            for (int bar : new int[] {20, 40}) {
                for (int side = -4; side <= 4; side++) light(end + out * bar, z + side);
            }
        }
        for (int x = west; x <= east; x += 10) {
            light(x, z - half - 3);
            light(x, z + half + 3);
        }
    }

    static BlockPos tower;

    public static Object run(Context ctx) throws Exception {
        var args = Args.of(ctx);
        level = ((MinecraftServer) ctx.server()).overworld();
        o = new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z"));
        int height = (int) args.integer("tower");
        // The tower's survey reads everything within its validation radius: all of it is loaded and kept loaded.
        int survey = 392;
        for (int cx = (o.getX() - survey) >> 4; cx <= (o.getX() + survey) >> 4; cx++) {
            for (int cz = (o.getZ() - 300) >> 4; cz <= (o.getZ() + 170 + survey) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        // A clean field first: grass at the ground cell and nothing above it, so a re-run starts over.
        var grass = Blocks.GRASS_BLOCK.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        for (int x = -340; x <= 340; x++) {
            for (int z = -260; z <= 220; z++) {
                var ground = at(x, z);
                if (!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)) level.setBlock(ground, grass, 2);
                for (int y = 1; y <= 64; y++) {
                    if (!level.getBlockState(ground.above(y)).isAir()) level.setBlock(ground.above(y), air, 2);
                }
            }
        }
        // The parallel runways, 140 apart and staggered. Each draws its own parallel taxiway 33 off its centreline
        // on the side between them, with end entries and rapid exits: 09L/27R is drawn westward (taxiway south), 09R/27L
        // eastward (taxiway north).
        draw("runway", path(140, -140, -180, -140), Map.of("width", 15, "entries", true, "taxi_width", 9));
        draw("runway", path(-150, 0, 170, 0), Map.of("width", 15, "entries", true, "taxi_width", 9));
        // The crosswind runway, 04/22, out east beyond the helipads.
        draw("runway", path(185, 20, 265, -70), Map.of("width", 11, "entries", false));
        // Two connectors across the middle, joining the parallel taxiways (z -107 and -33) at T junctions.
        taxiway(-100, -106, -100, -34);
        taxiway(92, -106, 92, -34);
        // A continuous rectangular apron: six stands face the terminal, with open manoeuvring pavement behind.
        draw("apron", new Hint.Zone(at(-80, -78), at(69, -40)), Map.of("stands", 6, "size", 23));
        taxiway(-78, -46, -99, -46);
        taxiway(92, -46, 68, -46);
        taxiway(143, -35, 205, -2);
        // Four white-bordered helipads share one continuous hardstand east of the connector.
        for (int x = 91; x <= 154; x++) {
            for (int z = -99; z <= -35; z++) {
                put(x, 0, z, Blocks.GRAY_CONCRETE.defaultBlockState());
            }
        }
        int[][] pads = {{118, -88}, {143, -88}, {118, -46}, {143, -46}};
        for (int[] p : pads) pad(p[0], p[1]);
        taxiway(93, -67, 143, -67);
        for (int x : new int[] {118, 143}) taxiway(x, -80, x, -54);
        lights(-180, 140, -140, 7);
        lights(-150, 170, 0, 7);
        terminal(-82, 71, -82, -94, new int[] {-68, -43, -18, 7, 32, 57});
        // The tower south of everything, facing south: its rear 120 degree sector takes in the whole field.
        draw("airport_tower", new Hint.Zone(at(-1, 169), at(1, 171)),
            Map.of("height", height, "heading", option("south")));
        tower = at(0, 170).above(height - 1);
        cab(height - 1);
        report.put("stands", List.of(-68, -43, -18, 7, 32, 57));
        report.put("standStopZ", -75);
        report.put("origin", o.toShortString());
        report.put("tower", Map.of("x", tower.getX(), "y", tower.getY(), "z", tower.getZ(),
            "block", level.getBlockState(tower).toString()));
        return report;
    }

    /**
     * The terminal behind the stands: a glass hall under a slab roof, with a glass jet bridge reaching out to the
     * nose of each stand. Nothing in it is a full opaque block, so the tower sees the north taxiway over it.
     */
    static void terminal(int west, int east, int south, int north, int[] stands) {
        var floor = block("minecraft:polished_andesite");
        var glass = block("minecraft:light_blue_stained_glass");
        var mullion = block("minecraft:iron_bars");
        var roof = block("minecraft:smooth_quartz_slab");
        var eave = block("minecraft:white_concrete_powder");
        var bridgeFloor = block("minecraft:smooth_stone_slab").setValue(SlabBlock.TYPE, SlabType.TOP);
        var bridgeWall = block("minecraft:glass_pane");
        var leg = block("minecraft:polished_diorite_wall");
        int top = 7;
        for (int x = west; x <= east; x++) {
            for (int z = north; z <= south; z++) {
                put(x, 0, z, floor);
                boolean wall = x == west || x == east || z == north || z == south;
                for (int y = 1; y < top; y++) {
                    if (wall) put(x, y, z, Math.floorMod(x, 4) == 0 || x == west || x == east ? mullion : glass);
                }
                // A low-pitched roof: the middle third one slab higher than the eaves.
                boolean ridge = z <= south - 4 && z >= north + 4;
                put(x, top, z, roof);
                if (ridge) put(x, top + 1, z, roof);
            }
        }
        for (int stand : stands) {
            for (int z = south + 1; z <= south + 5; z++) {
                for (int x = stand - 1; x <= stand + 1; x++) {
                    put(x, 4, z, bridgeFloor);
                    put(x, 6, z, roof);
                    if (x != stand) put(x, 5, z, bridgeWall);
                }
            }
            for (int y = 1; y <= 3; y++) put(stand, y, south + 4, leg);
        }
        report.put("terminal", List.of(west, east, south, north));
    }

    /** A glass cab round the tower's top, its floor level with the shaft's top and a slab roof two above the chair. */
    static void cab(int y) {
        var floor = block("minecraft:polished_andesite_slab").setValue(SlabBlock.TYPE, SlabType.TOP);
        var glass = block("minecraft:light_blue_stained_glass");
        var frame = block("minecraft:polished_diorite_wall");
        var roof = block("minecraft:smooth_quartz_slab");
        for (int x = -3; x <= 3; x++) {
            for (int z = 167; z <= 173; z++) {
                boolean inside = Math.abs(x) <= 1 && z >= 169 && z <= 171;
                if (!inside) put(x, y - 1, z, floor);
                boolean ring = Math.abs(x) == 3 || z == 167 || z == 173;
                boolean corner = Math.abs(x) == 3 && (z == 167 || z == 173);
                if (ring) {
                    for (int up = 0; up <= 2; up++) put(x, y + up, z, corner ? frame : glass);
                }
                put(x, y + 3, z, roof);
            }
        }
        put(0, y + 4, 170, block("minecraft:lightning_rod"));
        put(0, y + 5, 170, block("minecraft:lightning_rod"));
    }
}
