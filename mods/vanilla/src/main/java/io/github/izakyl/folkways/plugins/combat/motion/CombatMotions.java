package io.github.izakyl.folkways.plugins.combat.motion;

import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Optional native actions, opened only by the current runner and revoked before another controller acts. */
public final class CombatMotions {
    public enum Kind { DODGE, SPRINT }
    public record Request(Kind kind, Vec3 direction, BooleanSupplier authorized, Predicate<Vec3> within) {
        public Request {
            Objects.requireNonNull(kind); Objects.requireNonNull(direction);
            Objects.requireNonNull(authorized); Objects.requireNonNull(within);
            if (!Double.isFinite(direction.lengthSqr()) || direction.horizontalDistanceSqr() < 1.0E-6) {
                throw new IllegalArgumentException("combat motion needs a finite horizontal direction");
            }
            direction = direction.multiply(1, 0, 1).normalize();
        }
    }
    public interface Adapter {
        boolean supports(Worker worker, Kind kind);
        Optional<Session> open(Worker worker, Request request);
    }
    public interface Session extends AutoCloseable {
        boolean tick();
        @Override void close();
    }
    private record Entry(ResourceLocation id, Adapter adapter) { }
    private static volatile List<Entry> adapters = List.of();
    private static final Map<Mob, Session> active = new WeakHashMap<>();
    private CombatMotions() { }

    public static synchronized void register(ResourceLocation id, Adapter adapter) {
        Objects.requireNonNull(id); Objects.requireNonNull(adapter);
        if (adapters.stream().anyMatch(entry -> entry.id().equals(id))) throw new IllegalArgumentException("duplicate combat motion: " + id);
        List<Entry> next = new ArrayList<>(adapters); next.add(new Entry(id, adapter)); adapters = List.copyOf(next);
    }
    public static boolean supports(Worker worker, Kind kind) {
        return adapters.stream().anyMatch(entry -> entry.adapter().supports(worker, kind));
    }
    public static Optional<Session> open(Worker worker, Request request) {
        Mob body = worker.body();
        if (!(body.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !body.isAlive() || body.isRemoved() || active.containsKey(body) || !request.authorized().getAsBoolean()) return Optional.empty();
        for (Entry entry : adapters) {
            if (!entry.adapter().supports(worker, request.kind())) continue;
            Optional<Session> nativeAction = entry.adapter().open(worker, request);
            if (nativeAction.isEmpty()) continue;
            Session tracked = new Session() {
                private boolean closed;
                public boolean tick() {
                    if (closed) return false;
                    if (!request.authorized().getAsBoolean() || !nativeAction.get().tick()) { close(); return false; }
                    return true;
                }
                public void close() {
                    if (closed) return;
                    closed = true; active.remove(body, this); nativeAction.get().close();
                }
            };
            active.put(body, tracked); return Optional.of(tracked);
        }
        return Optional.empty();
    }
    public static void tick(Mob body) { Session session = active.get(body); if (session != null) session.tick(); }
    public static void stop(Mob body) { Session session = active.remove(body); if (session != null) session.close(); }
}
