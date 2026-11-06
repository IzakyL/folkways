package io.github.izakyl.folkways.carts;

import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.PathView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

// A cart line as drawn: every drawn point is a station, and the cart is drawn along the line through marks a few
// blocks apart, so it keeps to the way drawn. An open line runs out and back, a closed one round and round.
record Line(UUID id, ResourceKey<Level> dimension, Kind kind, boolean loop, List<Mark> course, int stations) {

    // How far apart the marks the cart is drawn through lie at most.
    static final int SPACING = 6;

    // How long the cart stands at each station.
    static final int DWELL_TICKS = 200;

    private static final int[] RISE = {1, 0, 2, -1, 3, -2, 4, -3};

    enum Kind {
        HAND("hand_cart"),
        SUPPLY("supply_cart");

        private final String path;

        Kind(String path) {
            this.path = path;
        }

        ResourceLocation entity() {
            return ResourceLocation.fromNamespaceAndPath(CartLinks.ASTIKOR, path);
        }

        // Each wood makes its own item of this kind of cart; any of them will do.
        List<Item> items() {
            List<Item> found = new ArrayList<>();
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (id.getNamespace().equals(CartLinks.ASTIKOR) && id.getPath().endsWith("_" + path)) {
                    found.add(BuiltInRegistries.ITEM.get(id));
                }
            }
            return List.copyOf(found);
        }

        boolean is(Entity cart) {
            return BuiltInRegistries.ENTITY_TYPE.getKey(cart.getType()).equals(entity());
        }

        static Optional<Kind> of(ResourceLocation entity) {
            for (Kind kind : values()) {
                if (kind.entity().equals(entity)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    // One place the cart is drawn to, and the station it is, if it is one.
    record Mark(WorldPos at, Stances stances, int station) {

        boolean isStation() {
            return station >= 0;
        }
    }

    Line {
        course = List.copyOf(course);
    }

    static Optional<Line> of(PathView path, ServerLevel level) {
        if (!path.delegation().equals(FolkwaysCartsMod.LINE) || !path.dimension().equals(level.dimension())
                || path.points().size() < 2) {
            return Optional.empty();
        }
        Optional<Kind> kind = Kind.of(path.settings().choice(FolkwaysCartsMod.CART.key()));
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        boolean loop = path.settings().choice(FolkwaysCartsMod.ROUND.key()).equals(FolkwaysCartsMod.CLOSED)
            && path.points().size() > 2;
        List<BlockPos> points = path.points();
        List<Mark> course = new ArrayList<>();
        int legs = loop ? points.size() : points.size() - 1;
        for (int at = 0; at <= legs; at++) {
            BlockPos point = points.get(at % points.size());
            if (at == 0) {
                mark(level, point, 0).ifPresent(course::add);
                continue;
            }
            BlockPos from = points.get(at - 1);
            double length = Math.sqrt(from.distSqr(point));
            int steps = Math.max(1, Mth.ceil(length / SPACING));
            for (int step = 1; step <= steps; step++) {
                double part = (double) step / steps;
                BlockPos between = BlockPos.containing(Mth.lerp(part, from.getX(), point.getX()) + 0.5,
                    Mth.lerp(part, from.getY(), point.getY()) + 0.5, Mth.lerp(part, from.getZ(), point.getZ()) + 0.5);
                boolean station = step == steps && !(loop && at == legs);
                mark(level, between, station ? at : -1).ifPresent(mark -> {
                    if (course.isEmpty() || !course.getLast().at().equals(mark.at())) {
                        course.add(mark);
                    }
                });
            }
        }
        // A loop comes back round to its first station, which begins the course already.
        if (loop && course.size() > 1 && course.getLast().at().equals(course.getFirst().at())) {
            course.removeLast();
        }
        long stations = course.stream().filter(Mark::isStation).count();
        if (stations < 2) {
            return Optional.empty();
        }
        return Optional.of(new Line(path.id(), level.dimension(), kind.get(), loop, course, points.size()));
    }

    // The marks of one lap in the order the cart is drawn through them. An open line's laps run out and back in
    // turn, each beginning where the last ended; a loop's laps all begin at its first station.
    List<Mark> lap(int number) {
        if (loop) {
            return course;
        }
        List<Mark> ordered = new ArrayList<>(course);
        if (number % 2 == 1) {
            Collections.reverse(ordered);
        }
        return number == 0 ? ordered : ordered.subList(1, ordered.size());
    }

    Optional<Mark> station(int station) {
        return course.stream().filter(mark -> mark.station() == station).findFirst();
    }

    // How long the cart takes from one station to another, going the way it runs, stops on the way included; empty
    // when it never runs that way.
    long ride(int from, int to) {
        if (from == to) {
            return -1;
        }
        List<Mark> way = new ArrayList<>(course);
        if (loop) {
            way.addAll(course);
        } else if (indexOf(course, from) > indexOf(course, to)) {
            Collections.reverse(way);
        }
        int start = indexOf(way, from);
        if (start < 0) {
            return -1;
        }
        double blocks = 0;
        long stops = 0;
        for (int at = start + 1; at < way.size(); at++) {
            blocks += Math.sqrt(way.get(at - 1).at().cell().distSqr(way.get(at).at().cell()));
            if (way.get(at).station() == to) {
                return (long) Math.ceil(blocks / Gait.BLOCKS_PER_TICK) + stops * DWELL_TICKS;
            }
            if (way.get(at).isStation()) {
                stops++;
            }
        }
        return -1;
    }

    // How long a round of the whole line takes: out and back for an open line, once round for a loop.
    long cycle() {
        double blocks = 0;
        for (int at = 1; at < course.size(); at++) {
            blocks += Math.sqrt(course.get(at - 1).at().cell().distSqr(course.get(at).at().cell()));
        }
        long stops = course.stream().filter(Mark::isStation).count();
        if (loop) {
            blocks += Math.sqrt(course.getLast().at().cell().distSqr(course.getFirst().at().cell()));
            return (long) Math.ceil(blocks / Gait.BLOCKS_PER_TICK) + stops * DWELL_TICKS;
        }
        return 2 * ((long) Math.ceil(blocks / Gait.BLOCKS_PER_TICK) + stops * DWELL_TICKS);
    }

    private static int indexOf(List<Mark> marks, int station) {
        for (int at = 0; at < marks.size(); at++) {
            if (marks.get(at).station() == station) {
                return at;
            }
        }
        return -1;
    }

    private static Optional<Mark> mark(ServerLevel level, BlockPos near, int station) {
        for (int rise : RISE) {
            BlockPos feet = near.above(rise);
            if (level.isLoaded(feet) && Footing.withFeetAt(level, feet)) {
                // Goods are handled beside the station, with several cells available while the cart stops.
                Stances stances = station < 0 ? Stances.at(level, feet)
                    : Stances.of(level, Reach.workableCells(level, feet).stream()
                        .filter(cell -> !cell.equals(feet)).toList()).orElse(Stances.at(level, feet));
                return Optional.of(new Mark(WorldPos.of(level, feet.immutable()), stances, station));
            }
        }
        return Optional.empty();
    }
}
