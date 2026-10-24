package io.github.izakyl.folkways.plugins.fishing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

final class WaterLoot {
    // Loot conditions see the actual water origin, dimension, biome and fishing rod.
    static final ResourceKey<LootTable> DISCOVERIES = ResourceKey.create(Registries.LOOT_TABLE,
        ResourceLocation.fromNamespaceAndPath("folkways", "gameplay/fishing_discoveries"));

    static boolean fish(ItemStack stack) { return stack.is(ItemTags.FISHES); }

    static ItemStack uncommon(ServerLevel level, LootParams params, ItemStack original, double rank) {
        if (rank <= 0 || !fish(original)) { return original; }
        // Sample the active local fishing table, preserving datapack and biome conditions. Flatten its fish
        // frequency progressively; uncommon species gain relative weight without inventing absent species.
        Map<Item, Integer> counts = new LinkedHashMap<>();
        Map<Item, ItemStack> samples = new LinkedHashMap<>();
        counts.put(original.getItem(), 1);
        samples.put(original.getItem(), original);
        LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        for (int i = 0; i < 31; i++) {
            for (ItemStack sample : table.getRandomItems(params)) {
                if (!fish(sample)) { continue; }
                counts.merge(sample.getItem(), 1, Integer::sum);
                samples.putIfAbsent(sample.getItem(), sample);
            }
        }
        double exponent = 1 - Math.min(3, rank) / 3;
        double total = counts.values().stream().mapToDouble(n -> Math.pow(n, exponent)).sum();
        double roll = level.random.nextDouble() * total;
        for (var entry : counts.entrySet()) {
            roll -= Math.pow(entry.getValue(), exponent);
            if (roll <= 0) { return samples.get(entry.getKey()).copyWithCount(original.getCount()); }
        }
        return original;
    }

    static List<ItemStack> discoveries(ServerLevel level, LootParams params) {
        return level.getServer().reloadableRegistries().getLootTable(DISCOVERIES).getRandomItems(params);
    }
}
