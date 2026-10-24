package io.github.izakyl.folkways.plugins.wares;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.io.IOException;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Explicit recipe allowlist: reversible storage/packing recipes receive no refunds by default. */
final class CraftPerks {
    private CraftPerks() { }

    static void reward(ServerLevel level, ResourceLocation recipe, List<ItemStack> before,
            Container after, Worker who, List<ItemStack> made) {
        double refundChance = who.effects().value(WaresContent.REFUND);
        double byproductChance = who.effects().value(WaresContent.BYPRODUCT);
        if (refundChance <= 0 && byproductChance <= 0) { return; }
        // Resource stacks let a higher-priority data pack replace one recipe's policy independently.
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath(recipe.getNamespace(),
            "crafting_perks/" + recipe.getPath() + ".json");
        level.getServer().getResourceManager().getResource(path).ifPresent(resource -> {
            try (var reader = resource.openAsReader()) {
                JsonObject rule = JsonParser.parseReader(reader).getAsJsonObject();
                if (refundChance > 0 && rule.has("refund") && rule.get("refund").getAsBoolean()) {
                    for (int slot = 0; slot < before.size(); slot++) {
                        ItemStack original = before.get(slot);
                        if (original.isEmpty()) { continue; }
                        // Assembly removes units without compacting the pool; compare the original slot.
                        ItemStack remaining = after.getItem(slot);
                        int consumed = original.getCount() - (ItemStack.isSameItemSameComponents(original, remaining)
                            ? remaining.getCount() : 0);
                        int refund = 0;
                        for (int unit = 0; unit < consumed; unit++) {
                            if (level.random.nextDouble() < refundChance) { refund++; }
                        }
                        if (refund > 0) { made.add(original.copyWithCount(refund)); }
                    }
                }
                if (byproductChance > 0 && rule.has("byproduct") && level.random.nextDouble() < byproductChance) {
                    ResourceLocation item = ResourceLocation.parse(rule.get("byproduct").getAsString());
                    BuiltInRegistries.ITEM.getOptional(item).ifPresent(value -> made.add(new ItemStack(value,
                        rule.has("count") ? Math.clamp(rule.get("count").getAsInt(), 1, 64) : 1)));
                }
            } catch (IOException | RuntimeException invalid) {
                org.slf4j.LoggerFactory.getLogger("folkways").warn("Invalid crafting perk rule {}", path, invalid);
            }
        });
    }
}
