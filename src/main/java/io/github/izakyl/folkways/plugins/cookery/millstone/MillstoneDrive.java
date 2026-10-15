package io.github.izakyl.folkways.plugins.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.world.phys.Vec3;

/** Runtime permission never survives a save; the real ingredients and the obligation to leave do. */
public final class MillstoneDrive {
    private static final String EXIT = "folkways.cookery.millstone.exit";
    private static final ResourceLocation LEAVING = ResourceLocation.fromNamespaceAndPath("folkways", "leaving_millstone");
    private static final Map<UUID, Lease> LEASES = new HashMap<>();
    private static final Map<UUID, Exit> EXITS = new HashMap<>();

    private MillstoneDrive() { }

    private static final class Lease {
        final UUID colony;
        final UUID batch;
        final WorldPos at;
        final MillstoneBlockEntity mill;
        final Mob actor;
        final KitchenBookings bookings;
        final UUID turn;
        final long generation;
        WorldPos exit;
        long surroundings;
        boolean driving = true;
        Lease(UUID colony, UUID batch, WorldPos at, MillstoneBlockEntity mill, Mob actor,
              KitchenBookings bookings, UUID turn, long generation, WorldPos exit) {
            this.colony = colony; this.batch = batch; this.at = at; this.mill = mill; this.actor = actor;
            this.bookings = bookings; this.turn = turn; this.generation = generation; this.exit = exit;
            surroundings = surroundings((ServerLevel) actor.level(), at);
        }
        boolean valid() {
            return driving && actor.isAlive() && !actor.isPassenger() && actor.hurtTime == 0
                && !mill.isRemoved() && bookings.valid(at, turn, generation) && bookings.first(at, turn);
        }
    }
    private static final class Exit {
        final WorldPos at;
        final UUID colony;
        final Mob actor;
        WorldPos target;
        Conveyance path;
        float started;
        boolean awaited;
        long stepped = Long.MIN_VALUE;
        Exit(WorldPos at, UUID colony, Mob actor, WorldPos target, boolean awaited) {
            this.at = at; this.colony = colony; this.actor = actor; this.target = target; this.awaited = awaited;
        }
    }

    public static boolean permitted(MillstoneBlockEntity mill, LivingEntity entity) {
        Lease lease = LEASES.get(entity.getUUID());
        return lease != null && lease.mill == mill && lease.actor == entity && lease.valid();
    }

    static Optional<WorldPos> exit(ServerLevel level, WorldPos at, Mob actor) {
        var body = Bodies.of(actor);
        if (body.isEmpty()) { return Optional.empty(); }
        body.get().kind().locomotion().fit(actor);
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos center = at.block(level);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) < 3) { continue; }
                BlockPos feet = center.offset(x, 0, z);
                WorldPos goal = new WorldPos(at.realm(), at.cell().offset(x, 0, z));
                if (level.isLoaded(feet) && Footing.withFeetAt(level, feet) && Reach.withinReach(feet, center)
                        && WorldSpaces.world(level, goal).filter(world -> actor.distanceToSqr(world) >= 1.5).isPresent()) {
                    candidates.add(feet);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(feet -> WorldSpaces.world(level,
            WorldPos.of(level, feet)).map(actor::distanceToSqr).orElse(Double.MAX_VALUE)));
        for (BlockPos feet : candidates) {
            var path = actor.getNavigation().createPath(Set.of(feet), 0);
            if (path != null && path.canReach()) { return Optional.of(WorldPos.of(level, feet)); }
        }
        return Optional.empty();
    }

    static boolean bind(UUID colony, UUID batch, ServerLevel level, WorldPos at, Mob actor,
                        KitchenBookings bookings, UUID turn, long generation) {
        var mill = MillstoneStation.mill(level, at);
        Optional<WorldPos> exit = exit(level, at, actor);
        if (mill.isEmpty() || exit.isEmpty() || mill.get().hasEntity() || LEASES.containsKey(actor.getUUID())
                || exiting(actor) || !bookings.valid(at, turn, generation) || !bookings.first(at, turn)) {
            return false;
        }
        Lease lease = new Lease(colony, batch, at, mill.get(), actor, bookings, turn, generation, exit.get());
        LEASES.put(actor.getUUID(), lease);
        mill.get().bindEntity(actor);
        if (!mill.get().isBoundTo(actor)) {
            LEASES.remove(actor.getUUID());
            return false;
        }
        actor.getNavigation().stop();
        return true;
    }

    public static void before(MillstoneBlockEntity mill, UUID entity) {
        if (!(mill.getLevel() instanceof ServerLevel level)
                || !(level.getEntity(entity) instanceof Mob actor) || Bodies.of(actor).isEmpty()) {
            return;
        }
        Lease lease = LEASES.get(entity);
        if (lease == null || lease.mill != mill || !lease.valid()
                || MillstoneBatch.read(mill, level).filter(batch -> batch.id().equals(lease.batch)).isEmpty()) {
            mill.unbindEntity();
            mill.refresh();
            if (lease != null && lease.mill == mill) {
                detach(lease, false);
            } else {
                WorldPos at = WorldPos.of(level, mill.getBlockPos());
                UUID colony = Bodies.of(actor).flatMap(body -> body.colonyId()).orElse(new UUID(0, 0));
                queueExit(actor, at, colony, exit(level, at, actor).orElse(null), false);
            }
        } else {
            long current = surroundings(level, lease.at);
            if (current != lease.surroundings) {
                lease.surroundings = current;
                lease.exit = exit(level, lease.at, actor).orElse(null);
                if (lease.exit == null) { detach(lease, false); }
            }
        }
    }

    // Native movement is controlled by the mill, so rebuilding paths every rotation tick is unnecessary.
    // Recheck a real path only when nearby support or collision blocks actually change.
    private static long surroundings(ServerLevel level, WorldPos at) {
        long fingerprint = 0xcbf29ce484222325L;
        BlockPos center = at.block(level);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -1; y <= 2; y++) {
                    BlockPos cell = center.offset(x, y, z);
                    fingerprint = (fingerprint ^ (level.isLoaded(cell) ? Block.getId(level.getBlockState(cell)) : -1)) * 0x100000001b3L;
                }
            }
        }
        return fingerprint;
    }

    public static void after(MillstoneBlockEntity mill) {
        if (!(mill.getLevel() instanceof ServerLevel level)) { return; }
        for (Lease lease : List.copyOf(LEASES.values())) {
            if (lease.mill != mill || !lease.driving) { continue; }

            // A native tick may replace input with actual outputs. Only an accepted running lease may adopt that change.
            CompoundTag tag = mill.getPersistentData().getCompound(MillstoneBatch.KEY);
            if (!tag.hasUUID("batch") || !tag.getUUID("batch").equals(lease.batch)) {
                detach(lease, false);
                continue;
            }
            boolean ready = mill.getInput().isEmpty();
            ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
            if (recipe != null) {
                MillstoneBatch.remember(mill, level, new MillstoneBatch.Batch(lease.colony, lease.batch, recipe, ready));
            }
            if (ready || !mill.isBoundTo(lease.actor)) { detach(lease, ready); }
        }
    }

    private static void detach(Lease lease, boolean awaited) {
        if (lease.mill.isBoundTo(lease.actor)) {
            lease.mill.setCacheRot(lease.mill.getRotation(lease.actor.level(), 0));
            lease.mill.unbindEntity();
            lease.mill.refresh();
        }
        lease.driving = false;
        queueExit(lease.actor, lease.at, lease.colony, lease.exit, awaited);
        if (!awaited) { LEASES.remove(lease.actor.getUUID()); }
    }

    static void interrupt(Mob actor) {
        Lease lease = LEASES.get(actor.getUUID());
        if (lease != null) { detach(lease, false); }
        Exit exit = EXITS.get(actor.getUUID());
        if (exit != null) {
            exit.awaited = false;
            if (exit.path != null && actor.level() instanceof ServerLevel level) { exit.path.release(level, actor); }
            exit.path = null;
        }
    }

    private static void queueExit(Mob actor, WorldPos at, UUID colony, WorldPos target, boolean awaited) {
        if (!actor.isAlive() || actor.isPassenger()) { return; }
        installExitGoal(actor);
        Exit existing = EXITS.get(actor.getUUID());
        if (existing != null) { existing.awaited = awaited; return; }
        EXITS.put(actor.getUUID(), new Exit(at, colony, actor, target, awaited));
        CompoundTag data = new CompoundTag();
        data.put("station", at.save());
        data.putUUID("colony", colony);
        if (target != null) { data.put("target", target.save()); }
        actor.getPersistentData().put(EXIT, data);
    }

    static boolean exiting(Mob actor) { return actor.getPersistentData().contains(EXIT); }

    static boolean leave(ServerLevel level, Mob actor) {
        if (!exiting(actor)) { return true; }
        Exit exit = EXITS.get(actor.getUUID());
        if (exit == null) {
            CompoundTag data = actor.getPersistentData().getCompound(EXIT);
            Optional<WorldPos> at = WorldPos.load(Reader.of(data.getCompound("station")));
            if (at.isEmpty() || !data.hasUUID("colony")) { return false; }
            exit = new Exit(at.get(), data.getUUID("colony"), actor, null, false);
            EXITS.put(actor.getUUID(), exit);
        }
        if (!actor.isAlive() || actor.isPassenger() || !exit.at.in(level)) { return false; }
        if (exit.stepped == level.getGameTime()) { return false; }
        exit.stepped = level.getGameTime();
        if (exit.target == null || !Footing.withFeetAt(level, exit.target.block(level))) {
            if (exit.path != null) { exit.path.release(level, actor); }
            exit.path = null;
            exit.target = exit(level, exit.at, actor).orElse(null);
        }
        if (exit.target == null) { return false; }
        if (exit.path == null) {
            var body = Bodies.of(actor).orElseThrow();
            body.kind().locomotion().fit(actor);
            exit.path = body.kind().locomotion().along(1.0, CookeryRefusal.STATION_CHANGED);
            exit.started = actor.walkDist;
            exit.path.setOut(level, actor, Set.of(exit.target));
        }
        Going state = exit.path.step(level, actor, Set.of(exit.target));
        if (state instanceof Going.Arrived && actor.walkDist > exit.started + 0.05F) {
            exit.path.release(level, actor);
            EXITS.remove(actor.getUUID());
            LEASES.remove(actor.getUUID());
            actor.getPersistentData().remove(EXIT);
            return true;
        }
        if (state instanceof Going.Failed) {
            exit.path.release(level, actor);
            exit.path = null;
            exit.target = null;
        }
        return false;
    }

    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob actor && exiting(actor)) {
            installExitGoal(actor);
        }
    }

    private static void installExitGoal(Mob actor) {
        if (actor.goalSelector.getAvailableGoals().stream().noneMatch(goal -> goal.getGoal() instanceof ExitGoal)) {
            actor.goalSelector.addGoal(-1, new ExitGoal(actor));
        }
    }

    // A destroyed colony has no urge source left. Its former residents still finish their physical exit.
    private static final class ExitGoal extends Goal {
        private final Mob actor;
        ExitGoal(Mob actor) { this.actor = actor; setFlags(EnumSet.of(Flag.MOVE)); }
        public boolean canUse() {
            Exit exit = EXITS.get(actor.getUUID());
            return actor.isAlive() && !actor.isPassenger() && exiting(actor) && (exit == null || !exit.awaited);
        }
        public boolean canContinueToUse() { return canUse(); }
        public boolean requiresUpdateEveryTick() { return true; }
        public void tick() {
            if (actor.level() instanceof ServerLevel level) { leave(level, actor); }
        }
        public void stop() {
            Exit exit = EXITS.get(actor.getUUID());
            if (exit != null && exit.path != null && actor.level() instanceof ServerLevel level) {
                exit.path.release(level, actor);
                exit.path = null;
            }
        }
    }
    public static List<Urge> urges(Colony colony, Worker who, ColonyView view) {
        Mob actor = who.body();
        Exit exit = EXITS.get(actor.getUUID());
        if (!exiting(actor) || !actor.isAlive() || actor.isPassenger() || exit != null && exit.awaited) {
            return List.of();
        }
        CompoundTag saved = actor.getPersistentData().getCompound(EXIT);
        if (saved.hasUUID("colony") && !saved.getUUID("colony").equals(new UUID(0, 0))
                && !saved.getUUID("colony").equals(colony.id())) { return List.of(); }
        return List.of(new Urge(LEAVING, Urge.DIRE + 0.1, true, () -> new Leaving(actor)));
    }
    private static final class Leaving implements Node, Workload.Continuous {
        private final NodeSpec spec;
        Leaving(Mob actor) {
            spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtEntity(actor.getUUID(), WorldSpaces.at(actor)),
                Stances.WHEREVER, this).doing(LEAVING).done();
        }
        public NodeSpec spec() { return spec; }
        public boolean holds(ServerLevel level, Worker who) { return !leave(level, who.body()); }
        public Outcome commit(ServerLevel level, Worker who) {
            return exiting(who.body()) ? Outcome.failed(CookeryRefusal.STATION_CHANGED) : Outcome.done();
        }
        public void released(ServerLevel level, Worker who, Ending how) { interrupt(who.body()); }
    }

    public static boolean blocked(ServerLevel level, WorldPos at) {
        return EXITS.values().stream().anyMatch(exit -> exit.at.equals(at) && at.in(level)
            && (exit.target == null || !Footing.withFeetAt(level, exit.target.block(level))));
    }

    private static void forget(Mob actor) {
        Lease lease = LEASES.remove(actor.getUUID());
        if (lease != null && lease.mill.isBoundTo(actor)) {
            lease.mill.unbindEntity();
            lease.mill.refresh();
        }
        Exit exit = EXITS.remove(actor.getUUID());
        if (exit != null && exit.path != null && actor.level() instanceof ServerLevel level) {
            exit.path.release(level, actor);
        }
        actor.getPersistentData().remove(EXIT);
    }

    public static void tick(ServerLevel level, UUID colony) {
        for (Lease lease : List.copyOf(LEASES.values())) {
            if (!lease.colony.equals(colony) || lease.actor.level() != level) { continue; }
            if (!lease.actor.isAlive() || lease.actor.isRemoved()) { forget(lease.actor); }
            else if (lease.driving && (!lease.valid() || !lease.mill.isBoundTo(lease.actor))) { detach(lease, false); }
        }
        for (Exit exit : List.copyOf(EXITS.values())) {
            if (exit.colony.equals(colony) && exit.actor.level() == level && (!exit.actor.isAlive() || exit.actor.isRemoved())) {
                forget(exit.actor);
            }
        }
    }
    public static void release(UUID colony, ServerLevel level, WorldPos at) {
        for (Lease lease : List.copyOf(LEASES.values())) {
            if (lease.colony.equals(colony) && lease.actor.level() == level && lease.at.equals(at)) { detach(lease, false); }
        }
    }
    public static void stop(UUID colony) {
        for (Lease lease : List.copyOf(LEASES.values())) {
            if (lease.colony.equals(colony)) { detach(lease, false); }
        }
        EXITS.entrySet().removeIf(entry -> entry.getValue().colony.equals(colony));
    }

    public static Vec3 project(MillstoneBlockEntity mill, LivingEntity actor, Vec3 stored) {
        if (!permitted(mill, actor)) { return stored; }
        return WorldSpaces.containing(actor.level(), mill.getBlockPos())
            .map(frame -> frame.toWorld(stored.subtract(Vec3.atLowerCornerOf(frame.storageOrigin())))).orElse(stored);
    }
}
