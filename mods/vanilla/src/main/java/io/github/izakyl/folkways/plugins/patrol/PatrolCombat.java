package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

public final class PatrolCombat {
    private PatrolCombat() { }

    @SubscribeEvent
    public static void support(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0 || !(event.getEntity().level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof Mob attacker)) return;
        Bodies.of(event.getEntity()).ifPresent(victim -> victim.colonyId()
            .flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(colony -> colony.service(PatrolContent.ID, PatrolPresence.class))
            .ifPresent(patrol -> patrol.support().attacked(victim, attacker)));
    }

    @SubscribeEvent
    public static void damage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Enemy && event.getSource().getEntity() != null) {
            Bodies.of(event.getSource().getEntity()).ifPresent(body ->
                event.setAmount((float) (event.getAmount() * (1 + body.effects().value(PatrolContent.DAMAGE)))));
        }
        if (event.getSource().getEntity() instanceof Enemy) {
            Bodies.of(event.getEntity()).ifPresent(body ->
                event.setAmount((float) (event.getAmount() * (1 - body.effects().value(PatrolContent.DEFENCE)))));
        }
    }
}
