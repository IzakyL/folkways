package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.inventory.internal.ProxyStores;
import io.github.izakyl.folkways.core.inventory.internal.PortHooks;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import net.minecraft.network.chat.Component;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ProxyChecks {

    private ProxyChecks() {
    }

    public static void lifecycle(GameTestHelper helper, BlockPos proxy, BlockPos first, BlockPos second,
                                 BlockPos late, Consumer<Boolean> unsafe, Runnable grow) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var rival = Colonies.mint(level.getServer());
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "proxy_test");
        WorldPos access = WorldPos.of(level, helper.absolutePos(proxy));
        WorldPos one = WorldPos.of(level, helper.absolutePos(first));
        WorldPos two = WorldPos.of(level, helper.absolutePos(second));
        WorldPos addition = WorldPos.of(level, helper.absolutePos(late));
        try {
            helper.assertTrue(PortChecks.at(helper, proxy).proxyMembers().orElseThrow().containsAll(List.of(one, two)),
                "proxy enumerates physical members");
            unsafe.accept(true);
            helper.assertTrue(colony.hold(owner, new Claimed.Block(access)).isEmpty() && colony.claims().isEmpty(),
                "one refused member prevents every claim atomically");
            helper.assertTrue(ProxyStores.refusal(level.getServer(), colony, owner, access).isPresent(),
                "atomic refusal has a translated explanation");
            helper.assertTrue(PortChecks.at(helper, first).usability().usable(), "bad member does not poison sibling");
            unsafe.accept(false);
            var occupied = rival.hold(owner, new Claimed.Block(two)).orElseThrow();
            helper.assertTrue(colony.hold(owner, new Claimed.Block(access)).isEmpty() && colony.claims().isEmpty(),
                "another colony's member prevents every claim atomically");
            rival.release(occupied.id(), ClaimRelease.LET_GO);
            colony.hold(owner, new Claimed.Block(access)).orElseThrow();
            helper.assertTrue(colony.claimAt(one).isPresent() && colony.claimAt(two).isPresent(),
                "enrolling proxy enrolls every member");
            helper.assertTrue(PortChecks.at(helper, first).put(level, List.of(new ItemStack(Items.STONE, 23)),
                List.of(), false, false).done(), "seed first member");
            helper.assertTrue(PortChecks.count(helper, PortChecks.at(helper, first)) == 23
                && PortChecks.count(helper, PortChecks.at(helper, second)) == 0
                && PortChecks.count(helper, PortChecks.at(helper, proxy)) == 0,
                "member exposes only its own cells and proxy has no duplicate stock");
            grow.run();
            unsafe.accept(true);
            StorePorts.condemn(level, addition, Component.translatable("folkways.store.refused"));
            new ProxyStores(colony).refresh(level.getServer());
            helper.assertTrue(colony.claimAt(addition).isEmpty() && colony.claimAt(access).isPresent()
                && colony.claimAt(one).isPresent(), "refused new member alone stays un-enrolled");
            PortHooks.registered(colony, addition);
            new ProxyStores(colony).refresh(level.getServer());
            helper.assertTrue(colony.claimAt(addition).isPresent(), "new safe member is automatically enrolled");
            helper.assertTrue(PortChecks.at(helper, first).usability().usable(), "late refusal remains per member");
            unsafe.accept(false);
            colony.release(colony.claimAt(one).orElseThrow().id(), ClaimRelease.LET_GO);
            helper.assertTrue(colony.claimAt(one).isEmpty() && colony.claimAt(access).isEmpty()
                && colony.claimAt(two).isPresent() && colony.claimAt(addition).isPresent(),
                "removing one member un-enrolls access point and preserves other members");
            colony.hold(owner, new Claimed.Block(access)).orElseThrow();
            colony.release(colony.claimAt(access).orElseThrow().id(), ClaimRelease.LET_GO);
            helper.assertTrue(colony.claims().isEmpty(), "removing proxy removes all represented members");
        } finally {
            colony.raze();
            rival.raze();
        }
    }
}
