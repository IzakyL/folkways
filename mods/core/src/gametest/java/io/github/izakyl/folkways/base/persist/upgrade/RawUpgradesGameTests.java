package io.github.izakyl.folkways.base.persist.upgrade;

import io.github.izakyl.folkways.core.shell.neoforge.FolkwaysAttachments;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.supply.SupplyBox;
import io.github.izakyl.folkways.plugins.supply.SupplyContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_persistence")
@PrefixGameTestTemplate(false)
public final class RawUpgradesGameTests {
    private RawUpgradesGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RawUpgradesGameTests.class);
    }

    private static void stamped(GameTestHelper helper, CompoundTag raw) {
        helper.assertTrue(raw.getCompound("NeoForgeData").getInt(RawUpgrades.VERSION) == DataVersion.CURRENT,
            "Folkways payload is stamped");
    }

    @GameTest(template = "empty")
    public static void entitySaveAndLoadCoverOwnedTypesMarkersAndAttachments(GameTestHelper helper) {
        var level = helper.getLevel();
        var vanilla = EntityType.COW.create(level);
        helper.assertFalse(vanilla.saveWithoutId(new CompoundTag()).getCompound("NeoForgeData")
            .contains(RawUpgrades.VERSION), "untouched vanilla entities are not stamped");
        for (String key : List.of("folkways:test", "folkways.test", "folkways_test")) {
            vanilla.getPersistentData().putBoolean(key, true);
            CompoundTag raw = vanilla.saveWithoutId(new CompoundTag());
            stamped(helper, raw);
            raw.getCompound("NeoForgeData").remove(RawUpgrades.VERSION);
            CompoundTag before = raw.copy();
            var restored = EntityType.COW.create(level);
            restored.load(raw);
            helper.assertValueEqual(raw, before, "unstamped entity input is not rewritten");
            helper.assertFalse(restored.getPersistentData().contains(RawUpgrades.VERSION),
                "unstamped owned entity data stays unstamped until save");
            stamped(helper, restored.saveWithoutId(new CompoundTag()));
            vanilla.getPersistentData().remove(key);
        }
        UUID colony = UUID.randomUUID();
        vanilla.setData(FolkwaysAttachments.COLONY_MEMBER, Optional.of(colony));
        CompoundTag raw = vanilla.saveWithoutId(new CompoundTag());
        stamped(helper, raw);
        var restored = EntityType.COW.create(level);
        restored.load(raw);
        helper.assertValueEqual(restored.getData(FolkwaysAttachments.COLONY_MEMBER), Optional.of(colony),
            "codec attachment round trips");
        var resident = PersonBody.RESIDENT.get().create(level);
        resident.setNames("Save", "Keeper");
        stamped(helper, resident.saveWithoutId(new CompoundTag()));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blockEntitySaveLoadAndPartialPayloadsStayDistinct(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var state = SupplyContent.BLOCK.get().defaultBlockState();
        SupplyBox box = new SupplyBox(BlockPos.ZERO, state);
        box.configure(false, List.of());
        CompoundTag saved = box.saveWithFullMetadata(registries);
        stamped(helper, saved);
        SupplyBox restored = (SupplyBox) BlockEntity.loadStatic(BlockPos.ZERO, state, saved, registries);
        helper.assertTrue(restored != null && !restored.allItems() && restored.revision() == 1,
            "stamped full disk data round trips");
        for (boolean customOnly : List.of(false, true)) {
            CompoundTag partial = new CompoundTag();
            partial.putBoolean("all", false);
            CompoundTag before = partial.copy();
            if (customOnly) {
                box.loadCustomOnly(partial, registries);
            } else {
                box.loadWithComponents(partial, registries);
            }
            helper.assertValueEqual(partial, before, "unstamped command/sync data is not rewritten");
            helper.assertTrue(!box.allItems() && box.revision() == 0 && box.generated().isEmpty(),
                "partial SupplyBox fields retain their business defaults");
        }
        CompoundTag unstamped = saved.copy();
        unstamped.getCompound("NeoForgeData").remove(RawUpgrades.VERSION);
        CompoundTag before = unstamped.copy();
        restored.loadWithComponents(unstamped, registries);
        restored.loadCustomOnly(unstamped, registries);
        helper.assertValueEqual(unstamped, before, "owned full block-entity input without a stamp stays unchanged");
        stamped(helper, restored.saveWithFullMetadata(registries));
        FurnaceBlockEntity furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        stamped(helper, furnace.saveWithoutMetadata(registries));
        stamped(helper, furnace.saveCustomOnly(registries));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyStampedRawInputsMigrateWhenCurrentAdvances(GameTestHelper helper) {
        int current = DataVersion.BASELINE + 2;
        Upgrades upgrades = new Upgrades(DataVersion.BASELINE, current);
        for (Surface surface : List.of(Surface.ENTITY, Surface.BLOCK_ENTITY)) {
            upgrades.addPart("test/first", surface, current - 1, raw -> {
                raw.putString("folkways:current", raw.getString("folkways:legacy"));
                raw.remove("folkways:legacy");
            });
            upgrades.addPart("test/second", surface, current,
                raw -> raw.putInt("folkways:runs", raw.getInt("folkways:runs") + 1));
        }
        upgrades.seal();
        for (Surface surface : List.of(Surface.ENTITY, Surface.BLOCK_ENTITY)) {
            for (String marker : List.of("type", "persistent", "attachment", "root", "empty")) {
                CompoundTag raw = new CompoundTag();
                switch (marker) {
                    case "type" -> raw.putString("id", "folkways:test");
                    case "persistent" -> {
                        CompoundTag data = new CompoundTag();
                        data.putBoolean("folkways:test", true);
                        raw.put("NeoForgeData", data);
                    }
                    case "attachment" -> {
                        CompoundTag attachments = new CompoundTag();
                        attachments.put("folkways:test", new CompoundTag());
                        raw.put("neoforge:attachments", attachments);
                    }
                    case "root" -> raw.putString("folkways:current", "command");
                    default -> { }
                }
                CompoundTag before = raw.copy();
                RawUpgrades.load(surface, raw, upgrades);
                helper.assertValueEqual(raw, before, "unstamped " + surface + " " + marker + " is current input");
            }
            CompoundTag raw = new CompoundTag();
            CompoundTag data = new CompoundTag();
            data.putInt(RawUpgrades.VERSION, DataVersion.BASELINE);
            raw.put("NeoForgeData", data);
            raw.putString("folkways:legacy", "saved");
            RawUpgrades.load(surface, raw, upgrades);
            helper.assertTrue(raw.getString("folkways:current").equals("saved")
                && !raw.contains("folkways:legacy") && raw.getInt("folkways:runs") == 1,
                "stamped old " + surface + " runs the complete upgrade chain");
            helper.assertTrue(data.getInt(RawUpgrades.VERSION) == current, "successful upgrade advances the stamp");
            CompoundTag upgraded = raw.copy();
            RawUpgrades.load(surface, raw, upgrades);
            helper.assertValueEqual(raw, upgraded, "stamped current input is not upgraded again");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unsupportedLazyDataIsNeverRelabelledAsCurrent(GameTestHelper helper) {
        List<LogEvent> events = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("folkways-raw-upgrade-test", null, null,
                false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        Logger logger = (Logger) LogManager.getLogger("folkways-persist");
        appender.start();
        logger.addAppender(appender);
        try {
            for (Surface surface : List.of(Surface.ENTITY, Surface.BLOCK_ENTITY)) {
                for (var version : List.of(net.minecraft.nbt.IntTag.valueOf(DataVersion.BASELINE - 1),
                        net.minecraft.nbt.IntTag.valueOf(DataVersion.CURRENT + 1),
                        net.minecraft.nbt.StringTag.valueOf("invalid"))) {
                    CompoundTag raw = new CompoundTag();
                    CompoundTag data = new CompoundTag();
                    data.put(RawUpgrades.VERSION, version);
                    raw.put("NeoForgeData", data);
                    raw.putString("folkways:unknown", "retain");
                    CompoundTag original = raw.copy();
                    int before = events.size();
                    RawUpgrades.load(surface, raw);
                    RawUpgrades.stamp(raw, false);
                    helper.assertValueEqual(raw, original, "unsupported lazy input and its version are retained");
                    helper.assertTrue(events.subList(before, events.size()).stream().anyMatch(event ->
                        event.getLevel() == Level.ERROR && event.getMessage().getFormattedMessage()
                            .contains("Unsupported Folkways " + surface + " data version " + version)),
                        "unsupported version logs its surface and original stamp");
                }
            }
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        helper.succeed();
    }
}
