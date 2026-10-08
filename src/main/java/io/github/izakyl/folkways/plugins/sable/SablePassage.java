package io.github.izakyl.folkways.plugins.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.passage.Fare;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Stances;
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
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "sable_step");
    private static final Fare STEP = Fare.flat(10);
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
            Set<UUID> structures = new LinkedHashSet<>();
            for (Holding holding : view.holdings()) {
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
                    edge(level, space, x, box.minZ(), box.minY(), box.maxY(), hops);
                    if (box.maxZ() != box.minZ()) {
                        edge(level, space, x, box.maxZ(), box.minY(), box.maxY(), hops);
                    }
                }
                for (int z = box.minZ() + 1; z < box.maxZ(); z++) {
                    edge(level, space, box.minX(), z, box.minY(), box.maxY(), hops);
                    if (box.maxX() != box.minX()) {
                        edge(level, space, box.maxX(), z, box.minY(), box.maxY(), hops);
                    }
                }
            }
        }
        List<Hop> found = List.copyOf(hops);
        contacts.put(colony, new Contacts(now, found));
        return found;
    }

    private void edge(ServerLevel level, WorldSpaces.Frame space, int x, int z, int minY, int maxY,
                      List<Hop> hops) {
        for (int y = minY + 1; y <= maxY + 1; y++) {
            BlockPos storage = new BlockPos(x, y, z);
            if (!level.isLoaded(storage) || !Footing.withFeetAt(level, storage)) {
                continue;
            }
            WorldPos aboard = new WorldPos(new Realm.Frame(space.id()), space.local(storage));
            Vec3 world = space.toWorld(Vec3.atBottomCenterOf(aboard.cell()));
            Vec3 up = space.toWorld(Vec3.atBottomCenterOf(aboard.cell()).add(0, 1, 0)).subtract(world);
            if (up.normalize().y < 0.95D) {
                continue;
            }
            BlockPos centre = BlockPos.containing(world);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos ground = centre.relative(direction);
                WorldPos shore = WorldPos.of(level.dimension(), ground);
                if (connected(level, aboard, shore)) {
                    var on = new Stances.Cells(Set.of(aboard));
                    var off = new Stances.Cells(Set.of(shore));
                    hops.add(new Hop(ID, on, off, STEP));
                    hops.add(new Hop(ID, off, on, STEP));
                }
            }
        }
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
        return Structures.obstacles(level, corridor).isEmpty() && level.noCollision(corridor.deflate(0, 0.01, 0));
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
                WorldPos from = hop.boarding().cells().iterator().next();
                WorldPos to = hop.landing().cells().iterator().next();
                WorldPos structure = from.realm() instanceof Realm.Frame ? from : to;
                WorldPos shore = from.realm() instanceof Realm.Dimension ? from : to;
                if (!connected(level, structure, shore) || ++ticks > 80) {
                    return new Going.Failed(() -> "folkways.refusal.sable_step_gone");
                }
                if (Reach.standingIn(body, hop.landing())) {
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
