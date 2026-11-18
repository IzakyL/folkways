package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.resident.api.Gait;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * When a resident sleeps: lightly tired by night, only exhausted by day, judged at the hour the resident would
 * reach the bed. A level whose clock stands still keeps the purely value-driven mark.
 */
final class SleepRhythm {

    static final int DAY = 24000;

    // Beds take a sleeper from about 12542 to 23459; residents keep the same night.
    static final int DUSK = 12500;
    static final int DAWN = 23500;

    // Alertness at or below which sleep engages: a night's sleep needs a little wear, a nap by day nearly all of it.
    static final int NIGHT_HOLD = Rest.SPENT - 50;
    static final int DAY_HOLD = Rest.SPENT - 1000;
    static final int STILL_HOLD = Rest.SPENT - Rest.SLEEPY;

    // Walks bend around buildings; a far bed never brings bedtime forward by more than three hours.
    static final double DETOUR = 1.5D;
    static final int MAX_LEAD = 3000;

    private final boolean cycles;
    private final long now;

    SleepRhythm(boolean cycles, long dayTime) {
        this.cycles = cycles;
        this.now = Math.floorMod(dayTime, DAY);
    }

    static SleepRhythm of(ServerLevel level) {
        return new SleepRhythm(!level.dimensionType().hasFixedTime()
            && level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT), level.getDayTime());
    }

    boolean cycles() {
        return cycles;
    }

    long now() {
        return now;
    }

    static int lead(double blocks) {
        return Math.min(MAX_LEAD, Gait.ticksToWalk(blocks * DETOUR));
    }

    boolean nightAt(int lead) {
        return cycles && night(now + lead);
    }

    /** The alertness mark that engages sleep for a resident arriving in bed {@code lead} ticks from now. */
    int holdAt(int lead) {
        return !cycles ? STILL_HOLD : nightAt(lead) ? NIGHT_HOLD : DAY_HOLD;
    }

    /** A night's sleep lasts until about dawn; a nap, or a still clock, only sleeps the wear off. */
    int sleepTicks(int lead, int toSleepOff) {
        if (!nightAt(lead)) {
            return toSleepOff;
        }
        long arrival = Math.floorMod(now + lead, DAY);
        return (int) Math.max(toSleepOff, Math.floorMod(DAWN - arrival, DAY));
    }

    private static boolean night(long time) {
        long hour = Math.floorMod(time, DAY);
        return hour >= DUSK && hour < DAWN;
    }
}
