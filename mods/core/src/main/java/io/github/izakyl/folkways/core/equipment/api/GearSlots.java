package io.github.izakyl.folkways.core.equipment.api;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.Consumer;
import io.github.izakyl.folkways.core.resident.api.Body;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

/**
 * Every slot an equipment profile can name, grouped for the page. Core declares the four armour slots; other modules
 * register their own groups and slots during mod construction. A profile keeps entries for slots no installed module
 * declares, so removing an accessory mod loses nothing, but such entries are neither supplied nor shown.
 */
public final class GearSlots {
    public static final ResourceLocation BODY = core("body");
    public static final ResourceLocation HEAD = core("head");
    public static final ResourceLocation CHEST = core("chest");
    public static final ResourceLocation LEGS = core("legs");
    public static final ResourceLocation FEET = core("feet");

    private static final Map<ResourceLocation, Consumer<Body>> drops = new LinkedHashMap<>();

    private static volatile Map<ResourceLocation, GearGroup> groups = Map.of();
    private static volatile Map<ResourceLocation, GearSlot> slots = Map.of();
    private static final Map<ResourceLocation, Supplier<List<GearSlot>>> sources = new LinkedHashMap<>();

    static {
        register(new GearGroup(BODY, Component.translatable("folkways.equipment.group.body"),
            GearGroup.Placement.LEFT, 0));
        body(HEAD, EquipmentSlot.HEAD, 0, "empty_armor_slot_helmet");
        body(CHEST, EquipmentSlot.CHEST, 1, "empty_armor_slot_chestplate");
        body(LEGS, EquipmentSlot.LEGS, 2, "empty_armor_slot_leggings");
        body(FEET, EquipmentSlot.FEET, 3, "empty_armor_slot_boots");
    }

    private GearSlots() {
    }

    public static synchronized void register(GearGroup group) {
        Map<ResourceLocation, GearGroup> next = new LinkedHashMap<>(groups);
        if (next.putIfAbsent(group.id(), group) != null) {
            throw new IllegalArgumentException("gear group already registered: " + group.id());
        }
        groups = Collections.unmodifiableMap(next);
    }

    public static synchronized void register(GearSlot slot) {
        Objects.requireNonNull(slot.id());
        if (!groups.containsKey(slot.group())) {
            throw new IllegalArgumentException("gear slot " + slot.id() + " names unknown group " + slot.group());
        }
        Map<ResourceLocation, GearSlot> next = new LinkedHashMap<>(slots);
        if (next.putIfAbsent(slot.id(), slot) != null) {
            throw new IllegalArgumentException("gear slot already registered: " + slot.id());
        }
        slots = Collections.unmodifiableMap(next);
    }

    public static Optional<GearSlot> slot(ResourceLocation id) {
        return all().stream().filter(slot -> slot.id().equals(id)).findFirst();
    }

    public static Optional<GearGroup> group(ResourceLocation id) {
        return Optional.ofNullable(groups.get(id));
    }

    /** Groups in page order: by placement, then order, then registration. */
    public static List<GearGroup> groups() {
        return groups.values().stream()
            .sorted(Comparator.comparing(GearGroup::placement).thenComparingInt(GearGroup::order)).toList();
    }

    public static List<GearSlot> in(ResourceLocation group) {
        return java.util.stream.Stream.concat(slots.values().stream(), sources.values().stream()
            .flatMap(source -> source.get().stream())).filter(slot -> slot.group().equals(group))
            .sorted(Comparator.comparingInt(GearSlot::order)).toList();
    }

    /** All slots in page order. */
    public static List<GearSlot> all() {
        return groups().stream().flatMap(group -> in(group.id()).stream()).toList();
    }

    /** Frameworks drain their real and cosmetic inventories before a resident is destroyed. */
    public static synchronized void drops(ResourceLocation owner, Consumer<Body> drain) {
        if (drops.putIfAbsent(owner, drain) != null) {
            throw new IllegalArgumentException("gear drops already registered: " + owner);
        }
    }

    public static void dropAccessories(Body body) {
        drops.values().forEach(drain -> drain.accept(body));
    }

    /** The core slot standing for a vanilla equipment slot, for the four armour slots. */
    public static Optional<ResourceLocation> of(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Optional.of(HEAD);
            case CHEST -> Optional.of(CHEST);
            case LEGS -> Optional.of(LEGS);
            case FEET -> Optional.of(FEET);
            default -> Optional.empty();
        };
    }

    public static Optional<EquipmentSlot> equipment(ResourceLocation slot) {
        for (EquipmentSlot each : EquipmentSlot.values()) {
            if (of(each).filter(slot::equals).isPresent()) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }

    /** Where an item is worn by its own account: its equipment slot, or the main hand. */
    public static EquipmentSlot wornAt(ItemStack stack) {
        EquipmentSlot custom = stack.getEquipmentSlot();
        if (custom != null) {
            return custom;
        }
        Equipable equipable = Equipable.get(stack);
        return equipable == null ? EquipmentSlot.MAINHAND : equipable.getEquipmentSlot();
    }

    private static void body(ResourceLocation id, EquipmentSlot slot, int order, String icon) {
        register(GearSlot.of(id, BODY, order, text(id.getPath()), Optional.of(itemTexture(icon)),
            stack -> wornAt(stack) == slot, body -> body.mob().getItemBySlot(slot),
            (figure, stack) -> figure.setItemSlot(slot, stack)));
    }

    /** Reads framework data on demand, after that framework has reloaded or synchronized its own slot data. */
    public static synchronized void dynamic(ResourceLocation owner, Supplier<List<GearSlot>> source) {
        if (sources.putIfAbsent(owner, source) != null) {
            throw new IllegalArgumentException("gear source already registered: " + owner);
        }
    }

    private static ResourceLocation itemTexture(String name) {
        return ResourceLocation.withDefaultNamespace("textures/item/" + name + ".png");
    }

    private static Component text(String key) {
        return Component.translatable("folkways.equipment.slot." + key);
    }

    private static ResourceLocation core(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", path);
    }
}
