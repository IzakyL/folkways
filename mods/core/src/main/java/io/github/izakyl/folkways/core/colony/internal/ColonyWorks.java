package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import io.github.izakyl.folkways.core.base.task.Cleanup;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyContext;
import io.github.izakyl.folkways.core.colony.api.ColonyLifecycle;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.WorkListener;
import io.github.izakyl.folkways.core.inventory.api.ColonyInventory;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.internal.InventorySources;
import io.github.izakyl.folkways.core.inventory.internal.ProxyStores;
import io.github.izakyl.folkways.core.inventory.internal.Stores;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.ReservedInput;
import io.github.izakyl.folkways.core.work.api.Stall;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
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
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class ColonyWorks extends Stretch implements Colony, InventorySources {

    private final ColonyData colony;
    private final ProxyStores proxies = new ProxyStores(this);

    private final List<Scope> scopes = new ArrayList<>();

    private final Map<ResourceLocation, Object> services = new LinkedHashMap<>();

    private Function<ResourceKey<Level>, List<ReservedInput>> reservations = dimension -> List.of();

    private boolean settled;

    private MinecraftServer server;

    private final Workshops workshops = new Workshops();
    private final Transports transports = new Transports();

    private final Rules rules = new Rules();

    private final StorePlaces stores = new StorePlaces();
    private final StorePlaces withheld = new StorePlaces();
    private long inventoryMembershipRevision;

    // What the labor in each dimension last said stands still there.
    private final Map<ResourceKey<Level>, List<Stall>> stalls = new HashMap<>();

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
    private final Map<ResourceKey<Level>, WorkListener> listening = new HashMap<>();

    public void listen(ResourceKey<Level> dimension, WorkListener to) {
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

    public void unlisten(ResourceKey<Level> dimension, WorkListener to) {
        listening.remove(dimension, to);
    }

    @Override
    public Optional<UUID> holder(UUID node) {
        return listening.values().stream().map(listener -> listener.holder(node))
            .flatMap(Optional::stream).findFirst();
    }

    @Override
    public boolean isApproaching(UUID node) {
        return listening.values().stream().anyMatch(listener -> listener.isApproaching(node));
    }

    @Override
    public boolean isSuspended(UUID node) {
        return listening.values().stream().anyMatch(listener -> listener.isSuspended(node));
    }

    @Override
    public void controllerOrder(Body body) {
        if (body.mob().level() instanceof ServerLevel level) {
            listener(level.dimension()).ifPresent(listener -> listener.controllerOrder(level, body));
        }
    }

    private Optional<WorkListener> listener(ResourceKey<Level> dimension) {
        return Optional.ofNullable(listening.get(dimension));
    }

    // Every node submitted is asked on its own. A submission that names a node still being withdrawn waits, whole,
    // until that node has ended.
    @Override
    public void submit(ResourceLocation owner, ResourceKey<Level> dimension, Grown work,
            BiConsumer<UUID, Ending> ended) {
        work = WorkDecoratorRegistry.apply(this, work);
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
        Optional<WorkListener> to = listener(standing.from.dimension);
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
        try (Cleanup cleanup = new Cleanup("Colony " + id() + " forgetting work from " + owner)) {
            for (Asked one : List.copyOf(asked.values())) {
                if (one.from.owner.equals(owner)) {
                    cleanup.run("withdraw " + one.node, () -> forget(one));
                }
            }
        }
    }

    private void clearWork() {
        colony.inventory().clear();
        inventoryMembershipRevision++;
        workshops.clear();
        transports.clear();
        stores.clear();
        withheld.clear();
        rules.clear();
        Stores.ruled(id(), Map.of());
        waiting.clear();
        try (Cleanup cleanup = new Cleanup("Colony " + id() + " clearing work")) {
            for (Asked one : List.copyOf(asked.values())) {
                cleanup.run("withdraw " + one.from.owner + " " + one.node, () -> forget(one));
            }
        }
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
        residents().forEach(resident -> colony.inventory().joined(resident.id()));
        for (Contributions.Entry entry : Contributions.all()) {
            Scope scope = opened(new Scope(entry.owner()));
            scopes.add(scope);
            try {
                entry.setup().accept(scope);
            } finally {
                scope.configuring = false;
            }
            for (Claim claim : colony.claims().all()) {
                scope.heard(claim.owner(), watch -> watch.held(claim));
            }
        }
    }

    void refresh(MinecraftServer server, Runnable remap) {
        List.copyOf(listening.values()).forEach(WorkListener::relocating);
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
    public ColonyInventory inventory() {
        return colony.inventory();
    }

    @Override
    public InventoryFeedback inventoryFeedback() {
        return colony.inventory();
    }

    public long inventoryMembershipRevision() {
        return inventoryMembershipRevision;
    }

    @Override
    public void publish(ResourceLocation owner, ResourceKey<Level> dimension,
                        Collection<Workshop> offering) {
        if (workshops.publish(owner, dimension, offering.stream().map(workshop ->
                WorkDecoratorRegistry.apply(this, workshop)).toList())) {
            inventoryMembershipRevision++;
            colony.inventory().offered(dimension, this, inventoryMembershipRevision);
            listener(dimension).ifPresent(WorkListener::offered);
        }
    }

    @Override
    public void publishTransport(ResourceLocation owner, ResourceKey<Level> dimension,
                                 Collection<TransportPublication.Offering> modes) {
        if (transports.publish(owner, dimension, List.copyOf(modes))) {
            List.copyOf(listening.values()).forEach(WorkListener::offered);
        }
    }

    @Override
    public long transportVersion() {
        return transports.version();
    }

    @Override
    public TransportPublication transports() {
        return transports.snapshot();
    }

    @Override
    public void stores(ResourceLocation owner, ResourceKey<Level> dimension, Collection<WorldPos> offering) {
        if (stores.publish(owner, dimension, List.copyOf(offering))) {
            inventoryMembershipRevision++;
            colony.inventory().offered(dimension, this, inventoryMembershipRevision);
            if (server != null) {
                colony.inventory().published(server, dimension);
            }
            listener(dimension).ifPresent(WorkListener::offered);
        }
    }

    @Override
    public Optional<net.minecraft.world.Container> store(ServerLevel level, WorldPos at) {
        List<WorldPos> offered = storesIn(level.dimension());
        if (offered.contains(at)) {
            return Stores.at(level, at);
        }
        BlockPos anchor = WorldSpaces.storage(level, at).filter(level::isLoaded)
            .map(pos -> Containers.anchor(level, pos)).orElse(null);
        if (anchor == null) {
            return Optional.empty();
        }
        var known = inventory().snapshot().stores().get(Stash.at(WorldPos.of(level, anchor)));
        if (known != null && known.aliases().stream().filter(offered::contains).anyMatch(alias ->
            WorldSpaces.storage(level, alias).filter(level::isLoaded)
                .map(pos -> Containers.anchor(level, pos).equals(anchor)).orElse(false))) {
            return Stores.at(level, at);
        }
        return Optional.empty();
    }

    @Override
    public List<StoreRule> storeRules(WorldPos at) {
        for (var store : inventory().snapshot().stores().values()) {
            if (store.aliases().contains(at)) {
                return Stores.rulesAt(store.aliases());
            }
        }
        return Stores.rulesAt(at);
    }

    public void reservations(Function<ResourceKey<Level>, List<ReservedInput>> query) {
        reservations = query;
    }

    @Override
    public List<ReservedInput> reservedInputs(ServerLevel level) {
        return reservations.apply(level.dimension());
    }

    @Override
    public List<WorldPos> storesIn(ResourceKey<Level> dimension) {
        return stores.in(dimension);
    }

    @Override
    public List<Stall> stallsIn(ResourceKey<Level> dimension) {
        return stalls.getOrDefault(dimension, List.of());
    }

    public void stalled(ResourceKey<Level> dimension, List<Stall> standing) {
        if (standing.isEmpty()) {
            stalls.remove(dimension);
        } else {
            stalls.put(dimension, List.copyOf(standing));
        }
    }

    @Override
    public void withhold(ResourceLocation owner, ResourceKey<Level> dimension, Collection<WorldPos> answered) {
        if (withheld.publish(owner, dimension, List.copyOf(answered))) {
            inventoryMembershipRevision++;
            colony.inventory().offered(dimension, this, inventoryMembershipRevision);
            listener(dimension).ifPresent(WorkListener::offered);
        }
    }

    public Set<WorldPos> withheldIn(ResourceKey<Level> dimension) {
        return withheld.setIn(dimension);
    }

    @Override
    public void rule(ResourceLocation owner, ResourceKey<Level> dimension, Collection<StoreRule> ruling) {
        if (rules.publish(owner, dimension, List.copyOf(ruling))) {
            Stores.ruled(id(), rules.all());
            colony.inventory().rulesChanged();
            listener(dimension).ifPresent(WorkListener::offered);
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
    public Optional<Claim> hold(ResourceLocation owner, Claimed what) {
        return proxies.hold(required(), owner, what, () -> holdSingle(owner, what));
    }

    private Optional<Claim> holdSingle(ResourceLocation owner, Claimed what) {
        MinecraftServer running = required();
        if (what instanceof Claimed.Point point && WorldSpaces.resolve(running, point.cell())
                .filter(where -> where.level().isLoaded(where.cell())
                    && PointSites.accepts(owner, where.level(), where.cell())).isEmpty()) {
            return Optional.empty();
        }
        if (ColonyRegistry.get(running).taken(what)) {
            return Optional.empty();
        }
        Optional<Claim> taken = colony.claims().hold(running, owner, what);
        taken.ifPresent(claim -> {
            what.singleCell().ifPresent(at ->
                io.github.izakyl.folkways.core.inventory.internal.PortHooks.registered(this, at));
            held(claim);
        });
        return taken;
    }

    @Override
    public void release(UUID claim, ClaimRelease why) {
        colony.claims().release(server, claim, why);
    }

    @Override
    public List<Claim> claims() {
        return colony.claims().all();
    }

    @Override
    public List<Claim> claims(ResourceLocation owner) {
        return colony.claims().of(owner);
    }

    @Override
    public Optional<Claim> claim(UUID claim) {
        return colony.claims().get(claim);
    }

    @Override
    public Optional<Claim> claimAt(WorldPos cell) {
        return colony.claims().at(cell);
    }

    @Override
    public Optional<Claim> claimOf(UUID entity) {
        return colony.claims().ofEntity(entity);
    }

    @Override
    public List<Resident> residents() {
        return colony.claims().residents();
    }

    @Override
    public void entered(Body body) {
        colony.claims().entered(body);
        if (claimOf(body.id()).isPresent()) {
            colony.inventory().entered(body);
        }
    }

    @Override
    public void exited(UUID entity) {
        colony.claims().exited(entity);
        colony.inventory().unloaded(entity);
    }

    @Override
    public void removed(UUID entity, Entity.RemovalReason reason) {
        if (isCarriedOff(entity)) {
            return;
        }
        ClaimRelease.fromWorld(reason).ifPresentOrElse(
            why -> claimOf(entity).ifPresent(held -> release(held.id(), why)),
            () -> exited(entity));
    }

    @Override
    public void carriedOff(UUID entity) {
        colony.claims().carriedOff(entity);
        colony.inventory().unloaded(entity);
    }

    @Override
    public boolean isCarriedOff(UUID entity) {
        return colony.claims().isCarriedOff(entity);
    }

    private void held(Claim claim) {
        if (claim.what() instanceof Claimed.Entity entity && ResidentKinds.of(entity.type()).isPresent()) {
            colony.inventory().joined(entity.entity());
            for (ServerLevel level : required().getAllLevels()) {
                Entity present = level.getEntity(entity.entity());
                if (present != null) {
                    Bodies.of(present)
                        .ifPresent(body -> colony.inventory().entered(body));
                    break;
                }
            }
        }
        for (Scope scope : List.copyOf(scopes)) {
            scope.heard(claim.owner(), watch -> watch.held(claim));
        }
    }

    void released(Claim claim, ClaimRelease why) {
        proxies.released(server, claim, why);
        if (claim.what() instanceof Claimed.Entity entity) {
            colony.inventory().left(entity.entity());
        }
        for (Scope scope : List.copyOf(scopes)) {
            scope.heard(claim.owner(), watch -> watch.released(claim, why));
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
        for (WorldPos cell : colony.claims().cells()) {
            cell.level(server).ifPresent(levels::add);
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!colony.claims().bodiesIn(level).isEmpty()) {
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

    @Override
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
        proxies.refresh(server);
        for (ServerLevel level : server.getAllLevels()) {
            Map<UUID, Body> present = new LinkedHashMap<>();
            for (Body body : colony.claims().bodiesIn(level)) {
                if (!colony.claims().isCarriedOff(body.id())) {
                    present.put(body.id(), body);
                }
            }
            colony.inventory().advance(level, this, present);
        }
        for (Scope scope : List.copyOf(scopes)) {
            long began = System.nanoTime();
            scope.ticks.forEach(callback -> callback.accept(server));
            long took = System.nanoTime() - began;
            // A plugin's tick is the colony's tick too: one that takes a good share of the budget says so.
            if (took >= SLOW_PULSE_NANOS && FolkwaysConfig.logPlanHeartbeat()) {
                org.slf4j.LoggerFactory.getLogger("folkways-work").info("slow pulse owner={} us={} tick={}",
                    scope.owner, took / 1000, server.getTickCount());
            }
        }
    }

    private static final long SLOW_PULSE_NANOS = 20_000_000L;

    @Override
    protected void ended(Closing why) {
        try (Cleanup cleanup = new Cleanup("Closing colony " + id() + " as " + why)) {
            try {
                cleanup.run("listeners", () -> ColonyLifecycle.closed(this, why));
                cleanup.run("work", this::clearWork);
            } finally {
                scopes.clear();
                services.clear();
                settled = false;
                if (why == Closing.RAZED) {
                    cleanup.run("keeps", () -> colony.keeps().clear());
                }
            }
        }
    }

    private final class Scope extends Stretch implements ColonyContext {
        private final ResourceLocation owner;
        private final List<BiFunction<Worker, ColonyView, List<Urge>>> urges = new ArrayList<>();
        private final List<Consumer<MinecraftServer>> ticks = new ArrayList<>();
        private final List<Consumer<Closing>> endings = new ArrayList<>();
        private final Map<ResourceLocation, List<Claim.Watch>> watches = new LinkedHashMap<>();
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

        public void watch(ResourceLocation held, Claim.Watch watch) {
            writing();
            watches.computeIfAbsent(java.util.Objects.requireNonNull(held), key -> new ArrayList<>())
                .add(java.util.Objects.requireNonNull(watch));
        }

        private void heard(ResourceLocation held, Consumer<Claim.Watch> telling) {
            if (over()) {
                return;
            }
            for (Claim.Watch watch : watches.getOrDefault(held, List.of())) {
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
            try (Cleanup cleanup = new Cleanup("Closing colony " + id() + " owner " + owner + " as " + why)) {
                try {
                    int index = 0;
                    for (Consumer<Closing> callback : endings) {
                        cleanup.run("callback " + index++, () -> callback.accept(why));
                    }
                } finally {
                    if (scopes.stream().noneMatch(scope -> scope.owner.equals(owner))) {
                        cleanup.run("workshops", () -> workshops.forget(owner));
                        cleanup.run("transports", () -> transports.forget(owner));
                        cleanup.run("stores", () -> stores.forget(owner));
                        cleanup.run("withheld stores", () -> withheld.forget(owner));
                        cleanup.run("rules", () -> rules.forget(owner));
                        cleanup.run("published rules", () -> Stores.ruled(id(), rules.all()));
                        inventoryMembershipRevision++;
                        for (var dimension : stores.dimensions()) {
                            colony.inventory().offered(dimension, ColonyWorks.this, inventoryMembershipRevision);
                        }
                        colony.inventory().rulesChanged();
                        cleanup.run("asked work", () -> forgetAsked(owner));
                    }
                }
            }
        }
    }
}
