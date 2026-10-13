import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import io.github.izakyl.folkways.plugins.build.draft.*;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.core.Direction;
import net.minecraft.data.BlockFamilies;
import net.minecraft.data.BlockFamily;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import java.util.*;

public final class Task {
    static final class Materials implements Settings {
        final int width;
        final String[] blocks;
        Materials(int width, String... blocks) { this.width = width; this.blocks = blocks; }
        public boolean flag(String key) { return false; }
        public int count(String key) { return width; }
        public ResourceLocation choice(String key) { return null; }
        public Optional<ItemSpec> items(String key) {
            int index = List.of("arch", "deck", "parapet", "abutment", "accent").indexOf(key);
            return Optional.of(ItemSpec.of(ResourceLocation.withDefaultNamespace(blocks[index])));
        }
    }
    static Drawn draw(Pattern pattern, BlockPos a, BlockPos b, Materials materials) {
        return draw(pattern, a, b, materials, banks(a, b, 1));
    }
    static World banks(BlockPos a, BlockPos b, int drop) {
        return new World() {
            int ground(int x, int z) {
                double da = Math.hypot(x-a.getX(), z-a.getZ());
                double db = Math.hypot(x-b.getX(), z-b.getZ());
                return (da <= db ? a.getY() : b.getY()) - drop;
            }
            public Optional<net.minecraft.world.level.block.state.BlockState> block(BlockPos at) {
                return Optional.of((at.getY() <= ground(at.getX(), at.getZ()) ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
            public OptionalInt top(int x, int z) { return OptionalInt.of(ground(x,z)+1); }
        };
    }
    static Drawn draw(Pattern pattern, BlockPos a, BlockPos b, Materials materials, World world) {
        return pattern.drawOn(new Commission(new Hint.Path(List.of(a, b)), materials, world, Direction.NORTH, 0L));
    }
    static Drawn.Ready ready(Drawn drawn) {
        if (drawn instanceof Drawn.Ready result) return result;
        throw new AssertionError(drawn.toString());
    }
    static boolean deck(Draft.Cell cell) {
        return cell.source().role().equals("deck") || cell.source().role().equals("edge");
    }
    static boolean barrier(Draft.Cell cell) {
        return cell.source().role().equals("parapet");
    }
    static List<Set<BlockPos>> railComponents(Drawn.Ready result) {
        var remaining = new HashSet<BlockPos>();
        for (var cell : result.draft().cells()) if (barrier(cell)) remaining.add(result.corner().offset(cell.offset()));
        var components = new ArrayList<Set<BlockPos>>();
        while (!remaining.isEmpty()) {
            var found = new HashSet<BlockPos>();
            var queue = new ArrayDeque<BlockPos>();
            var first = remaining.iterator().next();
            remaining.remove(first);
            found.add(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                var pos = queue.removeFirst();
                for (var direction : Direction.values()) {
                    var next = pos.relative(direction);
                    if (remaining.remove(next)) { queue.addLast(next); found.add(next); }
                }
            }
            components.add(found);
        }
        return components;
    }
    static void verify(Drawn.Ready result) {
        verify(result, true);
    }
    static void verify(Drawn.Ready result, boolean requireHalf) {
        var deck = new HashMap<BlockPos, Integer>();
        var rails = railComponents(result);
        var occupied = new HashSet<BlockPos>();
        boolean half = false;
        var section = new HashMap<BlockPos, Set<Integer>>();
        for (var cell : result.draft().cells()) {
            var pos = result.corner().offset(cell.offset());
            if (cell.source().role().equals("post")) throw new AssertionError("unexpected decorative post");
            if (!occupied.add(pos)) throw new AssertionError("duplicate cell " + pos);
            if (!barrier(cell)) {
                var layers = section.computeIfAbsent(new BlockPos(pos.getX(), 0, pos.getZ()), key -> new HashSet<>());
                var type = cell.state().getBlock() instanceof SlabBlock ? cell.state().getValue(SlabBlock.TYPE) : SlabType.DOUBLE;
                if (type != SlabType.TOP) layers.add(pos.getY() * 2);
                if (type != SlabType.BOTTOM) layers.add(pos.getY() * 2 + 1);
            }
            if (deck(cell)) {
                deck.merge(new BlockPos(pos.getX(), 0, pos.getZ()), pos.getY(), Math::max);
                half |= cell.state().getBlock() instanceof SlabBlock
                    && cell.state().getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
            }
        }
        for (var entry : section.entrySet()) {
            var layers = entry.getValue();
            for (int y = Collections.min(layers); y <= Collections.max(layers); y++) {
                if (!layers.contains(y)) {
                    var column = new StringBuilder();
                    for (var c : result.draft().cells()) {
                        var p = result.corner().offset(c.offset());
                        if (p.getX() == entry.getKey().getX() && p.getZ() == entry.getKey().getZ())
                            column.append(' ').append(p.getY()).append(':').append(c.source().role()).append(':').append(c.state());
                    }
                    throw new AssertionError("gap in bridge section at " + entry.getKey() + " halfY=" + y + " corner=" + result.corner() + " size=" + result.draft().extent().size() + column);
                }
            }
        }
        if (requireHalf && !half) throw new AssertionError("no half slabs on deck at " + result.corner() + " size=" + result.draft().extent().size());
        if (rails.isEmpty() || rails.size() > 2) throw new AssertionError("disconnected railing " + rails.size() + " at " + result.corner() + " size=" + result.draft().extent().size());
        for (var rail : rails) {
            var remaining = new HashSet<>(rail);
            var queue = new ArrayDeque<BlockPos>();
            var first = remaining.iterator().next();
            remaining.remove(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                var pos = queue.removeFirst();
                var top = deck.get(new BlockPos(pos.getX(), 0, pos.getZ()));
                if (top == null || pos.getY() <= top)
                    throw new AssertionError("railing outside/inside deck " + pos);
                if (pos.getY() > top + 1 && !rail.contains(pos.below()))
                    throw new AssertionError("unsupported railing column " + pos);
                for (var direction : Direction.values()) {
                    var next = pos.relative(direction);
                    if (remaining.remove(next)) queue.addLast(next);
                }
            }
            if (!remaining.isEmpty()) throw new AssertionError("disconnected railing " + remaining);
        }
    }
    static void verifyApproaches(Drawn.Ready result, BlockPos a, BlockPos b, int drop) {
        verify(result);
        var tops = new HashMap<BlockPos, Double>();
        var rails = railComponents(result);
        for (var cell : result.draft().cells()) {
            var pos = result.corner().offset(cell.offset());
            if (deck(cell)) {
                double top = pos.getY() + (cell.state().getBlock() instanceof SlabBlock
                    && cell.state().getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? 0.5 : 1.0);
                tops.merge(new BlockPos(pos.getX(), 0, pos.getZ()), top, Math::max);
            }
        }
        int distance = Math.max(2, drop * 2);
        for (int side = 0; side < 2; side++) {
            var end = side == 0 ? a : b;
            int sign = side == 0 ? -1 : 1;
            double previous = end.getY() + 1;
            for (int step = 0; step <= distance; step++) {
                var column = new BlockPos(end.getX() + sign * step, 0, end.getZ());
                var top = tops.get(column);
                if (top == null || Math.abs(top - previous) > 0.500001)
                    throw new AssertionError("rough/missing approach " + column + " top=" + top + " previous=" + previous);
                previous = top;
            }
            if (Math.abs(previous - (end.getY() - drop + 1)) > 0.001)
                throw new AssertionError("approach does not meet bank surface");
            for (var rail : rails) {
                if (rail.stream().noneMatch(pos -> (pos.getX()-end.getX())*sign >= distance))
                    throw new AssertionError("railing stops before landing");
            }
        }
    }
    static void verifyProfile(Drawn.Ready result) {
        var low = new HashMap<Integer, Double>();
        var high = new HashMap<Integer, Double>();
        var panel = new HashMap<Integer, Integer>();
        for (var cell : result.draft().cells()) {
            var pos = result.corner().offset(cell.offset());
            if (!barrier(cell) && pos.getZ() == 0) {
                var type = cell.state().getBlock() instanceof SlabBlock ? cell.state().getValue(SlabBlock.TYPE) : SlabType.DOUBLE;
                low.merge(pos.getX(), pos.getY() + (type == SlabType.TOP ? 0.5 : 0.0), Math::min);
                high.merge(pos.getX(), pos.getY() + (type == SlabType.BOTTOM ? 0.5 : 1.0), Math::max);
            }
            if (pos.getZ() == -2) {
                if (cell.source().role().equals("parapet")) panel.merge(pos.getX(), pos.getY(), Math::max);
            }
        }
        if (Math.abs(high.get(12) - 3.0) > 0.001) throw new AssertionError("deck lost its gentle two-block rise");
        if (high.get(3) - low.get(3) <= high.get(12) - low.get(12))
            throw new AssertionError("arch shoulders must be thicker than crown");
        for (int x = 4; x <= 8; x++) {
            if (!Objects.equals(panel.get(x), panel.get(4))) throw new AssertionError("uneven panel top in first bay");
        }
        if (panel.get(9) == null) throw new AssertionError("missing railing at former post position");
        for (int x = 0; x <= 24; x++) {
            if (!Objects.equals(high.get(x), high.get(24-x))) throw new AssertionError("asymmetric deck profile");
        }
    }
    static boolean familyMaterial(net.minecraft.world.level.block.Block actual, ResourceLocation wanted) {
        var base = BuiltInRegistries.BLOCK.get(wanted);
        return actual == base || (base == Blocks.SMOOTH_STONE && actual == Blocks.SMOOTH_STONE_SLAB)
            || BlockFamilies.getAllFamilies().anyMatch(family ->
            family.getBaseBlock() == base && family.getVariants().get(BlockFamily.Variant.SLAB) == actual);
    }
    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) {
        var args = Args.of(ctx);
        var pattern = Pattern.parse(ResourceLocation.parse("folkways:arch_bridge"), args.string("source")).orElseThrow();
        var stone = new Materials(5, "stone_bricks", "smooth_stone", "stone_brick_wall", "chiseled_stone_bricks", "polished_andesite");
        int verified = 0;
        for (int[] delta : new int[][] {{24,0}, {-24,0}, {0,24}, {0,-24}, {19,13}, {-19,13}, {19,-13}, {-19,-13}}) {
            for (int width : new int[] {3,4,5,10,15}) {
                var mat = new Materials(width, stone.blocks);
                var result = ready(draw(pattern, BlockPos.ZERO, new BlockPos(delta[0], 0, delta[1]), mat));
                verify(result);
                var occupied = new HashSet<BlockPos>();
                var roles = new HashSet<String>();
                for (var cell : result.draft().cells()) {
                    if (!occupied.add(result.corner().offset(cell.offset()))) throw new AssertionError("duplicate cell");
                    roles.add(cell.source().role());
                    var wanted = mat.items(switch (cell.source().role()) { case "edge" -> "arch"; case "ring", "cornice" -> "accent"; case "keystone" -> "abutment"; default -> cell.source().role(); }).orElseThrow().item().orElseThrow();
                    if (!familyMaterial(cell.state().getBlock(), wanted))
                        throw new AssertionError("wrong material for " + cell.source().role());
                }
                if (!occupied.contains(BlockPos.ZERO) || !occupied.contains(new BlockPos(delta[0],0,delta[1])))
                    throw new AssertionError("missing endpoint " + Arrays.toString(delta) + " width=" + width);
                if (roles.size() != 8) throw new AssertionError("lost material region " + roles);
                if (occupied.contains(new BlockPos(delta[0]/2, -2, delta[1]/2))) throw new AssertionError("blocked arch opening");
                verified++;
            }
        }
        for (int[] delta : new int[][] {{6,0}, {96,0}, {33,34}}) {
            verify(ready(draw(pattern, BlockPos.ZERO, new BlockPos(delta[0],0,delta[1]), new Materials(15, stone.blocks))),
                Math.hypot(delta[0], delta[1]) >= 12);
            verified++;
        }
        for (int width : new int[] {2,16}) {
            if (!(draw(pattern, BlockPos.ZERO, new BlockPos(24,0,0), new Materials(width, stone.blocks)) instanceof Drawn.Refused))
                throw new AssertionError("accepted width " + width);
            verified++;
        }
        for (int dy : new int[] {-4,4}) {
            var result = ready(draw(pattern, BlockPos.ZERO, new BlockPos(24,dy,0), stone));
            verify(result);
            if (result.draft().cells().stream().noneMatch(c -> result.corner().offset(c.offset()).equals(new BlockPos(24,dy,0))))
                throw new AssertionError("wrong endpoint height " + dy);
            verified++;
        }
        for (int[] delta : new int[][] {{24,1}, {24,23}, {1,24}, {-24,1}, {24,-23}, {-1,-24}}) {
            for (int width : new int[] {3,4,5,10,15}) {
                for (int dy : new int[] {-6,0,6}) {
                    var result = ready(draw(pattern, new BlockPos(101,20,-91),
                        new BlockPos(101+delta[0],20+dy,-91+delta[1]), new Materials(width, stone.blocks)));
                    verify(result);
                    verified++;
                }
            }
        }
        for (Hint hint : List.of(new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(12,0,0), new BlockPos(24,0,0))),
                new Hint.Zone(BlockPos.ZERO, new BlockPos(24,0,0)),
                new Hint.Path(List.of(BlockPos.ZERO, BlockPos.ZERO)),
                new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(0,12,0))),
                new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(97,0,0))))) {
            if (!(pattern.drawOn(new Commission(hint, stone, 0L)) instanceof Drawn.Refused)) throw new AssertionError("accepted " + hint);
            verified++;
        }
        for (int drop : new int[] {0,1,2,4,6}) {
            for (int width : new int[] {3,4,5,10,15}) {
                var a = new BlockPos(100,20,-30);
                var b = new BlockPos(124,24,-30);
                verifyApproaches(ready(draw(pattern, a, b, new Materials(width, stone.blocks), banks(a,b,drop))), a,b,drop);
                verified++;
            }
        }
        var a = BlockPos.ZERO;
        var b = new BlockPos(24,0,0);
        if (!(draw(pattern,a,b,stone,World.NONE) instanceof Drawn.Refused)) throw new AssertionError("accepted missing shore");
        if (!(draw(pattern,a,b,stone,banks(a,b,7)) instanceof Drawn.Refused)) throw new AssertionError("accepted too steep shore");
        verified += 2;
        verifyProfile(ready(draw(pattern, BlockPos.ZERO, new BlockPos(24,0,0), stone)));
        verified++;
        var level = ctx.server().overworld();
        var reports = new ArrayList<Object>();
        int[][] lines = {{-30, -22, -8, -22, 3}, {4,-22,32,-22,7}, {-30,0,-7,9,5}, {10,0,30,-8,6}, {-20,24,-20,43,4}, {5,35,35,35,10}};
        String[][] palette = {
            {"stone_bricks", "smooth_stone", "stone_brick_wall", "chiseled_stone_bricks", "polished_andesite"},
            {"deepslate_bricks", "polished_andesite", "deepslate_brick_wall", "polished_deepslate", "deepslate_tiles"},
            {"bricks", "smooth_sandstone", "brick_wall", "sandstone", "cut_sandstone"},
            {"sandstone", "smooth_sandstone", "sandstone_wall", "chiseled_sandstone", "cut_red_sandstone"},
            {"oak_planks", "spruce_planks", "oak_fence", "stripped_oak_log", "dark_oak_planks"},
            {"prismarine_bricks", "dark_prismarine", "prismarine_wall", "prismarine", "dark_prismarine"}
        };
        for (int i=0; i<lines.length; i++) {
            var v = lines[i];
            for (int end : new int[] {0,2}) {
                int radius = v[4] / 2 + 3;
                for (int dx=-radius; dx<=radius; dx++) for(int dz=-radius; dz<=radius; dz++) {
                    if(dx*dx + dz*dz > radius*radius) continue;
                    for(int y=0; y<=3; y++) level.setBlock(new BlockPos(v[end]+dx,y,v[end+1]+dz),
                        (y==3 ? Blocks.GRASS_BLOCK : Blocks.STONE).defaultBlockState(),3);
                }
            }
            var result = ready(draw(pattern, new BlockPos(v[0], 4, v[1]), new BlockPos(v[2],4,v[3]), new Materials(v[4], palette[i]), World.of(level)));
            verify(result);
            for (var cell : result.draft().cells()) level.setBlock(result.corner().offset(cell.offset()), cell.state(), 3);
            reports.add(Map.of("width",v[4],"blocks",result.draft().cells().size(),"palette",palette[i]));
        }
        return Map.of("ok",true,"verified",verified,"examples",reports,
            "bundled", Patterns.find(ResourceLocation.parse("folkways:arch_bridge")).isPresent());
    }
}
