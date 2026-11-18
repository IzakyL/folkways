package io.github.izakyl.folkways.core.work.support;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** One visible target per worker, including work that commits several blocks atomically. */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID)
public final class BreakingProgress {

    private record Crack(BlockPos at, BlockState state, int stage) {
    }

    private static final Map<Entity, Crack> cracks = new WeakHashMap<>();

    /** Repeats the swing through the wind-up and cracks the target block as the work progresses. */
    public static final WorkGesture MINING = new WorkGesture() {
        @Override
        public void perform(LivingEntity body, int windupTick, BlockPos at) {
            if (windupTick % 6 == 0) {
                body.swing(InteractionHand.MAIN_HAND);
            }
        }

        @Override
        public void progress(LivingEntity body, int tick, int duration, BlockPos at) {
            update(body, at, tick, duration);
        }

        @Override
        public void release(LivingEntity body) {
            clear(body);
        }
    };

    private BreakingProgress() {
    }

    public static void update(Entity body, BlockPos at, int tick, int duration) {
        if (!(body.level() instanceof ServerLevel level)) {
            return;
        }
        if (!level.isLoaded(at)) {
            clear(body);
            return;
        }
        BlockState state = level.getBlockState(at);
        Crack previous = cracks.get(body);
        if (previous != null && !previous.at().equals(at)) {
            clear(body);
            previous = null;
        }
        // A different block at the same cell is not the block this action started mining.
        if (previous != null && !previous.state().equals(state)) {
            if (previous.stage() >= 0) {
                level.destroyBlockProgress(body.getId(), previous.at(), -1);
                cracks.put(body, new Crack(previous.at(), previous.state(), -1));
            }
            return;
        }
        if (state.isAir() || state.liquid() || state.getDestroySpeed(level, at) < 0 || duration <= 0) {
            clear(body);
            return;
        }
        int stage = Math.min(9, Math.max(0, (int) (10L * tick / duration)));
        if (previous == null || previous.stage() != stage) {
            level.destroyBlockProgress(body.getId(), at, stage);
            cracks.put(body, new Crack(at.immutable(), state, stage));
        }
    }

    public static void clear(Entity body) {
        if (body.level() instanceof ServerLevel level) {
            Crack crack = cracks.remove(body);
            if (crack != null) {
                level.destroyBlockProgress(body.getId(), crack.at(), -1);
            }
        }
    }

    @SubscribeEvent
    public static void removed(EntityLeaveLevelEvent event) {
        clear(event.getEntity());
    }
}
