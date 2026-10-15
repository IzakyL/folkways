package io.github.izakyl.folkways.plugins.cookery.spit;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** The documented knife grip, limited to a booked resident taking its own finished native batch. */
public final class SpitSafety {
    private record Use(ShawarmaSpitBlockEntity spit, LivingEntity user, BooleanSupplier current) { }
    private static final ThreadLocal<Use> CURRENT = new ThreadLocal<>();
    private SpitSafety() { }

    static <T> T taking(ShawarmaSpitBlockEntity spit, ServerLevel level, LivingEntity user,
                        UUID colony, UUID batch, BooleanSupplier current, Supplier<T> action) {
        if (spit.cookTime > 0 || !spit.cookingItem.isEmpty() || !user.getMainHandItem().is(TagMod.KITCHEN_KNIFE)
                || SpitBatch.read(spit, level).filter(owned -> owned.colony().equals(colony) && owned.id().equals(batch)).isEmpty()) {
            throw new IllegalStateException("safe extraction requires a real knife and an owned finished batch");
        }
        Use previous = CURRENT.get();
        CURRENT.set(new Use(spit, user, current));
        try { return action.get(); }
        finally {
            if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); }
        }
    }

    public static boolean protectedBurn(ShawarmaSpitBlockEntity spit, LivingEntity user, DamageSource source) {
        Use scope = CURRENT.get();
        return scope != null && scope.spit() == spit && scope.user() == user && scope.current().getAsBoolean()
            && user.getMainHandItem().is(TagMod.KITCHEN_KNIFE) && source.is(DamageTypeTags.IS_FIRE);
    }
}
