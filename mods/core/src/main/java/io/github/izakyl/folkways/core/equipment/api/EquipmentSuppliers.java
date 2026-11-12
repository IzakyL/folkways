package io.github.izakyl.folkways.core.equipment.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Who supplies which profile entries, and who hears that a resident's profile changed. */
public final class EquipmentSuppliers {
    private static volatile Map<ResourceLocation, EquipmentSupplier> suppliers = Map.of();
    private static final List<BiConsumer<Colony, UUID>> LISTENERS = new CopyOnWriteArrayList<>();

    private EquipmentSuppliers() {
    }

    public static synchronized void register(ResourceLocation id, EquipmentSupplier supplier) {
        Map<ResourceLocation, EquipmentSupplier> next = new LinkedHashMap<>(suppliers);
        if (next.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(supplier)) != null) {
            throw new IllegalArgumentException("equipment supplier already registered: " + id);
        }
        suppliers = Collections.unmodifiableMap(next);
    }

    public static Optional<EquipmentSupplier> of(EquipmentProfile.Entry entry) {
        return suppliers.values().stream().filter(supplier -> supplier.supplies(entry)).findFirst();
    }

    /** Every supplier's view of the profile, merged; each reports only the entries it supplies. */
    public static EquipmentPreview preview(Colony colony, EquipmentProfile profile, MinecraftServer server) {
        EquipmentPreview merged = EquipmentPreview.EMPTY;
        for (EquipmentSupplier supplier : suppliers.values()) {
            List<EquipmentProfile.Entry> own = profile.entries().stream()
                .filter(entry -> of(entry).filter(supplier::equals).isPresent()).toList();
            if (!own.isEmpty()) {
                merged = merged.merge(supplier.preview(colony, profile.with(own), server));
            }
        }
        return merged;
    }

    /** Hears every resident whose assigned profile changed or was replaced, on the server thread. */
    public static void listen(BiConsumer<Colony, UUID> changed) {
        LISTENERS.add(Objects.requireNonNull(changed));
    }

    public static void changed(Colony colony, UUID resident) {
        LISTENERS.forEach(listener -> listener.accept(colony, resident));
    }
}
