package io.github.izakyl.folkways.war;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

public record RaidDefinition(List<Wave> waves, Limits limits, String snapshot) {
    private static final Set<EntityType<?>> SUPPORTED = Set.of(EntityType.ZOMBIE, EntityType.HUSK,
        EntityType.SKELETON, EntityType.STRAY, EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.CREEPER, EntityType.RAVAGER, EntityType.WITCH);

    public record Limits(int brokenBlocks, double hardness) { }

    public record Entry(EntityType<?> entity, String role, int count, Map<EquipmentSlot, Item> equipment) {
        public Entry { equipment = Map.copyOf(equipment); }
    }
    public record Wave(int delayTicks, List<Entry> entries) {
        public Wave { entries = List.copyOf(entries); }
        public int count() { return entries.stream().mapToInt(Entry::count).sum(); }
    }
    public RaidDefinition { waves = List.copyOf(waves); }

    public static RaidDefinition parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (GsonHelper.getAsInt(root, "schemaVersion") != 1) { throw new IllegalArgumentException("Unsupported raid schema"); }
        List<Wave> waves = new ArrayList<>();
        for (var value : GsonHelper.getAsJsonArray(root, "waves")) {
            JsonObject wave = value.getAsJsonObject();
            List<Entry> entries = new ArrayList<>();
            for (var member : GsonHelper.getAsJsonArray(wave, "entries")) {
                JsonObject entry = member.getAsJsonObject();
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(GsonHelper.getAsString(entry, "entity")));
                if (!SUPPORTED.contains(type)) { throw new IllegalArgumentException("Unsupported raid entity: " + entry); }
                String role = GsonHelper.getAsString(entry, "role", "fighter");
                if (!Set.of("fighter", "breacher", "arsonist").contains(role)) { throw new IllegalArgumentException("Unknown raid role: " + role); }
                Map<EquipmentSlot, Item> equipment = new LinkedHashMap<>();
                for (var slot : GsonHelper.getAsJsonObject(entry, "equipment", new JsonObject()).entrySet()) {
                    ResourceLocation id = ResourceLocation.parse(slot.getValue().getAsString());
                    if (!BuiltInRegistries.ITEM.containsKey(id)) { throw new IllegalArgumentException("Unknown equipment: " + id); }
                    equipment.put(EquipmentSlot.byName(slot.getKey()), BuiltInRegistries.ITEM.get(id));
                }
                entries.add(new Entry(type, role, bounded(entry, "count", 1, 64), equipment));
            }
            Wave parsed = new Wave(bounded(wave, "delayTicks", 0, 24000), entries);
            if (parsed.count() < 1 || parsed.count() > 64) { throw new IllegalArgumentException("Raid wave needs 1–64 members"); }
            waves.add(parsed);
        }
        if (waves.isEmpty() || waves.size() > 16) { throw new IllegalArgumentException("Raid needs 1–16 waves"); }
        JsonObject limits = GsonHelper.getAsJsonObject(root, "limits", new JsonObject());
        int breaks = GsonHelper.getAsInt(limits, "brokenBlocks", 96);
        double hardness = GsonHelper.getAsDouble(limits, "hardness", 5);
        if (breaks < 0 || breaks > 4096 || !Double.isFinite(hardness) || hardness < 0 || hardness >= 50) {
            throw new IllegalArgumentException("Invalid raid damage limits");
        }
        return new RaidDefinition(waves, new Limits(breaks, hardness), root.toString());
    }

    private static int bounded(JsonObject object, String key, int min, int max) {
        int value = GsonHelper.getAsInt(object, key);
        if (value < min || value > max) { throw new IllegalArgumentException(key + " outside " + min + ".." + max); }
        return value;
    }
}
