package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.equipment.api.EquipmentPreview;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSupplier;
import io.github.izakyl.folkways.vanilla.patrol.PatrolContent;
import io.github.izakyl.folkways.vanilla.patrol.PatrolStaffing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Patrol-only supply of the rider’s saddle and horse armor; Core supplies personal equipment at any time. */
public final class PatrolGear implements EquipmentSupplier {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "patrol");
    private static final Set<ResourceLocation> SLOTS = Set.of(MountGear.HORSE_ARMOR);

    @Override
    public boolean supplies(EquipmentProfile.Entry entry) {
        return entry.slot().map(SLOTS::contains).orElse(false);
    }

    @Override
    public EquipmentPreview preview(Colony colony, EquipmentProfile profile, MinecraftServer server) {
        LoadoutProfile view = LoadoutProfile.of(profile);
        ProfileShortages shortages = ProfileShortages.read(colony, view, server);
        Map<UUID, Component> alerts = new HashMap<>();
        if (shortages.unavailableHorses() > 0) {
            view.entries().stream().filter(entry -> entry.kind() == LoadoutProfile.Kind.MOUNT)
                .forEach(entry -> alerts.put(entry.id(), Component.translatable("folkways.equipment.status.no_horse")));
        }
        List<Component> figures = new ArrayList<>();
        int unstaffed = PatrolStaffing.of(colony).shortageFor(profile.id());
        if (unstaffed > 0) {
            figures.add(Component.translatable("folkways.equipment.figure.staffing", unstaffed));
        }
        Map<UUID, Integer> inTransit = PatrolContent.suppliesOf(colony)
            .map(supplies -> supplies.inTransit(supplies.profiles().residents(profile.id()))).orElse(Map.of());
        return new EquipmentPreview(shortages.complete(), shortages.incompleteMembers(), shortages.unloadedMembers(),
            shortages.missing(), shortages.unavailableStock(), inTransit, alerts, figures);
    }
}
