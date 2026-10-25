package io.github.izakyl.folkways.plugins.combat.command;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.EquipmentPlans;
import io.github.izakyl.folkways.plugins.person.living.LivingContent;
import io.github.izakyl.folkways.plugins.combat.CombatReadiness;
import io.github.izakyl.folkways.plugins.combat.motion.CombatEvasion;
import io.github.izakyl.folkways.plugins.person.fear.FearContent;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.net.SelectionOrders;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Server-side orders executed exclusively by Core's existing labour runner. */
public final class ResidentCommands {

    public enum Mode { MOVE, HOLD, RETREAT, GUARD, ATTACK }
    public enum Status { MOVING, HOLDING, ARRIVED, RELEASED, FAILED }
    public enum AttackEnd { NONE, TARGET_DEFEATED, TARGET_LOST, OUT_OF_RANGE, TIMED_OUT, AUTHORITY_LOST, REPLACED, CANCELLED, FAILED }
    public record Target(Body body, WorldPos destination, Mode mode, Optional<Vec3> facing) { }

    /** A handle can release only its own order, including after a replacement was issued. */
    public final class Order implements AutoCloseable {
        private final UUID id = UUID.randomUUID();
        private final Body body;
        private final WorldPos destination;
        private final Mode mode;
        private final Optional<Vec3> facing;
        private UUID target;
        private UUID commander;
        private UUID step = UUID.randomUUID();
        private WorldPos approached;
        private long retargetAt;
        private double attackRange = 4.5;
        private long movingSince = server.overworld().getGameTime();
        private long engagedUntil = Long.MIN_VALUE;
        private boolean yielded;
        private boolean deliberateGuard;
        private boolean dangerRetreat;
        private volatile AttackEnd attackEnd = AttackEnd.NONE;
        private volatile Status status = Status.MOVING;
        private volatile RefusalKind failure;

        private Order(Body body, WorldPos destination, Mode mode, Optional<Vec3> facing) {
            this.body = body;
            this.destination = destination;
            this.mode = mode;
            this.facing = facing;
        }

        public UUID id() { return id; }
        public UUID resident() { return body.id(); }
        public WorldPos destination() { return destination; }
        public Mode mode() { return mode; }
        UUID step() { return step; }
        public Status status() { return status; }
        public AttackEnd attackEnd() { return attackEnd; }
        /** Formation commands can lend the same runner to wounded fear and bounded retaliation. */
        public Order allowDangerRetreat() {
            requireServerThread();
            if (active.get(resident()) != this) { throw new IllegalStateException("only a current order can yield to danger"); }
            dangerRetreat = true;
            return this;
        }
        public boolean defensiveOnly() { return dangerRetreat && mode == Mode.HOLD; }
        public boolean yieldingToDanger() {
            if (active.get(resident()) != this) { return false; }
            if (mode != Mode.MOVE && mode != Mode.RETREAT
                    && (CombatReadiness.explosiveNear(body.mob()).isPresent() || CombatEvasion.active(body.mob(), this))) return true;
            return (dangerRetreat || mode == Mode.GUARD && deliberateGuard) && woundedDanger(this)
                || defensiveOnly() && (engaged() || retaliating(this)
                    || body.mob() instanceof ResidentEntity resident && resident.hasWeaponSession());
        }
        /** A reloaded entity with the same resident UUID needs a fresh transient order. */
        public boolean boundTo(Body candidate) { return body.mob() == candidate.mob(); }
        /** A short, renewed combat lease; never survives a cancelled or replaced order. */
        public boolean engaged() {
            return (mode == Mode.GUARD || defensiveOnly()) && active.get(resident()) == this
                && server.overworld().getGameTime() < engagedUntil;
        }
        /** Patrol renews this only while offering a valid enemy inside the guard's leash. */
        public void engaging() {
            requireServerThread();
            if ((mode == Mode.GUARD || defensiveOnly()) && active.get(resident()) == this) {
                engagedUntil = server.overworld().getGameTime() + 20;
            }
        }
        /** Player-authored patrol stops keep control between battles; legacy guards keep their ordinary priority. */
        public Order deliberateGuard() {
            requireServerThread();
            if (mode != Mode.GUARD || active.get(resident()) != this) {
                throw new IllegalStateException("only an active guard can be made deliberate");
            }
            deliberateGuard = true;
            return this;
        }
        public Optional<RefusalKind> failure() { return Optional.ofNullable(failure); }
        public void close() { release(this); }
    }

    private final Colony colony;
    private final MinecraftServer server;
    private final Map<UUID, Order> active = new HashMap<>();

    ResidentCommands(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
    }

    public static ResidentCommands of(Colony colony) {
        return colony.service(CommandContent.ID, ResidentCommands.class).orElseThrow();
    }

    public Order move(Body body, WorldPos destination) { return issue(body, destination, Mode.MOVE, Optional.empty()); }
    public Order hold(Body body, WorldPos destination) { return issue(body, destination, Mode.HOLD, Optional.empty()); }
    public Order hold(Body body, WorldPos destination, Vec3 facing) {
        if (!Double.isFinite(facing.lengthSqr()) || facing.horizontalDistanceSqr() < .01) {
            throw new IllegalArgumentException("hold facing must have a finite horizontal direction");
        }
        return issue(body, destination, Mode.HOLD, Optional.of(facing.normalize()));
    }
    public Order retreat(Body body, WorldPos destination) { return issue(body, destination, Mode.RETREAT, Optional.empty()); }

    /** One explicit mob target, bound to this commander's continuing book authority and each actual body. */
    public List<Order> attack(List<Body> bodies, Mob target, ServerPlayer commander) {
        requireServerThread();
        if (SelectionOrders.authorized(commander, colony.id()).isEmpty() || bodies.isEmpty()) {
            throw new IllegalArgumentException("unauthorized attack");
        }
        var seen = new HashSet<UUID>();
        List<Order> next = bodies.stream().map(body -> {
            validate(body, WorldSpaces.at(target));
            if (!seen.add(body.id()) || !Threats.explicitlyAttackable(target, body.mob(), commander)) {
                throw new IllegalArgumentException("friendly or unavailable target");
            }
            Order order = new Order(body, WorldSpaces.at(target), Mode.ATTACK, Optional.empty());
            order.target = target.getUUID();
            order.commander = commander.getUUID();
            order.approached = order.destination;
            return order;
        }).toList();
        for (Order order : next) {
            Order previous = active.put(order.resident(), order);
            if (previous != null) { replaced(previous); }
        }
        return next;
    }
    /** Yield to combat and fear, then return to this same slot through the existing labour runner. */
    public Order guard(Body body, WorldPos destination, Vec3 facing) {
        if (!Double.isFinite(facing.lengthSqr()) || facing.horizontalDistanceSqr() < .01) {
            throw new IllegalArgumentException("guard facing must have a finite horizontal direction");
        }
        return issue(body, destination, Mode.GUARD, Optional.of(facing.normalize()));
    }

    public Optional<Order> current(UUID resident) {
        requireServerThread();
        return Optional.ofNullable(active.get(resident));
    }

    /** Validates a proposed order without interrupting anything; groups validate every member first. */
    public void validate(Body body, WorldPos destination) {
        requireServerThread();
        Objects.requireNonNull(destination);
        if (!valid(body) || !body.kind().id().equals(PersonBody.ID)) {
            throw new IllegalArgumentException("orders require a loaded person held by this colony");
        }
        ServerLevel level = (ServerLevel) body.mob().level();
        WorldPos from = WorldSpaces.at(body.mob());
        if (!destination.sameRealm(from) || !destination.in(level)
                || !level.hasChunkAt(destination.block(level)) || destination.cell().distSqr(from.cell()) > 128 * 128) {
            throw new IllegalArgumentException("order destination must be loaded, in the same realm and within 128 blocks");
        }
    }

    private Order issue(Body body, WorldPos destination, Mode mode, Optional<Vec3> facing) {
        return replace(List.of(new Target(body, destination, mode, facing))).getFirst();
    }

    /** Prepare every replacement before changing any runner-owned handle. */
    public List<Order> replace(List<Target> targets) {
        requireServerThread();
        var seen = new HashSet<UUID>();
        List<Order> next = targets.stream().map(target -> {
            validate(target.body(), target.destination());
            Objects.requireNonNull(target.mode());
            if (target.mode() == Mode.ATTACK) { throw new IllegalArgumentException("attack requires a live target and commander"); }
            if (!seen.add(target.body().id()) || target.facing().filter(front ->
                    !Double.isFinite(front.lengthSqr()) || front.horizontalDistanceSqr() < .01).isPresent()) {
                throw new IllegalArgumentException("invalid batch command");
            }
            return new Order(target.body(), target.destination(), target.mode(), target.facing().map(Vec3::normalize));
        }).toList();
        for (Order order : next) {
            Order previous = active.put(order.resident(), order);
            if (previous != null) { replaced(previous); }
        }
        return next;
    }

    public void release(Order order) {
        requireServerThread();
        if (active.remove(order.resident(), order)) {
            if (order.mode == Mode.ATTACK && order.attackEnd == AttackEnd.NONE) {
                AttackEnd why = attackInvalidation(order);
                order.attackEnd = why == AttackEnd.NONE ? AttackEnd.CANCELLED : why;
            }
            order.status = Status.RELEASED;
        }
    }

    public void release(UUID resident) {
        requireServerThread();
        Order order = active.get(resident);
        if (order != null) { release(order); }
    }

    private void replaced(Order previous) {
        if (previous.mode == Mode.ATTACK) { previous.attackEnd = AttackEnd.REPLACED; }
        previous.status = Status.RELEASED;
    }

    void targetLeft(UUID target) { targetLeft(target, false); }

    void targetLeft(UUID target, boolean defeated) {
        requireServerThread();
        for (Order order : List.copyOf(active.values())) {
            if (target.equals(order.target)) {
                order.attackEnd = defeated ? AttackEnd.TARGET_DEFEATED : AttackEnd.TARGET_LOST;
                release(order);
            }
        }
    }

    List<Urge> urges(Worker who) {
        Order order = active.get(who.resident().id());
        if (order == null || !active(order)) { return List.of(); }
        if (order.mode == Mode.ATTACK) { order.attackRange = Weapons.range(who); }
        // A replacement has its own urge identity: it safely interrupts even a trip already in progress.
        ResourceLocation intent = ResourceLocation.fromNamespaceAndPath("folkways", "command/"
            + (order.mode == Mode.ATTACK ? order.step : order.id));
        // Keep the player's labour reservation while the same runner performs explicit finite resupply.
        if (lendsRunner(who.resident().id())) { return List.of(Urge.reserve(intent)); }
        double weight = order.mode == Mode.GUARD ? Urge.DIRE - .5
            : Urge.DIRE + (order.mode == Mode.RETREAT ? 2 : 1);
        if (order.yieldingToDanger()) { weight = Math.min(weight, Urge.DIRE - .5); }
        return List.of(new Urge(intent, weight, true,
            () -> order.mode == Mode.ATTACK ? new AttackNode(this, order, who) : new CommandNode(this, order),
            priority(order)));
    }

    Urge.Priority priority(Order order) {
        if (order.yieldingToDanger()) { return Urge.Priority.ORDINARY; }
        if (order.mode != Mode.GUARD) { return Urge.Priority.CONTROL; }
        if (!order.deliberateGuard || order.engaged()
                // A native attack may outlive the short detection lease. Let its actual hand loan finish;
                // repeatedly cancelling a long draw at the lease boundary would prevent every shot.
                || order.body.mob() instanceof ResidentEntity resident && resident.hasWeaponSession()) {
            return Urge.Priority.ORDINARY;
        }
        if (woundedDanger(order)) { return Urge.Priority.ORDINARY; }
        return Urge.Priority.CONTROL;
    }

    private boolean lendsRunner(UUID resident) {
        return LivingContent.lendsRunner(colony, resident) || EquipmentPlans.lendsRunner(colony, resident);
    }

    private boolean woundedDanger(Order order) {
        var body = order.body.mob();
        boolean fleeing = order.body.doing().filter(doing ->
            doing.toward().orElse(doing.what()).equals(FearContent.FLEEING)).isPresent();
        if (fleeing) { return true; }
        if (body.getHealth() > body.getMaxHealth() * .35F) { return false; }
        var attacker = body.getLastHurtByMob();
        // LivingEntity expires this source after forty game ticks; old fire cannot hold a squad forever.
        var hurt = body.getLastDamageSource();
        return attacker instanceof Mob foe && foe.getTarget() == body
                && Threats.hostile(foe, body) && body.distanceToSqr(foe) <= 16 * 16
            || hurt != null && (hurt.is(DamageTypeTags.PANIC_CAUSES) || hurt.is(DamageTypeTags.IS_FIRE));
    }

    private boolean retaliating(Order order) {
        var body = order.body.mob();
        return body.getLastHurtByMob() instanceof Mob foe
            && body.tickCount - body.getLastHurtByMobTimestamp() <= 100
            && Threats.hostile(foe, body) && body.distanceToSqr(foe) <= 12 * 12 && body.hasLineOfSight(foe)
            && WorldSpaces.world(body.level(), order.destination)
                .filter(at -> at.distanceToSqr(foe.position()) <= 12 * 12).isPresent();
    }

    boolean active(Order order) {
        boolean activeNow = active.get(order.resident()) == order && valid(order.body)
            && (order.mode != Mode.ATTACK || attackTarget(order).isPresent());
        if (activeNow && order.status == Status.HOLDING
                && !Reach.standingIn(order.body.mob(), Stances.at(order.destination))) {
            order.status = Status.MOVING;
            order.movingSince = server.overworld().getGameTime();
        }
        return activeNow;
    }

    boolean arrived(Order order) {
        if (!active(order)) { return false; }
        if (order.mode == Mode.MOVE) {
            order.status = Status.ARRIVED;
            active.remove(order.resident(), order);
            return false;
        }
        order.status = Status.HOLDING;
        order.facing.ifPresent(front -> order.body.mob().getLookControl().setLookAt(
            order.body.mob().getX() + front.x * 10, order.body.mob().getEyeY(), order.body.mob().getZ() + front.z * 10));
        return true;
    }

    void ended(Order order, Ending how) {
        if (active.get(order.resident()) == order && how instanceof Ending.Dropped
                && lendsRunner(order.resident())) { return; }
        if (how instanceof Ending.Failed failed && active.remove(order.resident(), order)) {
            order.failure = failed.why();
            if (order.mode == Mode.ATTACK) {
                AttackEnd why = attackInvalidation(order);
                order.attackEnd = why == AttackEnd.NONE ? AttackEnd.FAILED : why;
            }
            order.status = Status.FAILED;
        } else if (active.get(order.resident()) == order && order.mode == Mode.ATTACK) {
            if (how instanceof Ending.Done) { order.step = UUID.randomUUID(); }
        } else if (active.get(order.resident()) == order && order.mode == Mode.GUARD) {
            // Only a command that actually owned the runner can yield it. A never-started order
            // still has its original bounded timeout instead of waiting forever for execution.
            if (order.status == Status.HOLDING) {
                order.status = Status.MOVING;
                order.movingSince = server.overworld().getGameTime();
            }
            order.yielded = true;
        }
    }

    void taking(Order order) {
        active(order);
        order.yielded = false;
    }

    void tick(MinecraftServer ignored) {
        for (Order order : List.copyOf(active.values())) {
            if (!valid(order.body) || order.mode == Mode.ATTACK && attackTarget(order).isEmpty()) {
                release(order);
            } else if (order.mode == Mode.ATTACK) {
                // A moving target replaces only this runner's short approach step, never starts another navigator.
                Mob target = attackTarget(order).orElseThrow();
                long now = server.overworld().getGameTime();
                WorldPos at = WorldSpaces.at(target);
                if (now >= order.retargetAt && !at.equals(order.approached)
                        && (order.body.mob().distanceTo(target) > order.attackRange
                            || !order.body.mob().hasLineOfSight(target))) {
                    order.step = UUID.randomUUID();
                    order.approached = at;
                    order.retargetAt = now + 10;
                }
            } else if (lendsRunner(order.resident())) {
                // Resupply borrows a stable order; its travel is not failed command travel time.
                order.movingSince++;
            } else if ((order.mode == Mode.GUARD || order.defensiveOnly())
                    && (order.engaged() || order.yielded || order.yieldingToDanger())
                    && order.status == Status.MOVING) {
                // Combat and fear borrow this same runner. Only time owned by the command is travel time.
                order.movingSince++;
            } else if (order.status == Status.MOVING && server.overworld().getGameTime() - order.movingSince >= 1200) {
                active.remove(order.resident(), order);
                order.status = Status.FAILED;
            }
            if (active.get(order.resident()) == order) { active(order); }
        }
    }

    void clear() {
        active.values().forEach(order -> {
            if (order.mode == Mode.ATTACK) { order.attackEnd = AttackEnd.CANCELLED; }
            order.status = Status.RELEASED;
        });
        active.clear();
    }

    private boolean valid(Body body) {
        return body.mob().level() instanceof ServerLevel level && level.getServer() == server
            && body.mob().isAlive() && !body.mob().isRemoved()
            && !body.mob().isPassenger()
            && body.colonyId().filter(colony.id()::equals).isPresent()
            && colony.holdingOf(body.id()).isPresent();
    }

    Optional<Mob> attackTarget(Order order) {
        if (order.mode != Mode.ATTACK || active.get(order.resident()) != order
                || attackInvalidation(order) != AttackEnd.NONE) { return Optional.empty(); }
        return Optional.ofNullable(((ServerLevel) order.body.mob().level()).getEntity(order.target))
            .filter(Mob.class::isInstance).map(Mob.class::cast);
    }

    private AttackEnd attackInvalidation(Order order) {
        if (!valid(order.body)) { return AttackEnd.CANCELLED; }
        if (server.overworld().getGameTime() - order.movingSince >= 600) { return AttackEnd.TIMED_OUT; }
        ServerPlayer commander = server.getPlayerList().getPlayer(order.commander);
        if (commander == null || commander.isSpectator() || !commander.isAlive()
                || SelectionOrders.continuing(commander, colony.id()).isEmpty()
                || commander.level() != order.body.mob().level()
                || !WorldSpaces.at(commander).sameRealm(order.destination)) { return AttackEnd.AUTHORITY_LOST; }
        ServerLevel level = (ServerLevel) order.body.mob().level();
        if (!(level.getEntity(order.target) instanceof Mob target)) { return AttackEnd.TARGET_LOST; }
        if (!target.isAlive()) { return CommandContent.defeated(target) ? AttackEnd.TARGET_DEFEATED : AttackEnd.TARGET_LOST; }
        if (!Threats.explicitlyAttackable(target, order.body.mob(), commander)
                || !level.hasChunkAt(target.blockPosition())) { return AttackEnd.TARGET_LOST; }
        if (!WorldSpaces.at(target).sameRealm(order.destination)
                || WorldSpaces.at(target).cell().distSqr(order.destination.cell()) > 64 * 64
                || order.body.mob().distanceToSqr(target) > 128 * 128 || commander.distanceToSqr(target) > 128 * 128) {
            return AttackEnd.OUT_OF_RANGE;
        }
        return AttackEnd.NONE;
    }

    boolean permitsAttack(Order order, UUID step, Worker worker, Mob target) {
        return order.step.equals(step) && worker.body() == order.body.mob()
            && worker.resident().id().equals(order.resident())
            && attackTarget(order).filter(found -> found == target).isPresent();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) { throw new IllegalStateException("resident orders belong to the server thread"); }
    }
}
