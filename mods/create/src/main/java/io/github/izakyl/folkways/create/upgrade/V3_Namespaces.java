package io.github.izakyl.folkways.create.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Generated from the v2 declarations/resources. Frozen release table: never infer an id from a prefix. */
public final class V3_Namespaces implements Step {
    private static final Map<String, String> IDS = Map.ofEntries(
        Map.entry("folkways:boarding", "folkways_create:boarding"),
        Map.entry("folkways:collecting", "folkways_create:collecting"),
        Map.entry("folkways:conducting", "folkways_create:conducting"),
        Map.entry("folkways:create_handoff", "folkways_create:create_handoff"),
        Map.entry("folkways:delegation/port", "folkways_create:delegation/port"),
        Map.entry("folkways:dispatch", "folkways_create:dispatch"),
        Map.entry("folkways:dispatching", "folkways_create:dispatching"),
        Map.entry("folkways:doing/boarding", "folkways_create:doing/boarding"),
        Map.entry("folkways:doing/collecting", "folkways_create:doing/collecting"),
        Map.entry("folkways:doing/driving", "folkways_create:doing/driving"),
        Map.entry("folkways:doing/keeping_post", "folkways_create:doing/keeping_post"),
        Map.entry("folkways:doing/packing", "folkways_create:doing/packing"),
        Map.entry("folkways:doing/taking_seat", "folkways_create:doing/taking_seat"),
        Map.entry("folkways:doing/tidying", "folkways_create:doing/tidying"),
        Map.entry("folkways:driving", "folkways_create:driving"),
        Map.entry("folkways:elevator", "folkways_create:elevator"),
        Map.entry("folkways:elevator_boarding", "folkways_create:elevator_boarding"),
        Map.entry("folkways:elevator_riding", "folkways_create:elevator_riding"),
        Map.entry("folkways:firefighting", "folkways_create:firefighting"),
        Map.entry("folkways:keeping_post", "folkways_create:keeping_post"),
        Map.entry("folkways:ledger/delegation/port", "folkways_create:ledger/delegation/port"),
        Map.entry("folkways:ledger/doing/boarding", "folkways_create:ledger/doing/boarding"),
        Map.entry("folkways:ledger/doing/collecting", "folkways_create:ledger/doing/collecting"),
        Map.entry("folkways:ledger/doing/driving", "folkways_create:ledger/doing/driving"),
        Map.entry("folkways:ledger/doing/keeping_post", "folkways_create:ledger/doing/keeping_post"),
        Map.entry("folkways:ledger/doing/packing", "folkways_create:ledger/doing/packing"),
        Map.entry("folkways:ledger/doing/taking_seat", "folkways_create:ledger/doing/taking_seat"),
        Map.entry("folkways:ledger/doing/tidying", "folkways_create:ledger/doing/tidying"),
        Map.entry("folkways:packing", "folkways_create:packing"),
        Map.entry("folkways:port", "folkways_create:port"),
        Map.entry("folkways:rail", "folkways_create:rail"),
        Map.entry("folkways:request_roster", "folkways_create:request_roster"),
        Map.entry("folkways:resident_spray", "folkways_create:resident_spray"),
        Map.entry("folkways:ride_destination", "folkways_create:ride_destination"),
        Map.entry("folkways:roster_sync", "folkways_create:roster_sync"),
        Map.entry("folkways:taking_seat", "folkways_create:taking_seat"),
        Map.entry("folkways:textures/gui/handoff_tool.png", "folkways_create:textures/gui/handoff_tool.png"),
        Map.entry("folkways:textures/gui/sprites/delegation/port.png",
            "folkways_create:textures/gui/sprites/delegation/port.png"),
        Map.entry("folkways:textures/gui/sprites/doing/boarding.png",
            "folkways_create:textures/gui/sprites/doing/boarding.png"),
        Map.entry("folkways:textures/gui/sprites/doing/collecting.png",
            "folkways_create:textures/gui/sprites/doing/collecting.png"),
        Map.entry("folkways:textures/gui/sprites/doing/driving.png",
            "folkways_create:textures/gui/sprites/doing/driving.png"),
        Map.entry("folkways:textures/gui/sprites/doing/keeping_post.png",
            "folkways_create:textures/gui/sprites/doing/keeping_post.png"),
        Map.entry("folkways:textures/gui/sprites/doing/packing.png",
            "folkways_create:textures/gui/sprites/doing/packing.png"),
        Map.entry("folkways:textures/gui/sprites/doing/taking_seat.png",
            "folkways_create:textures/gui/sprites/doing/taking_seat.png"),
        Map.entry("folkways:textures/gui/sprites/doing/tidying.png",
            "folkways_create:textures/gui/sprites/doing/tidying.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/delegation/port.png",
            "folkways_create:textures/gui/sprites/ledger/delegation/port.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/boarding.png",
            "folkways_create:textures/gui/sprites/ledger/doing/boarding.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/collecting.png",
            "folkways_create:textures/gui/sprites/ledger/doing/collecting.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/driving.png",
            "folkways_create:textures/gui/sprites/ledger/doing/driving.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/keeping_post.png",
            "folkways_create:textures/gui/sprites/ledger/doing/keeping_post.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/packing.png",
            "folkways_create:textures/gui/sprites/ledger/doing/packing.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/taking_seat.png",
            "folkways_create:textures/gui/sprites/ledger/doing/taking_seat.png"),
        Map.entry("folkways:textures/gui/sprites/ledger/doing/tidying.png",
            "folkways_create:textures/gui/sprites/ledger/doing/tidying.png"),
        Map.entry("folkways:tidying", "folkways_create:tidying"),
        Map.entry("folkways:track-layer", "folkways_create:track-layer"),
        Map.entry("folkways:train", "folkways_create:train"));
    private final Surface surface;

    private V3_Namespaces(Surface surface) {
        this.surface = surface;
    }

    public static void register() {
        NamespaceIds.register(IDS);
        for (Surface surface : Surface.values()) {
            Upgrades.part("create/namespaces", new V3_Namespaces(surface));
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
