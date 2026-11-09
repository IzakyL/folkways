package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.base.persist.Required;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.resident.api.WorkPriorities;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public final class WorkDefaults {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "work_defaults");

    private WorkDefaults() {
    }

    public static WorkPriorities of(Colony colony, ResourceLocation kind) {
        return of(colony.kept(ID), kind);
    }

    public static WorkPriorities of(CompoundTag tag, ResourceLocation kind) {
        if (tag.isEmpty()) {
            return WorkPriorities.from(new CompoundTag());
        }
        CompoundTag kinds = Required.compound(tag, "kinds");
        if (!kinds.contains(kind.toString())) {
            return WorkPriorities.from(new CompoundTag());
        }
        return WorkPriorities.from(Required.compound(Required.compound(kinds, kind.toString()), "trades"));
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

    private static void write(Colony colony, CompoundTag tag, ResourceLocation kind, WorkPriorities priorities) {
        CompoundTag kinds = tag.isEmpty() ? new CompoundTag() : Required.compound(tag, "kinds");
        CompoundTag settings = kinds.contains(kind.toString())
            ? Required.compound(kinds, kind.toString()) : new CompoundTag();
        settings.put("trades", priorities.save());
        kinds.put(kind.toString(), settings);
        tag.put("kinds", kinds);
        colony.keep(ID, tag);
    }

}
