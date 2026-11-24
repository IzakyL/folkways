package io.github.izakyl.folkways.core.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Frozen. Published extension IDs checked by tests/architecture/v5-namespaces.py.
 * Keep these release mappings explicit when later versions move the targets again.
 */
public final class V5_Namespaces implements Step {
    private static final Map<String, String> IDS = Map.ofEntries(
        // Modular Golems resident, maintenance, lesson and GUI sprites.
        Map.entry("folkways_golems:delegation/golem_body", "folkways_core:delegation/golem_body"),
        Map.entry("folkways_golems:doing/mending", "folkways_core:doing/mending"),
        Map.entry("folkways_golems:golem", "folkways_core:golem"),
        Map.entry("folkways_golems:golem_body", "folkways_core:golem_body"),
        Map.entry("folkways_golems:golems", "folkways_core:golems"),
        Map.entry("folkways_golems:ledger/delegation/golem_body", "folkways_core:ledger/delegation/golem_body"),
        Map.entry("folkways_golems:ledger/doing/mending", "folkways_core:ledger/doing/mending"),
        Map.entry("folkways_golems:mend", "folkways_core:mend"),
        Map.entry("folkways_golems:mending", "folkways_core:mending"),
        Map.entry("folkways_golems:textures/gui/sprites/delegation/golem_body.png",
            "folkways_core:textures/gui/sprites/delegation/golem_body.png"),
        Map.entry("folkways_golems:textures/gui/sprites/doing/mending.png",
            "folkways_core:textures/gui/sprites/doing/mending.png"),
        Map.entry("folkways_golems:textures/gui/sprites/ledger/delegation/golem_body.png",
            "folkways_core:textures/gui/sprites/ledger/delegation/golem_body.png"),
        Map.entry("folkways_golems:textures/gui/sprites/ledger/doing/mending.png",
            "folkways_core:textures/gui/sprites/ledger/doing/mending.png"),

        // Epic Fight adapter, loadout, labor hook and patch registration.
        Map.entry("folkways_epicfight:epicfight_labor", "folkways_vanilla:epicfight_labor"),
        Map.entry("folkways_epicfight:melee", "folkways_core:melee"),
        Map.entry("folkways_epicfight:resident", "folkways_vanilla:resident"),

        // Chisels & Bits payload keys and built-in pattern ids.
        Map.entry("folkways_carving:chiselsandbits", "folkways_core:chiselsandbits"),
        Map.entry("folkways_carving:curved_gallery", "folkways_vanilla:curved_gallery"),
        Map.entry("folkways_carving:curved_gallery.star", "folkways_vanilla:curved_gallery.star"),
        Map.entry("folkways_carving:gothic_cloister", "folkways_vanilla:gothic_cloister"),
        Map.entry("folkways_carving:gothic_cloister.star", "folkways_vanilla:gothic_cloister.star"),
        Map.entry("folkways_carving:pavilion", "folkways_vanilla:pavilion"),
        Map.entry("folkways_carving:pavilion.star", "folkways_vanilla:pavilion.star"),
        Map.entry("folkways_carving:rampart", "folkways_vanilla:rampart"),
        Map.entry("folkways_carving:rampart.star", "folkways_vanilla:rampart.star"),
        Map.entry("folkways_carving:resolution", "folkways_core:resolution"),
        Map.entry("folkways_carving:rotunda", "folkways_vanilla:rotunda"),
        Map.entry("folkways_carving:rotunda.star", "folkways_vanilla:rotunda.star"),
        Map.entry("folkways_carving:schematic", "folkways_core:schematic"),
        Map.entry("folkways_carving:stone_arch_bridge", "folkways_vanilla:stone_arch_bridge"),
        Map.entry("folkways_carving:stone_arch_bridge.star", "folkways_vanilla:stone_arch_bridge.star"),
        Map.entry("folkways_carving:torii", "folkways_vanilla:torii"),
        Map.entry("folkways_carving:torii.star", "folkways_vanilla:torii.star"),

        // Kaleidoscope Cookery and Tavern.
        Map.entry("folkways_cookery:block/climbable", "folkways_core:block/climbable"),
        Map.entry("folkways_cookery:chopping_food", "folkways_vanilla:chopping_food"),
        Map.entry("folkways_cookery:cookery", "folkways_vanilla:cookery"),
        Map.entry("folkways_cookery:cookery/procedural/dark_cuisine",
            "folkways_vanilla:cookery/procedural/dark_cuisine"),
        Map.entry("folkways_cookery:cookery/procedural/mystery_tea",
            "folkways_vanilla:cookery/procedural/mystery_tea"),
        Map.entry("folkways_cookery:cookery/procedural/suspicious_stir_fry",
            "folkways_vanilla:cookery/procedural/suspicious_stir_fry"),
        Map.entry("folkways_cookery:cookery_chain", "folkways_vanilla:cookery_chain"),
        Map.entry("folkways_cookery:cookery_clothing", "folkways_vanilla:cookery_clothing"),
        Map.entry("folkways_cookery:cookery_clothing_items", "folkways_vanilla:cookery_clothing_items"),
        Map.entry("folkways_cookery:cookery_dining", "folkways_vanilla:cookery_dining"),
        Map.entry("folkways_cookery:cookery_equipment", "folkways_vanilla:cookery_equipment"),
        Map.entry("folkways_cookery:cookery_heat", "folkways_vanilla:cookery_heat"),
        Map.entry("folkways_cookery:cookery_millstone", "folkways_vanilla:cookery_millstone"),
        Map.entry("folkways_cookery:cookery_orders", "folkways_vanilla:cookery_orders"),
        Map.entry("folkways_cookery:cookery_pots", "folkways_vanilla:cookery_pots"),
        Map.entry("folkways_cookery:cookery_rice", "folkways_vanilla:cookery_rice"),
        Map.entry("folkways_cookery:cookery_seeds", "folkways_vanilla:cookery_seeds"),
        Map.entry("folkways_cookery:cookery_sickles", "folkways_vanilla:cookery_sickles"),
        Map.entry("folkways_cookery:cookery_spit", "folkways_vanilla:cookery_spit"),
        Map.entry("folkways_cookery:cookery_steamer", "folkways_vanilla:cookery_steamer"),
        Map.entry("folkways_cookery:cookery_teapot", "folkways_vanilla:cookery_teapot"),
        Map.entry("folkways_cookery:cookery_tools", "folkways_vanilla:cookery_tools"),
        Map.entry("folkways_cookery:cookery_tray", "folkways_vanilla:cookery_tray"),
        Map.entry("folkways_cookery:cooking_food", "folkways_vanilla:cooking_food"),
        Map.entry("folkways_cookery:gathering_seeds", "folkways_vanilla:gathering_seeds"),
        Map.entry("folkways_cookery:item/cookery_sickles", "folkways_vanilla:item/cookery_sickles"),
        Map.entry("folkways_cookery:keeping_seeds", "folkways_vanilla:keeping_seeds"),
        Map.entry("folkways_cookery:kitchen", "folkways_vanilla:kitchen"),
        Map.entry("folkways_cookery:leaving_millstone", "folkways_vanilla:leaving_millstone"),
        Map.entry("folkways_cookery:preparing_food", "folkways_vanilla:preparing_food"),
        Map.entry("folkways_cookery:restoring_grass", "folkways_vanilla:restoring_grass"),
        Map.entry("folkways_cookery:seed_grass", "folkways_vanilla:seed_grass"),
        Map.entry("folkways_cookery:serving_food", "folkways_vanilla:serving_food"),
        Map.entry("folkways_cookery:sickle_ready_at", "folkways_vanilla:sickle_ready_at"),
        Map.entry("folkways_cookery:stove", "folkways_vanilla:stove"),
        Map.entry("folkways_cookery:tavern", "folkways_vanilla:tavern"),
        Map.entry("folkways_cookery:tavern_counter", "folkways_vanilla:tavern_counter"),

        // AstikorCarts Redux.
        Map.entry("folkways_carts:cart_cells", "folkways_vanilla:cart_cells"),
        Map.entry("folkways_carts:cart_line", "folkways_vanilla:cart_line"),
        Map.entry("folkways_carts:carting", "folkways_vanilla:carting"),
        Map.entry("folkways_carts:carts", "folkways_vanilla:carts"),
        Map.entry("folkways_carts:line_closed", "folkways_vanilla:line_closed"),
        Map.entry("folkways_carts:line_open", "folkways_vanilla:line_open"),

        // Firearms
        Map.entry("folkways_firearms:controller", "folkways_core:controller"),
        Map.entry("folkways_firearms:item/warfare/ak_47", "folkways_core:item/warfare/ak_47"),
        Map.entry("folkways_firearms:item/warfare/firearms", "folkways_core:item/warfare/firearms"),
        Map.entry("folkways_firearms:item/warfare/glock_17", "folkways_core:item/warfare/glock_17"),
        Map.entry("folkways_firearms:item/warfare/m_4", "folkways_core:item/warfare/m_4"),
        Map.entry("folkways_firearms:item/warfare/m_870", "folkways_core:item/warfare/m_870"),
        Map.entry("folkways_firearms:item/warfare/mp_5", "folkways_core:item/warfare/mp_5"),
        Map.entry("folkways_firearms:item/warfare/svd", "folkways_core:item/warfare/svd"),
        Map.entry("folkways_firearms:resident_shot", "folkways_core:resident_shot"),
        Map.entry("folkways_firearms:tacz", "folkways_core:tacz"),
        Map.entry("folkways_firearms:vehicle_control", "folkways_vanilla:vehicle_control"),
        Map.entry("folkways_firearms:vehicle_crew", "folkways_vanilla:vehicle_crew"),
        Map.entry("folkways_firearms:vehicle_seat", "folkways_core:vehicle_seat"),
        Map.entry("folkways_firearms:warfare/ak_47", "folkways_core:warfare/ak_47"),
        Map.entry("folkways_firearms:warfare/firearm", "folkways_core:warfare/firearm"),
        Map.entry("folkways_firearms:warfare/firearms", "folkways_core:warfare/firearms"),
        Map.entry("folkways_firearms:warfare/glock_17", "folkways_core:warfare/glock_17"),
        Map.entry("folkways_firearms:warfare/m_4", "folkways_core:warfare/m_4"),
        Map.entry("folkways_firearms:warfare/m_870", "folkways_core:warfare/m_870"),
        Map.entry("folkways_firearms:warfare/mp_5", "folkways_core:warfare/mp_5"),
        Map.entry("folkways_firearms:warfare/stations", "folkways_vanilla:warfare/stations"),
        Map.entry("folkways_firearms:warfare/svd", "folkways_core:warfare/svd"),
        Map.entry("folkways_firearms:warfare_mortar", "folkways_vanilla:warfare_mortar"),
        Map.entry("folkways_firearms:warfare_pending", "folkways_vanilla:warfare_pending"),
        Map.entry("folkways_firearms:warfare_range", "folkways_vanilla:warfare_range"),

        // Create rail, elevator, package networks, building and firefighting.
        Map.entry("folkways_create:boarding", "folkways_vanilla:boarding"),
        Map.entry("folkways_create:collecting", "folkways_vanilla:collecting"),
        Map.entry("folkways_create:conducting", "folkways_vanilla:conducting"),
        Map.entry("folkways_create:create_handoff", "folkways_vanilla:create_handoff"),
        Map.entry("folkways_create:dark/vocation/conducting", "folkways_vanilla:dark/vocation/conducting"),
        Map.entry("folkways_create:dark/vocation/dispatching", "folkways_vanilla:dark/vocation/dispatching"),
        Map.entry("folkways_create:delegation/port", "folkways_vanilla:delegation/port"),
        Map.entry("folkways_create:dispatch", "folkways_vanilla:dispatch"),
        Map.entry("folkways_create:dispatching", "folkways_vanilla:dispatching"),
        Map.entry("folkways_create:doing/boarding", "folkways_core:doing/boarding"),
        Map.entry("folkways_create:doing/collecting", "folkways_vanilla:doing/collecting"),
        Map.entry("folkways_create:doing/driving", "folkways_vanilla:doing/driving"),
        Map.entry("folkways_create:doing/keeping_post", "folkways_vanilla:doing/keeping_post"),
        Map.entry("folkways_create:doing/packing", "folkways_vanilla:doing/packing"),
        Map.entry("folkways_create:doing/riding", "folkways_core:doing/riding"),
        Map.entry("folkways_create:doing/taking_seat", "folkways_vanilla:doing/taking_seat"),
        Map.entry("folkways_create:doing/tidying", "folkways_vanilla:doing/tidying"),
        Map.entry("folkways_create:driving", "folkways_vanilla:driving"),
        Map.entry("folkways_create:elevator", "folkways_vanilla:elevator"),
        Map.entry("folkways_create:elevator_boarding", "folkways_vanilla:elevator_boarding"),
        Map.entry("folkways_create:elevator_riding", "folkways_vanilla:elevator_riding"),
        Map.entry("folkways_create:firefighting", "folkways_vanilla:firefighting"),
        Map.entry("folkways_create:keeping_post", "folkways_vanilla:keeping_post"),
        Map.entry("folkways_create:ledger/delegation/port", "folkways_vanilla:ledger/delegation/port"),
        Map.entry("folkways_create:ledger/doing/boarding", "folkways_core:ledger/doing/boarding"),
        Map.entry("folkways_create:ledger/doing/collecting", "folkways_vanilla:ledger/doing/collecting"),
        Map.entry("folkways_create:ledger/doing/driving", "folkways_vanilla:ledger/doing/driving"),
        Map.entry("folkways_create:ledger/doing/keeping_post", "folkways_vanilla:ledger/doing/keeping_post"),
        Map.entry("folkways_create:ledger/doing/packing", "folkways_vanilla:ledger/doing/packing"),
        Map.entry("folkways_create:ledger/doing/riding", "folkways_core:ledger/doing/riding"),
        Map.entry("folkways_create:ledger/doing/taking_seat", "folkways_vanilla:ledger/doing/taking_seat"),
        Map.entry("folkways_create:ledger/doing/tidying", "folkways_vanilla:ledger/doing/tidying"),
        Map.entry("folkways_create:packing", "folkways_vanilla:packing"),
        Map.entry("folkways_create:port", "folkways_vanilla:port"),
        Map.entry("folkways_create:rail", "folkways_vanilla:rail"),
        Map.entry("folkways_create:request_roster", "folkways_vanilla:request_roster"),
        Map.entry("folkways_create:resident_spray", "folkways_core:resident_spray"),
        Map.entry("folkways_create:ride_destination", "folkways_core:ride_destination"),
        Map.entry("folkways_create:riding", "folkways_vanilla:riding"),
        Map.entry("folkways_create:roster_sync", "folkways_vanilla:roster_sync"),
        Map.entry("folkways_create:taking_seat", "folkways_vanilla:taking_seat"),
        Map.entry("folkways_create:textures/gui/handoff_tool.png", "folkways_core:textures/gui/handoff_tool.png"),
        Map.entry("folkways_create:textures/gui/sprites/dark/vocation/conducting.png",
            "folkways_vanilla:textures/gui/sprites/dark/vocation/conducting.png"),
        Map.entry("folkways_create:textures/gui/sprites/dark/vocation/dispatching.png",
            "folkways_vanilla:textures/gui/sprites/dark/vocation/dispatching.png"),
        Map.entry("folkways_create:textures/gui/sprites/delegation/port.png",
            "folkways_vanilla:textures/gui/sprites/delegation/port.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/boarding.png",
            "folkways_core:textures/gui/sprites/doing/boarding.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/collecting.png",
            "folkways_vanilla:textures/gui/sprites/doing/collecting.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/driving.png",
            "folkways_vanilla:textures/gui/sprites/doing/driving.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/keeping_post.png",
            "folkways_vanilla:textures/gui/sprites/doing/keeping_post.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/packing.png",
            "folkways_vanilla:textures/gui/sprites/doing/packing.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/riding.png",
            "folkways_core:textures/gui/sprites/doing/riding.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/taking_seat.png",
            "folkways_vanilla:textures/gui/sprites/doing/taking_seat.png"),
        Map.entry("folkways_create:textures/gui/sprites/doing/tidying.png",
            "folkways_vanilla:textures/gui/sprites/doing/tidying.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/delegation/port.png",
            "folkways_vanilla:textures/gui/sprites/ledger/delegation/port.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/boarding.png",
            "folkways_core:textures/gui/sprites/ledger/doing/boarding.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/collecting.png",
            "folkways_vanilla:textures/gui/sprites/ledger/doing/collecting.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/driving.png",
            "folkways_vanilla:textures/gui/sprites/ledger/doing/driving.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/keeping_post.png",
            "folkways_vanilla:textures/gui/sprites/ledger/doing/keeping_post.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/packing.png",
            "folkways_vanilla:textures/gui/sprites/ledger/doing/packing.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/riding.png",
            "folkways_core:textures/gui/sprites/ledger/doing/riding.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/taking_seat.png",
            "folkways_vanilla:textures/gui/sprites/ledger/doing/taking_seat.png"),
        Map.entry("folkways_create:textures/gui/sprites/ledger/doing/tidying.png",
            "folkways_vanilla:textures/gui/sprites/ledger/doing/tidying.png"),
        Map.entry("folkways_create:textures/gui/sprites/vocation/conducting.png",
            "folkways_vanilla:textures/gui/sprites/vocation/conducting.png"),
        Map.entry("folkways_create:textures/gui/sprites/vocation/dispatching.png",
            "folkways_vanilla:textures/gui/sprites/vocation/dispatching.png"),
        Map.entry("folkways_create:tidying", "folkways_vanilla:tidying"),
        Map.entry("folkways_create:track-layer", "folkways_core:track-layer"),
        Map.entry("folkways_create:train", "folkways_core:train"),
        Map.entry("folkways_create:vocation/conducting", "folkways_vanilla:vocation/conducting"),
        Map.entry("folkways_create:vocation/dispatching", "folkways_vanilla:vocation/dispatching"));

    private final Surface surface;

    private V5_Namespaces(Surface surface) {
        this.surface = surface;
    }

    public static void register() {
        NamespaceIds.register(IDS);
        for (Surface surface : Surface.values()) {
            Upgrades.part("core/consolidated_namespaces", new V5_Namespaces(surface));
        }
    }

    @Override
    public int target() {
        return 5;
    }

    @Override
    public Surface surface() {
        return surface;
    }

    @Override
    public void apply(CompoundTag raw) {
        if (surface == Surface.ENTITY) {
            // The old id was shared by the crew vocation and the hull's seat assignments.
            CompoundTag persistent = raw.getCompound("NeoForgeData");
            String oldCrew = "folkways_firearms:vehicle_crew";
            String crew = "folkways_core:vehicle_crew";
            if (persistent.contains(oldCrew)) {
                if (!persistent.contains(crew)) {
                    persistent.put(crew, persistent.get(oldCrew));
                }
                persistent.remove(oldCrew);
            }
        }
        NamespaceIds.rewrite(raw, IDS);
        if (surface == Surface.COLONY_REGISTRY) {
            rewriteSettings(raw);
        } else if (surface == Surface.BLOCK_ENTITY) {
            ListTag whitelist = raw.getList("whitelist", Tag.TAG_STRING);
            for (int index = 0; index < whitelist.size(); index++) {
                String line = whitelist.getString(index);
                if (line.startsWith("q")) {
                    whitelist.set(index, StringTag.valueOf("q" + NamespaceIds.rewriteText(line.substring(1), IDS)));
                }
            }
        }
    }

    private static void rewriteSettings(Tag raw) {
        if (raw instanceof CompoundTag compound) {
            for (Tag value : compound.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) value;
                if (entry.get("query") instanceof StringTag query) {
                    entry.putString("query", NamespaceIds.rewriteText(query.getAsString(), IDS));
                }
            }
            for (String key : compound.getAllKeys()) {
                rewriteSettings(compound.get(key));
            }
        } else if (raw instanceof ListTag list) {
            list.forEach(V5_Namespaces::rewriteSettings);
        }
    }
}
