package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.WorkNoise;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

final class PastureNoise {

    static final WorkNoise MILKED = WorkNoise.voice(SoundEvents.COW_MILK);

    // The blow that takes a beast from the herd.
    static final WorkNoise STRUCK = (level, emitter) -> level.playSound(null,
        emitter.getX(), emitter.getY(), emitter.getZ(),
        SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 1.0F);

    private PastureNoise() {
    }
}
