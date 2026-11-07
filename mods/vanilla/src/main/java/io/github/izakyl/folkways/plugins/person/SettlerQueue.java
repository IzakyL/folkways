package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import net.minecraft.nbt.CompoundTag;

final class SettlerQueue {

    private static final long UNREAD = Long.MIN_VALUE;

    private int waiting;
    private long progress;
    private long lastDayTime = UNREAD;

    static SettlerQueue from(CompoundTag kept) {
        SettlerQueue queue = new SettlerQueue();
        queue.load(Reader.of(kept));
        return queue;
    }

    int waiting() {
        return waiting;
    }

    int cap() {
        return FolkwaysVanillaConfig.maxWaitingSettlers();
    }

    static long ticksPerSettler(int population) {
        return (long) FolkwaysVanillaConfig.settlerIntervalTicks() * (Math.max(0, population) + 1L);
    }

    long ticksToNext(int population) {
        return Math.max(0L, ticksPerSettler(population) - progress);
    }

    boolean gather(long dayTime, int population) {
        long since = lastDayTime == UNREAD ? 0L : dayTime - lastDayTime;
        lastDayTime = dayTime;
        if (waiting >= cap()) {
            progress = 0L;
            return false;
        }
        if (since <= 0L) {
            return false;
        }
        progress += since;
        long per = ticksPerSettler(population);
        int arrived = 0;
        while (progress >= per && waiting < cap()) {
            progress -= per;
            waiting++;
            arrived++;
        }
        if (waiting >= cap()) {
            progress = 0L;
        }
        return arrived > 0;
    }

    boolean take() {
        if (waiting <= 0) {
            return false;
        }
        waiting--;
        return true;
    }

    CompoundTag save() {
        return Writer.of()
            .integer("waiting", waiting)
            .longValue("progress", progress)
            .longValue("lastDayTime", lastDayTime)
            .tag();
    }

    private void load(Reader reader) {
        if (reader.keys().isEmpty()) {
            return;
        }
        waiting = Math.max(0, Math.min(reader.integer("waiting").orElseThrow(), cap()));
        progress = Math.max(0L, reader.longValue("progress").orElseThrow());
        lastDayTime = reader.longValue("lastDayTime").orElseThrow();
    }
}
