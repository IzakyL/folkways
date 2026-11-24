package io.github.izakyl.folkways.vanilla.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class CartEvents {
    private CartEvents() {
    }

    public static void horseTick(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof AbstractHorse horse && !horse.level().isClientSide()) {
            for (var goal : horse.goalSelector.getAvailableGoals()) {
                if (goal.getGoal() instanceof HorseLine) {
                    // These poses bypass serverAiStep entirely, so the line's goal cannot clear them itself.
                    horse.setEating(false);
                    horse.setStanding(false);
                    break;
                }
            }
        }
    }

    public static void removed(EntityLeaveLevelEvent event) {
        var entity = event.getEntity();
        boolean horse = entity.getTags().contains(io.github.izakyl.folkways.vanilla.pasture.PastureContent.CLAIMED);
        if ((CartLinks.admissible(entity) || horse) && event.getLevel() instanceof ServerLevel level) {
            if (!horse && CartLinks.residentDrawn(entity)) {
                CartLinks.sync((AbstractCargoCart) entity, ((SavedPuller) entity).folkways$puller());
            }
            ClaimRelease.fromWorld(entity.getRemovalReason()).ifPresent(why -> {
                for (var colony : Colonies.all(level.getServer())) {
                    colony.claimOf(entity.getUUID()).filter(holding -> holding.owner().equals(CartsPlugin.OWNER))
                        .ifPresent(holding -> colony.release(holding.id(), why));
                }
            });
        }
    }
}
