package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID)
public final class ColonyDamage {

    private static final String SHOT_COLONY = "folkways_core:shot_colony";
    private static final String TACZ_SHOT_COLONY = "folkways_tacz_colony";
    private static final String WARFARE_SHOOTER = "folkways_core:resident_shot";
    private static final List<Function<Entity, Entity>> SOURCES = new ArrayList<>();

    private ColonyDamage() {
    }

    public static void registerSource(Function<Entity, Entity> source) {
        SOURCES.add(source);
    }

    public static void mark(Entity projectile, UUID colony) {
        if (shotColony(projectile).isEmpty() && !projectile.getPersistentData().hasUUID(WARFARE_SHOOTER)) {
            projectile.getPersistentData().putUUID(SHOT_COLONY, colony);
        }
    }

    public static boolean belongs(ServerLevel level, Colony colony, Entity entity) {
        if (entity == null) {
            return false;
        }
        var resident = Bodies.colonyOf(entity);
        if (resident.isPresent()) {
            return resident.get().equals(colony.id());
        }
        if (entity instanceof Player) {
            return Charters.member(level.getServer(), colony, entity.getUUID());
        }
        return Combatant.get().colonyPet(entity, colony.id())
            || Vehicles.of(entity).flatMap(provider -> provider.owner(entity)).filter(colony.id()::equals).isPresent()
            || colony.claimOf(entity.getUUID()).isPresent()
            || Facilities.ownerOf(level, entity.getUUID())
                .filter(facility -> Facilities.registered(level, colony.id(), facility.id())).isPresent();
    }

    public static boolean friendly(Entity victim, DamageSource damage) {
        return friendly(victim, damage.getDirectEntity(), damage.getEntity());
    }

    private static boolean friendly(Entity victim, Entity direct, Entity causing) {
        if (!(victim.level() instanceof ServerLevel level) || direct == null && causing == null) {
            return false;
        }
        Optional<UUID> attributed = shotColony(direct).or(() -> shotColony(causing));
        if (attributed.isPresent()) {
            return attributed.flatMap(id -> Colonies.of(level.getServer(), id))
                .filter(colony -> belongs(level, colony, victim)).isPresent();
        }
        return Colonies.all(level.getServer()).stream().anyMatch(colony -> belongs(level, colony, victim)
            && (from(level, colony, direct, 0) || direct != causing && from(level, colony, causing, 0)));
    }

    private static Optional<UUID> shotColony(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        var data = entity.getPersistentData();
        if (data.hasUUID(SHOT_COLONY)) {
            return Optional.of(data.getUUID(SHOT_COLONY));
        }
        return data.hasUUID(TACZ_SHOT_COLONY) ? Optional.of(data.getUUID(TACZ_SHOT_COLONY)) : Optional.empty();
    }

    private static boolean from(ServerLevel level, Colony colony, Entity source, int depth) {
        if (source == null || depth >= 8) {
            return false;
        }
        var attributed = shotColony(source);
        if (attributed.isPresent()) {
            return attributed.get().equals(colony.id());
        }
        if (belongs(level, colony, source)) {
            return true;
        }
        var data = source.getPersistentData();
        if (data.hasUUID(WARFARE_SHOOTER)) {
            UUID shooter = data.getUUID(WARFARE_SHOOTER);
            if (colony.residents().stream().anyMatch(body -> body.id().equals(shooter))) {
                return true;
            }
        }
        if (source instanceof Projectile projectile && from(level, colony, projectile.getOwner(), depth + 1)) {
            return true;
        }
        return SOURCES.stream().map(adapter -> adapter.apply(source))
            .anyMatch(owner -> owner != source && from(level, colony, owner, depth + 1));
    }

    public static Optional<UUID> party(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return Colonies.all(level.getServer()).stream().filter(colony -> belongs(level, colony, entity))
            .map(Colony::id).findFirst();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void incoming(LivingIncomingDamageEvent event) {
        if (friendly(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void invulnerability(EntityInvulnerabilityCheckEvent event) {
        if (!(event.getEntity() instanceof LivingEntity) && friendly(event.getEntity(), event.getSource())) {
            event.setInvulnerable(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void detonate(ExplosionEvent.Detonate event) {
        var explosion = event.getExplosion();
        event.getAffectedEntities().removeIf(entity ->
            friendly(entity, explosion.getDirectSourceEntity(), explosion.getIndirectSourceEntity()));
    }
}
