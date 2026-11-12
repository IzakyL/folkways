package io.github.izakyl.folkways.core.equipment.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;

/**
 * How far a profile's assigned residents are from what it names, as one supplier sees the entries it supplies. A
 * preview never reserves anything. Maps are keyed by entry id: units the residents still lack, units the colony's
 * stores cannot cover, and residents with a delivery of the entry under way. An alert replaces an entry's status
 * (a rider without a horse); figures are extra headline counts for the page.
 */
public record EquipmentPreview(boolean complete, Set<UUID> incomplete, Set<UUID> unloaded,
                               Map<UUID, Integer> missing, Map<UUID, Integer> unavailableStock,
                               Map<UUID, Integer> inTransit, Map<UUID, Component> alerts, List<Component> figures,
                               Map<UUID, EquipmentKits.Slots> slots) {
    public static final EquipmentPreview EMPTY =
        new EquipmentPreview(true, Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());

    public EquipmentPreview(boolean complete, Set<UUID> incomplete, Set<UUID> unloaded,
                            Map<UUID, Integer> missing, Map<UUID, Integer> unavailableStock,
                            Map<UUID, Integer> inTransit, Map<UUID, Component> alerts, List<Component> figures) {
        this(complete, incomplete, unloaded, missing, unavailableStock, inTransit, alerts, figures, Map.of());
    }

    public EquipmentPreview {
        incomplete = Set.copyOf(incomplete);
        unloaded = Set.copyOf(unloaded);
        missing = Map.copyOf(missing);
        unavailableStock = Map.copyOf(unavailableStock);
        inTransit = Map.copyOf(inTransit);
        alerts = Map.copyOf(alerts);
        figures = List.copyOf(figures);
        slots = Map.copyOf(slots);
    }

    public EquipmentPreview merge(EquipmentPreview other) {
        Set<UUID> short1 = new LinkedHashSet<>(incomplete);
        short1.addAll(other.incomplete);
        Set<UUID> away = new LinkedHashSet<>(unloaded);
        away.addAll(other.unloaded);
        List<Component> lines = new ArrayList<>(figures);
        lines.addAll(other.figures);
        var usage = new HashMap<>(slots);
        other.slots.forEach(usage::putIfAbsent);
        return new EquipmentPreview(complete && other.complete, short1, away, sum(missing, other.missing),
            sum(unavailableStock, other.unavailableStock), sum(inTransit, other.inTransit),
            joined(alerts, other.alerts), lines, usage);
    }

    private static Map<UUID, Integer> sum(Map<UUID, Integer> one, Map<UUID, Integer> two) {
        Map<UUID, Integer> total = new HashMap<>(one);
        two.forEach((key, value) -> total.merge(key, value, Integer::sum));
        return total;
    }

    private static Map<UUID, Component> joined(Map<UUID, Component> one, Map<UUID, Component> two) {
        Map<UUID, Component> total = new HashMap<>(one);
        two.forEach(total::putIfAbsent);
        return total;
    }
}
