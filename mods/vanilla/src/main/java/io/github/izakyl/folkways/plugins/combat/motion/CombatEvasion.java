package io.github.izakyl.folkways.plugins.combat.motion;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.CombatReadiness;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;

/** Local retreat, stop and fire. It never replaces a player's command or installs another navigation goal. */
public final class CombatEvasion {
    private static final Map<Mob, WeakReference<EvasionNode>> active = new WeakHashMap<>();
    private static final Map<Mob, Long> retryAt = new WeakHashMap<>();
    private CombatEvasion() { }

    public static boolean active(Mob body, ResidentCommands.Order order) {
        EvasionNode node = Optional.ofNullable(active.get(body)).map(WeakReference::get).orElse(null);
        return node != null && node.order() == order && node.valid();
    }
    public static List<Urge> urges(Worker who, Colony colony, ColonyView view) {
        if (!(who.body() instanceof ResidentEntity body) || !body.isAlive() || body.isRemoved() || body.isPassenger()) return List.of();
        var commands = ResidentCommands.of(colony);
        var order = commands.current(body.id()).orElse(null);
        if (order != null && (order.mode() == ResidentCommands.Mode.MOVE || order.mode() == ResidentCommands.Mode.RETREAT)) return List.of();
        EvasionNode previous = Optional.ofNullable(active.get(body)).map(WeakReference::get).orElse(null);
        if (previous != null && previous.valid()) return List.of(previous.urge());
        active.remove(body);
        long now = view.level().getGameTime();
        if (now < retryAt.getOrDefault(body, Long.MIN_VALUE)) return List.of();
        Optional<? extends Mob> danger = CombatReadiness.explosiveNear(body);
        boolean ranged = Weapons.ranged(who) || Weapons.ammunition(body).isPresent()
            || ResidentEquipment.assigned(body).getItem() instanceof ProjectileWeaponItem;
        boolean explosive = danger.isPresent();
        if (danger.isEmpty() && order != null && order.mode() == ResidentCommands.Mode.ATTACK) return List.of();
        if (danger.isEmpty()) {
            danger = view.level().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(ranged ? 5 : 3.5),
                foe -> Threats.hostile(foe, body) && body.hasLineOfSight(foe) && foe.getTarget() == body
                    && (ranged || CombatMotions.supports(who, CombatMotions.Kind.DODGE) && (foe.swinging || body.hurtTime > 0)))
                .stream().min(Comparator.comparingDouble(body::distanceToSqr));
        }
        if (danger.isEmpty()) return List.of();
        Mob foe = danger.get();
        retryAt.put(body, now + 10);
        CombatReadiness.fighting(body);
        Vec3 away = body.position().subtract(foe.position()).multiply(1, 0, 1);
        if (away.horizontalDistanceSqr() < .01) away = new Vec3(1, 0, 0);
        away = away.normalize();
        double ideal = explosive ? foe instanceof Creeper creeper && creeper.isPowered() ? 12 : 10 : ranged ? 8 : 4;
        var origin = WorldSpaces.at(body);
        var anchor = order == null ? body.position() : WorldSpaces.world(view.level(), order.destination()).orElse(body.position());
        double leash = explosive ? 16 : 12;
        java.util.function.Predicate<Vec3> within = point -> WorldSpaces.at(body).sameRealm(origin)
            && point.distanceToSqr(anchor) <= leash * leash;
        List<BlockPos> candidates = new ArrayList<>();
        for (int distance : new int[] {3, 5, 8}) for (double turn : new double[] {0, -.6, .6, -1.1, 1.1}) {
            Vec3 point = body.position().add(away.yRot((float) turn).scale(distance));
            for (int dy : new int[] {0, -1, 1}) {
                BlockPos feet = BlockPos.containing(point).offset(0, dy, 0);
                Vec3 world = Vec3.atBottomCenterOf(feet);
                if (view.level().hasChunkAt(feet) && Footing.withFeetAt(view.level(), feet) && within.test(world)
                        && world.distanceToSqr(foe.position()) > body.distanceToSqr(foe) + 4) candidates.add(feet);
            }
        }
        candidates.sort(Comparator.comparingDouble(feet -> Math.abs(Vec3.atBottomCenterOf(feet).distanceTo(foe.position()) - ideal)));
        for (BlockPos feet : candidates.stream().distinct().limit(12).toList()) {
            var path = body.getNavigation().createPath(feet, 0);
            if (path == null || !path.canReach()) continue;
            EvasionNode node = new EvasionNode(who, colony, order, foe, feet, away, within, explosive, ranged);
            active.put(body, new WeakReference<>(node)); return List.of(node.urge());
        }
        return List.of();
    }
}
