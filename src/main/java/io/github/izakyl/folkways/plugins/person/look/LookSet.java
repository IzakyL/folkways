package io.github.izakyl.folkways.plugins.person.look;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.izakyl.folkways.FolkwaysMod;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

// A named set of looks a data pack offers, for a colony to take on as its look pool in one go.
public record LookSet(List<ResourceLocation> looks) {

    public static final Codec<LookSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.listOf().fieldOf("looks").forGetter(LookSet::looks)
    ).apply(instance, LookSet::new));

    public static final ResourceKey<Registry<LookSet>> REGISTRY = ResourceKey.createRegistryKey(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_look_set"));

    public LookSet {
        looks = List.copyOf(looks);
    }

    public static void registerDataPackRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(REGISTRY, CODEC, CODEC);
    }

    public static List<Map.Entry<ResourceLocation, LookSet>> all(RegistryAccess registries) {
        return registries.registry(REGISTRY)
            .map(sets -> sets.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey().location(), entry.getValue()))
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .toList())
            .orElseGet(List::of);
    }

    public static Optional<LookSet> of(RegistryAccess registries, ResourceLocation id) {
        return registries.registry(REGISTRY).flatMap(sets -> sets.getOptional(id));
    }
}
