package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** Colony-kept profiles; hull bindings travel with the hull's entity NBT. Server thread only. */
public final class VehicleProfiles {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-combat");
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "vehicle_profiles");
    public static final String BINDING = "folkways_core:vehicle_profile";
    private final Colony colony;
    private final HolderLookup.Provider registries;

    public VehicleProfiles(Colony colony, HolderLookup.Provider registries) {
        this.colony = colony;
        this.registries = registries;
    }

    public List<VehicleProfile> all() {
        List<VehicleProfile> profiles = new ArrayList<>();
        for (Tag tag : colony.kept(ID).getList("profiles", Tag.TAG_COMPOUND)) {
            try {
                profiles.add(VehicleProfile.load((CompoundTag) tag, registries));
            } catch (RuntimeException invalid) {
                LOGGER.warn("Skipping invalid vehicle profile in colony {}", colony.id(), invalid);
            }
        }
        return List.copyOf(profiles);
    }

    public Optional<VehicleProfile> get(UUID id) {
        return all().stream().filter(profile -> profile.id().equals(id)).findFirst();
    }

    public Optional<VehicleProfile> defaultFor(ResourceLocation type) {
        CompoundTag defaults = colony.kept(ID).getCompound("defaults");
        return defaults.hasUUID(type.toString()) ? get(defaults.getUUID(type.toString())) : Optional.empty();
    }

    public void replace(VehicleProfile profile) {
        List<VehicleProfile> profiles = new ArrayList<>(all());
        profiles.removeIf(held -> held.id().equals(profile.id()));
        if (profiles.size() >= 256) {
            throw new IllegalArgumentException("too many vehicle profiles");
        }
        profiles.add(profile);
        CompoundTag tag = colony.kept(ID);
        ListTag rows = new ListTag();
        profiles.forEach(value -> rows.add(value.save(registries)));
        tag.put("profiles", rows);
        CompoundTag defaults = tag.getCompound("defaults");
        if (!defaults.hasUUID(profile.type().toString())) {
            defaults.putUUID(profile.type().toString(), profile.id());
        }
        tag.put("defaults", defaults);
        colony.keep(ID, tag);
    }

    public void delete(UUID id) {
        CompoundTag tag = colony.kept(ID);
        ListTag rows = new ListTag();
        all().stream().filter(profile -> !profile.id().equals(id)).forEach(profile -> rows.add(profile.save(registries)));
        tag.put("profiles", rows);
        CompoundTag defaults = tag.getCompound("defaults");
        for (String type : List.copyOf(defaults.getAllKeys())) {
            if (defaults.getUUID(type).equals(id)) {
                defaults.remove(type);
            }
        }
        tag.put("defaults", defaults);
        colony.keep(ID, tag);
    }

    public Optional<VehicleProfile> assigned(Entity hull) {
        return hull.getPersistentData().hasUUID(BINDING)
            ? get(hull.getPersistentData().getUUID(BINDING)) : Optional.empty();
    }

    public void bind(Entity hull, UUID profile, ResourceLocation type) {
        VehicleProfile value = get(profile).orElseThrow();
        if (!value.type().equals(type)) {
            throw new IllegalArgumentException("wrong vehicle type");
        }
        hull.getPersistentData().putUUID(BINDING, profile);
    }
}
