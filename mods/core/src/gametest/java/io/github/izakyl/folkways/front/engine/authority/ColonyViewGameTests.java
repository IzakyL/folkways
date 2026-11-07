package io.github.izakyl.folkways.front.engine.authority;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ColonyViewGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ColonyViewGameTests.class); }

    // A packet naming the colony the player acts for is answered whatever is in hand; any other colony, or one the
    // player no longer keeps, is not.
    @GameTest(template = "empty")
    public static void theColonyActedForIsAnsweredWithoutTheBook(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var colony = Colonies.mint(server);
        var other = Colonies.mint(server);
        try {
            var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "overlay_viewer"));
            Charters.keep(colony, Charter.founded("Viewed", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            helper.assertValueEqual(ColonyAuthority.forColony(player, colony.id()).map(found -> found.colony().id()),
                Optional.of(colony.id()), "the colony acted for is answered without the book");
            helper.assertTrue(ColonyAuthority.forColony(player, other.id()).isEmpty(),
                "a colony other than the one acted for is not answered");
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
            helper.assertTrue(ColonyAuthority.forColony(player, colony.id()).isPresent(),
                "with the book in hand the same colony is answered, as before");
            Charters.keep(colony, Charter.founded("Revoked", UUID.randomUUID()));
            helper.assertTrue(ColonyAuthority.forColony(player, colony.id()).isEmpty(),
                "a colony no longer kept is not answered");
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        } finally {
            colony.raze();
            other.raze();
        }
        helper.succeed();
    }
}
