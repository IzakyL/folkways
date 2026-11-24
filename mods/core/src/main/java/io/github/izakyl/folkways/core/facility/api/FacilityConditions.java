package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.ConditionProfile;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Entry;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Result;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class FacilityConditions {

    private static final int CONDITION_BUDGET = 32;
    private static final Map<MinecraftServer, ScriptBudget> SCRIPT_BUDGETS = new WeakHashMap<>();

    private final DefinedFacilities facilities;
    private final Colony colony;
    private final MinecraftServer server;
    private final ScriptBudget scriptBudget;
    private final List<PendingCondition> pendingConditions = new ArrayList<>();
    private int conditionCursor;
    ConditionProfile conditionProfile;

    private record PendingCondition(Evaluation evaluation, Map<String, Value> arguments,
                                    CompletableFuture<FacilityScripts.ConditionResult> future,
                                    int dispatched) {
    }

    static final class ConditionState {
        long nextEvaluation;
        long nextRun;
        int matches;
        boolean paused;
        boolean pending;
    }

    record Evaluation(Entry entry, String key, Trigger.Condition condition, ConditionState state,
                      ServerLevel level) {
    }

    private static final class ScriptBudget {
        private int tick = Integer.MIN_VALUE;
        private int remaining;

        boolean available(MinecraftServer server) {
            if (tick != server.getTickCount()) {
                tick = server.getTickCount();
                remaining = FacilityScripts.CONDITION_ADMISSIONS;
            }
            return remaining > 0;
        }
    }

    FacilityConditions(DefinedFacilities facilities, Colony colony, MinecraftServer server) {
        this.facilities = facilities;
        this.colony = colony;
        this.server = server;
        synchronized (SCRIPT_BUDGETS) {
            scriptBudget = SCRIPT_BUDGETS.computeIfAbsent(server, ignored -> new ScriptBudget());
        }
    }

    void collect(Entry entry, ServerLevel level, boolean loaded, List<Evaluation> conditions) {
        long time = level.getDayTime();
        long day = Math.floorDiv(time, 24000);
        for (var trigger : entry.facility.switches().entrySet()) {
            if (loaded && trigger.getValue().enabled()
                    && trigger.getValue().trigger() instanceof Trigger.Condition condition) {
                ConditionState state = entry.conditions.computeIfAbsent(trigger.getKey(),
                    ignored -> new ConditionState());
                if (!state.paused && !state.pending && !entry.requests.containsKey(trigger.getKey())) {
                    conditions.add(new Evaluation(entry, trigger.getKey(), condition, state, level));
                }
            } else if (trigger.getValue().trigger() instanceof Trigger.Schedule schedule
                    && Math.floorMod(time, 24000) == schedule.dayTime()
                    && day > entry.days.getOrDefault(trigger.getKey(), Long.MIN_VALUE)) {
                entry.days.put(trigger.getKey(), day);
                if (trigger.getValue().enabled() && loaded && !entry.requests.containsKey(trigger.getKey())
                        && !DefinedFacilities.busy(entry, schedule.routine(), Map.of(), null)) {
                    facilities.begin(entry, trigger.getKey(), Map.of());
                }
                facilities.save();
            }
        }
    }

    void evaluate(List<Evaluation> conditions, long now) {
        int budget = CONDITION_BUDGET;
        for (int walked = 0; walked < conditions.size() && budget > 0; walked++) {
            conditionCursor %= conditions.size();
            Evaluation evaluation = conditions.get(conditionCursor++);
            if (now >= evaluation.state().nextEvaluation) {
                evaluate(evaluation, now);
                budget--;
            }
        }
    }

    private void evaluate(Evaluation evaluation, long now) {
        Entry entry = evaluation.entry();
        Trigger.Condition condition = evaluation.condition();
        ConditionState state = evaluation.state();
        if (condition.script().isPresent() && !scriptBudget.available(server)) {
            return;
        }
        state.nextEvaluation = now + condition.period();
        Map<String, Value> arguments = Map.of();
        if (condition.source().isPresent()) {
            var supplied = TriggerSources.of(condition.source().get())
                .flatMap(source -> source.arguments(evaluation.level(), colony, entry.facility, condition.routine()));
            if (supplied.isEmpty()) {
                state.matches = 0;
                return;
            }
            arguments = Map.copyOf(supplied.get());
        }
        if (entry.facility.unmet(evaluation.level(), condition.when()).isPresent()) {
            state.matches = 0;
            return;
        }
        if (condition.script().isPresent()) {
            var source = new FacilityScripts.Source(condition.script().get(), "when");
            var program = entry.programs.get(source);
            if (program == null) {
                conditionFault(evaluation, now, "script_eval");
                return;
            }
            var readings = Readings.snapshot(evaluation.level(), entry.facility.readings(evaluation.level()));
            boolean profile = conditionProfile != null;
            var libraries = ColonyScripts.snapshot(colony);
            var pending = FacilityScripts.submit(true, () -> program.matches(readings, profile, libraries));
            if (pending != null) {
                scriptBudget.remaining--;
                state.pending = true;
                pendingConditions.add(new PendingCondition(evaluation, arguments, pending, server.getTickCount()));
            } else {
                state.nextEvaluation = now + 1;
            }
            return;
        }
        matched(evaluation, now, arguments);
    }

    private void matched(Evaluation evaluation, long now, Map<String, Value> arguments) {
        Entry entry = evaluation.entry();
        Trigger.Condition condition = evaluation.condition();
        ConditionState state = evaluation.state();
        if (state.matches < condition.hysteresis()) {
            state.matches++;
        }
        if (state.matches == condition.hysteresis() && now >= state.nextRun
                && !entry.runs.containsValue(condition.routine())
                && !entry.requests.containsKey(evaluation.key())
                && !DefinedFacilities.busy(entry, condition.routine(), arguments, null)) {
            var use = entry.facility.routines().get(condition.routine());
            if (use != null && Routines.of(use.routine()).filter(routine -> !routine.automatic(evaluation.level(),
                    colony, entry.facility, use.resolve(entry.facility.parameters()).arguments(arguments))).isPresent()) {
                return;
            }
            state.nextRun = now + condition.cooldown();
            facilities.begin(entry, evaluation.key(), arguments);
        }
    }

    void finishConditions(long now) {
        var iterator = pendingConditions.iterator();
        while (iterator.hasNext()) {
            PendingCondition pending = iterator.next();
            if (pending.dispatched() == server.getTickCount() || !pending.future().isDone()) {
                continue;
            }
            iterator.remove();
            Evaluation evaluation = pending.evaluation();
            Entry entry = evaluation.entry();
            ConditionState state = evaluation.state();
            state.pending = false;
            if (facilities.entry(entry.facility.name()) != entry || facilities.loaded(entry.facility) == null) {
                state.matches = 0;
                continue;
            }
            try {
                var result = ScriptRuntime.result(pending.future());
                if (conditionProfile != null) {
                    conditionProfile.evaluated(pending.dispatched(), result.steps(), result.nanos());
                }
                if (result.matches() && entry.facility.unmet(evaluation.level(), evaluation.condition().when()).isEmpty()) {
                    matched(evaluation, now, pending.arguments());
                } else {
                    state.matches = 0;
                }
            } catch (ScriptFault fault) {
                conditionFault(evaluation, now, FacilityScripts.code(fault));
            }
        }
    }

    private void conditionFault(Evaluation evaluation, long now, String code) {
        evaluation.state().paused = true;
        evaluation.state().matches = 0;
        evaluation.entry().recent = new Result(evaluation.condition().routine(), now, "failed",
            Optional.of("folkways.facility.script." + code));
        facilities.save();
    }
}
