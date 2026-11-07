package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Licences;
import io.github.izakyl.folkways.core.resident.api.WorkPriorities;
import io.github.izakyl.folkways.core.shell.neoforge.FolkwaysAttachments;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import net.minecraft.nbt.CompoundTag;

final class WorkDefaultApplication {

    private WorkDefaultApplication() {
    }

    static void apply(CompoundTag tag, Body body) {
        if (!body.kind().workDefaults()) {
            return;
        }
        WorkPriorities priorities = WorkDefaults.of(tag, body.kind().id());
        Licences licences = body.licences();
        for (var vocation : Vocations.all()) {
            licences.trade(vocation).ifPresent(trade -> {
                WorkPriorities.Rule rule = priorities.of(vocation.id());
                licences.allow(trade, rule.allowed());
                licences.setKeenness(trade, rule.priority());
            });
        }
        body.mob().setData(FolkwaysAttachments.LICENCES, licences);
    }
}
