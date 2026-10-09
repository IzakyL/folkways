package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
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

    // Vanilla lays a sleeper from the bed's pillow toward its foot, so a bed named by its foot is slept in
    // from its head; lying down on the foot put the body past the end of the bed.
    WorkGesture LIE = new WorkGesture() {
        @Override
        public void perform(LivingEntity body, int windupTick, BlockPos at) {
            if (windupTick == 0 && body instanceof Mob mob) {
                mob.startSleeping(pillowOf(body.level(), at));
            }
        }

        private static BlockPos pillowOf(Level level, BlockPos at) {
            BlockState state = level.getBlockState(at);
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.FOOT) {
                return at;
            }
            BlockPos head = at.relative(BedBlock.getConnectedDirection(state));
            return level.getBlockState(head).getBlock() instanceof BedBlock ? head : at;
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
