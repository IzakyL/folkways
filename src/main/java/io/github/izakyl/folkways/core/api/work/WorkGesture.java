package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
                mob.startSleeping(pillowOf(body.level(), keptAt(body, at)));
            }
        }

        // The bed is read where the level keeps it: on a ship, among the ship's own blocks, not in the world cell
        // it shows in, which is air.
        private static BlockPos keptAt(LivingEntity body, BlockPos at) {
            Level level = body.level();
            Realm realm = WorldSpaces.at(body).realm();
            return WorldSpaces.relative(level, realm, Vec3.atCenterOf(at))
                .flatMap(local -> WorldSpaces.storage(level, new WorldPos(realm, BlockPos.containing(local))))
                .orElse(at);
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
            Entity seat = body.getVehicle();
            if (seat != null) {
                body.stopRiding();
                stepOff(body, seat.blockPosition());
            }
        }
    };

    // A seat sets whoever gets up on top of itself, and a seat sunk a level into the floor leaves them standing
    // in its own cell, a jump short of the floor round it: no way starts there, and the body stood up from its
    // post stayed on the seat for good. It steps off beside the seat instead, as off a bed or out of a boat.
    private static void stepOff(LivingEntity body, BlockPos seat) {
        Level level = body.level();
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (int dy : new int[] {0, 1, -1}) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos feet = seat.relative(side).above(dy);
                if (!level.isLoaded(feet) || !Footing.withFeetAt(level, feet)) {
                    continue;
                }
                double apart = Vec3.atBottomCenterOf(feet).distanceToSqr(body.position());
                if (apart < nearest) {
                    nearest = apart;
                    best = feet;
                }
            }
            if (best != null) {
                break;
            }
        }
        if (best != null) {
            body.teleportTo(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D);
        }
    }
}
