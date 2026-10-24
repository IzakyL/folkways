package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "piece", doc = "A piece of building written out cell by cell.")
public record Piece(Map<BlockPos, BlockState> cells, Vec3i size, List<Set<Integer>> stretch,
        Map<BlockPos, StructurePayload> payloads)
    implements StarlarkValue {

    public Piece {
        cells = Map.copyOf(cells);
        payloads = Map.copyOf(payloads);
        if (!cells.keySet().containsAll(payloads.keySet())) {
            throw new IllegalArgumentException("a payload belongs to a cell of its piece");
        }
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) {
            throw new IllegalArgumentException("a piece measures at least one cell on every axis");
        }
        List<Set<Integer>> kept = new ArrayList<>(3);
        for (int axis = 0; axis < 3; axis++) {
            Set<Integer> slices = stretch.size() > axis ? new TreeSet<>(stretch.get(axis)) : new TreeSet<>();
            int span = axis == 0 ? size.getX() : axis == 1 ? size.getY() : size.getZ();
            for (int slice : slices) {
                if (slice < 0 || slice >= span) {
                    throw new IllegalArgumentException("stretch slice " + slice + " lies outside a piece "
                        + span + " cells along " + "xyz".charAt(axis));
                }
            }
            kept.add(Set.copyOf(slices));
        }
        stretch = List.copyOf(kept);
    }

    public Piece(Map<BlockPos, BlockState> cells, Vec3i size, List<Set<Integer>> stretch) {
        this(cells, size, stretch, Map.of());
    }

    public Piece(Map<BlockPos, BlockState> cells, Vec3i size) {
        this(cells, size, List.of());
    }

    int span(int axis) {
        return axis == 0 ? size.getX() : axis == 1 ? size.getY() : size.getZ();
    }

    int least(int axis) {
        return span(axis) - stretch.get(axis).size();
    }

    boolean stretches(int axis) {
        return !stretch.get(axis).isEmpty();
    }

    @StarlarkMethod(name = "width", doc = "How many cells the piece runs along its own x.", structField = true)
    public StarlarkInt width() {
        return StarlarkInt.of(size.getX());
    }

    @StarlarkMethod(name = "height", doc = "How many layers the piece has.", structField = true)
    public StarlarkInt height() {
        return StarlarkInt.of(size.getY());
    }

    @StarlarkMethod(name = "depth", doc = "How many rows the piece has.", structField = true)
    public StarlarkInt depth() {
        return StarlarkInt.of(size.getZ());
    }

    Piece stretchedTo(int[] target) {
        List<List<Integer>> sources = new ArrayList<>(3);
        int[] made = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            int span = span(axis);
            List<Integer> order = new ArrayList<>();
            List<Integer> slices = new ArrayList<>(stretch.get(axis));
            if (slices.isEmpty() || target[axis] == span) {
                for (int index = 0; index < span; index++) {
                    order.add(index);
                }
            } else {
                int extra = target[axis] - span;
                int[] share = Splits.spread(Math.abs(extra), slices.size());
                for (int index = 0; index < span; index++) {
                    int at = slices.indexOf(index);
                    int copies = at < 0 ? 1 : (extra > 0 ? 1 + share[at] : 1 - share[at]);
                    for (int copy = 0; copy < copies; copy++) {
                        order.add(index);
                    }
                }
            }
            sources.add(order);
            made[axis] = order.size();
        }
        Map<BlockPos, BlockState> grown = new LinkedHashMap<>();
        Map<BlockPos, StructurePayload> payload = new LinkedHashMap<>();
        for (int y = 0; y < made[1]; y++) {
            for (int z = 0; z < made[2]; z++) {
                for (int x = 0; x < made[0]; x++) {
                    BlockPos source = new BlockPos(sources.get(0).get(x), sources.get(1).get(y), sources.get(2).get(z));
                    BlockState state = cells.get(source);
                    if (state != null) {
                        BlockPos at = new BlockPos(x, y, z);
                        grown.put(at, state);
                        if (payloads.containsKey(source)) payload.put(at, payloads.get(source));
                    }
                }
            }
        }
        return new Piece(grown, new Vec3i(made[0], made[1], made[2]), List.of(), payload);
    }

    Piece tiledTo(int[] room) {
        Map<BlockPos, BlockState> laid = new LinkedHashMap<>();
        Map<BlockPos, StructurePayload> payload = new LinkedHashMap<>();
        for (int y = 0; y < room[1]; y++) {
            for (int z = 0; z < room[2]; z++) {
                for (int x = 0; x < room[0]; x++) {
                    BlockPos source = new BlockPos(Math.floorMod(x, span(0)), Math.floorMod(y, span(1)),
                        Math.floorMod(z, span(2)));
                    BlockState state = cells.get(source);
                    if (state != null) {
                        BlockPos at = new BlockPos(x, y, z);
                        laid.put(at, state);
                        if (payloads.containsKey(source)) payload.put(at, payloads.get(source));
                    }
                }
            }
        }
        return new Piece(laid, new Vec3i(room[0], room[1], room[2]), List.of(), payload);
    }

    Map<BlockPos, BlockState> laidInto(Scope scope, int[] offset) {
        Rotation turn = turnTo(scope);
        Map<BlockPos, BlockState> laid = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> cell : cells.entrySet()) {
            BlockPos at = cell.getKey();
            BlockState state = cell.getValue();
            if (scope.mirrored()) {
                state = state.mirror(Mirror.FRONT_BACK);
            }
            laid.put(scope.cell(at.getX() + offset[0], at.getY() + offset[1], at.getZ() + offset[2]),
                state.rotate(turn));
        }
        return laid;
    }

    Map<BlockPos, StructurePayload> payloadsInto(Scope scope, int[] offset) {
        Map<BlockPos, StructurePayload> laid = new LinkedHashMap<>();
        payloads.forEach((at, payload) -> {
            BlockState state = cells.get(at);
            // Pieces count rows towards their front; Minecraft counts Z towards the south.
            StructurePayload facing = StructureCompat.transform(state, payload, Mirror.LEFT_RIGHT, Rotation.NONE);
            if (scope.mirrored()) {
                facing = StructureCompat.transform(state, facing, Mirror.FRONT_BACK, Rotation.NONE);
            }
            laid.put(scope.cell(at.getX() + offset[0], at.getY() + offset[1], at.getZ() + offset[2]),
                StructureCompat.transform(state, facing, Mirror.NONE, turnTo(scope)));
        });
        return Map.copyOf(laid);
    }

    private static Rotation turnTo(Scope scope) {
        return switch (Frame.compass(scope.forward())) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    @Override
    public boolean isImmutable() {
        return true;
    }

    @StarlarkBuiltin(name = "variants", doc = "Pieces of one kind at several sizes.")
    public record Variants(List<Piece> pieces) implements StarlarkValue {

        public Variants {
            pieces = List.copyOf(pieces);
            if (pieces.isEmpty()) {
                throw new IllegalArgumentException("variants holds at least one piece");
            }
        }

        @Override
        public boolean isImmutable() {
            return true;
        }
    }
}
