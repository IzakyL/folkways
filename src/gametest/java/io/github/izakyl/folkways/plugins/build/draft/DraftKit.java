package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class DraftKit {

    static final BlockPos ORIGIN = BlockPos.ZERO;

    private DraftKit() {
    }

    static Pattern parse(String source) {
        return parse("drawn", source, Map.of());
    }

    static Pattern parse(String name, String source, Map<String, String> libraries) {
        return Pattern.parse(ResourceLocation.withDefaultNamespace(name), source,
                wanted -> Optional.ofNullable(libraries.get(wanted.toString())))
            .orElseThrow(() -> new AssertionError("the pattern did not load"));
    }

    static Hint zone(int x, int y, int z) {
        return new Hint.Zone(ORIGIN, ORIGIN.offset(x - 1, y - 1, z - 1));
    }

    static Drawn draw(Pattern pattern, Hint hint) {
        return draw(pattern, hint, new Filled(), World.NONE);
    }

    static Drawn draw(Pattern pattern, Hint hint, Settings settings, World world) {
        return pattern.drawOn(new Commission(hint, settings, world, Direction.NORTH, 0L));
    }

    static Draft ready(GameTestHelper helper, Drawn drawn) {
        if (drawn instanceof Drawn.Ready ready) {
            return ready.draft();
        }
        Drawn.Refused refused = (Drawn.Refused) drawn;
        helper.fail("expected a drawing, got " + refused.why() + " " + refused.detail());
        throw new AssertionError(refused.why());
    }

    static Drawn.Refused refused(GameTestHelper helper, Drawn drawn) {
        if (drawn instanceof Drawn.Refused refused) {
            return refused;
        }
        helper.fail("expected a refusal, got a drawing: " + describe(((Drawn.Ready) drawn).draft()));
        throw new AssertionError("drawn");
    }

    static Draft laid(GameTestHelper helper, List<Massing.Part> parts) {
        return ready(helper, Lattice.of(new Massing(parts))
            .map(lattice -> Fitter.fit(lattice, ORIGIN, 0L))
            .orElseGet(() -> Drawn.refused(DraftRefusal.SITE_TOO_LARGE)));
    }

    static BlockState stateAt(Draft draft, int x, int y, int z) {
        for (Draft.Cell cell : draft.cells()) {
            if (cell.offset().equals(new BlockPos(x, y, z))) {
                return cell.state();
            }
        }
        return null;
    }

    static int count(Draft draft, Block block) {
        int found = 0;
        for (Draft.Cell cell : draft.cells()) {
            if (cell.state().is(block)) {
                found++;
            }
        }
        return found;
    }

    static List<Draft.Cell> column(Draft draft, int x, int z) {
        List<Draft.Cell> column = new ArrayList<>();
        for (Draft.Cell cell : draft.cells()) {
            if (cell.offset().getX() == x && cell.offset().getZ() == z) {
                column.add(cell);
            }
        }
        return column;
    }

    static String describe(Draft draft) {
        StringBuilder written = new StringBuilder();
        for (Draft.Cell cell : draft.cells()) {
            written.append(cell.offset().toShortString()).append('=').append(cell.state()).append(' ');
        }
        return written.toString();
    }

    static final class Ground implements World {

        private final int height;
        private final Map<BlockPos, BlockState> placed = new HashMap<>();
        private final Map<Long, Integer> heights = new HashMap<>();
        private final Set<Long> unloaded = new HashSet<>();
        private Predicate<BlockPos> water = at -> false;
        private int floor = Integer.MIN_VALUE;

        Ground(int height) {
            this.height = height;
        }

        Ground floorAt(int lowest) {
            floor = lowest;
            return this;
        }

        @Override
        public int floor() {
            return floor;
        }

        Ground column(int x, int z, int top) {
            heights.put(key(x, z), top);
            return this;
        }

        Ground place(int x, int y, int z, BlockState state) {
            placed.put(new BlockPos(x, y, z), state);
            return this;
        }

        Ground water(Predicate<BlockPos> wet) {
            water = wet;
            return this;
        }

        Ground unload(int x, int z) {
            unloaded.add(key(x, z));
            return this;
        }

        private int heightAt(int x, int z) {
            return heights.getOrDefault(key(x, z), height);
        }

        @Override
        public Optional<BlockState> block(BlockPos at) {
            if (unloaded.contains(key(at.getX(), at.getZ()))) {
                return Optional.empty();
            }
            if (at.getY() < floor) {
                return Optional.of(Blocks.VOID_AIR.defaultBlockState());
            }
            BlockState set = placed.get(at);
            if (set != null) {
                return Optional.of(set);
            }
            if (at.getY() <= heightAt(at.getX(), at.getZ())) {
                return Optional.of(Blocks.STONE.defaultBlockState());
            }
            return Optional.of(water.test(at) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }

        @Override
        public OptionalInt top(int x, int z) {
            if (unloaded.contains(key(x, z))) {
                return OptionalInt.empty();
            }
            int top = heightAt(x, z);
            for (int y = top + 1; y < top + 64; y++) {
                if (!block(new BlockPos(x, y, z)).orElseThrow().isAir()) {
                    top = y;
                }
            }
            return OptionalInt.of(top + 1);
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }
    }

    static final class Filled implements Settings {

        private final Map<String, Object> values = new HashMap<>();

        static Filled defaulting(Schema schema) {
            Filled filled = new Filled();
            for (Schema.Setting setting : schema.settings()) {
                switch (setting) {
                    case Schema.Setting.Flag flag -> filled.values.put(flag.key(), flag.byDefault());
                    case Schema.Setting.Count count -> filled.values.put(count.key(), count.byDefault());
                    case Schema.Setting.Choice choice -> filled.values.put(choice.key(), choice.byDefault());
                    case Schema.Setting.Items items -> filled.values.put(items.key(), specOf(items.byDefault()));
                }
            }
            return filled;
        }

        private static ItemSpec specOf(List<ItemFilter> filters) {
            List<ItemSpec> alternatives = new ArrayList<>();
            for (ItemFilter filter : filters) {
                alternatives.add(filter.tag()
                    ? ItemSpec.of(TagKey.create(Registries.ITEM, filter.id()))
                    : ItemSpec.of(filter.id()));
            }
            return ItemSpec.anyOf(alternatives);
        }

        Filled items(String key, String item) {
            values.put(key, ItemSpec.of(ResourceLocation.parse(item)));
            return this;
        }

        Filled count(String key, int value) {
            values.put(key, value);
            return this;
        }

        Filled flag(String key, boolean value) {
            values.put(key, value);
            return this;
        }

        Filled choice(String key, String value) {
            values.put(key, ResourceLocation.fromNamespaceAndPath(DraftSite.PLAIN, value));
            return this;
        }

        @Override
        public boolean flag(String key) {
            return values.get(key) instanceof Boolean set && set;
        }

        @Override
        public int count(String key) {
            return values.get(key) instanceof Integer dialled ? dialled : 0;
        }

        @Override
        public ResourceLocation choice(String key) {
            if (values.get(key) instanceof ResourceLocation picked) {
                return picked;
            }
            throw new IllegalStateException("no choice called " + key);
        }

        @Override
        public Optional<ItemSpec> items(String key) {
            return Optional.ofNullable((ItemSpec) values.get(key));
        }
    }
}
