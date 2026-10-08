package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.Locomotion;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

public final class Trip implements Conveyance {

    private static final double PASSING_REACH = 2.5D;

    private static final double PASSING_RISE = 1.5D;

    private record Stage(Conveyance carrying, Set<WorldPos> to, Optional<WorldPos> from, boolean passing) {
    }

    private final List<Stage> stages;
    private int at;
    private long setOutAt = -1L;

    private Trip(List<Stage> stages) {
        this.stages = stages;
    }

    public static Optional<Trip> afoot(Locomotion moving, Set<WorldPos> footings, double speed,
            RefusalKind lost) {
        return footings.isEmpty()
            ? Optional.empty()
            : Optional.of(new Trip(List.of(new Stage(moving.along(speed, lost), footings, Optional.empty(), false))));
    }

    public static Optional<Trip> to(Supplier<Ways> ways, Resident who, Locomotion moving, Mob body,
            Set<WorldPos> footings, double speed, RefusalKind lost) {
        return to(ways, who, moving, body, footings, speed, lost, Urgency.WALKING);
    }

    public static Optional<Trip> to(Supplier<Ways> ways, Resident who, Locomotion moving, Mob body,
            Set<WorldPos> footings, double speed, RefusalKind lost, Urgency urgency) {
        if (footings.isEmpty()) {
            return Optional.empty();
        }
        return switch (ways.get().journey(who, Feet.of(body), footings, urgency)) {
            case Faring.Yes(Journey journey) -> following(journey, moving, footings, speed, lost);
            case Faring.Later ignored -> Optional.of(new Trip(List.of(new Stage(
                new Awaiting(ways, who, moving, speed, lost, urgency), footings, Optional.empty(), false))));
            case Faring.No ignored -> Optional.empty();
        };
    }

    private static Optional<Trip> following(Journey best, Locomotion moving,
            Set<WorldPos> footings, double speed, RefusalKind lost) {
        List<Leg> legs = ridden(best.legs());
        List<Stage> stages = new ArrayList<>(legs.size());
        for (int leg = 0; leg < legs.size(); leg++) {
            Leg going = legs.get(leg);
            Set<WorldPos> to = leg == legs.size() - 1 ? footings : going.to();
            Optional<Hop> hop = going.hop();
            if (hop.isEmpty()) {
                Conveyance walking = moving.along(speed, lost);
                stages.add(new Stage(going.passing() ? new Passing(walking) : walking, to, going.from(),
                    going.passing()));
                continue;
            }
            Optional<Passage> ran = Hops.laid(hop.get());
            if (ran.isEmpty()) {

                return Optional.empty();
            }
            // A ride is steered by where the plan goes after it, so the conveyance can set down at the side that leads on.
            Set<WorldPos> onward = leg + 1 >= legs.size() - 1 ? footings : legs.get(leg + 1).to();
            stages.add(new Stage(ran.get().aboard(hop.get()), onward, Optional.empty(), false));
        }
        return Optional.of(new Trip(stages));
    }

    private record Passing(Conveyance walking) implements Conveyance {

        @Override
        public void setOut(ServerLevel level, Mob body, Set<WorldPos> to) {
            walking.setOut(level, body, to);
        }

        @Override
        public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
            for (WorldPos cell : to) {
                Optional<Vec3> feet = WorldSpaces.relative(level, cell.realm(), body.position());
                if (feet.isEmpty()) {
                    continue;
                }
                Vec3 centre = Vec3.atBottomCenterOf(cell.cell());
                double across = Math.hypot(feet.get().x - centre.x, feet.get().z - centre.z);
                if (across <= PASSING_REACH && Math.abs(feet.get().y - centre.y) <= PASSING_RISE) {
                    return Going.ARRIVED;
                }
            }
            return walking.step(level, body, to);
        }

        @Override
        public Optional<Doing> doing() {
            return walking.doing();
        }

        @Override
        public void release(ServerLevel level, Mob body) {
            walking.release(level, body);
        }
    }

    private static final class Awaiting implements Conveyance {

        private final Supplier<Ways> ways;
        private final Resident who;
        private final Locomotion moving;
        private final double speed;
        private final RefusalKind lost;
        private final Urgency urgency;
        private Trip resolved;

        private Awaiting(Supplier<Ways> ways, Resident who, Locomotion moving,
                double speed, RefusalKind lost, Urgency urgency) {
            this.ways = ways;
            this.who = who;
            this.moving = moving;
            this.speed = speed;
            this.lost = lost;
            this.urgency = urgency;
        }

        @Override
        public void setOut(ServerLevel level, Mob body, Set<WorldPos> to) {
            body.getNavigation().stop();
        }

        @Override
        public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
            if (resolved == null) {
                switch (ways.get().journey(who, Feet.of(body), to, urgency)) {
                    case Faring.Later ignored -> { return Going.UNDERWAY; }
                    case Faring.No ignored -> { return new Going.Failed(lost); }
                    case Faring.Yes(Journey journey) -> {
                        resolved = following(journey, moving, to, speed, lost).orElse(null);
                        if (resolved == null) {
                            return new Going.Failed(lost);
                        }
                        resolved.setOut(level, body, to);
                    }
                }
            }
            return resolved.step(level, body, to);
        }

        @Override
        public Optional<Doing> doing() {
            return resolved == null ? Optional.of(Doing.open(Doings.WAITING)) : resolved.doing();
        }

        @Override
        public void release(ServerLevel level, Mob body) {
            if (resolved != null) {
                resolved.release(level, body);
            }
        }
    }

    static List<Leg> ridden(List<Leg> legs) {
        List<Leg> folded = new ArrayList<>();
        for (Leg leg : legs) {
            Optional<Hop> hop = leg.hop();
            if (hop.isEmpty()) {
                folded.add(leg);
                continue;
            }
            int last = lastRidden(folded);
            if (last < 0 || !through(folded.get(last), hop.get())) {
                folded.add(leg);
                continue;
            }
            Hop carrying = folded.get(last).hop().orElseThrow();
            Hop joined = Hops.laid(carrying).orElseThrow().joined(carrying, hop.get());
            folded.subList(last, folded.size()).clear();
            folded.add(Leg.aboard(joined));
        }
        return folded;
    }

    private static int lastRidden(List<Leg> folded) {
        for (int at = folded.size() - 1; at >= 0; at--) {
            if (folded.get(at).hop().isPresent()) {
                return at;
            }
        }
        return -1;
    }

    private static boolean through(Leg riding, Hop next) {
        Hop first = riding.hop().orElseThrow();
        return first.service().equals(next.service())
            && Hops.laid(first).map(passage -> passage.through(first, next)).orElse(false);
    }

    @Override
    public void setOut(ServerLevel level, Mob body, Set<WorldPos> to) {
        Stage stage = stages.get(at);
        setOutAt = level.getGameTime();
        stage.carrying().setOut(level, body, at == stages.size() - 1 ? to : stage.to());
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        while (at < stages.size()) {
            Stage stage = stages.get(at);
            Set<WorldPos> toward = at == stages.size() - 1 ? to : stage.to();
            Going going = stage.carrying().step(level, body, toward);
            if (going instanceof Going.Failed && stage.from().isPresent()) {
                for (WorldPos goal : toward) {
                    ColonyWays.wrongEdge(level, stage.from().get(), goal);
                }
            }
            if (!(going instanceof Going.Arrived)) {
                return going;
            }
            if (stage.passing() && stage.from().isPresent() && setOutAt >= 0L) {
                ColonyWays.walkedEdge(level, stage.from().get(), toward.iterator().next(),
                    (int) Math.min(Integer.MAX_VALUE, level.getGameTime() - setOutAt));
            }
            stage.carrying().release(level, body);
            at++;
            if (at < stages.size()) {
                setOut(level, body, to);
            }
        }
        return Going.ARRIVED;
    }

    @Override
    public Optional<Doing> doing() {
        return at < stages.size() ? stages.get(at).carrying().doing() : Optional.empty();
    }

    @Override
    public void release(ServerLevel level, Mob body) {
        if (at < stages.size()) {
            stages.get(at).carrying().release(level, body);
        }
    }
}
