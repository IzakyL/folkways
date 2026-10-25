package io.github.izakyl.folkways.plugins.combat.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatIntentGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CombatIntentGameTests.class); }

    @GameTest(template = "empty")
    public static void routePacketPreservesAPrefixAndItsExplicitCycleStart(GameTestHelper helper) {
        UUID colony = UUID.randomUUID();
        List<UUID> residents = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<WorldPos> points = List.of(WorldPos.of(helper.getLevel(), BlockPos.ZERO),
            WorldPos.of(helper.getLevel(), BlockPos.ZERO.east()), WorldPos.of(helper.getLevel(), BlockPos.ZERO.south()));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CombatSelectionPacket sent = new CombatSelectionPacket(colony, residents, points, 1);
            CombatSelectionPacket.STREAM_CODEC.encode(buffer, sent);
            helper.assertValueEqual(CombatSelectionPacket.STREAM_CODEC.decode(buffer), sent,
                "the loop's start index survives packet transport");
            helper.assertTrue(SelectionOrders.validCycle(3, 1), "a prefix followed by a two-point loop is accepted");
            helper.assertFalse(SelectionOrders.validCycle(3, 2), "a one-point loop is rejected");
            helper.assertFalse(SelectionOrders.validCycle(3, -1), "an unclosed multi-point route is rejected");
            helper.assertFalse(SelectionOrders.validCycle(1, -2), "unknown negative modes are rejected");
            helper.assertTrue(SelectionOrders.validCycle(1, -1), "a single move stays compatible");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void attackPacketTransportsOnlyTheColonyMembersAndTargetIdentity(GameTestHelper helper) {
        CombatAttackPacket sent = new CombatAttackPacket(UUID.randomUUID(), List.of(UUID.randomUUID()), UUID.randomUUID());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CombatAttackPacket.STREAM_CODEC.encode(buffer, sent);
            helper.assertValueEqual(CombatAttackPacket.STREAM_CODEC.decode(buffer), sent,
                "the server receives identifiers and must resolve their current entities itself");
            helper.assertValueEqual(buffer.readableBytes(), 0, "the complete bounded intent is consumed");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void routeObservationRetainsCommandGenerationCompletionAndWaitingCount(GameTestHelper helper) {
        UUID group = UUID.randomUUID(), command = UUID.randomUUID();
        var moving = new CombatOrderState(group, command, "direct", "waiting_for_member", "route_member_blocked",
            4, 0, 1, false, false, 1);
        var finished = new CombatOrderState(group, UUID.randomUUID(), "direct", "holding", "none",
            4, 0, 1, false, true, 0);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            moving.write(buffer);
            finished.write(buffer);
            helper.assertValueEqual(CombatOrderState.read(buffer), moving,
                "an active stop reports the actual waiting count and reason without ending control");
            var arrived = CombatOrderState.read(buffer);
            helper.assertTrue(arrived.routeComplete() && !arrived.commandId().equals(command),
                "a completed observation belongs to its own command, so it cannot hide an older or newer route");
            helper.assertValueEqual(buffer.readableBytes(), 0, "both bounded observations are consumed completely");
        } finally { buffer.release(); }
        helper.succeed();
    }
}
