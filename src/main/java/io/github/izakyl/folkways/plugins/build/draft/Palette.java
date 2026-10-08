package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.BlockFamilies;
import net.minecraft.data.BlockFamily;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

final class Palette {

    private final List<Candidate> candidates;
    private final List<Integer> weights;

    private Palette(List<Candidate> candidates, List<Integer> weights) {
        this.candidates = candidates;
        this.weights = weights;
    }

    static Optional<Palette> resolve(Materials materials) {
        return resolve(materials, false);
    }

    static Optional<Palette> resolve(Materials materials, boolean slabs) {
        List<Candidate> candidates = new ArrayList<>();
        List<ItemFilter> filters = materials.filters();
        for (int group = 0; group < filters.size(); group++) {
            for (Block block : blocksOf(filters.get(group))) {
                statesOf(block, group, candidates);
                if (slabs) {
                    Block slab = FamilySlabs.BLOCKS.get(block);
                    if (slab != null) {
                        statesOf(slab, group, candidates);
                    }
                }
            }
        }
        return candidates.isEmpty()
            ? Optional.empty()
            : Optional.of(new Palette(candidates, materials.mixed() ? materials.weights() : List.of()));
    }

    private static final class FamilySlabs {
        private static final Map<Block, Block> BLOCKS = new HashMap<>();
        static {
            BLOCKS.put(Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB);
            BlockFamilies.getAllFamilies().forEach(family -> {
                Block slab = family.getVariants().get(BlockFamily.Variant.SLAB);
                if (slab != null) {
                    BLOCKS.put(family.getBaseBlock(), slab);
                }
            });
        }
    }

    private static final class Families {
        private static final Map<Block, BlockFamily> OF = new HashMap<>();
        private static final Map<Block, BlockFamily.Variant> KIND = new HashMap<>();
        static {
            BlockFamilies.getAllFamilies().forEach(family -> {
                OF.putIfAbsent(family.getBaseBlock(), family);
                family.getVariants().forEach((kind, block) -> {
                    OF.putIfAbsent(block, family);
                    KIND.putIfAbsent(block, kind);
                });
            });
        }
    }

    static BlockState alike(BlockState written, BlockState chosen) {
        BlockFamily.Variant kind = Families.KIND.get(written.getBlock());
        BlockFamily family = Families.OF.get(chosen.getBlock());
        if (kind == null || family == null) {
            return chosen;
        }
        Block variant = family.getVariants().get(kind);
        return variant == null ? chosen : variant.withPropertiesOf(written);
    }

    static Optional<Palette> resolve(List<ItemFilter> filters) {
        return resolve(Materials.inOrder(filters));
    }

    BlockState nearest(int octants) {
        return nearest(octants, null, 0L);
    }

    BlockState nearest(int octants, Direction.Axis axis, long cell) {
        int bestApart = Integer.MAX_VALUE;
        for (Candidate candidate : candidates) {
            bestApart = Math.min(bestApart, Octants.apart(candidate.octants, octants));
        }
        int group = -1;
        if (!weights.isEmpty()) {
            Set<Integer> eligible = new LinkedHashSet<>();
            for (Candidate candidate : candidates) {
                if (Octants.apart(candidate.octants, octants) == bestApart) {
                    eligible.add(candidate.group);
                }
            }
            long total = 0;
            for (int each : eligible) {
                total += weights.get(each);
            }
            long roll = Math.floorMod(scramble(cell), total);
            for (int each : eligible) {
                roll -= weights.get(each);
                if (roll < 0) {
                    group = each;
                    break;
                }
            }
        }
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (Octants.apart(candidate.octants, octants) != bestApart || (group >= 0 && candidate.group != group)) {
                continue;
            }
            if (best == null || rank(candidate, axis) < rank(best, axis)) {
                best = candidate;
            }
        }
        return best.state;
    }

    Optional<BlockState> stepped(int octants, Direction.Axis axis, long cell) {
        BlockState candidate = nearest(octants, axis, cell);
        return Octants.apart(maskOf(candidate), octants) > Octants.count(octants)
            ? Optional.empty() : Optional.of(candidate);
    }

    private static long rank(Candidate candidate, Direction.Axis axis) {
        int across = 0;
        if (axis != null && candidate.state.hasProperty(BlockStateProperties.AXIS)) {
            across = candidate.state.getValue(BlockStateProperties.AXIS) == axis ? 0 : 1;
        }
        return ((long) across << 20) + candidate.drift;
    }

    private static long scramble(long value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 32)) * 0xD6E8FEB86659FD93L;
        return mixed ^ (mixed >>> 32);
    }

    static Set<Block> blocksOf(ItemFilter filter) {
        Set<Block> blocks = new LinkedHashSet<>();
        if (filter.tag()) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, filter.id());
            BuiltInRegistries.ITEM.getTag(tag).ifPresent(members -> {
                for (Holder<Item> member : members) {
                    placed(member.value()).ifPresent(blocks::add);
                }
            });
            return blocks;
        }
        ResourceLocation id = filter.id();
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            placed(BuiltInRegistries.ITEM.get(id)).ifPresent(blocks::add);
        }
        return blocks;
    }

    private static Optional<Block> placed(Item item) {
        return item instanceof BlockItem placer ? Optional.of(placer.getBlock()) : Optional.empty();
    }

    private static void statesOf(Block block, int group, List<Candidate> into) {
        BlockState fallback = block.defaultBlockState();
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            into.add(new Candidate(state, maskOf(state), drift(state, fallback), group));
        }
    }

    static int maskOf(BlockState state) {
        if (state.getBlock() instanceof CrossCollisionBlock || state.getBlock() instanceof WallBlock) {
            return Octants.FULL;
        }
        try {
            return Octants.of(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (RuntimeException outsideAWorld) {
            return Octants.NONE;
        }
    }

    private static int drift(BlockState state, BlockState fallback) {
        int apart = 0;
        for (Property<?> property : state.getProperties()) {
            if (!state.getValue(property).equals(fallback.getValue(property))) {
                apart++;
            }
        }
        return apart;
    }

    private record Candidate(BlockState state, int octants, int drift, int group) {
    }
}
