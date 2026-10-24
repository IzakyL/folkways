package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class LoadoutRegistryGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(LoadoutRegistryGameTests.class); }

    @GameTest(template = "empty")
    public static void legacyEnumsMigrateAndAbsentExtensionsKeepTheirOptions(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            ListTag rows = new ListTag();
            for (String name : java.util.List.of("MELEE", "BOW", "CROSSBOW", "folkways:absent_gun")) {
                CompoundTag row = new CompoundTag(); row.putUUID("resident", UUID.randomUUID()); row.putString("loadout", name);
                if (name.contains(":")) { CompoundTag options = new CompoundTag(); options.putString("gun", "example:rifle"); row.put("options", options); }
                rows.add(row);
            }
            CompoundTag old = new CompoundTag(); old.put("loadouts", rows);
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath("folkways", "patrol_loadouts");
            colony.keep(key, old);
            PatrolSupplies supplies = new PatrolSupplies(colony);
            helper.assertValueEqual(supplies.preference(rows.getCompound(0).getUUID("resident")).orElseThrow(), LoadoutRegistry.MELEE, "legacy melee ID");
            helper.assertValueEqual(supplies.preference(rows.getCompound(1).getUUID("resident")).orElseThrow(), LoadoutRegistry.BOW, "legacy bow ID");
            helper.assertValueEqual(supplies.preference(rows.getCompound(2).getUUID("resident")).orElseThrow(), LoadoutRegistry.CROSSBOW, "legacy crossbow ID");
            var unknown = supplies.preference(rows.getCompound(3).getUUID("resident")).orElseThrow();
            helper.assertTrue(unknown.provider().isEmpty(), "absent integrations remain inactive");
            helper.assertValueEqual(unknown.options().getString("gun"), "example:rifle", "options survive migration");
            helper.assertValueEqual(colony.kept(key).getInt("version"), 2, "migration is written explicitly");
            PatrolSupplies restored = new PatrolSupplies(colony);
            helper.assertValueEqual(restored.preference(rows.getCompound(3).getUUID("resident")).orElseThrow(), unknown, "unknown ID survives another save/load");
            unknown.options().putString("gun", "mutated");
            helper.assertValueEqual(unknown.options().getString("gun"), "example:rifle", "caller cannot mutate persisted options");
        } finally { colony.raze(); }
        helper.succeed();
    }
}
