package io.github.izakyl.folkways.front.ui.screen;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.resident.body.WorkPriorities;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.engine.colony.WorkDefaults;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkDefaultsAuthorityGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WorkDefaultsAuthorityGameTests.class);
    }

    @GameTest(template = "empty")
    public static void emptyColonyCanBeEditedOnlyByItsKeepersWithoutOverwritingOtherEntries(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var keeper = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "keeper"),
            ClientInformation.createDefault());
        var stranger = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "stranger"),
            ClientInformation.createDefault());
        Charters.keep(colony, Charter.founded("defaults", keeper.getUUID()));
        keeper.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        stranger.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var hauling = Vocations.HAULING;
        var building = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "building");
        try {
            ResidentsPage.editDefault(stranger, PersonBody.ID, hauling, true, 0);
            helper.assertValueEqual(WorkDefaults.of(colony, PersonBody.ID).of(hauling), WorkPriorities.DEFAULT,
                "naming someone else's colony grants no authority");
            ResidentsPage.editDefault(keeper, PersonBody.ID, hauling, true, 0);
            ResidentsPage.editDefault(keeper, PersonBody.ID, building, false, -1);
            var priorities = WorkDefaults.of(colony, PersonBody.ID);
            helper.assertFalse(priorities.of(hauling).allowed(), "a keeper can toggle work before anyone joins");
            helper.assertTrue(priorities.of(building).priority() == Keenness.SECOND, "ranking another entry keeps the first edit");
            ResidentsPage.resetDefaults(stranger);
            helper.assertValueEqual(WorkDefaults.of(colony, PersonBody.ID), priorities, "a stranger cannot reset the template");
            ResidentsPage.resetDefaults(keeper);
            helper.assertValueEqual(WorkDefaults.of(colony, PersonBody.ID).of(hauling), WorkPriorities.DEFAULT,
                "a keeper can reset it");
            helper.assertTrue(colony.residents().isEmpty(), "defaults do not create roster entries");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
