package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeviceSettingsGameTests {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "ae2_network_devices");
    private static final WorldPos TERMINAL = WorldPos.of(Level.OVERWORLD, new BlockPos(3, 64, 3));
    private static final WorldPos GRID = WorldPos.of(Level.OVERWORLD, new BlockPos(9, 64, 3));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DeviceSettingsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 20)
    public static void deviceSettingsSurviveSaveAndLoad(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Cleanup.on(helper, colony::raze);
        DeviceSettings settings = new DeviceSettings(colony, OWNER);
        helper.assertTrue(settings.at(TERMINAL).equals(DeviceSettings.Device.DEFAULT)
                && settings.at(TERMINAL).rules(TERMINAL).isEmpty(),
            "an unconfigured device allows everything its tags allow and publishes no rule");
        DeviceSettings.Device changed = DeviceSettings.Device.DEFAULT.toggled("put").toggled("order")
            .toggled("filter_mode").filtering(List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("wheat"))));
        settings.set(TERMINAL, changed);
        settings.cpus(List.of(TERMINAL, GRID), Optional.of(3));

        CompoundTag saved = reread(colony.kept(OWNER));
        Colony reopened = Colonies.mint(helper.getLevel().getServer());
        Cleanup.on(helper, reopened::raze);
        reopened.keep(OWNER, saved);
        DeviceSettings loaded = new DeviceSettings(reopened, OWNER);
        helper.assertValueEqual(loaded.at(TERMINAL), changed.allowing(Optional.of(3)),
            "toggles, whitelist and CPU share load back unchanged");
        helper.assertValueEqual(loaded.at(GRID), DeviceSettings.Device.DEFAULT.allowing(Optional.of(3)),
            "the network's CPU share is remembered by every enrolled device");
        var rules = loaded.at(TERMINAL).rules(TERMINAL);
        helper.assertTrue(rules.contains(new StoreRule.Access(TERMINAL, true, false))
                && rules.stream().anyMatch(rule -> rule instanceof StoreRule.Admit admit && admit.only()
                    && admit.admits(ItemSpec.of(Items.WHEAT)) && !admit.admits(ItemSpec.of(Items.CARROT))),
            "the panel is enforced through the ordinary store rules: " + rules);

        loaded.set(TERMINAL, DeviceSettings.Device.DEFAULT);
        loaded.cpus(List.of(GRID), Optional.empty());
        helper.assertTrue(reopened.kept(OWNER).getList("devices", 10).isEmpty(),
            "a device back at its defaults leaves nothing in the save");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 20)
    public static void networkShareFollowsMergesAndSplits(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Cleanup.on(helper, colony::raze);
        DeviceSettings settings = new DeviceSettings(colony, OWNER);
        helper.assertTrue(DeviceSettings.defaultCpus(1) == 0 && DeviceSettings.defaultCpus(2) == 1
                && DeviceSettings.defaultCpus(5) == 2,
            "by default the colony takes half the CPUs, and none of a single-CPU network");
        helper.assertTrue(settings.cpus(List.of(TERMINAL, GRID), 4, 2) == 2 && settings.defaultCpus(List.of(TERMINAL)),
            "a network no device remembers uses the default");
        settings.cpus(List.of(TERMINAL), Optional.of(3));
        settings.cpus(List.of(GRID), Optional.of(1));
        helper.assertTrue(settings.cpus(List.of(TERMINAL, GRID), 4, 2) == 1,
            "merged networks take the smaller share either side allowed");
        helper.assertTrue(settings.cpus(List.of(TERMINAL), 4, 2) == 3 && settings.cpus(List.of(GRID), 4, 2) == 1,
            "split networks each keep the share their devices remember");
        helper.assertTrue(settings.cpus(List.of(TERMINAL), 2, 1) == 2,
            "a share is never more than the network's CPUs");
        helper.succeed();
    }

    private static CompoundTag reread(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                NbtAccounter.unlimitedHeap());
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
