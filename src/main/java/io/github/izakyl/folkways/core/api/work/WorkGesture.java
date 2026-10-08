package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

// How a body looks while it works. The ones here are what vanilla bodies can already show; a plugin
// with its own motion implements this rather than asking for a new constant.
public interface WorkGesture {

    default void perform(LivingEntity body, int windupTick, BlockPos at) {
    }

    default void release(LivingEntity body) {
    }

    default void present(Body body, Optional<BlockPos> focus) {
    }

    WorkGesture NONE = new WorkGesture() {
    };

    // One swing per piece of work, as it starts: swinging again whenever the last swing ended kept the arm
    // going for the whole wind-up.
    WorkGesture SWING = new WorkGesture() {
        @Override
        public void perform(LivingEntity body, int windupTick, BlockPos at) {
            if (windupTick == 0) {
                body.swing(InteractionHand.MAIN_HAND);
            }
        }
    };

    WorkGesture CAST = new WorkGesture() {
        @Override
        public void perform(LivingEntity body, int windupTick, BlockPos at) {
            if (windupTick == 0) {
                body.swing(InteractionHand.MAIN_HAND);
            }
        }

        @Override
        public void present(Body body, Optional<BlockPos> focus) {
            body.presentCastLine(focus);
        }
    };

    WorkGesture LIE = new WorkGesture() {
        @Override
        public void perform(LivingEntity body, int windupTick, BlockPos at) {
            if (windupTick == 0 && body instanceof Mob mob) {
                mob.startSleeping(at);
            }
        }

        @Override
        public void release(LivingEntity body) {
            if (body.isSleeping()) {
                body.stopSleeping();
            }
        }
    };

    WorkGesture SIT = new WorkGesture() {
        @Override
        public void perform(LivingEntity body, int windupTick, BlockPos at) {
            if (windupTick == 0 && !body.isPassenger()) {
                for (Entity seat : body.level().getEntities(body, new AABB(at))) {
                    if (seat.isAlive() && body.startRiding(seat)) {
                        break;
                    }
                }
            }
        }

        @Override
        public void release(LivingEntity body) {
            if (body.isPassenger()) {
                body.stopRiding();
            }
        }
    };
}
