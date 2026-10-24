package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

public final class PatrolCombat {
    private PatrolCombat() { }

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
