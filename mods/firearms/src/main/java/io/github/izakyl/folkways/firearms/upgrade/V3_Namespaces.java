package io.github.izakyl.folkways.firearms.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Generated from the v2 declarations/resources. Frozen release table: never infer an id from a prefix. */
public final class V3_Namespaces implements Step {
    private static final Map<String, String> IDS = Map.ofEntries(
        Map.entry("folkways:item/warfare/ak_47", "folkways_firearms:item/warfare/ak_47"),
        Map.entry("folkways:item/warfare/firearms", "folkways_firearms:item/warfare/firearms"),
        Map.entry("folkways:item/warfare/glock_17", "folkways_firearms:item/warfare/glock_17"),
        Map.entry("folkways:item/warfare/m_4", "folkways_firearms:item/warfare/m_4"),
        Map.entry("folkways:item/warfare/m_870", "folkways_firearms:item/warfare/m_870"),
        Map.entry("folkways:item/warfare/mp_5", "folkways_firearms:item/warfare/mp_5"),
        Map.entry("folkways:item/warfare/svd", "folkways_firearms:item/warfare/svd"),
        Map.entry("folkways:resident_shot", "folkways_firearms:resident_shot"),
        Map.entry("folkways:tacz", "folkways_firearms:tacz"),
        Map.entry("folkways:warfare/ak_47", "folkways_firearms:warfare/ak_47"),
        Map.entry("folkways:warfare/firearm", "folkways_firearms:warfare/firearm"),
        Map.entry("folkways:warfare/firearms", "folkways_firearms:warfare/firearms"),
        Map.entry("folkways:warfare/glock_17", "folkways_firearms:warfare/glock_17"),
        Map.entry("folkways:warfare/m_4", "folkways_firearms:warfare/m_4"),
        Map.entry("folkways:warfare/m_870", "folkways_firearms:warfare/m_870"),
        Map.entry("folkways:warfare/mp_5", "folkways_firearms:warfare/mp_5"),
        Map.entry("folkways:warfare/stations", "folkways_firearms:warfare/stations"),
        Map.entry("folkways:warfare/svd", "folkways_firearms:warfare/svd"),
        Map.entry("folkways:warfare_mortar", "folkways_firearms:warfare_mortar"),
        Map.entry("folkways:warfare_pending", "folkways_firearms:warfare_pending"),
        Map.entry("folkways:warfare_range", "folkways_firearms:warfare_range"));
    private final Surface surface;

    private V3_Namespaces(Surface surface) {
        this.surface = surface;
    }

    public static void register() {
        NamespaceIds.register(IDS);
        for (Surface surface : Surface.values()) {
            Upgrades.part("firearms/namespaces", new V3_Namespaces(surface));
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
