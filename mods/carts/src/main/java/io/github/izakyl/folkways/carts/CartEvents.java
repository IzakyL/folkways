package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = FolkwaysCartsMod.ID)
public final class CartEvents {
    private CartEvents() {
    }

    @SubscribeEvent
    public static void stockTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof AbstractCargoCart cart && !cart.level().isClientSide()
                && cart.tickCount % 5 == 0) {
            // Some container callers grow a slot stack in place and only call setChanged afterwards.
            CartContents.synchronize(cart);
        }
    }

    @SubscribeEvent
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

    @SubscribeEvent
    public static void removed(EntityLeaveLevelEvent event) {
        var entity = event.getEntity();
        boolean horse = entity.getTags().contains(io.github.izakyl.folkways.vanilla.pasture.PastureContent.CLAIMED);
        if ((CartLinks.admissible(entity) || horse) && event.getLevel() instanceof ServerLevel level) {
            if (!horse && CartLinks.residentDrawn(entity)) {
                CartLinks.sync((AbstractCargoCart) entity, ((SavedPuller) entity).folkways$puller());
            }
            ClaimRelease.fromWorld(entity.getRemovalReason()).ifPresent(why -> {
                for (var colony : Colonies.all(level.getServer())) {
                    colony.claimOf(entity.getUUID()).filter(holding -> holding.owner().equals(FolkwaysCartsMod.OWNER))
                        .ifPresent(holding -> colony.release(holding.id(), why));
                }
            });
        }
    }
}
