package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import net.minecraft.nbt.CompoundTag;

final class Rest {

    private static final String TAG_TIRED = "Tired";

    static final int RESTED = 0;
    static final int SPENT = 1200;
    static final int SLEEPY = 800;

    // One loaded day awake reaches the sleep threshold; walking can bring it forward.
    // Time has one live clock, independent of delayed work-node settlement and player control.
    private static final double PER_AWAKE_TICK = 1.0D / 30.0D;

    private static final double PER_BLOCK_WALKED = 0.3D;

    private static final double PER_TICK_ASLEEP = 6.0D / 30.0D;

    private double tired;

    int alertness() {
        return SPENT - tiredness();
    }

    void awake(double ticks) { tired = Math.min(SPENT, tired + ticks * PER_AWAKE_TICK); }

    void walked(double blocks) { tired = Math.min(SPENT, tired + blocks * PER_BLOCK_WALKED); }

    void sleep(int ticks) {
        tired = Math.max(RESTED, tired - ticks * PER_TICK_ASLEEP);
    }

    int tiredness() {
        return (int) Math.round(tired);
    }

    int ticksToSleepOff() {
        return (int) Math.max(1, Math.ceil(tired / PER_TICK_ASLEEP));
    }

    CompoundTag save() {
        return Writer.of().decimal(TAG_TIRED, (float) tired).tag();
    }

    void load(Reader reader) {
        reader.decimal(TAG_TIRED).or(() -> reader.integer(TAG_TIRED).map(Integer::floatValue))
            .ifPresent(value -> tired = Math.clamp(value, RESTED, SPENT));
    }
}
