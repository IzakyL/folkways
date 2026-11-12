package io.github.izakyl.folkways.core.shell.toms;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.inventory.internal.ProxyStores;
import io.github.izakyl.folkways.core.shell.storage.NetworkChecks;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class TomsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TomsGameTests.class);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 250)
    public static void everyAccessPointRepresentsSameMembersOnce(GameTestHelper helper) {
        new TomsRig(helper);
        helper.startSequence().thenIdle(45).thenExecute(() -> {
            var first = WorldPos.of(helper.getLevel(),
                helper.absolutePos(TomsRig.MEMBER));
            var second = WorldPos.of(helper.getLevel(),
                helper.absolutePos(TomsRig.SECOND));
            for (BlockPos access : List.of(TomsRig.CONNECTOR, TomsRig.TERMINAL, TomsRig.CRAFTING,
                    TomsRig.CABLE, TomsRig.FRAMED)) {
                helper.assertTrue(PortChecks.at(helper, access).proxyMembers().orElseThrow()
                    .containsAll(List.of(first, second)), "native access point has physical members: " + access);
                helper.assertTrue(PortChecks.count(helper, PortChecks.at(helper, access)) == 0,
                    "proxy never advertises aggregate stock");
            }
            PortChecks.at(helper, TomsRig.MEMBER).put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 23)),
                List.of(), false, false);
            NetworkChecks.observed(helper, List.of(TomsRig.CONNECTOR, TomsRig.TERMINAL, TomsRig.CRAFTING,
                TomsRig.CABLE, TomsRig.FRAMED, TomsRig.MEMBER, TomsRig.SECOND), 2, 23);
        });
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 250)
    public static void unknownMemberFailsAtomicallyAndAllowedMemberPasses(GameTestHelper helper) {
        new TomsRig(helper);
        PortChecks.place(helper, TomsRig.SECOND, "folkways_core:toms_unknown");
        helper.startSequence().thenIdle(45).thenExecute(() -> {
            var level = helper.getLevel();
            var colony = Colonies.mint(level.getServer());
            var owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "toms_atomic");
            var access = WorldPos.of(level,
                helper.absolutePos(TomsRig.CONNECTOR));
            try {
                helper.assertTrue(PortChecks.at(helper, TomsRig.CONNECTOR).proxyMembers().orElseThrow().size() == 2,
                    "unknown native inventory is retained in the proxy declaration");
                helper.assertTrue(colony.hold(owner, new Claimed.Block(access))
                    .isEmpty() && colony.claims().isEmpty(), "unknown member prevents every claim atomically");
                helper.assertTrue(ProxyStores
                    .refusal(level.getServer(), colony, owner, access).isPresent(), "translated atomic refusal");
                helper.assertTrue(PortChecks.at(helper, TomsRig.MEMBER).usability().usable(),
                    "vanilla member still usable");
            } finally {
                colony.raze();
            }
            PortChecks.place(helper, TomsRig.SECOND, "folkways_core:toms_allowed");
        }).thenIdle(45).thenExecute(() -> {
            var level = helper.getLevel();
            var colony = Colonies.mint(level.getServer());
            try {
                var access = WorldPos.of(level,
                    helper.absolutePos(TomsRig.CONNECTOR));
                helper.assertTrue(colony.hold(ResourceLocation.parse("folkways_core:toms_atomic"),
                    new Claimed.Block(access)).isPresent()
                    && colony.claims().size() == 3, "allow-tagged modded member and vanilla member enroll atomically");
            } finally {
                colony.raze();
            }
        }).thenSucceed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void filtersAndAutomationRefuseWithReasons(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 2);
        for (String block : List.of("inventory_proxy", "level_emitter", "inventory_interface",
                "basic_inventory_hopper", "open_crate")) {
            PortChecks.place(helper, pos, "toms_storage:" + block);
            NetworkChecks.refused(helper, pos, "buffer");
        }
        new TomsRig(helper);
        com.tom.storagemod.inventory.BlockFilter.getOrCreateFilterAt(helper.getLevel(),
            helper.absolutePos(TomsRig.MEMBER)).setKeepLast(true);
        helper.startSequence().thenIdle(45).thenExecute(() -> {
            for (BlockPos access : List.of(TomsRig.CONNECTOR, TomsRig.TERMINAL, TomsRig.CRAFTING,
                    TomsRig.CABLE, TomsRig.FRAMED)) {
                NetworkChecks.refused(helper, access, "filtered");
            }
        }).thenSucceed();
    }
}
