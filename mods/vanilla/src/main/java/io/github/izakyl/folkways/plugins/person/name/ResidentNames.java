package io.github.izakyl.folkways.plugins.person.name;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

public final class ResidentNames {
    public static final ResourceKey<Registry<NamePool>> REGISTRY = ResourceKey.createRegistryKey(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_name"));

    private static final long SALT = 0x9E3779B97F4A7C15L;

    private static final Component FALLBACK = Component.translatable("entity.folkways.resident");

    public record Drawn(String given, String surname) {

        public Component asName() {
            return Component.literal(surname.isEmpty() ? given : given + " " + surname);
        }
    }

    private ResidentNames() {
    }

    public static void registerDataPackRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(REGISTRY, NamePool.CODEC, NamePool.CODEC);
    }

    public static Component of(RegistryAccess registries, UUID resident) {
        return draw(merged(registries), resident).map(Drawn::asName).orElse(FALLBACK);
    }

    // Every name set the data packs offer, taken together in the order of their ids.
    public static NamePool merged(RegistryAccess registries) {
        List<NamePool> ordered = sets(registries).stream().map(Map.Entry::getValue).toList();
        return new NamePool(
            ordered.stream().flatMap(entry -> entry.names().stream()).toList(),
            ordered.stream().flatMap(entry -> entry.surnames().stream()).toList());
    }

    public static List<Map.Entry<ResourceLocation, NamePool>> sets(RegistryAccess registries) {
        return registries.registry(REGISTRY)
            .map(pool -> pool.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey().location(), entry.getValue()))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .toList())
            .orElseGet(List::of);
    }

    public static Optional<NamePool> set(RegistryAccess registries, ResourceLocation id) {
        return registries.registry(REGISTRY).flatMap(pool -> pool.getOptional(id));
    }

    // The same resident always draws the same name from the same pool.
    public static Optional<Drawn> draw(NamePool pool, UUID resident) {
        if (pool.names().isEmpty()) {
            return Optional.empty();
        }
        RandomSource lots = RandomSource.create(
            resident.getMostSignificantBits() ^ resident.getLeastSignificantBits() ^ SALT);
        String given = pool.names().get(lots.nextInt(pool.names().size()));
        String surname = pool.surnames().isEmpty()
            ? "" : pool.surnames().get(lots.nextInt(pool.surnames().size()));
        return Optional.of(new Drawn(given, surname));
    }
}
