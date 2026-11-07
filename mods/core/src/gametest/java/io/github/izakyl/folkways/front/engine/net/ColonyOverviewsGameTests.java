package io.github.izakyl.folkways.front.engine.net;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
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
public final class ColonyOverviewsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ColonyOverviewsGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void viewersShareSnapshotsButEditsAndExpiryRefreshThem(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var first = new ServerPlayer(level.getServer(), level,
            new GameProfile(UUID.randomUUID(), "first"), ClientInformation.createDefault());
        var second = new ServerPlayer(level.getServer(), level,
            new GameProfile(UUID.randomUUID(), "second"), ClientInformation.createDefault());
        try {
            var before = ColonyOverviews.of(first, colony);
            var shared = ColonyOverviews.of(second, colony);
            helper.assertTrue(before.notes() == shared.notes(), "viewers share the colony snapshot");
            var edited = ColonyOverviews.fresh(first, colony);
            helper.assertTrue(before.notes() != edited.notes(), "explicit edits bypass the cached snapshot");
            helper.assertTrue(edited.notes() == ColonyOverviews.of(second, colony).notes(),
                "the refreshed snapshot is shared with other viewers");
            helper.runAfterDelay(21, () -> {
                try {
                    helper.assertTrue(edited.notes() != ColonyOverviews.of(second, colony).notes(),
                        "time expiry rebuilds dynamic notices");
                } finally {
                    colony.raze();
                    ColonyOverviews.forgetServer();
                }
                helper.succeed();
            });
        } catch (RuntimeException | Error broken) {
            colony.raze();
            ColonyOverviews.forgetServer();
            throw broken;
        }
    }
}
