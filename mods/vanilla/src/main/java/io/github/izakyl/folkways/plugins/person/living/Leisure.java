package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

// Reservations live only while actions do. Cancellation, unloading and a missed rendezvous release both ends.
final class Leisure {
    static final ResourceLocation URGE = ResourceLocation.fromNamespaceAndPath("folkways", "leisure");
    private final Colony colony;
    private final LivingPresence living;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> pets = new HashMap<>();
    private final Map<WorldPos, UUID> positions = new HashMap<>();
    private final Map<UUID, Long> nextTry = new HashMap<>();

    Leisure(Colony colony, LivingPresence living) { this.colony = colony; this.living = living; }

    Optional<Urge> urge(Worker who, ColonyView view) {
        UUID id = who.resident().id();
        Session active = sessions.get(id);
        if (active != null) {
            boolean invitedToWork = id.equals(active.peer) && !who.route().isEmpty()
                && Bodies.of(who.body()).flatMap(body -> body.doing())
                    .filter(doing -> doing.what().getPath().equals("chatting")).isEmpty();
            if (active.cancelled || invitedToWork || unsafe(who, view.level(), true)
                    || view.level().getGameTime() > active.expires) {
                cancel(id); return Optional.empty();
            }
            return Optional.of(new Urge(URGE, active.restBreak && !id.equals(active.peer) ? Urge.SPARE : Urge.IDLE, false,
                () -> new Activity(id, active, view.level())));
        }
        long now = view.level().getGameTime();
        Mood mood = living.mood(id);
        boolean restBreak = mood.leisure < 20 && now >= mood.nextBreak;
        if ((!who.route().isEmpty() && !restBreak) || now < nextTry.getOrDefault(id, 0L)
                || unsafe(who, view.level(), false)) {
            return Optional.empty();
        }
        return Optional.of(new Urge(URGE, restBreak ? Urge.SPARE : Urge.IDLE, false,
            () -> choose(who, view, restBreak)));
    }

    private static boolean unsafe(Worker who, ServerLevel level, boolean underway) {
        if (who.body().getLastHurtByMob() != null
                && who.body().tickCount - who.body().getLastHurtByMobTimestamp() < 200) { return true; }
        // Stagger ongoing checks; a newly offered activity always checks immediately.
        if (underway && (who.body().tickCount + who.body().getId()) % 20 != 0) { return false; }
        return !level.getEntitiesOfClass(Mob.class, who.body().getBoundingBox().inflate(10),
            mob -> mob instanceof Enemy).isEmpty();
    }

    private Node choose(Worker who, ColonyView view, boolean restBreak) {
        ServerLevel level = view.level();
        UUID id = who.resident().id();
        // An invitation may have arrived after the urge was offered.
        if (sessions.containsKey(id)) { return new Activity(id, sessions.get(id), level); }
        long now = level.getGameTime();
        Session selected = null;
        int first = level.random.nextInt(3);
        for (int offset = 0; offset < 3 && selected == null; offset++) {
            int kind = (first + offset) % 3;
            if (kind == 0) {
                for (var resident : view.residents()) {
                    if (resident.id().equals(id) || sessions.containsKey(resident.id())) { continue; }
                    Entity found = level.getEntity(resident.id());
                    if (!(found instanceof ResidentEntity peer) || peer.doing().isPresent()
                            || peer.distanceToSqr(who.body()) > 100 || peer.isSleeping()) { continue; }
                    BlockPos at = beside(level, WorldSpaces.at(peer).block(level));
                    if (at == null) { continue; }
                    selected = new Session("chatting", 400, 18, resident.id(), null, at,
                        now + 800, restBreak);
                    sessions.put(resident.id(), selected);
                    break;
                }
            } else if (kind == 1) {
                for (TamableAnimal pet : level.getEntitiesOfClass(TamableAnimal.class,
                        who.body().getBoundingBox().inflate(10), pet -> pet instanceof Cat || pet instanceof Wolf || pet instanceof Parrot)) {
                    boolean memberPet = pet.isTame() && pet.getOwnerUUID() != null
                        && Charters.member(level.getServer(), colony, pet.getOwnerUUID());
                    boolean colonyPet = !pet.isTame() && ColonyPets.belongsTo(pet, colony);
                    if ((!memberPet && !colonyPet) || !pet.isAlive() || pet.getTarget() != null
                            || pet.getHealth() < pet.getMaxHealth() || pets.containsKey(pet.getUUID())
                            || pet.isPassenger()) { continue; }
                    BlockPos at = beside(level, WorldSpaces.at(pet).block(level));
                    if (at == null) { continue; }
                    selected = new Session("petting", 400, 20, null, pet.getUUID(), at, now + 800, restBreak);
                    pets.put(pet.getUUID(), id);
                    break;
                }
            } else {
                BlockPos at = walkTo(level, WorldSpaces.at(who.body()).block(level));
                if (at != null) {
                    boolean garden = !level.getBlockState(at.below()).isAir()
                        && BlockPos.betweenClosedStream(at.offset(-2, 0, -2), at.offset(2, 1, 2))
                            .anyMatch(pos -> level.getBlockState(pos).is(BlockTags.FLOWERS));
                    selected = new Session(garden ? "relaxing" : "strolling", 400, garden ? 15 : 8,
                        null, null, at, now + 1000, restBreak);
                }
            }
        }
        if (selected == null) {
            selected = new Session("relaxing", 300, 10, null, null, WorldSpaces.at(who.body()).block(level), now + 600, restBreak);
        }
        positions.put(WorldPos.of(level, selected.at), id);
        sessions.put(id, selected);
        return new Activity(id, selected, level);
    }

    private static BlockPos beside(ServerLevel level, BlockPos at) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos feet = at.relative(side);
            if (level.isLoaded(feet) && Footing.withFeetAt(level, feet) && level.getFluidState(feet).isEmpty()) { return feet; }
        }
        return null;
    }

    private static BlockPos walkTo(ServerLevel level, BlockPos from) {
        for (int i = 0; i < 12; i++) {
            BlockPos at = from.offset(level.random.nextInt(13) - 6, level.random.nextInt(3) - 1, level.random.nextInt(13) - 6);
            if (level.isLoaded(at) && Footing.withFeetAt(level, at) && level.getFluidState(at).isEmpty()) { return at; }
        }
        return null;
    }

    void retain(Set<UUID> residents) {
        for (UUID id : List.copyOf(sessions.keySet())) { if (!residents.contains(id)) { cancel(id); } }
        nextTry.keySet().retainAll(residents);
    }

    void cancel(UUID id) {
        positions.values().removeIf(id::equals);
        Session session = sessions.remove(id);
        if (session == null) { return; }
        // Keep the other participant's action alive only long enough to notice its invitation ended.
        session.cancelled = true;
        if (session.pet != null) { pets.remove(session.pet); }
    }

    void clear() { sessions.clear(); pets.clear(); positions.clear(); nextTry.clear(); }

    private static final class Session {
        final String kind;
        final int duration;
        final double recovery;
        final UUID peer, pet;
        final BlockPos at;
        final long expires;
        final boolean restBreak;
        boolean cancelled;
        Session(String kind, int duration, double recovery, UUID peer, UUID pet, BlockPos at, long expires, boolean restBreak) {
            this.kind = kind; this.duration = duration; this.recovery = recovery; this.peer = peer; this.pet = pet;
            this.at = at; this.expires = expires; this.restBreak = restBreak;
        }
    }

    private final class Activity implements Node, Workload.Continuous {
        final UUID resident;
        final Session session;
        final NodeSpec spec;
        int elapsed, enjoyed, waiting;
        boolean finished;
        Activity(UUID resident, Session session, ServerLevel level) {
            this.resident = resident; this.session = session;
            // The invited resident stays put; the initiator walks beside them.
            BlockPos at = session.peer != null && session.peer.equals(resident)
                ? Optional.ofNullable(level.getEntity(resident)).map(entity -> WorldSpaces.at(entity).block(level)).orElse(session.at) : session.at;
            spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.at(level, at), Stances.at(level, at), this)
                .effort(WorkEffort.NONE).pace(0.75).doing(ResourceLocation.fromNamespaceAndPath("folkways", session.kind))
                .gesture(new WorkGesture() {
                    @Override public void perform(LivingEntity body, int tick, BlockPos focus) {
                        if (body instanceof ResidentEntity person) {
                            person.leisureMotion(session.kind, session.peer != null && session.peer.equals(person.getUUID()) ? 1 : 0);
                            Entity target = target(level);
                            if (target != null) { person.getLookControl().setLookAt(target, 30, 30); }
                            else {
                                double angle = (tick / 80 + person.getId()) * 1.7;
                                person.getLookControl().setLookAt(person.getX() + Math.cos(angle) * 3,
                                    person.getEyeY(), person.getZ() + Math.sin(angle) * 3, 20, 20);
                            }
                        }
                    }
                    @Override public void release(LivingEntity body) {
                        if (body instanceof ResidentEntity person) { person.leisureMotion(""); }
                    }
                }).done();
        }
        Entity target(ServerLevel level) {
            if (session.pet != null) { return level.getEntity(session.pet); }
            if (session.peer == null) { return null; }
            return sessions.entrySet().stream().filter(entry -> entry.getValue() == session && !entry.getKey().equals(resident))
                .findFirst().map(entry -> level.getEntity(entry.getKey())).orElse(null);
        }
        @Override public NodeSpec spec() { return spec; }
        @Override public boolean holds(ServerLevel level, Worker who) {
            if (session.cancelled || level.getGameTime() > session.expires) { return false; }
            if ((!session.restBreak || resident.equals(session.peer)) && elapsed + waiting >= 40 && !who.route().isEmpty()) { return false; }
            Entity target = target(level);
            if ((session.pet != null || session.peer != null) && (target == null || !target.isAlive())) { return false; }
            if (session.peer != null && target instanceof ResidentEntity peer) {
                boolean chatting = peer.doing().filter(doing -> doing.what().getPath().equals("chatting")).isPresent();
                if (!chatting || target.distanceToSqr(who.body()) > 9) {
                    // The invited partner waits while the initiator walks to the meeting point.
                    return elapsed == 0 && ++waiting < 200;
                }
            } else if (session.pet != null && target.distanceToSqr(who.body()) > 9) { return false; }
            if (++elapsed >= session.duration) { return false; }
            double recovery = session.recovery;
            if (session.kind.equals("chatting")) {
                double social = who.effects().value(Stats.SOCIAL_RECOVERY);
                if (target != null) {
                    social = Math.max(social, Bodies.of(target)
                        .map(body -> body.effects().value(Stats.SOCIAL_RECOVERY)).orElse(1.0));
                }
                recovery *= social;
            }
            living.mood(resident).enjoyed(session.kind, 1, session.duration, recovery, level.getGameTime());
            enjoyed++;
            living.changed();
            return true;
        }
        @Override public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        @Override public void released(ServerLevel level, Worker who, Ending how) { finish(level); }
        @Override public void ended(ServerLevel level, Ending how) { finish(level); }
        private void finish(ServerLevel level) {
            if (finished) { return; }
            finished = true;
            if (enjoyed >= session.duration / 2) {
                living.mood(resident).repetitions.merge(session.kind, 1, Integer::sum);
            }
            if (session.restBreak && !resident.equals(session.peer) && enjoyed > 0) { living.mood(resident).nextBreak = level.getGameTime() + Mood.DAY / 2; }
            nextTry.put(resident, level.getGameTime() + 100 + level.random.nextInt(100));
            if (sessions.get(resident) == session) { cancel(resident); }
            living.changed();
        }
    }
}
