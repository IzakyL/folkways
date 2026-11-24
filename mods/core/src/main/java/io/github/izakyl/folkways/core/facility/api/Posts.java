package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Server-thread resolution, staffing requests and occupancy of duty places. */
public final class Posts {

    @FunctionalInterface
    public interface Source {
        Collection<Post> resolve(ServerLevel level, Colony colony);
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    private final Colony colony;
    private final Map<PostKey, Duty> duties = new HashMap<>();
    private final Map<PostKey, Integer> requests = new HashMap<>();
    private final Map<Object, Duty> leases = new HashMap<>();

    Posts(Colony colony) {
        this.colony = colony;
    }

    public static Optional<Posts> of(Colony colony) {
        return DefinedFacilities.of(colony).map(DefinedFacilities::posts);
    }

    public static void register(Source source) {
        SOURCES.add(source);
    }

    public List<Post> resolve(ServerLevel level, FacilityDefinition facility) {
        List<Post> posts = new ArrayList<>(declared(level, facility));
        SOURCES.forEach(source -> posts.addAll(source.resolve(level, colony)));
        return List.copyOf(posts);
    }

    static List<Post> declared(ServerLevel level, FacilityDefinition facility) {
        if (facility.posts().isEmpty()) {
            return List.of();
        }
        var controls = facility.resolve(level);
        return facility.posts().entrySet().stream().sorted(Map.Entry.comparingByKey()).flatMap(entry -> {
            var definition = entry.getValue();
            var refs = definition.controls().stream().map(controls::get).filter(java.util.Objects::nonNull)
                .map(Control::ref).collect(Collectors.toUnmodifiableSet());
            return definition.resolve(level, facility, entry.getKey(), refs).stream();
        }).toList();
    }

    public Optional<Worker> occupant(ServerLevel level, Post post) {
        return occupant(level, post.key(), post.station().leaseKey(post))
            .filter(worker -> post.station().present(level, post, worker.body()));
    }

    public Optional<Worker> occupant(ServerLevel level, PostKey key, Object leaseKey) {
        var duty = leases.get(leaseKey);
        if (duty == null || !duty.post.key().equals(key) || !duty.ready(level) || colony.isSuspended(duty.id())) {
            return Optional.empty();
        }
        return Optional.ofNullable(duty.occupant).filter(worker -> duty.retained.stream().allMatch(Lease::open)
            && worker.body().isAlive() && worker.body().level() == level
            && duty.post.station().present(level, duty.post, worker.body()));
    }

    public boolean staffed(ServerLevel level, Post post) {
        return occupant(level, post).isPresent();
    }

    public Optional<Post> coveringPost(ServerLevel level, FacilityDefinition facility, ControlRef control) {
        return resolve(level, facility).stream().filter(post -> post.covers(level, control))
            .filter(post -> staffed(level, post)).findFirst();
    }

    /** The request remains active until its caller runs the returned release action. */
    public Runnable request(PostKey key) {
        requests.merge(key, 1, Integer::sum);
        return new Runnable() {
            private boolean open = true;

            @Override
            public void run() {
                if (open) {
                    requests.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
                    open = false;
                }
            }
        };
    }

    public Node duty(Post post, BooleanSupplier demanded) {
        return new Duty(post, demanded);
    }

    /** Adapters supplying urgent crew work can offer the same duty through a resident's urges. */
    public Urge urge(Post post, BooleanSupplier demanded) {
        var demand = post.demand();
        return urge(post, demanded, post.key().source(), demand.urgent() ? Urge.DIRE : Urge.NOW,
            demand.urgent(), demand.urgent() ? Disposition.END : Disposition.SUSPEND);
    }

    public Urge urge(Post post, BooleanSupplier demanded, ResourceLocation intent,
            double weight, boolean preempts, Disposition disposition) {
        var demand = post.demand();
        return new Urge(intent, weight, preempts, () -> duty(post, demanded), demand.priority(),
            false, demand.urgent(), disposition);
    }

    /** Release a station that its incumbent has left while the runner still owns the duty. */
    public void release(Post post) {
        Duty duty = leases.get(post.station().leaseKey(post));
        if (duty != null && duty.post.key().equals(post.key())) {
            duty.release();
        }
    }

    void refresh(Collection<ServerLevel> levels, Map<ServerLevel, List<Post>> definitions) {
        Map<PostKey, Post> desired = new LinkedHashMap<>();
        Map<PostKey, ServerLevel> worlds = new HashMap<>();
        for (ServerLevel level : levels) {
            List<Post> found = new ArrayList<>(definitions.getOrDefault(level, List.of()));
            SOURCES.forEach(source -> found.addAll(source.resolve(level, colony)));
            for (Post post : found) {
                if (post.demand().staffing() == Post.Staffing.STANDING || requests.containsKey(post.key())) {
                    desired.putIfAbsent(post.key(), post);
                    worlds.putIfAbsent(post.key(), level);
                }
            }
        }
        for (Duty duty : List.copyOf(duties.values())) {
            var next = desired.get(duty.post.key());
            if (next == null || !next.stance().equals(duty.post.stance())
                    || worlds.get(next.key()) != duty.level) {
                withdraw(duty.post.key());
            } else {
                duty.post = next;
            }
        }
        desired.forEach((key, post) -> {
            if (!post.demand().urgent()) {
                offer(worlds.get(key), post);
            }
        });
    }

    /** Publish work immediately when a source is notified of a station loading. */
    public UUID offer(ServerLevel level, Post post) {
        var key = post.key();
        Duty existing = duties.get(key);
        if (existing != null) {
            return existing.id();
        }
        Duty duty = new Duty(post, () -> duties.containsKey(key));
        duty.level = level;
        duties.put(key, duty);
        colony.submit(duty.spec.owner(), level.dimension(), Grown.of(duty), (id, ending) ->
            level.getServer().execute(() -> duties.remove(key, duty)));
        return duty.id();
    }

    public void withdraw(PostKey key) {
        Duty duty = duties.remove(key);
        if (duty != null) {
            duty.close();
            colony.withdraw(duty.spec.owner(), duty.id());
        }
    }

    void close() {
        duties.values().forEach(Duty::close);
        duties.clear();
        requests.clear();
    }

    private final class Duty implements Node, Workload.Continuous {

        private volatile Post post;
        private ServerLevel level;
        private final BooleanSupplier demanded;
        private final NodeSpec spec;
        private final List<Lease> retained = new ArrayList<>();
        private Object key;
        private Worker occupant;
        private boolean closed;
        private boolean staffed;
        private int boardingTicks;

        Duty(Post post, BooleanSupplier demanded) {
            this.post = post;
            this.demanded = demanded;
            var builder = NodeSpec.of(UUID.randomUUID(), post.key().source(), post.site(), post.station().approach(post), this)
                .effort(WorkEffort.NONE);
            post.station().describe(builder);
            if (post.key().source().equals(DefinedFacilities.ID)) {
                builder.doing(Doings.OPERATING, Optional.of(post.key().name()));
            }
            spec = builder.done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public Optional<UUID> worker() {
            return post.station().worker();
        }

        @Override
        public boolean ready(ServerLevel world) {
            return !closed && (level == null || level == world) && demanded.getAsBoolean()
                && (occupant != null || post.station().available(world, post));
        }

        @Override
        public boolean admits(ServerLevel world, Mob body) {
            var held = leases.get(post.station().leaseKey(post));
            return (held == null || held == this) && post.station().admits(body);
        }

        @Override
        public boolean directTravel() {
            return post.station().directTravel();
        }

        @Override
        public boolean mountedWork() {
            return true;
        }

        @Override
        public boolean staysAtWorkSite() {
            return true;
        }

        @Override
        public boolean arrived(ServerLevel world, Body body, Stances footing, boolean atStance) {
            if (occupant == null) {
                return atStance;
            }
            return (atStance || body.mob().isPassenger()) && post.station().present(world, post, body.mob());
        }

        @Override
        public boolean holds(ServerLevel world, Worker worker) {
            if (!ready(world) || !admits(world, worker.body())) {
                return false;
            }
            if (occupant != null) {
                if (!post.station().present(world, post, worker.body())
                        || retained.stream().anyMatch(lease -> !lease.open())) {
                    return false;
                }
                work(world, worker);
                return true;
            }
            if (++boardingTicks <= post.station().boardingTicks(worker)) {
                return true;
            }
            key = post.station().leaseKey(post);
            leases.put(key, this);
            var boarded = post.station().board(world, post, worker.body());
            if (boarded != Post.Boarding.SEATED) {
                leases.remove(key, this);
                return boarded == Post.Boarding.WAITING;
            }
            occupant = worker;
            for (var ref : post.station().retainedControls()) {
                var lease = Controls.resolve(world, ref).flatMap(control -> Lease.take(control, worker));
                if (lease.isEmpty()) {
                    release();
                    return false;
                }
                retained.add(lease.get());
            }
            staffed = true;
            work(world, worker);
            return true;
        }

        private void work(ServerLevel world, Worker worker) {
            if (retained.isEmpty()) {
                post.station().additionalLease(world, worker).ifPresent(retained::add);
            }
            post.station().onDuty(world, worker, List.copyOf(retained));
        }

        @Override
        public Outcome commit(ServerLevel world, Worker worker) {
            return post.station().finish(worker, staffed, !closed && demanded.getAsBoolean());
        }

        @Override
        public void released(ServerLevel world, Worker worker, Ending ending) {
            release();
        }

        @Override
        public void ended(ServerLevel world, Ending ending) {
            close();
        }

        private void close() {
            closed = true;
            release();
        }

        private void release() {
            boardingTicks = 0;
            leases.remove(key, this);
            retained.forEach(Lease::close);
            retained.clear();
            if (occupant != null) {
                post.station().leave(occupant.body());
                if (post.station().dismountOnRelease() && post.stance() instanceof Stances.Seat seat) {
                    io.github.izakyl.folkways.core.work.api.Seating.rise(occupant.body(), seat);
                }
                occupant = null;
            }
        }
    }
}
