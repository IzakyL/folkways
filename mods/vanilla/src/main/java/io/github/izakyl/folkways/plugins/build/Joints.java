package io.github.izakyl.folkways.plugins.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

public final class Joints {

    public record Joining(boolean joined, List<ItemStack> left) {

        public Joining {
            left = List.copyOf(left);
        }
    }

    public interface Joiner {

        Optional<List<ItemStack>> cost(ServerLevel level, BlockPos from, BlockPos to);

        boolean joined(Level level, BlockPos from, BlockPos to);

        Joining join(ServerLevel level, BlockPos from, BlockPos to, List<ItemStack> paid);

        List<ItemStack> loosen(ServerLevel level, BlockPos cell);
    }

    private static final Joiner NONE = new Joiner() {
        @Override
        public Optional<List<ItemStack>> cost(ServerLevel level, BlockPos from, BlockPos to) {
            return Optional.empty();
        }

        @Override
        public boolean joined(Level level, BlockPos from, BlockPos to) {
            return false;
        }

        @Override
        public Joining join(ServerLevel level, BlockPos from, BlockPos to, List<ItemStack> paid) {
            return new Joining(false, paid);
        }

        @Override
        public List<ItemStack> loosen(ServerLevel level, BlockPos cell) {
            return List.of();
        }
    };

    private static volatile Joiner joiner = NONE;

    private Joints() {
    }

    public static void install(Joiner installed) {
        joiner = installed;
    }

    public static Joiner joiner() {
        return joiner;
    }

    public static boolean installed() {
        return joiner != NONE;
    }

    static Map<BlockPos, BlockState> sketch(BlockPos from, BlockState start, BlockPos to, BlockState end) {
        Optional<Vec3> out = heading(start);
        Optional<Vec3> in = heading(end);
        Map<BlockPos, BlockState> drawn = new LinkedHashMap<>();
        if (out.isEmpty() || in.isEmpty()) {
            return drawn;
        }
        Vec3 first = Vec3.atBottomCenterOf(from);
        Vec3 last = Vec3.atBottomCenterOf(to);
        Vec3 toward = last.subtract(first);
        Vec3 leave = out.get().dot(toward) < 0 ? out.get().reverse() : out.get();
        Vec3 arrive = in.get().dot(toward) > 0 ? in.get().reverse() : in.get();
        double handle = toward.length() / 3.0D;
        Vec3 pull = first.add(leave.scale(handle));
        Vec3 push = last.add(arrive.scale(handle));
        int samples = (int) Math.ceil(toward.length() * 3.0D);
        for (int step = 1; step < samples; step++) {
            double t = (double) step / samples;
            double u = 1.0D - t;
            Vec3 at = first.scale(u * u * u).add(pull.scale(3 * u * u * t)).add(push.scale(3 * u * t * t))
                .add(last.scale(t * t * t));
            Vec3 along = pull.subtract(first).scale(3 * u * u).add(push.subtract(pull).scale(6 * u * t))
                .add(last.subtract(push).scale(3 * t * t));
            BlockPos cell = BlockPos.containing(at.x, Math.floor(at.y + 0.25D), at.z);
            if (!cell.equals(from) && !cell.equals(to)) {
                drawn.putIfAbsent(cell, shaped(start, along));
            }
        }
        return drawn;
    }

    private static Optional<Vec3> heading(BlockState state) {
        return shapeOf(state).map(shape -> switch (shape) {
            case "zo" -> new Vec3(0, 0, 1);
            case "xo" -> new Vec3(1, 0, 0);
            case "pd" -> new Vec3(1, 0, 1).normalize();
            case "nd" -> new Vec3(-1, 0, 1).normalize();
            default -> null;
        });
    }

    private static Optional<String> shapeOf(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("shape")) {
                return Optional.of(named(state, property));
            }
        }
        return Optional.empty();
    }

    private static <T extends Comparable<T>> String named(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static BlockState shaped(BlockState state, Vec3 along) {
        double x = Math.abs(along.x);
        double z = Math.abs(along.z);
        String shape = x > 2 * z ? "xo" : z > 2 * x ? "zo" : along.x * along.z > 0 ? "pd" : "nd";
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("shape")) {
                return with(state, property, shape);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(found -> state.setValue(property, found)).orElse(state);
    }
}
