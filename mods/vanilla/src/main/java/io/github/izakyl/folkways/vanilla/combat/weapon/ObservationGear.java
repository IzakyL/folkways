package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Observation equipment uses ordinary profile supplies and is planned from assigned profile supplies. */
public final class ObservationGear {
    private static final List<ResourceLocation> ITEMS = List.of(
        ResourceLocation.withDefaultNamespace("spyglass"),
        ResourceLocation.fromNamespaceAndPath("superbwarfare", "binoculars"),
        ResourceLocation.fromNamespaceAndPath("superbwarfare", "spyglass"));

    private ObservationGear() {
    }

    public static List<ItemStack> choices() {
        return ITEMS.stream().filter(BuiltInRegistries.ITEM::containsKey)
            .map(id -> new ItemStack(BuiltInRegistries.ITEM.get(id))).toList();
    }

    public static boolean assigned(ResidentEntity body) {
        if (!(body.level() instanceof ServerLevel level)) {
            return false;
        }
        return body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(colony -> LoadoutProfiles.of(colony).assigned(body.id()))
            .stream().flatMap(profile -> profile.entries().stream())
            .filter(entry -> entry.kind() == LoadoutProfile.Kind.SUPPLY)
            .anyMatch(entry -> choices().stream().anyMatch(entry::matches));
    }
}
