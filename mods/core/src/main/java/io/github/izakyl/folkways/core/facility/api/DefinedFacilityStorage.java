package io.github.izakyl.folkways.core.facility.api;

import static io.github.izakyl.folkways.core.facility.api.DefinedFacilities.ID;
import static io.github.izakyl.folkways.core.facility.api.FacilityScriptRuns.compile;
import static io.github.izakyl.folkways.core.facility.api.FacilityScriptRuns.script;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Entry;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Result;
import io.github.izakyl.folkways.core.facility.api.FacilityConditions.ConditionState;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

final class DefinedFacilityStorage {

    private DefinedFacilityStorage() {
    }

    static void load(Colony colony, Map<String, Entry> facilities) {
        CompoundTag saved = colony.kept(ID);
        for (String name : saved.getAllKeys()) {
            CompoundTag tag = saved.getCompound(name);
            FacilityDefinition.load(tag).ifPresent(facility -> {
                var programs = compile(facility, Map.of(), new ArrayList<>());
                Entry entry = new Entry(facility, programs);
                if (tag.contains("workshopRun")) {
                    entry.workshopRun = tag.getCompound("workshopRun");
                }
                CompoundTag recent = tag.getCompound("recent");
                if (recent.contains("routine")) {
                    entry.recent = new Result(recent.getString("routine"), recent.getLong("time"),
                        recent.getString("ending"), recent.contains("reason")
                            ? Optional.of(recent.getString("reason")) : Optional.empty());
                }
                CompoundTag days = tag.getCompound("days");
                days.getAllKeys().forEach(trigger -> entry.days.put(trigger, days.getLong(trigger)));
                for (Tag item : tag.getList("scripts", Tag.TAG_COMPOUND)) {
                    ScriptRun run = ScriptRun.load((CompoundTag) item);
                    if (script(entry, run.routine) != null) {
                        entry.scripts.put(run.id, run);
                        entry.runs.put(run.id, run.routine);
                    }
                }
                CompoundTag requests = tag.getCompound("requests");
                facility.switches().forEach((key, trigger) -> {
                    if (trigger.enabled() && requests.contains(key)) {
                        TriggerRun request = TriggerRun.load(requests.getCompound(key));
                        if (request.pinnedArguments != null) {
                            // Native work graphs are transient. Replan this request when the controls are ready.
                            request.active = null;
                        }
                        if (request.active == null || entry.runs.containsKey(request.active)) {
                            entry.requests.put(key, request);
                        }
                    }
                });
                CompoundTag paused = tag.getCompound("paused");
                for (String trigger : paused.getAllKeys()) {
                    if (facility.switches().containsKey(trigger) && paused.getBoolean(trigger)) {
                        entry.conditions.computeIfAbsent(trigger, ignored -> new ConditionState()).paused = true;
                    }
                }
                entry.pausedSamples.addAll(tag.getCompound("pausedSamples").getAllKeys());
                entry.pausedAdmissions.addAll(tag.getCompound("pausedAdmissions").getAllKeys());
                facilities.put(facility.name(), entry);
            });
        }
    }

    static void save(Colony colony, Map<String, Entry> facilities) {
        CompoundTag saved = new CompoundTag();
        facilities.forEach((name, entry) -> {
            CompoundTag tag = entry.facility.save();
            if (entry.recent != null) {
                CompoundTag recent = new CompoundTag();
                recent.putString("routine", entry.recent.routine());
                recent.putLong("time", entry.recent.time());
                recent.putString("ending", entry.recent.ending());
                entry.recent.reason().ifPresent(reason -> recent.putString("reason", reason));
                tag.put("recent", recent);
            }
            CompoundTag days = new CompoundTag();
            entry.days.forEach(days::putLong);
            tag.put("days", days);
            if (entry.workshopRun != null) {
                tag.put("workshopRun", entry.workshopRun);
            }
            if (!entry.scripts.isEmpty()) {
                var scripts = new net.minecraft.nbt.ListTag();
                entry.scripts.values().stream().filter(run -> !entry.rides.owns(run.id))
                    .forEach(run -> scripts.add(run.save()));
                tag.put("scripts", scripts);
            }
            if (!entry.requests.isEmpty()) {
                CompoundTag requests = new CompoundTag();
                entry.requests.forEach((key, request) -> requests.put(key, request.save()));
                tag.put("requests", requests);
            }
            CompoundTag paused = new CompoundTag();
            entry.conditions.forEach((key, state) -> {
                if (state.paused) {
                    paused.putBoolean(key, true);
                }
            });
            if (!paused.isEmpty()) {
                tag.put("paused", paused);
            }
            savePaused(tag, "pausedSamples", entry.pausedSamples);
            savePaused(tag, "pausedAdmissions", entry.pausedAdmissions);
            saved.put(name, tag);
        });
        colony.keep(ID, saved);
    }

    private static void savePaused(CompoundTag tag, String name, Set<String> samples) {
        if (!samples.isEmpty()) {
            var paused = new CompoundTag();
            samples.forEach(sample -> paused.putBoolean(sample, true));
            tag.put(name, paused);
        }
    }
}
