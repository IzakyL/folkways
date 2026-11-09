package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Supplies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Patrol supplies and loadout profiles, as the command page shows them. */
public record PatrolSupplyReadout(Colony colony) implements Supplies {

    @Override
    public boolean underway(UUID resident) {
        return PatrolSupplies.of(colony).underway(resident);
    }

    @Override
    public Optional<Component> stock(Body body) {
        PatrolSupplies supplies = PatrolSupplies.of(colony);
        MutableComponent stock = Component.translatable(
            "folkways.combat.stock." + supplies.status(body.id()).state().name().toLowerCase(Locale.ROOT));
        if (body instanceof ResidentEntity person) {
            stock.append(" · ").append(supplies.detail(person));
        }
        return Optional.of(stock);
    }

    @Override
    public Optional<Component> loadout(Collection<UUID> members) {
        LoadoutProfiles profiles = PatrolSupplies.of(colony).profiles();
        Set<String> names = new LinkedHashSet<>();
        members.forEach(member -> names.add(profiles.assigned(member).map(LoadoutProfile::name).orElse("")));
        Component name = names.size() > 1
            ? Component.translatable("folkways.combat.loadout.mixed")
            : names.isEmpty() || names.iterator().next().isEmpty()
            ? Component.translatable("folkways.loadouts.none")
            : Component.literal(names.iterator().next());
        return Optional.of(Component.translatable("folkways.combat.preference", name));
    }

    @Override
    public List<Component> help() {
        return List.of(
            Component.translatable("folkways.combat.supply_help"),
            Component.translatable("folkways.loadouts.manage"));
    }
}
