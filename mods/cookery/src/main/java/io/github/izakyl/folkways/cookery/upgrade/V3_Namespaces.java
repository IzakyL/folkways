package io.github.izakyl.folkways.cookery.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Generated from the v2 declarations/resources. Frozen release table: never infer an id from a prefix. */
public final class V3_Namespaces implements Step {
    private static final Map<String, String> IDS = Map.ofEntries(
        Map.entry("folkways:block/climbable", "folkways_cookery:block/climbable"),
        Map.entry("folkways:chopping_food", "folkways_cookery:chopping_food"),
        Map.entry("folkways:cookery", "folkways_cookery:cookery"),
        Map.entry("folkways:cookery/procedural/dark_cuisine", "folkways_cookery:cookery/procedural/dark_cuisine"),
        Map.entry("folkways:cookery/procedural/mystery_tea", "folkways_cookery:cookery/procedural/mystery_tea"),
        Map.entry("folkways:cookery/procedural/suspicious_stir_fry",
            "folkways_cookery:cookery/procedural/suspicious_stir_fry"),
        Map.entry("folkways:cookery_chain", "folkways_cookery:cookery_chain"),
        Map.entry("folkways:cookery_clothing", "folkways_cookery:cookery_clothing"),
        Map.entry("folkways:cookery_clothing_items", "folkways_cookery:cookery_clothing_items"),
        Map.entry("folkways:cookery_dining", "folkways_cookery:cookery_dining"),
        Map.entry("folkways:cookery_equipment", "folkways_cookery:cookery_equipment"),
        Map.entry("folkways:cookery_heat", "folkways_cookery:cookery_heat"),
        Map.entry("folkways:cookery_millstone", "folkways_cookery:cookery_millstone"),
        Map.entry("folkways:cookery_orders", "folkways_cookery:cookery_orders"),
        Map.entry("folkways:cookery_pots", "folkways_cookery:cookery_pots"),
        Map.entry("folkways:cookery_rice", "folkways_cookery:cookery_rice"),
        Map.entry("folkways:cookery_seeds", "folkways_cookery:cookery_seeds"),
        Map.entry("folkways:cookery_sickles", "folkways_cookery:cookery_sickles"),
        Map.entry("folkways:cookery_spit", "folkways_cookery:cookery_spit"),
        Map.entry("folkways:cookery_steamer", "folkways_cookery:cookery_steamer"),
        Map.entry("folkways:cookery_teapot", "folkways_cookery:cookery_teapot"),
        Map.entry("folkways:cookery_tools", "folkways_cookery:cookery_tools"),
        Map.entry("folkways:cookery_tray", "folkways_cookery:cookery_tray"),
        Map.entry("folkways:cooking_food", "folkways_cookery:cooking_food"),
        Map.entry("folkways:gathering_seeds", "folkways_cookery:gathering_seeds"),
        Map.entry("folkways:item/cookery_sickles", "folkways_cookery:item/cookery_sickles"),
        Map.entry("folkways:keeping_seeds", "folkways_cookery:keeping_seeds"),
        Map.entry("folkways:kitchen", "folkways_cookery:kitchen"),
        Map.entry("folkways:leaving_millstone", "folkways_cookery:leaving_millstone"),
        Map.entry("folkways:preparing_food", "folkways_cookery:preparing_food"),
        Map.entry("folkways:restoring_grass", "folkways_cookery:restoring_grass"),
        Map.entry("folkways:seed_grass", "folkways_cookery:seed_grass"),
        Map.entry("folkways:serving_food", "folkways_cookery:serving_food"),
        Map.entry("folkways:sickle_ready_at", "folkways_cookery:sickle_ready_at"),
        Map.entry("folkways:stove", "folkways_cookery:stove"),
        Map.entry("folkways:tavern", "folkways_cookery:tavern"),
        Map.entry("folkways:tavern_counter", "folkways_cookery:tavern_counter"));
    private final Surface surface;

    private V3_Namespaces(Surface surface) {
        this.surface = surface;
    }

    public static void register() {
        NamespaceIds.register(IDS);
        for (Surface surface : Surface.values()) {
            Upgrades.part("cookery/namespaces", new V3_Namespaces(surface));
        }
    }

    @Override
    public int target() {
        return 3;
    }

    @Override
    public Surface surface() {
        return surface;
    }

    @Override
    public void apply(CompoundTag raw) {
        NamespaceIds.rewrite(raw, IDS);
    }
}
