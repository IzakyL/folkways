package io.github.izakyl.folkways.golems.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Generated from the v2 declarations/resources. Frozen release table: never infer an id from a prefix. */
public final class V3_Namespaces implements Step {
    private static final Map<String, String> IDS = Map.ofEntries(
        Map.entry("folkways:delegation/golem_body", "folkways_golems:delegation/golem_body"),
        Map.entry("folkways:doing/mending", "folkways_golems:doing/mending"),
        Map.entry("folkways:golem", "folkways_golems:golem"),
        Map.entry("folkways:golem_body", "folkways_golems:golem_body"),
        Map.entry("folkways:golems", "folkways_golems:golems"),
        Map.entry("folkways:ledger/delegation/golem_body", "folkways_golems:ledger/delegation/golem_body"),
        Map.entry("folkways:ledger/doing/mending", "folkways_golems:ledger/doing/mending"),
        Map.entry("folkways:mend", "folkways_golems:mend"),
        Map.entry("folkways:mending", "folkways_golems:mending"),
        Map.entry("folkways:textures/gui/sprites/delegation/golem_body.png",
            "folkways_golems:textures/gui/sprites/delegation/golem_body.png"),
        Map.entry("folkways:textures/gui/sprites/doing/mending.png",
            "folkways_golems:textures/gui/sprites/doing/mending.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/delegation/golem_body.png",
            "folkways_golems:textures/gui/sprites/ledger/delegation/golem_body.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/mending.png",
            "folkways_golems:textures/gui/sprites/ledger/doing/mending.png"));
    private final Surface surface;

    private V3_Namespaces(Surface surface) {
        this.surface = surface;
    }

    public static void register() {
        NamespaceIds.register(IDS);
        for (Surface surface : Surface.values()) {
            Upgrades.part("golems/namespaces", new V3_Namespaces(surface));
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
