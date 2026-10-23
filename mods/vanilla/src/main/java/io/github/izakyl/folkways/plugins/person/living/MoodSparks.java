package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

// A mood that just turned shows over the resident's head, to those reading the colony with the book in hand.
final class MoodSparks {
    private static final double RANGE_SQR = 32.0D * 32.0D;

    private MoodSparks() {
    }

    static void show(ServerLevel level, LivingEntity resident, boolean better) {
        double x = resident.getX();
        double y = resident.getY() + resident.getBbHeight() + 0.3D;
        double z = resident.getZ();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(resident) <= RANGE_SQR && ColonyBookItem.heldBook(player).isPresent()) {
                level.sendParticles(player, better ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER,
                    false, x, y, z, better ? 6 : 2, 0.3D, 0.2D, 0.3D, 0.0D);
            }
        }
    }
}
