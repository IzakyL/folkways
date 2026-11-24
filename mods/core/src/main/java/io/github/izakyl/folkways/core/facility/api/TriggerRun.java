package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** A trigger's outstanding request, including its original arguments across fresh routine snapshots. */
final class TriggerRun {

    final Map<String, Value> arguments;
    // Keep the routine snapshot arguments across replans.
    Map<String, Value> pinnedArguments;
    int retries;
    long due;
    UUID active;
    String stalled;

    TriggerRun(Map<String, Value> arguments) {
        this.arguments = Map.copyOf(arguments);
    }

    void failed(Trigger.Retry policy, long now, Routine.Failure failure) {
        active = null;
        if (!failure.retryable() || retries >= policy.retries()) {
            stalled = failure.translationKey();
        } else {
            due = now + policy.delay(retries++);
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("arguments", Value.saveAll(arguments));
        if (pinnedArguments != null) {
            tag.put("pinned_arguments", Value.saveAll(pinnedArguments));
        }
        tag.putInt("retries", retries);
        tag.putLong("due", due);
        if (active != null) {
            tag.putUUID("active", active);
        }
        if (stalled != null) {
            tag.putString("stalled", stalled);
        }
        return tag;
    }

    static TriggerRun load(CompoundTag tag) {
        TriggerRun run = new TriggerRun(Value.loadAll(tag.getCompound("arguments")));
        if (tag.contains("pinned_arguments", Tag.TAG_COMPOUND)) {
            run.pinnedArguments = Value.loadAll(tag.getCompound("pinned_arguments"));
        }
        run.retries = tag.getInt("retries");
        run.due = tag.getLong("due");
        run.active = tag.hasUUID("active") ? tag.getUUID("active") : null;
        run.stalled = tag.contains("stalled") ? tag.getString("stalled") : null;
        return run;
    }
}
