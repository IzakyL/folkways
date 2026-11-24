package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.ReservationPool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Runs the direct portions of a facility graph on the server thread. */
final class PostRuns {

    private static final int STAFFING_PATIENCE = 600;
    private static final int SETTLE_PATIENCE = 200;

    private final Colony colony;
    private final Posts posts;
    private final Map<UUID, Execution> active = new LinkedHashMap<>();

    PostRuns(Colony colony, Posts posts) {
        this.colony = colony;
        this.posts = posts;
    }

    boolean start(ServerLevel level, FacilityDefinition facility, UUID id, ResourceLocation owner, Grown graph,
            boolean resumed, BiConsumer<UUID, Ending> ended) {
        return start(level, facility, id, owner, graph, null, resumed, false, false, ended);
    }

    boolean start(ServerLevel level, FacilityDefinition facility, ScriptBatch batch, boolean resumed, boolean ride,
            BiConsumer<UUID, Ending> ended) {
        return batch.holds().isEmpty() && start(level, facility, batch.id(), DefinedFacilities.ID, batch.graph(),
            batch.reservations(), resumed, ride, false, ended);
    }

    boolean startStaffedCollection(ServerLevel level, FacilityDefinition facility, ScriptBatch batch,
            boolean resumed, boolean ride, BiConsumer<UUID, Ending> ended) {
        return batch.holds().isEmpty() && start(level, facility, batch.id(), DefinedFacilities.ID, batch.graph(),
            batch.reservations(), resumed, ride, true, ended);
    }

    private boolean start(ServerLevel level, FacilityDefinition facility, UUID id, ResourceLocation owner, Grown graph,
            ReservationPool reservations, boolean resumed, boolean ride, boolean staffedCollection,
            BiConsumer<UUID, Ending> ended) {
        List<Post> resolved = posts.resolve(level, facility);
        boolean direct = ride;
        Map<UUID, Action> collections = new HashMap<>();
        for (Node node : graph.nodes()) {
            if (node instanceof WriteSequence.Sequence sequence) {
                sequence.validate(level, resolved);
            }
            Action action = choose(level, resolved, facility, node, resumed);
            if (staffedCollection && node instanceof ItemCollection) {
                if (action == null) {
                    return false;
                }
                collections.put(node.id(), action);
            }
            direct |= action != null;
        }
        if (!direct || !graph.workerGroups().isEmpty() || !graph.follows().isEmpty()) {
            return false;
        }
        var execution = new Execution(level, facility, id, owner, graph, reservations, resumed, ride, ended);
        execution.writing.putAll(collections);
        active.put(id, execution);
        return true;
    }

    void tick() {
        List.copyOf(active.values()).forEach(Execution::tick);
    }

    boolean cancel(UUID id) {
        var execution = active.remove(id);
        if (execution != null) {
            execution.close();
        }
        return execution != null;
    }

    void close() {
        List.copyOf(active.keySet()).forEach(this::cancel);
    }

    private Action choose(ServerLevel level, List<Post> resolved, FacilityDefinition facility,
            Node node, boolean resumed) {
        if (node instanceof WriteSequence.Sequence sequence) {
            var covering = WriteSequence.covering(level, resolved, sequence.writes());
            var post = covering.stream().filter(candidate -> posts.staffed(level, candidate)).findFirst()
                .or(() -> covering.stream()
                    // A restored script can reach this leg before its standing post has been staffed again.
                    .filter(candidate -> resumed || !sequence.local()
                        || candidate.demand().staffing() == Post.Staffing.ON_DEMAND)
                    .findFirst());
            return post.map(place -> new Action(place.key(), sequence.clock())).orElse(null);
        }
        PostTransfer transfer = switch (node) {
            case ItemDeposit deposit -> deposit.transfer();
            case ItemCollection collection -> collection.transfer();
            default -> null;
        };
        if (transfer != null) {
            return resolved.stream().filter(post -> transfer.controls().stream()
                    .allMatch(ref -> post.covers(level, ref)) && posts.staffed(level, post))
                .findFirst().map(post -> new Action(post.key(), transfer)).orElse(null);
        }
        if (resolved.isEmpty() || !(node instanceof SignalWrite write)) {
            return null;
        }
        var ref = write.step().ref();
        // Later stages may name a carried control at its predicted position.
        var current = facility.resolve(level).get(write.partName());
        var required = current instanceof Signal signal
            ? signal.requiredPost(level) : WriteSequence.requiredPost(level, ref);
        return resolved.stream().filter(post -> post.covers(level, ref)
                || current != null && post.covers(level, current.ref()))
            .filter(post -> required.map(post.key()::equals).orElseGet(() -> posts.staffed(level, post)))
            .findFirst()
            .map(post -> new Action(post.key(), write)).orElse(null);
    }

    private final class Execution {
        private final ServerLevel level;
        private final FacilityDefinition facility;
        private final UUID id;
        private final ResourceLocation owner;
        private final Grown graph;
        private final ReservationPool reservations;
        private final boolean resumed;
        private final boolean ride;
        private final BiConsumer<UUID, Ending> ended;
        private final Set<UUID> done = new HashSet<>();
        private final Set<UUID> planned = new HashSet<>();
        private final Set<UUID> handoffs = new HashSet<>();
        private final Map<UUID, Action> writing = new HashMap<>();
        private final List<Action> written = new ArrayList<>();
        private final Map<UUID, Long> retrying = new HashMap<>();
        private final Map<UUID, Long> settling = new HashMap<>();

        Execution(ServerLevel level, FacilityDefinition facility, UUID id, ResourceLocation owner, Grown graph,
                ReservationPool reservations, boolean resumed, boolean ride, BiConsumer<UUID, Ending> ended) {
            this.level = level;
            this.facility = facility;
            this.id = id;
            this.owner = owner;
            this.graph = graph;
            this.reservations = reservations;
            this.resumed = resumed;
            this.ride = ride;
            this.ended = ended;
        }

        void tick() {
            if (!facility.extent().loaded(level)) {
                finish(new Ending.Failed(id, FacilityRefusal.GONE));
                return;
            }
            List<Post> resolved = posts.resolve(level, facility);
            for (Node node : graph.nodes()) {
                if (done.contains(node.id()) || planned.contains(node.id()) || !ready(node.id())) {
                    continue;
                }
                if (node instanceof Agreement agreement) {
                    if (agreement.agreed(level)) {
                        done.add(node.id());
                    } else if (level.getGameTime() - settling.computeIfAbsent(node.id(),
                            ignored -> level.getGameTime()) >= SETTLE_PATIENCE) {
                        finish(new Ending.Failed(node.id(), FacilityRefusal.UNSETTLED));
                        return;
                    }
                    continue;
                }
                if (ride && (node instanceof ScriptAwait || node instanceof SignalWrite write && write.remote())) {
                    if (!unattended(node)) {
                        return;
                    }
                    continue;
                }
                Action action = writing.computeIfAbsent(node.id(), ignored -> choose(level, resolved, facility, node, resumed));
                if (action == null) {
                    planned.add(node.id());
                    submit(node, this::childEnded);
                    continue;
                }
                if (action.retryAt > level.getGameTime()) {
                    continue;
                }
                if (action.transfer != null && action.transfer.from() == null && !action.handedOff) {
                    var occupant = action.occupant(level, resolved);
                    if (occupant.isPresent()) {
                        var worker = occupant.get();
                        var amount = action.transfer.wanted().getFirst();
                        long missing = action.transfer.missing(worker, amount, reservations, colony.reservedInputs(level));
                        if (reservations != null && reservations.remaining().stream().anyMatch(need ->
                                need.count() > 0 && Goods.overlap(need.spec(), amount.spec()))) {
                            missing = amount.count();
                        }
                        if (missing > 0) {
                            var post = action.post(level, resolved).orElseThrow();
                            action.retain(level, post);
                            var handoff = PostHandoff.to(level, owner, posts, post, worker,
                                new Need(amount.spec(), missing, true));
                            if (handoff.isEmpty()) {
                                finish(new Ending.Failed(node.id(), FacilityRefusal.POST_UNAVAILABLE));
                                return;
                            }
                            action.handedOff = true;
                            action.recipient = worker;
                            planned.add(node.id());
                            handoffs.add(handoff.get().id());
                            submit(handoff.get(), (child, ending) -> {
                                handoffs.remove(child);
                                planned.remove(node.id());
                                if (active.get(id) == this && !(ending instanceof Ending.Done)) {
                                    var why = ending instanceof Ending.Failed failed
                                        ? failed.why() : FacilityRefusal.POST_UNAVAILABLE;
                                    finish(new Ending.Failed(node.id(), why));
                                }
                            });
                            continue;
                        }
                    }
                }
                if (action.tick(level, resolved, reservations)) {
                    continue;
                }
                action.closeControls(level);
                if (action.failure == FacilityRefusal.BUSY && !action.sequence
                        && (action.pulse != null || action.transfer != null && node instanceof ItemDeposit
                            && action.transfer.wanted().getFirst().count() == 1)
                        && node.failing(level, action.failure) instanceof Recourse.Wait wait) {
                    action.retryAt = level.getGameTime() + wait.ticks();
                    action.failure = null;
                    continue;
                }
                writing.remove(node.id());
                written.add(action);
                if (action.failure != null) {
                    finish(new Ending.Failed(node.id(), action.failure));
                    return;
                }
                done.add(node.id());
            }
            if (done.size() == graph.nodes().size()) {
                finish(Ending.DONE);
            }
        }

        // A rider can be the only resident. Remote writes and reading checks need no other worker.
        private boolean unattended(Node node) {
            long now = level.getGameTime();
            if (retrying.getOrDefault(node.id(), now) > now) {
                return true;
            }
            Outcome result = node instanceof SignalWrite write ? write.writeRemote(level) : node.commit(level, null);
            if (result instanceof Outcome.Failed failure) {
                if (node.failing(level, failure.why()) instanceof Recourse.Wait wait) {
                    retrying.put(node.id(), now + wait.ticks());
                } else {
                    finish(new Ending.Failed(node.id(), failure.why()));
                    return false;
                }
            } else {
                done.add(node.id());
            }
            return true;
        }

        private void submit(Node node, BiConsumer<UUID, Ending> callback) {
            Grown child = Grown.of(node);
            Grown work = reservations == null ? child
                : Grown.of(new ScriptBatch(node.spec(), child, List.of(), reservations));
            colony.submit(owner, level.dimension(), work, (childId, ending) ->
                level.getServer().execute(() -> callback.accept(childId, ending)));
        }

        private boolean ready(UUID node) {
            for (Before link : graph.links()) {
                if (link.to().equals(node) && !done.contains(link.from())) {
                    return false;
                }
            }
            return true;
        }

        private void childEnded(UUID child, Ending ending) {
            if (active.get(id) != this) {
                return;
            }
            planned.remove(child);
            if (ending instanceof Ending.Done) {
                done.add(child);
            } else {
                finish(ending);
            }
        }

        private void finish(Ending ending) {
            active.remove(id);
            close();
            ended.accept(id, ending);
        }

        private void close() {
            writing.values().forEach(action -> action.close(level));
            // Keep the post through the closing agreement and any later stages in this run.
            written.forEach(action -> action.close(level));
            List.copyOf(planned).forEach(child -> colony.withdraw(owner, child));
            List.copyOf(handoffs).forEach(child -> colony.withdraw(owner, child));
        }
    }

    private final class Action {
        private final PostKey key;
        private final WriteSequence.Clock clock;
        private final boolean sequence;
        private final PostTransfer transfer;
        private final SignalWrite pulse;
        private boolean handedOff;
        private long retryAt;
        private Worker recipient;
        private long waiting = -1;
        private Runnable release = () -> { };
        private boolean started;
        private RefusalKind failure;

        Action(PostKey key, WriteSequence.Clock clock) {
            this.key = key;
            sequence = true;
            this.clock = clock;
            transfer = null;
            pulse = null;
        }

        Action(PostKey key, SignalWrite write) {
            this.key = key;
            sequence = false;
            transfer = null;
            pulse = write.simple() ? null : write;
            clock = new WriteSequence.Clock(List.of(write.step()), write.untilRead());
        }

        Action(PostKey key, PostTransfer transfer) {
            this.key = key;
            sequence = false;
            this.transfer = transfer;
            pulse = null;
            clock = null;
        }

        Optional<Post> post(ServerLevel level, List<Post> resolved) {
            var controls = transfer == null ? WriteSequence.controls(clock.writes) : transfer.controls();
            return resolved.stream().filter(candidate -> candidate.key().equals(key)
                && controls.stream().allMatch(ref -> candidate.covers(level, ref))).findFirst();
        }

        Optional<Worker> occupant(ServerLevel level, List<Post> resolved) {
            return post(level, resolved).flatMap(place -> posts.occupant(level, place));
        }

        void retain(ServerLevel level, Post post) {
            if (waiting < 0 && post.demand().staffing() == Post.Staffing.ON_DEMAND) {
                release = posts.request(key);
                waiting = level.getGameTime();
            }
        }

        boolean tick(ServerLevel level, List<Post> resolved, ReservationPool reservations) {
            var post = post(level, resolved);
            var occupant = post.flatMap(place -> posts.occupant(level, place));
            post.ifPresent(place -> retain(level, place));
            if (recipient != null && occupant.filter(worker -> worker.body() == recipient.body()).isEmpty()) {
                failure = FacilityRefusal.POST_UNAVAILABLE;
                return false;
            }
            if (occupant.isPresent()) {
                started = true;
                if (transfer != null) {
                    failure = transfer.move(level, occupant.get(), reservations, colony.reservedInputs(level));
                    return false;
                }
                if (pulse != null) {
                    if (pulse.directPressing(level, occupant.get())) {
                        return true;
                    }
                    if (pulse.commit(level, occupant.get()) instanceof
                            Outcome.Failed refused) {
                        failure = refused.why();
                    }
                    return false;
                }
                boolean more = clock.tick(level, occupant.get(), reservations, colony.reservedInputs(level));
                failure = clock.failure != null && sequence
                    ? new Routine.Failure(clock.failure.translationKey(), false) : clock.failure;
                return more;
            }
            if (started || post.isEmpty()) {
                failure = new Routine.Failure(FacilityRefusal.SEQUENCE_INTERRUPTED.translationKey(), false);
                return false;
            }
            if (waiting < 0) {
                release = posts.request(key);
                waiting = level.getGameTime();
            }
            if (level.getGameTime() - waiting >= STAFFING_PATIENCE) {
                failure = new Routine.Failure(FacilityRefusal.POST_UNAVAILABLE.translationKey(), false);
                return false;
            }
            return true;
        }

        void closeControls(ServerLevel level) {
            if (clock != null) {
                clock.close(level);
            }
            if (pulse != null) {
                pulse.ended(null, Ending.DONE);
            }
        }

        void close(ServerLevel level) {
            closeControls(level);
            release.run();
        }
    }
}
