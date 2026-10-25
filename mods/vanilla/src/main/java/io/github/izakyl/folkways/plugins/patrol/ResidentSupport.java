package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroup;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** One actual assault recruits a local, finite response, not a new patrol or a chain of village alarms. */
final class ResidentSupport {
    private static final int DURATION = 200;
    private final Colony colony;
    private final Map<UUID, Ticket> tickets = new HashMap<>();
    private final Map<UUID, Long> alertedUntil = new HashMap<>();
    private final Map<UUID, Long> recruitedUntil = new HashMap<>();

    final class Ticket {
        final UUID id = UUID.randomUUID();
        final WeakReference<Mob> helper;
        final WeakReference<Mob> victim;
        final WeakReference<Mob> foe;
        final ResidentCommands.Order order;
        final WorldPos anchor;
        final WorldPos from;
        final long until;

        Ticket(Body helper, Mob victim, Mob foe, ResidentCommands.Order order, long now) {
            this.helper = new WeakReference<>(helper.mob());
            this.victim = new WeakReference<>(victim);
            this.foe = new WeakReference<>(foe);
            this.order = order;
            anchor = WorldSpaces.at(victim);
            from = WorldSpaces.at(helper.mob());
            until = now + DURATION;
        }

        boolean within(WorldPos point) {
            Mob body = helper.get();
            if (body == null || !(body.level() instanceof ServerLevel level)) return false;
            return near(level, anchor, point, 12) && near(level, from, point, 32)
                && (order == null || near(level, order.destination(), point, 12));
        }

        boolean valid() {
            Mob body = helper.get(), defended = victim.get(), target = foe.get();
            if (body == null || defended == null || target == null || !(body.level() instanceof ServerLevel level)
                    || level.getGameTime() >= until || !loaded(level, body) || !loaded(level, defended) || !loaded(level, target)) return false;
            Body person = Bodies.of(body).orElse(null);
            return person != null && member(person) && Bodies.of(defended).filter(ResidentSupport.this::member).isPresent()
                && tickets.get(person.id()) == this
                && eligible(person, order) && from.sameRealm(WorldSpaces.at(body))
                && near(level, from, WorldSpaces.at(body), 32)
                && (order == null || near(level, order.destination(), WorldSpaces.at(body), 12))
                && within(WorldSpaces.at(target))
                && Threats.hostile(target, defended) && !target.isAlliedTo(body) && !body.isAlliedTo(target);
        }
    }

    ResidentSupport(Colony colony) { this.colony = colony; }

    void attacked(Body victim, Mob foe) {
        if (!(victim.mob().level() instanceof ServerLevel level) || !member(victim)
                || !loaded(level, foe) || !Threats.hostile(foe, victim.mob())) return;
        long now = level.getGameTime();
        prune(now);
        // Being struck while responding must not recruit another ring of helpers.
        if (recruitedUntil.getOrDefault(victim.id(), Long.MIN_VALUE) > now
                || alertedUntil.getOrDefault(foe.getUUID(), Long.MIN_VALUE) > now) return;
        alertedUntil.put(foe.getUUID(), now + DURATION * 2L);
        level.getEntitiesOfClass(Mob.class, victim.mob().getBoundingBox().inflate(24), mob -> mob != victim.mob()
                && mob.distanceToSqr(victim.mob()) <= 24 * 24)
            .stream().sorted(Comparator.comparingDouble(victim.mob()::distanceToSqr))
            .map(Bodies::of).flatMap(Optional::stream).filter(this::member)
            .filter(body -> WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(victim.mob())))
            .filter(body -> eligible(body, ResidentCommands.of(colony).current(body.id()).orElse(null)))
            .filter(body -> Weapons.armed(new Inspection(body)))
            .filter(body -> ResidentCommands.of(colony).current(body.id())
                .map(order -> near(level, order.destination(), WorldSpaces.at(foe), 12)).orElse(true))
            .filter(body -> !tickets.containsKey(body.id())).limit(8).forEach(body -> {
                var order = ResidentCommands.of(colony).current(body.id()).orElse(null);
                Ticket ticket = new Ticket(body, victim.mob(), foe, order, now);
                // Recruitment is 24 blocks; only the foe and firing cells use the incident's 12-block anchor.
                if (near(level, ticket.anchor, WorldSpaces.at(foe), 12)
                        && (order == null || near(level, order.destination(), WorldSpaces.at(foe), 12))) {
                    tickets.put(body.id(), ticket);
                    recruitedUntil.put(body.id(), ticket.until);
                }
            });
    }

    Optional<Ticket> ticket(Worker worker) {
        Ticket ticket = tickets.get(worker.resident().id());
        if (ticket == null) return Optional.empty();
        if (!ticket.valid() || ticket.helper.get() != worker.body() || !Weapons.armed(worker)) {
            tickets.remove(worker.resident().id(), ticket);
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    List<Urge> urge(Worker worker, Ticket ticket, Stances stances) {
        Mob foe = ticket.foe.get();
        if (foe == null) return List.of();
        var bounded = Stances.of(stances.cells().stream().filter(ticket::within).toList());
        if (bounded.isEmpty()) return List.of();
        if (ticket.order != null) ticket.order.engaging();
        ResourceLocation intent = ResourceLocation.fromNamespaceAndPath("folkways", "support/" + ticket.id + "/" + foe.getUUID());
        return List.of(new Urge(intent, Urge.DIRE + .5, true,
            () -> worker.body() instanceof ResidentEntity
                ? new StrikeNode((ServerLevel) worker.body().level(), foe, bounded.get(), ticket::valid)
                : new ToolStrikeNode((ServerLevel) worker.body().level(), foe, bounded.get(), ticket::valid)));
    }

    private boolean eligible(Body body, ResidentCommands.Order captured) {
        Mob mob = body.mob();
        if (!mob.isAlive() || mob.isRemoved() || mob.isPassenger() || mob.isOnFire()
                || mob.getHealth() <= mob.getMaxHealth() * .35F
                || body.doing().filter(doing -> doing.toward().orElse(doing.what()).getPath().equals("fleeing")).isPresent()
                || ResidentCommands.of(colony).current(body.id()).orElse(null) != captured) return false;
        var group = UnitGroups.of(colony).ofResident(body.id());
        if (group.filter(unit -> unit.attackMove() || unit.phase() == UnitGroup.Phase.FAILED_WAITING).isPresent()) return false;
        return captured == null ? !CombatSelectionOwnership.of(colony).reserved(body.id())
            : captured.mode() == ResidentCommands.Mode.GUARD && captured.boundTo(body);
    }

    private boolean member(Body body) {
        return body.colonyId().filter(colony.id()::equals).isPresent()
            && colony.residents().stream().anyMatch(resident -> resident.id().equals(body.id()));
    }

    /** Read the same real inventory as a worker, without borrowing a tool, item or navigator. */
    private record Inspection(Body person) implements Worker {
        public Resident resident() { return person.resident(); }
        public Mob body() { return person.mob(); }
        public Container pack() { return person.pack(); }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) {
            return Tools.from(person.pack().contents().stream().filter(stack -> !PersonalItems.marked(stack)).toList(), need)
                .orElse(ItemStack.EMPTY);
        }
        public void spill(ItemStack stack) { throw new IllegalStateException("support inspection never owns goods"); }
        public Effects effects() { return person.effects(); }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
    }

    private static boolean loaded(ServerLevel level, Mob mob) {
        return mob.level() == level && mob.isAlive() && !mob.isRemoved() && level.getEntity(mob.getUUID()) == mob;
    }

    private static boolean near(ServerLevel level, WorldPos anchor, WorldPos point, double range) {
        return anchor.sameRealm(point) && WorldSpaces.world(level, anchor).flatMap(origin ->
            WorldSpaces.world(level, point).map(at -> origin.distanceToSqr(at) <= range * range)).orElse(false);
    }

    void prune(long now) {
        tickets.values().removeIf(ticket -> !ticket.valid());
        alertedUntil.values().removeIf(until -> until <= now);
        recruitedUntil.values().removeIf(until -> until <= now);
    }

    void clear() { tickets.clear(); alertedUntil.clear(); recruitedUntil.clear(); }
}
