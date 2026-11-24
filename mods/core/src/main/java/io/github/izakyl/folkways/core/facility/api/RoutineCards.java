package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** A card for sending a destination and depositing a nearby item stack. */
public final class RoutineCards {

    private RoutineCards() {
    }

    public static FacilityDefinition destinationAndDeposit(UUID id, String name, WorldPos anchor,
                                                Map<String, Control> controls, String destination,
                                                Collection<String> cargo, Collection<UUID> bodies) {
        Map<String, FacilityDefinition.Placed> parts = new LinkedHashMap<>();
        controls.forEach((key, control) -> {
            if (anchor.sameRealm(control.anchor())) {
                parts.put(key, new FacilityDefinition.Placed(control.anchor().cell().subtract(anchor.cell()), control.part()));
            }
        });
        Map<String, Routine.Use> routines = new LinkedHashMap<>();
        Map<String, FacilityDefinition.Switch> switches = new LinkedHashMap<>();
        List<Card.Element> card = new ArrayList<>();
        if (parts.containsKey(destination)) {
            routines.put("move_to", new Routine.Use(Routines.MOVE_TO, Map.of("drive", new Value.Text(destination))));
            target(switches, card, "move_to", Card.Pick.GROUND, "compass");
        }
        List<Value> into = cargo.stream().filter(parts::containsKey)
            .filter(port -> Deposit.isRemoteItemPort(controls.get(port))).<Value>map(Value.Text::new).toList();
        if (!into.isEmpty()) {
            routines.put("deposit", new Routine.Use(Routines.DEPOSIT, Map.of("into", new Value.Sequence(into))));
            target(switches, card, "deposit", Card.Pick.ITEM, "chest");
        }
        return new FacilityDefinition(id, anchor, parts, routines, Map.of(), Map.of(), name,
            new FacilityExtent(bodies.stream().<FacilityExtent.Element>map(FacilityExtent.Entity::new)
                .collect(java.util.stream.Collectors.toSet())), new Card(card), List.of(),
            switches, Optional.empty(), List.of());
    }

    private static void target(Map<String, FacilityDefinition.Switch> switches, List<Card.Element> card, String name,
                               Card.Pick pick, String icon) {
        switches.put(name, new FacilityDefinition.Switch(new Trigger.Manual(name), true));
        card.add(new Card.Element.Target(name, pick, Optional.of(ResourceLocation.withDefaultNamespace(icon)),
            Optional.of(new Card.Label("folkways.rts.facility.routine." + name, true)), true, Map.of()));
    }
}
