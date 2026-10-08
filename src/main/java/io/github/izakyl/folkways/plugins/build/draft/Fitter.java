package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

final class Fitter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private Fitter() {
    }

    static Drawn fit(Lattice lattice, BlockPos anchor, long seed) {
        List<Massing.Part> parts = lattice.parts();
        Map<Integer, Palette> palettes = new HashMap<>();
        Map<Integer, List<Rewrite>> rewrites = new HashMap<>();
        for (int index = 0; index < parts.size(); index++) {
            Massing.Part part = parts.get(index);
            switch (part.skin()) {
                case Skin.Cleared ignored -> {
                }
                case Skin.Fitted fitted -> {
                    Optional<Palette> palette = Palette.resolve(fitted.materials(), fitted.fit().partial());
                    if (palette.isEmpty()) {
                        return Drawn.refused(DraftRefusal.NOTHING_TO_BUILD_WITH, part.role());
                    }
                    palettes.put(index, palette.get());
                }
                case Skin.Stamped stamped -> {
                    List<Rewrite> resolved = new ArrayList<>();
                    for (Swap swap : stamped.swaps()) {
                        Optional<Palette> into = Palette.resolve(swap.into());
                        if (into.isEmpty()) {
                            return Drawn.refused(DraftRefusal.NOTHING_TO_BUILD_WITH, part.role());
                        }
                        resolved.add(new Rewrite(Palette.blocksOf(swap.from()), into.get()));
                    }
                    rewrites.put(index, resolved);
                }
            }
        }

        List<BlockPos> at = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        List<Draft.Source> sources = new ArrayList<>();
        List<Massing.Part> owners = new ArrayList<>();
        Draft.Source[] sourceOf = new Draft.Source[parts.size()];
        lattice.forEachCovered((x, y, z, index, octants) -> {
            Massing.Part part = parts.get(index);
            BlockPos cell = new BlockPos(x, y, z);
            long hash = seed * 31L + cell.asLong();
            state(part.skin(), palettes.get(index), rewrites.get(index), cell, octants, hash).ifPresent(state -> {
                if (sourceOf[index] == null) {
                    sourceOf[index] = new Draft.Source(part.path(), part.role());
                }
                at.add(cell);
                states.add(state);
                sources.add(sourceOf[index]);
                owners.add(part);
            });
        });
        Map<BlockPos, Integer> occupied = new HashMap<>();
        for (int cell = 0; cell < at.size(); cell++) {
            occupied.put(at.get(cell), cell);
        }
        for (int index = 0; index < parts.size(); index++) {
            Massing.Part part = parts.get(index);
            if (!(part.skin() instanceof Skin.Fitted fitted) || fitted.fit() != Fit.CONNECTED) {
                continue;
            }
            Map<BlockPos, Integer> support = new HashMap<>();
            for (int cell = 0; cell < at.size(); cell++) {
                if (owners.get(cell).known(fitted.support()) && !states.get(cell).isAir()) {
                    BlockPos pos = at.get(cell);
                    support.merge(new BlockPos(pos.getX(), 0, pos.getZ()), pos.getY(), Math::max);
                }
            }
            Optional<List<BlockPos>> connected = ConnectedFit.cells(part.solid(), support);
            if (connected.isEmpty()) {
                return Drawn.refused(DraftRefusal.PATTERN_FAILED,
                    "Cannot connect " + part.role() + " within support " + fitted.support());
            }
            Draft.Source source = new Draft.Source(part.path(), part.role());
            for (BlockPos pos : connected.get()) {
                Integer existing = occupied.get(pos);
                if (existing != null) {
                    if (part.over().takes(owners.get(existing))) {
                        states.set(existing, palettes.get(index).nearest(Octants.FULL, fitted.axis(), seed * 31L + pos.asLong()));
                        sources.set(existing, source);
                        owners.set(existing, part);
                    }
                    continue;
                }
                Integer footing = occupied.get(pos.below());
                if (footing != null && owners.get(footing).known(fitted.support())) {
                    BlockState floor = states.get(footing);
                    if (floor.getBlock() instanceof SlabBlock && floor.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                        states.set(footing, floor.setValue(SlabBlock.TYPE, SlabType.DOUBLE));
                    }
                }
                occupied.put(pos, at.size());
                at.add(pos);
                states.add(palettes.get(index).nearest(Octants.FULL, fitted.axis(), seed * 31L + pos.asLong()));
                sources.add(source);
                owners.add(part);
            }
        }
        if (at.isEmpty()) {
            return Drawn.refused(DraftRefusal.NOTHING_DRAWN);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos cell : at) {
            minX = Math.min(minX, cell.getX());
            minY = Math.min(minY, cell.getY());
            minZ = Math.min(minZ, cell.getZ());
        }
        BlockPos low = new BlockPos(minX, minY, minZ);
        List<Draft.Cell> cells = new ArrayList<>(at.size());
        for (int index = 0; index < at.size(); index++) {
            cells.add(new Draft.Cell(at.get(index).subtract(low), states.get(index), sources.get(index)));
        }
        return Extent.tightAround(cells.stream().map(Draft.Cell::offset).toList())
            .<Drawn>map(drawn -> new Drawn.Ready(anchor.offset(low), new Draft(drawn, cells)))
            .orElseGet(() -> Drawn.refused(DraftRefusal.SITE_TOO_LARGE));
    }

    private static Optional<BlockState> state(Skin skin, Palette palette, List<Rewrite> rewrites, BlockPos at,
            int octants, long hash) {
        return switch (skin) {
            case Skin.Cleared ignored -> Optional.of(AIR);
            case Skin.Fitted fitted -> switch (fitted.fit()) {
                case SOLID -> Octants.count(octants) * 2 >= 8
                    ? Optional.of(palette.nearest(Octants.FULL, fitted.axis(), hash))
                    : Optional.empty();
                case CONNECTED -> Optional.empty();
                case STEPPED, LAYERED -> palette.stepped(octants, fitted.axis(), hash);
            };
            case Skin.Stamped stamped -> Optional.ofNullable(stamped.cells().get(at))
                .map(written -> rewritten(written, rewrites, hash));
        };
    }

    private static BlockState rewritten(BlockState written, List<Rewrite> rewrites, long hash) {
        for (Rewrite rewrite : rewrites) {
            if (rewrite.from().contains(written.getBlock())) {
                return kept(written, Palette.alike(written, rewrite.into().nearest(Palette.maskOf(written), null, hash)));
            }
        }
        return written;
    }

    private static BlockState kept(BlockState written, BlockState chosen) {
        BlockState kept = chosen;
        for (Property<?> property : written.getProperties()) {
            if (kept.hasProperty(property)) {
                kept = copied(kept, written, property);
            }
        }
        return kept;
    }

    private static <T extends Comparable<T>> BlockState copied(BlockState into, BlockState from, Property<T> property) {
        return into.setValue(property, from.getValue(property));
    }

    private record Rewrite(Set<Block> from, Palette into) {
    }
}
