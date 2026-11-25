package io.github.izakyl.folkways.core.upgrade;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.izakyl.folkways.core.base.persist.upgrade.DataVersion;
import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import io.github.izakyl.folkways.core.shell.carts.upgrade.CartsUpgrades;
import io.github.izakyl.folkways.core.shell.chiselsandbits.upgrade.CarvingUpgrades;
import io.github.izakyl.folkways.core.shell.cookery.upgrade.CookeryUpgrades;
import io.github.izakyl.folkways.core.shell.create.upgrade.CreateUpgrades;
import io.github.izakyl.folkways.core.shell.golems.upgrade.GolemsUpgrades;
import io.github.izakyl.folkways.core.shell.warfare.upgrade.FirearmsUpgrades;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.BeforeClass;
import org.junit.Test;

public final class NamespaceUpgradeTest {
    @BeforeClass
    public static void registerUpgrades() {
        CoreUpgrades.register();
        GolemsUpgrades.register();
        CarvingUpgrades.register();
        CookeryUpgrades.register();
        CartsUpgrades.register();
        FirearmsUpgrades.register();
        CreateUpgrades.register();
        Upgrades.freeze();
    }

    @Test
    public void versionFourRewritesOnlyDeclaredIdsOnEverySurface() {
        assertEquals(5, DataVersion.CURRENT);
        for (Surface surface : Surface.values()) {
            CompoundTag raw = new CompoundTag();
            raw.putInt("folkways_carving:resolution", 8);
            raw.putBoolean("folkways_carving:schematic", true);
            raw.putInt("folkways_core:resolution", 4);
            CompoundTag nested = new CompoundTag();
            nested.putString("!folkways_golems:golem_body", "folkways_epicfight:melee");
            nested.putString("model", "folkways_golems:textures/gui/sprites/ledger/delegation/golem_body.png");
            ListTag entries = new ListTag();
            entries.add(nested);
            raw.put("entries", entries);
            raw.putString("unknown", "folkways_carving:unreleased_pattern");
            raw.putString("native", "modulargolems:metal_golem");

            Upgrades.upgrade(surface, raw, 4);

            assertEquals(4, raw.getInt("folkways_core:resolution"));
            assertTrue(raw.getBoolean("folkways_core:schematic"));
            assertFalse(raw.contains("folkways_carving:resolution"));
            assertFalse(nested.contains("!folkways_golems:golem_body"));
            assertEquals("folkways_core:melee", nested.getString("!folkways_core:golem_body"));
            assertEquals("folkways_core:textures/gui/sprites/ledger/delegation/golem_body.png",
                nested.getString("model"));
            assertEquals("folkways_carving:unreleased_pattern", raw.getString("unknown"));
            assertEquals("modulargolems:metal_golem", raw.getString("native"));
            CompoundTag upgraded = raw.copy();
            Upgrades.upgrade(surface, raw, 4);
            assertEquals(upgraded, raw);
        }
    }

    @Test
    public void versionTwoRunsBothNamespaceSteps() {
        CompoundTag raw = new CompoundTag();
        raw.putString("folkways:golem_body", "folkways:golem");
        raw.putString("pattern", "folkways_chiselsandbits:rotunda.star");
        ListTag filters = new ListTag();
        filters.add(StringTag.valueOf("tfolkways:mending"));
        filters.add(StringTag.valueOf("ifolkways_carving:pavilion"));
        raw.put("filters", filters);
        CompoundTag item = new CompoundTag();
        item.putString("query", "#folkways:golem | folkways_carving:torii | folkways_carving:tor*");
        ListTag items = new ListTag();
        items.add(item);
        raw.put("items", items);

        Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, 2);

        assertFalse(raw.contains("folkways:golem_body"));
        assertFalse(raw.contains("folkways_golems:golem_body"));
        assertEquals("folkways_core:golem", raw.getString("folkways_core:golem_body"));
        assertEquals("folkways_vanilla:rotunda.star", raw.getString("pattern"));
        assertEquals("tfolkways_core:mending", raw.getList("filters", Tag.TAG_STRING).getString(0));
        assertEquals("ifolkways_vanilla:pavilion", raw.getList("filters", Tag.TAG_STRING).getString(1));
        assertEquals("#folkways_core:golem | folkways_vanilla:torii | folkways_carving:tor*", item.getString("query"));
        assertEquals("folkways_core:mend", NamespaceIds.current("folkways:mend"));
        assertEquals("folkways_vanilla:rotunda", NamespaceIds.current("folkways_chiselsandbits:rotunda"));
    }

    @Test
    public void cookeryIdsMigrateFromVersionsTwoAndFour() {
        for (int version : new int[] {2, 4}) {
            String namespace = version == 2 ? "folkways:" : "folkways_cookery:";
            CompoundTag raw = new CompoundTag();
            raw.putString(namespace + "sickle_ready_at", namespace + "tavern_counter");
            ListTag filters = new ListTag();
            filters.add(StringTag.valueOf("t" + namespace + "cookery_sickles"));
            raw.put("filters", filters);
            raw.putString("tag", "#" + namespace + "block/climbable");

            Upgrades.upgrade(Surface.BLOCK_ENTITY, raw, version);

            assertFalse(raw.contains(namespace + "sickle_ready_at"));
            assertEquals("folkways_vanilla:tavern_counter", raw.getString("folkways_vanilla:sickle_ready_at"));
            assertEquals("tfolkways_vanilla:cookery_sickles", filters.getString(0));
            assertEquals("#folkways_core:block/climbable", raw.getString("tag"));
        }
        assertEquals("folkways_vanilla:tavern_counter", NamespaceIds.current("folkways:tavern_counter"));
    }

    @Test
    public void cartIdsMigrateFromVersionsTwoAndFour() {
        for (int version : new int[] {2, 4}) {
            String namespace = version == 2 ? "folkways:" : "folkways_carts:";
            CompoundTag raw = new CompoundTag();
            raw.putString(namespace + "cart_line", namespace + "line_closed");
            raw.putString("mode", namespace + "carting");

            Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, version);

            assertFalse(raw.contains(namespace + "cart_line"));
            assertEquals("folkways_vanilla:line_closed", raw.getString("folkways_vanilla:cart_line"));
            assertEquals("folkways_vanilla:carting", raw.getString("mode"));
        }
        assertEquals("folkways_vanilla:cart_cells", NamespaceIds.current("folkways:cart_cells"));
    }

    @Test
    public void firearmsIdsMigrateFromVersionsTwoAndFour() {
        for (int version : new int[] {2, 4}) {
            String namespace = version == 2 ? "folkways:" : "folkways_firearms:";
            CompoundTag raw = new CompoundTag();
            raw.putString("loadout", namespace + "tacz");
            raw.putString("station", namespace + "warfare/stations");
            ListTag items = new ListTag();
            items.add(StringTag.valueOf("t" + namespace + "warfare/firearms"));
            raw.put("filters", items);

            Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, version);

            assertEquals("folkways_core:tacz", raw.getString("loadout"));
            assertEquals("folkways_vanilla:warfare/stations", raw.getString("station"));
            assertEquals("tfolkways_core:warfare/firearms", items.getString(0));
        }
        assertEquals("folkways_vanilla:vehicle_crew", NamespaceIds.current("folkways_firearms:vehicle_crew"));
        assertEquals("#folkways_core:warfare/svd | folkways_core:tacz",
            NamespaceIds.rewriteText("#folkways_firearms:warfare/svd | folkways_firearms:tacz"));
    }

    @Test
    public void firearmsEntityDataRetainsCrewAssignmentsAndPendingShots() {
        CompoundTag raw = new CompoundTag();
        CompoundTag persistent = new CompoundTag();
        raw.put("NeoForgeData", persistent);
        for (String key : new String[] {"controller", "vehicle_crew", "vehicle_seat", "resident_shot",
            "warfare_pending"}) {
            persistent.putString("folkways_firearms:" + key, key);
        }
        CompoundTag loan = new CompoundTag();
        UUID lease = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        loan.putUUID("lease", lease);
        loan.putUUID("vehicle", vehicle);
        persistent.put("folkways_vanilla:station_seat", loan);
        persistent.putString("folkways_tacz_colony", "colony");

        Upgrades.upgrade(Surface.ENTITY, raw, 4);

        for (String key : new String[] {"controller", "vehicle_crew", "vehicle_seat", "resident_shot"}) {
            assertEquals(key, persistent.getString("folkways_core:" + key));
            assertFalse(persistent.contains("folkways_firearms:" + key));
        }
        assertEquals("warfare_pending", persistent.getString("folkways_vanilla:warfare_pending"));
        assertFalse(persistent.contains("folkways_firearms:warfare_pending"));
        assertEquals(lease, persistent.getCompound("folkways_core:seat_stance").getUUID("token"));
        assertEquals(vehicle, persistent.getCompound("folkways_core:seat_stance").getUUID("entity"));
        assertFalse(persistent.contains("folkways_vanilla:station_seat"));
        assertEquals("colony", persistent.getString("folkways_tacz_colony"));
    }

    @Test
    public void versionFourRewritesScopedTextAndLeavesOtherTextAlone() {
        CompoundTag raw = new CompoundTag();
        String query = "folkways_golems:golem | #folkways_epicfight:melee";
        raw.putString("label", query);
        ListTag whitelist = new ListTag();
        whitelist.add(StringTag.valueOf("q" + query));
        raw.put("whitelist", whitelist);

        Upgrades.upgrade(Surface.BLOCK_ENTITY, raw, 4);

        assertEquals(query, raw.getString("label"));
        assertEquals("qfolkways_core:golem | #folkways_core:melee", whitelist.getString(0));
    }
    @Test
    public void createIdsMigrateFromVersionsTwoAndFour() {
        for (int version : new int[] {2, 4}) {
            String old = version == 2 ? "folkways:" : "folkways_create:";
            CompoundTag colony = new CompoundTag();
            CompoundTag dispatch = new CompoundTag();
            dispatch.putInt("orders", 7);
            colony.put(old + "dispatch", dispatch);
            colony.putString("vocation", old + "conducting");
            colony.putString("passage", old + "train");
            Upgrades.upgrade(Surface.COLONY_REGISTRY, colony, version);

            assertFalse(colony.contains(old + "dispatch"));
            assertEquals(dispatch, colony.getCompound("folkways_vanilla:dispatch"));
            assertEquals("folkways_vanilla:conducting", colony.getString("vocation"));
            assertEquals("folkways_core:train", colony.getString("passage"));

            CompoundTag entity = new CompoundTag();
            CompoundTag persistent = new CompoundTag();
            entity.put("NeoForgeData", persistent);
            persistent.putString(old + "ride_destination", "North Station");
            Upgrades.upgrade(Surface.ENTITY, entity, version);

            assertFalse(persistent.contains(old + "ride_destination"));
            assertEquals("North Station", persistent.getString("folkways_core:ride_destination"));
        }
        assertEquals("folkways_core:textures/gui/handoff_tool.png",
            NamespaceIds.current("folkways_create:textures/gui/handoff_tool.png"));
    }

}
