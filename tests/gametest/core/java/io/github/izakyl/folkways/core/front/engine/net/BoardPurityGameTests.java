package io.github.izakyl.folkways.core.front.engine.net;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.core.testing.Purity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BoardPurityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BoardPurityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void overviewMemoizationIsPureAndInvalidatesOnRefreshAndShutdown(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var player = new ServerPlayer(level.getServer(), level,
            new GameProfile(UUID.randomUUID(), "overview-test"), ClientInformation.createDefault());
        try {
            Purity.assertPure(helper, "cold overview memoization", () -> ColonyOverviews.of(player, colony));
            var notes = ColonyOverviews.of(player, colony).notes();
            helper.assertTrue(ColonyOverviews.of(player, colony).notes() == notes, "same-tick overviews reuse the snapshot");
            ColonyFront.refresh(colony);
            helper.assertTrue(ColonyOverviews.of(player, colony).notes() != notes, "front refresh invalidates the overview");
            notes = ColonyOverviews.of(player, colony).notes();
            ColonyOverviews.forgetServer();
            helper.assertTrue(ColonyOverviews.of(player, colony).notes() != notes, "shutdown discards cached overviews");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void allRegisteredFacingsAndOverviewArePureColdAndWarm(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            var data = ColonyData.find(helper.getLevel().getServer(), colony.id()).orElseThrow();
            Purity.assertPure(helper, "navigation lookup", () -> {
                var ways = ColonyWays.of(data, helper.getLevel());
                var at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
                var result = ways.reckon(new Resident(UUID.randomUUID(), PersonBody.ID), at, Set.of(at));
                helper.assertTrue(result instanceof Faring.Later, "cold route estimates read as pending");
            });
        } finally {
            colony.raze();
        }
        FacilityCardReads.assertPure(helper, List.of());
        helper.succeed();
    }
}
