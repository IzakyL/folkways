package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyContext;
import io.github.izakyl.folkways.core.api.colony.ColonyLifecycle;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Contributions;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class ColonyWorks extends Stretch implements Colony {

    private final ColonyData colony;

    private final List<Scope> scopes = new ArrayList<>();

    private final Map<ResourceLocation, Object> services = new LinkedHashMap<>();

    private boolean settled;

    private MinecraftServer server;

    private final Workshops workshops = new Workshops();

    private final Rules rules = new Rules();

    private final StorePlaces stores = new StorePlaces();

    public interface Listening {

        void submitted(ResourceLocation owner, Grown work);

        void withdrawn(ResourceLocation owner, UUID node);

        void offered();

        default void relocating() { }
    }

    // Work an owner submitted at once: any shape of nodes, each of which ends on its own.
    private static final class Submission {
        private final ResourceLocation owner;
        private final ResourceKey<Level> dimension;
        private final Grown work;
        private final BiConsumer<UUID, Ending> ended;
        private final Set<UUID> left = new LinkedHashSet<>();

        private Submission(ResourceLocation owner, ResourceKey<Level> dimension, Grown work,
                BiConsumer<UUID, Ending> ended) {
            this.owner = owner;
            this.dimension = dimension;
            this.work = work;
            this.ended = ended;
            this.left.addAll(work.completions());
        }

        Grown remaining() {
            return work.remaining(left);
        }
    }

    // One node asked of the colony, as part of the submission it came in.
    private static final class Asked {
        private final Submission from;
        private final UUID node;
        private boolean withdrawing;

        private Asked(Submission from, UUID node) {
            this.from = from;
            this.node = node;
        }
    }

    private final Map<UUID, Asked> asked = new LinkedHashMap<>();
    private final List<Submission> waiting = new ArrayList<>();
    private final Map<ResourceKey<Level>, Listening> listening = new HashMap<>();

    public void listen(ResourceKey<Level> dimension, Listening to) {
        listening.put(dimension, to);
        Set<Submission> told = new LinkedHashSet<>();
        for (Asked one : List.copyOf(asked.values())) {
            if (!one.from.dimension.equals(dimension)) {
                continue;
            }
            if (one.withdrawing) {
                end(one, Ending.REVOKED);
            } else {
                told.add(one.from);
            }
        }
        for (Submission one : told) {
            Set<UUID> standing = new LinkedHashSet<>();
            for (UUID node : one.left) {
                Asked at = asked.get(node);
                if (at != null && at.from == one && !at.withdrawing) {
                    standing.add(node);
                }
            }
            if (!standing.isEmpty()) {
                to.submitted(one.owner, one.work.remaining(standing));
            }
        }
    }

    public void unlisten(ResourceKey<Level> dimension, Listening to) {
        listening.remove(dimension, to);
    }

    private Optional<Listening> listener(ResourceKey<Level> dimension) {
        return Optional.ofNullable(listening.get(dimension));
    }

    // Every node submitted is asked on its own. A submission that names a node still being withdrawn waits, whole,
    // until that node has ended.
    @Override
    public void submit(ResourceLocation owner, ResourceKey<Level> dimension, Grown work,
            BiConsumer<UUID, Ending> ended) {
        Submission fresh = new Submission(owner, dimension, work, java.util.Objects.requireNonNull(ended));
        boolean blocked = false;
        for (UUID node : fresh.left) {
            Asked standing = asked.get(node);
            if (standing != null && !standing.from.owner.equals(owner)) {
                throw new IllegalArgumentException(owner + " submitted node " + node
                    + " already asked for by " + standing.from.owner);
            }
            if (queued(node) != null || standing != null && !standing.withdrawing) {
                throw new IllegalStateException(owner + " submitted " + node + " before it ended");
            }
            blocked |= standing != null;
        }
        if (blocked) {
            waiting.add(fresh);
        } else {
            open(fresh);
        }
    }

    private Submission queued(UUID node) {
        for (Submission one : waiting) {
            if (one.left.contains(node)) {
                return one;
            }
        }
        return null;
    }

    private void open(Submission fresh) {
        for (UUID node : fresh.left) {
            asked.put(node, new Asked(fresh, node));
        }
        listener(fresh.dimension).ifPresent(to -> to.submitted(fresh.owner, fresh.remaining()));
    }

    @Override
    public void withdraw(ResourceLocation owner, UUID node) {
        Submission queued = queued(node);
        if (queued != null && queued.owner.equals(owner)) {
            queued.left.remove(node);
            if (queued.left.isEmpty()) {
                waiting.remove(queued);
            }
            deliver(queued, node, Ending.REVOKED);
            return;
        }
        Asked standing = asked.get(node);
        if (standing == null || !standing.from.owner.equals(owner) || standing.withdrawing) {
            return;
        }
        Optional<Listening> to = listener(standing.from.dimension);
        if (to.isEmpty()) {
            end(standing, Ending.REVOKED);
            return;
        }
        standing.withdrawing = true;
        to.get().withdrawn(owner, node);
    }

    public void ended(ResourceKey<Level> dimension, UUID node, Ending how) {
        Asked standing = asked.get(node);
        if (standing != null && standing.from.dimension.equals(dimension)) {
            end(standing, how);
        }
    }

    public void withdrawnGone(ResourceKey<Level> dimension, UUID node) {
        Asked standing = asked.get(node);
        if (standing != null && standing.withdrawing && standing.from.dimension.equals(dimension)) {
            end(standing, Ending.REVOKED);
        }
    }

    private void end(Asked gone, Ending how) {
        asked.remove(gone.node, gone);
        gone.from.left.remove(gone.node);
        for (Submission next : List.copyOf(waiting)) {
            if (next.left.stream().noneMatch(asked::containsKey)) {
                waiting.remove(next);
                open(next);
            }
        }
        deliver(gone.from, gone.node, how);
    }

    private static void deliver(Submission from, UUID node, Ending how) {
        try {
            from.ended.accept(node, how);
        } catch (RuntimeException | LinkageError broken) {
            org.slf4j.LoggerFactory.getLogger("folkways-work").error(
                "{} failed hearing that {} ended as {}", from.owner, node, how, broken);
        }
    }

    private void forgetAsked(ResourceLocation owner) {
        waiting.removeIf(one -> one.owner.equals(owner));
        for (Asked one : List.copyOf(asked.values())) {
            if (one.from.owner.equals(owner)) {
                forget(one);
            }
        }
    }

    private void clearWork() {
        workshops.clear();
        stores.clear();
        rules.clear();
        Stores.ruled(id(), Map.of());
        waiting.clear();
        List.copyOf(asked.values()).forEach(this::forget);
    }

    private void forget(Asked gone) {
        asked.remove(gone.node, gone);
        if (!gone.withdrawing) {
            listener(gone.from.dimension).ifPresent(to -> to.withdrawn(gone.from.owner, gone.node));
        }
    }

    ColonyWorks(ColonyData colony) {
        this.colony = colony;
    }

    boolean settled() {
        return settled;
    }

    void settleIn(ColonyRuntime.Run run) {
        settled = true;
        server = run.server();
        for (Contributions.Entry entry : Contributions.all()) {
            Scope scope = opened(new Scope(entry.owner()));
            scopes.add(scope);
            try {
                entry.setup().accept(scope);
            } finally {
                scope.configuring = false;
            }
            for (Holding holding : colony.holdings().all()) {
                scope.heard(holding.owner(), watch -> watch.held(holding));
            }
        }
    }

    void refresh(MinecraftServer server, Runnable remap) {
        List.copyOf(listening.values()).forEach(Listening::relocating);
        for (Scope scope : List.copyOf(scopes)) {
            scope.closed(Closing.SHUTDOWN);
        }
        clearWork();
        settled = false;
        remap.run();
        ColonyRuntime.refresh(server, this);
    }

    @Override
    public UUID id() {
        return colony.colonyId();
    }

    @Override
    public void publish(ResourceLocation owner, ResourceKey<Level> dimension,
                        Collection<Workshop> offering) {
        if (workshops.publish(owner, dimension, List.copyOf(offering))) {
            listener(dimension).ifPresent(Listening::offered);
        }
    }

    @Override
    public void stores(ResourceLocation owner, ResourceKey<Level> dimension, Collection<WorldPos> offering) {
        if (stores.publish(owner, dimension, List.copyOf(offering))) {
            listener(dimension).ifPresent(Listening::offered);
        }
    }

    @Override
    public List<WorldPos> storesIn(ResourceKey<Level> dimension) {
        return stores.in(dimension);
    }

    @Override
    public void rule(ResourceLocation owner, ResourceKey<Level> dimension, Collection<StoreRule> ruling) {
        if (rules.publish(owner, dimension, List.copyOf(ruling))) {
            Stores.ruled(id(), rules.all());
            listener(dimension).ifPresent(Listening::offered);
        }
    }

    public Map<WorldPos, List<StoreRule>> rulesIn(ResourceKey<Level> dimension) {
        return rules.in(dimension);
    }

    @Override
    public void keep(ResourceLocation owner, CompoundTag bag) {
        if (colony.keeps().kept(owner).equals(bag)) {
            return;
        }
        colony.keeps().keep(owner, bag);
    }

    @Override
    public CompoundTag kept(ResourceLocation owner) {
        return colony.keeps().kept(owner);
    }

    @Override
    public Optional<Holding> hold(ResourceLocation owner, Held what) {
        MinecraftServer running = required();
        if (ColonyRegistry.get(running).taken(what)) {
            return Optional.empty();
        }
        Optional<Holding> taken = colony.holdings().hold(running, owner, what);
        taken.ifPresent(this::held);
        return taken;
    }

    @Override
    public void release(UUID holding, Release why) {
        colony.holdings().release(server, holding, why);
    }

    @Override
    public List<Holding> holdings() {
        return colony.holdings().all();
    }

    @Override
    public List<Holding> holdings(ResourceLocation owner) {
        return colony.holdings().of(owner);
    }

    @Override
    public Optional<Holding> holding(UUID holding) {
        return colony.holdings().get(holding);
    }

    @Override
    public Optional<Holding> holdingAt(WorldPos cell) {
        return colony.holdings().at(cell);
    }

    @Override
    public Optional<Holding> holdingOf(UUID entity) {
        return colony.holdings().ofEntity(entity);
    }

    @Override
    public List<Resident> residents() {
        return colony.holdings().residents();
    }

    @Override
    public void entered(Body body) {
        colony.holdings().entered(body);
    }

    @Override
    public void exited(UUID entity) {
        colony.holdings().exited(entity);
    }

    private void held(Holding holding) {
        for (Scope scope : List.copyOf(scopes)) {
            scope.heard(holding.owner(), watch -> watch.held(holding));
        }
    }

    void released(Holding holding, Release why) {
        for (Scope scope : List.copyOf(scopes)) {
            scope.heard(holding.owner(), watch -> watch.released(holding, why));
        }
    }

    @Override
    public void raze() {
        MinecraftServer running = required();
        ColonyRegistry.get(running).raze(running, id());
    }

    private MinecraftServer required() {
        if (server == null) {
            throw new IllegalStateException(id() + " has not settled into a running server yet");
        }
        return server;
    }

    @Override
    public <T> Optional<T> service(ResourceLocation owner, Class<T> type) {
        return Optional.ofNullable(services.get(owner)).filter(type::isInstance).map(type::cast);
    }

    @Override
    public List<ColonyView> views(MinecraftServer server) {
        Set<ServerLevel> levels = new LinkedHashSet<>();
        for (WorldPos cell : colony.holdings().cells()) {
            cell.level(server).ifPresent(levels::add);
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!colony.holdings().bodiesIn(level).isEmpty()) {
                levels.add(level);
            }
        }
        List<ColonyView> views = new ArrayList<>(levels.size());
        for (ServerLevel level : levels) {
            views.add(view(level));
        }
        return List.copyOf(views);
    }

    @Override
    public ColonyView view(ServerLevel level) {
        return ColonySnapshot.of(colony, level);
    }

    public List<Workshop> workshopsIn(ResourceKey<Level> dimension) {
        return workshops.in(dimension);
    }

    public Set<BlockPos> sitesIn(ResourceKey<Level> dimension) {
        if (server == null) {
            Set<BlockPos> cells = new LinkedHashSet<>();
            workshops.in(dimension).stream()
                .map(workshop -> workshop.site().where()).filter(cell -> cell.in(dimension))
                .forEach(cell -> cells.add(cell.cell()));
            return Set.copyOf(cells);
        }
        ServerLevel level = server.getLevel(dimension);
        return level == null ? Set.of() : workshops.sitesIn(level);
    }

    public record Felt(ResourceLocation owner, Urge urge) { }

    public List<Felt> urges(Worker who, ColonyView view) {
        List<Felt> felt = new ArrayList<>();
        for (Scope scope : List.copyOf(scopes)) {
            for (BiFunction<Worker, ColonyView, List<Urge>> source : scope.urges) {
                try {
                    source.apply(who, view).forEach(urge -> felt.add(new Felt(scope.owner, urge)));
                } catch (RuntimeException | LinkageError broken) {
                    org.slf4j.LoggerFactory.getLogger("folkways-work").error(
                        "{} failed collecting urges", scope.owner, broken);
                }
            }
        }
        return List.copyOf(felt);
    }

    public void pulse(MinecraftServer server) {
        if (over()) {
            return;
        }
        for (Scope scope : List.copyOf(scopes)) {
            scope.ticks.forEach(callback -> callback.accept(server));
        }
    }

    @Override
    protected void ended(Closing why) {
        ColonyLifecycle.closed(this, why);
        clearWork();
        scopes.clear();
        services.clear();
        settled = false;
        if (why == Closing.RAZED) {
            colony.keeps().clear();
        }
    }

    private final class Scope extends Stretch implements ColonyContext {
        private final ResourceLocation owner;
        private final List<BiFunction<Worker, ColonyView, List<Urge>>> urges = new ArrayList<>();
        private final List<Consumer<MinecraftServer>> ticks = new ArrayList<>();
        private final List<Consumer<Closing>> endings = new ArrayList<>();
        private final Map<ResourceLocation, List<Holding.Watch>> watches = new LinkedHashMap<>();
        private Object shared;
        private boolean configuring = true;

        private Scope(ResourceLocation owner) {
            this.owner = owner;
        }

        private void writing() {
            if (!configuring || over()) {
                throw new IllegalStateException(owner + " registered callbacks outside colony setup");
            }
        }

        public ResourceLocation owner() { return owner; }
        public Colony colony() { return ColonyWorks.this; }
        public MinecraftServer server() { return required(); }

        public void urges(BiFunction<Worker, ColonyView, List<Urge>> source) {
            writing();
            urges.add(java.util.Objects.requireNonNull(source));
        }

        public void tick(Consumer<MinecraftServer> callback) {
            writing();
            ticks.add(java.util.Objects.requireNonNull(callback));
        }

        public void closed(Consumer<Closing> callback) {
            writing();
            endings.add(java.util.Objects.requireNonNull(callback));
        }

        public void watch(ResourceLocation held, Holding.Watch watch) {
            writing();
            watches.computeIfAbsent(java.util.Objects.requireNonNull(held), key -> new ArrayList<>())
                .add(java.util.Objects.requireNonNull(watch));
        }

        private void heard(ResourceLocation held, Consumer<Holding.Watch> telling) {
            if (over()) {
                return;
            }
            for (Holding.Watch watch : watches.getOrDefault(held, List.of())) {
                try {
                    telling.accept(watch);
                } catch (RuntimeException | LinkageError broken) {
                    org.slf4j.LoggerFactory.getLogger("folkways-work").error(
                        "{} failed hearing about what the colony holds", owner, broken);
                }
            }
        }

        public void share(Object state) {
            writing();
            java.util.Objects.requireNonNull(state);
            if (services.putIfAbsent(owner, state) != null) {
                throw new IllegalStateException(owner + " already shares colony state");
            }
            shared = state;
        }

        @Override
        protected void ended(Closing why) {
            scopes.remove(this);
            if (shared != null) {
                services.remove(owner, shared);
            }
            endings.forEach(callback -> callback.accept(why));
            if (scopes.stream().noneMatch(scope -> scope.owner.equals(owner))) {
                workshops.forget(owner);
                stores.forget(owner);
                rules.forget(owner);
                Stores.ruled(id(), rules.all());
                forgetAsked(owner);
            }
        }
    }
}
