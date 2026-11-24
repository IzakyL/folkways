package io.github.izakyl.folkways.core.shell.structure;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.travel.api.Fare;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.Structures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class SablePassage implements Passage {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "sable_step");
    private static final Fare STEP = Fare.flat(10);
    private static final Fare JUMP = Fare.flat(10);
    // Four blocks bridge small gaps and tilted decks while keeping travel along each side on foot.
    private static final int JUMP_REACH = 4;
    private static final double LANDING_LIFT = 0.0625D;
    private record Contacts(int tick, List<Hop> hops) {
    }
    private final Map<Colony, Contacts> contacts = new WeakHashMap<>();

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public List<Hop> hopsIn(Colony colony) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of();
        }
        int now = server.getTickCount();
        Contacts cached = contacts.get(colony);
        if (cached != null && now >= cached.tick() && now - cached.tick() < 10) {
            return cached.hops();
        }
        List<Hop> hops = new ArrayList<>();
        for (var view : colony.views(server)) {
            ServerLevel level = view.level();
            List<? extends WorldSpaces.Frame> frames = WorldSpaces.frames(level);
            Set<UUID> structures = new LinkedHashSet<>();
            for (Claim holding : view.claims()) {
                for (WorldPos cell : holding.what().cells()) {
                    if (cell.realm() instanceof Realm.Frame frame) {
                        structures.add(frame.structure());
                    }
                }
            }
            for (var id : structures) {
                var subLevel = SubLevelContainer.getContainer(level).getSubLevel(id);
                var space = WorldSpaces.frame(level, id).orElse(null);
                if (subLevel == null || space == null) {
                    continue;
                }
                var box = subLevel.getPlot().getBoundingBox();
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    edge(level, space, x, box.minZ(), box.minY(), box.maxY(), frames, hops);
                    if (box.maxZ() != box.minZ()) {
                        edge(level, space, x, box.maxZ(), box.minY(), box.maxY(), frames, hops);
                    }
                }
                for (int z = box.minZ() + 1; z < box.maxZ(); z++) {
                    edge(level, space, box.minX(), z, box.minY(), box.maxY(), frames, hops);
                    if (box.maxX() != box.minX()) {
                        edge(level, space, box.maxX(), z, box.minY(), box.maxY(), frames, hops);
                    }
                }
            }
        }
        List<Hop> found = List.copyOf(hops);
        contacts.put(colony, new Contacts(now, found));
        return found;
    }

    private static void edge(ServerLevel level, WorldSpaces.Frame space, int x, int z, int minY, int maxY,
                             List<? extends WorldSpaces.Frame> frames, List<Hop> hops) {
        for (int y = minY + 1; y <= maxY + 1; y++) {
            BlockPos storage = new BlockPos(x, y, z);
            if (!level.isLoaded(storage) || !Footing.withFeetAt(level, storage)) {
                continue;
            }
            WorldPos aboard = new WorldPos(new Realm.Frame(space.id()), space.local(storage));
            Vec3 world = space.toWorld(Vec3.atBottomCenterOf(aboard.cell()));
            BlockPos centre = BlockPos.containing(world);
            boolean walking = false;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                WorldPos shore = WorldPos.of(level.dimension(), centre.relative(direction));
                if (connected(level, aboard, shore)) {
                    link(hops, aboard, shore, STEP);
                    walking = true;
                }
            }
            if (!safeFooting(level, aboard, storage)) {
                continue;
            }
            if (!walking) {
                jumpTo(level, hops, aboard, world, Realm.of(level), centre);
            }
            for (WorldSpaces.Frame other : frames) {
                if (!other.id().equals(space.id())) {
                    jumpTo(level, hops, aboard, world, new Realm.Frame(other.id()),
                        BlockPos.containing(other.toLocal(world)));
                }
            }
        }
    }

    private static void link(List<Hop> hops, WorldPos first, WorldPos second, Fare fare) {
        var on = new Stances.Cells(Set.of(first));
        var off = new Stances.Cells(Set.of(second));
        hops.add(new Hop(ID, on, off, fare));
        hops.add(new Hop(ID, off, on, fare));
    }

    private static void jumpTo(ServerLevel level, List<Hop> hops, WorldPos from, Vec3 world,
                               Realm realm, BlockPos centre) {
        var frame = realm instanceof Realm.Frame other
            ? WorldSpaces.frame(level, other.structure()).orElse(null) : null;
        var bounds = frame == null ? null : frame.bounds().orElse(null);
        if (bounds != null && (centre.getX() + JUMP_REACH < bounds.minX()
                || centre.getX() - JUMP_REACH > bounds.maxX()
                || centre.getY() + JUMP_REACH <= bounds.minY()
                || centre.getY() - JUMP_REACH > bounds.maxY() + 1
                || centre.getZ() + JUMP_REACH < bounds.minZ()
                || centre.getZ() - JUMP_REACH > bounds.maxZ())) {
            return;
        }
        double nearest = Math.nextUp((double) JUMP_REACH * JUMP_REACH);
        WorldPos landing = null;
        for (BlockPos cell : BlockPos.betweenClosed(centre.offset(-JUMP_REACH, -JUMP_REACH, -JUMP_REACH),
                centre.offset(JUMP_REACH, JUMP_REACH, JUMP_REACH))) {
            Vec3 local = Vec3.atBottomCenterOf(cell);
            if (bounds != null && !bounds.isInside(cell.below())) {
                continue;
            }
            Vec3 target = frame == null ? local : frame.toWorld(local);
            double distance = world.distanceToSqr(target);
            if (distance >= nearest) {
                continue;
            }
            BlockPos storage = frame == null ? cell : frame.storage(cell);
            WorldPos to = new WorldPos(realm, cell);
            if (safeFooting(level, to, storage)) {
                AABB room = new AABB(-0.3, LANDING_LIFT, -0.3, 0.3, 1.9, 0.3).move(target);
                UUID excluded = frame == null ? null : frame.id();
                if (level.noCollision(room) && Structures.obstacles(level, room, excluded).isEmpty()) {
                    nearest = distance;
                    landing = to;
                }
            }
        }
        if (landing != null) {
            link(hops, from, landing, JUMP);
        }
    }

    private static boolean safeFooting(ServerLevel level, WorldPos footing, BlockPos storage) {
        return level.isLoaded(storage) && WorldSpaces.at(level, storage.below()).realm().equals(footing.realm())
            && Footing.withFeetAt(level, storage) && Footing.holdsAbove(level, storage.below())
            && level.getFluidState(storage).isEmpty() && level.getFluidState(storage.above()).isEmpty()
            && Closures.in(level).stream().noneMatch(closed -> closed.holds(footing));
    }

    private static Going jump(ServerLevel level, Mob body, Hop hop) {
        WorldPos from = hop.boarding().cells().iterator().next();
        WorldPos to = hop.landing().cells().iterator().next();
        var source = WorldSpaces.world(level, from);
        var target = WorldSpaces.world(level, to);
        var storage = WorldSpaces.storage(level, to);
        if (body.isPassenger() || source.isEmpty() || target.isEmpty() || storage.isEmpty()
                || !WorldSpaces.at(body).realm().equals(from.realm())
                || !WorkStances.standingIn(body, hop.boarding())
                || source.get().distanceToSqr(target.get()) > JUMP_REACH * JUMP_REACH
                || body.position().distanceToSqr(target.get().add(0, LANDING_LIFT, 0)) > JUMP_REACH * JUMP_REACH
                || !safeFooting(level, to, storage.get())) {
            return new Going.Failed(() -> "folkways.refusal.sable_step_gone");
        }
        Vec3 feet = target.get().add(0, LANDING_LIFT, 0);
        AABB room = body.getBoundingBox().move(feet.subtract(body.position()));
        UUID structure = to.realm() instanceof Realm.Frame frame ? frame.structure() : null;
        if (!level.noCollision(body, room) || !Structures.obstacles(level, room, structure).isEmpty()) {
            return new Going.Failed(() -> "folkways.refusal.sable_step_gone");
        }
        body.getNavigation().stop();
        body.teleportTo(feet.x, feet.y, feet.z);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0;
        var container = SubLevelContainer.getContainer(level);
        ((EntityMovementExtension) body).sable$setTrackingSubLevel(
            structure == null ? null : container.getSubLevel(structure));
        return Going.ARRIVED;
    }

    private static boolean connected(ServerLevel level, WorldPos aboard, WorldPos shore) {
        var on = WorldSpaces.world(level, aboard);
        var off = WorldSpaces.world(level, shore);
        var storage = WorldSpaces.storage(level, aboard);
        if (on.isEmpty() || off.isEmpty() || storage.isEmpty() || !level.isLoaded(storage.get())
                || !Footing.withFeetAt(level, storage.get()) || !level.isLoaded(shore.cell())
                || !Footing.withFeetAt(level, shore.cell())) {
            return false;
        }
        var above = WorldSpaces.world(level, aboard.above());
        if (above.isEmpty() || above.get().subtract(on.get()).normalize().y < 0.95D) {
            return false;
        }
        Vec3 delta = on.get().subtract(off.get());
        if (Math.abs(delta.y) > 0.5D || delta.horizontalDistanceSqr() > 2.25D) {
            return false;
        }
        AABB corridor = new AABB(on.get(), off.get()).inflate(0.3, 0, 0.3).expandTowards(0, 1.9, 0);
        return Structures.obstacles(level, corridor, ((Realm.Frame) aboard.realm()).structure()).isEmpty() && level.noCollision(corridor.deflate(0, 0.01, 0));
    }

    @Override
    public boolean through(Hop first, Hop next) {
        return false;
    }

    @Override
    public Conveyance aboard(Hop hop) {
        return new Conveyance() {
            private int ticks;

            @Override
            public Going step(ServerLevel level, Mob body, Set<WorldPos> goals) {
                if (hop.fare() == JUMP) {
                    return jump(level, body, hop);
                }
                WorldPos from = hop.boarding().cells().iterator().next();
                WorldPos to = hop.landing().cells().iterator().next();
                WorldPos structure = from.realm() instanceof Realm.Frame ? from : to;
                WorldPos shore = from.realm() instanceof Realm.Dimension ? from : to;
                if (!connected(level, structure, shore) || ++ticks > 80) {
                    return new Going.Failed(() -> "folkways.refusal.sable_step_gone");
                }
                if (WorkStances.standingIn(body, hop.landing())) {
                    return Going.ARRIVED;
                }
                Vec3 aim = WorldSpaces.world(level, to).orElseThrow();
                body.getMoveControl().setWantedPosition(aim.x, aim.y, aim.z, 1.0D);
                return Going.UNDERWAY;
            }

            @Override
            public void release(ServerLevel level, Mob body) {
                body.getNavigation().stop();
                body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0);
            }
        };
    }
}
