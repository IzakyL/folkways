package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.motion.CombatMotions;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Optional;

/** The work runner supplies intent and its live boundary; this adapter supplies native EF movement. */
public final class EpicFightMotionAdapter implements CombatMotions.Adapter {
    public static final EpicFightMotionAdapter INSTANCE = new EpicFightMotionAdapter();

    private EpicFightMotionAdapter() { }

    @Override
    public boolean supports(Worker worker, CombatMotions.Kind kind) {
        return worker.body() instanceof ResidentEntity resident && ResidentEpicFightPatch.of(resident) != null;
    }

    @Override
    public Optional<CombatMotions.Session> open(Worker worker, CombatMotions.Request request) {
        return EpicFightMotionSession.open(worker, request).map(session -> session);
    }
}
