package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

public final class ResidentLooks {
    public static final ResourceKey<Registry<ResidentLook>> REGISTRY = ResourceKey.createRegistryKey(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_look"));

    public static final ResidentLook FALLBACK = ResidentLook.ofSkin(
        ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png"),
        ResidentLook.Arms.WIDE);

    private static volatile Map<ResourceLocation, ResidentLook> runtime = Map.of();

    private ResidentLooks() {
    }

    public static void installRuntime(Map<ResourceLocation, ResidentLook> entries) {
        runtime = Map.copyOf(entries);
    }

    public static void forgetRuntime() {
        runtime = Map.of();
    }

    public static Map<ResourceLocation, ResidentLook> pool(RegistryAccess registries) {
        Map<ResourceLocation, ResidentLook> merged = new TreeMap<>(runtime);
        registries.registry(REGISTRY).ifPresent(pool ->
            pool.entrySet().forEach(entry -> merged.put(entry.getKey().location(), entry.getValue())));
        return merged;
    }

    public static List<ResourceKey<ResidentLook>> keysInPool(RegistryAccess registries) {
        return pool(registries).keySet().stream().map(id -> ResourceKey.create(REGISTRY, id)).toList();
    }

    public static Optional<ResidentLook> entry(RegistryAccess registries, ResourceKey<ResidentLook> key) {
        ResidentLook fromFolder = runtime.get(key.location());
        return fromFolder != null
            ? Optional.of(fromFolder)
            : registries.registry(REGISTRY).flatMap(pool -> pool.getOptional(key));
    }

    public static List<ResidentLook.Model> modelsInPool(Level level) {
        if (level == null) {
            return List.of();
        }
        return pool(level.registryAccess()).values().stream()
            .map(ResidentLook::appearance)
            .filter(ResidentLook.Model.class::isInstance)
            .map(ResidentLook.Model.class::cast)
            .toList();
    }

    public static void registerDataPackRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(REGISTRY, ResidentLook.CODEC, ResidentLook.CODEC);
    }

    public static Optional<ResourceKey<ResidentLook>> draw(RegistryAccess registries, UUID resident) {
        return draw(registries, resident, key -> true);
    }

    public static Optional<ResourceKey<ResidentLook>> draw(
            RegistryAccess registries, UUID resident, Predicate<ResourceLocation> allowed) {
        return weighted(pool(registries), resident, allowed);
    }

    public static ResidentLook resolve(
            RegistryAccess registries, Optional<ResourceKey<ResidentLook>> pinned, UUID resident) {
        Optional<ResidentLook> byPin = pinned.flatMap(key -> entry(registries, key));
        if (byPin.isPresent()) {
            return byPin.get();
        }
        Map<ResourceLocation, ResidentLook> pool = pool(registries);
        return weighted(pool, resident, key -> true).map(key -> pool.get(key.location())).orElse(FALLBACK);
    }

    public static ResidentLook of(ResidentEntity resident) {
        return resolve(resident.level().registryAccess(), resident.lookId(), resident.getUUID());
    }

    private static Optional<ResourceKey<ResidentLook>> weighted(
            Map<ResourceLocation, ResidentLook> pool, UUID resident, Predicate<ResourceLocation> allowed) {
        List<Map.Entry<ResourceLocation, ResidentLook>> entries = pool.entrySet().stream()
            .filter(entry -> allowed.test(entry.getKey()))
            .toList();
        int total = entries.stream().mapToInt(entry -> entry.getValue().weight()).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        RandomSource lots = RandomSource.create(
            resident.getMostSignificantBits() ^ resident.getLeastSignificantBits());
        int roll = lots.nextInt(total);
        for (Map.Entry<ResourceLocation, ResidentLook> entry : entries) {
            roll -= entry.getValue().weight();
            if (roll < 0) {
                return Optional.of(ResourceKey.create(REGISTRY, entry.getKey()));
            }
        }
        return Optional.of(ResourceKey.create(REGISTRY, entries.getLast().getKey()));
    }
}
