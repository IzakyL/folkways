package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.FolkwaysAttachments;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.resident.body.Licences;
import io.github.izakyl.folkways.core.api.resident.body.WorkPriorities;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public final class WorkDefaults {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "work_defaults");

    private WorkDefaults() {
    }

    public static WorkPriorities of(Colony colony, ResourceLocation kind) {
        return of(colony.kept(ID), kind);
    }

    private static WorkPriorities of(CompoundTag tag, ResourceLocation kind) {
        return WorkPriorities.from(tag.getCompound("kinds").getCompound(kind.toString()).getCompound("trades"));
    }

    public static boolean set(Colony colony, ResourceLocation kind, ResourceLocation trade, boolean allowed, int level) {
        Optional<Keenness> priority = Keenness.ofNumber(level);
        Participation participation = Participations.between(kind, Stake.vocation(trade));
        if (ResidentKinds.of(kind).filter(known -> known.workDefaults()).isEmpty()
                || Vocations.of(trade).isEmpty() || participation == Participation.NONE || priority.isEmpty()) {
            return false;
        }
        // Read again for each entry, so another player's changes to other trades stay in place.
        CompoundTag tag = colony.kept(ID);
        write(colony, tag, kind, of(tag, kind).with(trade,
            new WorkPriorities.Rule(allowed || participation == Participation.ALWAYS, priority.get())));
        return true;
    }

    public static void reset(Colony colony, ResourceLocation kind) {
        if (ResidentKinds.of(kind).filter(known -> known.workDefaults()).isEmpty()) {
            return;
        }
        CompoundTag tag = colony.kept(ID);
        WorkPriorities priorities = of(tag, kind);
        // Keep missing plugins' entries until their trades are available to edit again.
        for (ResourceLocation trade : Vocations.ids()) {
            priorities = priorities.with(trade, WorkPriorities.DEFAULT);
        }
        write(colony, tag, kind, priorities);
    }

    private static void write(Colony colony, CompoundTag tag, ResourceLocation kind, WorkPriorities priorities) {
        CompoundTag kinds = tag.getCompound("kinds");
        CompoundTag settings = kinds.getCompound(kind.toString());
        settings.put("trades", priorities.save());
        kinds.put(kind.toString(), settings);
        tag.putInt("version", 1);
        tag.put("kinds", kinds);
        colony.keep(ID, tag);
    }

    static void apply(CompoundTag tag, Body body) {
        if (!body.kind().workDefaults()) {
            return;
        }
        WorkPriorities priorities = of(tag, body.kind().id());
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
