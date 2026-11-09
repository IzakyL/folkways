package io.github.izakyl.folkways.carving.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Generated from the v2 declarations/resources. Frozen release table: never infer an id from a prefix. */
public final class V3_Namespaces implements Step {
    private static final Map<String, String> IDS = Map.ofEntries(
        Map.entry("folkways:chiselsandbits", "folkways_carving:chiselsandbits"),
        Map.entry("folkways:curved_gallery", "folkways_carving:curved_gallery"),
        Map.entry("folkways:curved_gallery.star", "folkways_carving:curved_gallery.star"),
        Map.entry("folkways:gothic_cloister", "folkways_carving:gothic_cloister"),
        Map.entry("folkways:gothic_cloister.star", "folkways_carving:gothic_cloister.star"),
        Map.entry("folkways:pavilion", "folkways_carving:pavilion"),
        Map.entry("folkways:pavilion.star", "folkways_carving:pavilion.star"),
        Map.entry("folkways:rampart", "folkways_carving:rampart"),
        Map.entry("folkways:rampart.star", "folkways_carving:rampart.star"),
        Map.entry("folkways:resolution", "folkways_carving:resolution"),
        Map.entry("folkways:rotunda", "folkways_carving:rotunda"),
        Map.entry("folkways:rotunda.star", "folkways_carving:rotunda.star"),
        Map.entry("folkways:schematic", "folkways_carving:schematic"),
        Map.entry("folkways:stone_arch_bridge", "folkways_carving:stone_arch_bridge"),
        Map.entry("folkways:stone_arch_bridge.star", "folkways_carving:stone_arch_bridge.star"),
        Map.entry("folkways:torii", "folkways_carving:torii"),
        Map.entry("folkways:torii.star", "folkways_carving:torii.star"),
        // v0.1.0 Patterns.idOf used the resource namespace; both pattern and library ids were exposed.
        Map.entry("folkways_chiselsandbits:curved_gallery", "folkways_carving:curved_gallery"),
        Map.entry("folkways_chiselsandbits:curved_gallery.star", "folkways_carving:curved_gallery.star"),
        Map.entry("folkways_chiselsandbits:gothic_cloister", "folkways_carving:gothic_cloister"),
        Map.entry("folkways_chiselsandbits:gothic_cloister.star", "folkways_carving:gothic_cloister.star"),
        Map.entry("folkways_chiselsandbits:pavilion", "folkways_carving:pavilion"),
        Map.entry("folkways_chiselsandbits:pavilion.star", "folkways_carving:pavilion.star"),
        Map.entry("folkways_chiselsandbits:rampart", "folkways_carving:rampart"),
        Map.entry("folkways_chiselsandbits:rampart.star", "folkways_carving:rampart.star"),
        Map.entry("folkways_chiselsandbits:rotunda", "folkways_carving:rotunda"),
        Map.entry("folkways_chiselsandbits:rotunda.star", "folkways_carving:rotunda.star"),
        Map.entry("folkways_chiselsandbits:stone_arch_bridge", "folkways_carving:stone_arch_bridge"),
        Map.entry("folkways_chiselsandbits:stone_arch_bridge.star", "folkways_carving:stone_arch_bridge.star"),
        Map.entry("folkways_chiselsandbits:torii", "folkways_carving:torii"),
        Map.entry("folkways_chiselsandbits:torii.star", "folkways_carving:torii.star"));
    private final Surface surface;

    private V3_Namespaces(Surface surface) {
        this.surface = surface;
    }

    public static void register() {
        NamespaceIds.register(IDS);
        for (Surface surface : Surface.values()) {
            Upgrades.part("carving/namespaces", new V3_Namespaces(surface));
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
