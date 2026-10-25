package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VocationOrderGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(VocationOrderGameTests.class);
    }

    @GameTest(template = "empty")
    public static void matchingPeersAcknowledgeBeforeConfigurationCompletes(GameTestHelper helper) {
        var order = new VocationOrder.Order(Vocations.ids());
        List<String> client = new ArrayList<>();
        VocationOrder.received(order, context(PacketFlow.CLIENTBOUND, client));
        helper.assertValueEqual(client, List.of("reply"), "matching client acknowledges the ordered ids");
        List<String> server = new ArrayList<>();
        VocationOrder.received(order, context(PacketFlow.SERVERBOUND, server));
        helper.assertValueEqual(server, List.of("finishCurrentTask"), "matching reply unlocks play");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mismatchedPeersCannotEnterPlayWithMisboundColumns(GameTestHelper helper) {
        List<ResourceLocation> ids = Vocations.ids();
        List<ResourceLocation> extra = new ArrayList<>(ids);
        extra.add(ResourceLocation.parse("folkways_test:remote_only"));
        for (List<ResourceLocation> mismatch : List.of(ids.reversed(), ids.subList(1, ids.size()), extra)) {
            for (PacketFlow flow : PacketFlow.values()) {
                List<String> calls = new ArrayList<>();
                VocationOrder.received(new VocationOrder.Order(mismatch), context(flow, calls));
                helper.assertValueEqual(calls, List.of("disconnect"),
                    "reordered, missing or additional trades disconnect without acknowledging or entering play");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wireCodecPreservesExactColumnOrder(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var order = new VocationOrder.Order(Vocations.ids());
            VocationOrder.Order.STREAM_CODEC.encode(buffer, order);
            helper.assertValueEqual(VocationOrder.Order.STREAM_CODEC.decode(buffer), order,
                "the handshake retains every trade id and its position");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static IPayloadContext context(PacketFlow flow, List<String> calls) {
        return (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
            new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("flow")) {
                    return flow;
                }
                if (!List.of("reply", "finishCurrentTask", "disconnect").contains(method.getName())) {
                    throw new AssertionError("Unexpected network operation: " + method.getName());
                }
                calls.add(method.getName());
                return null;
            });
    }
}
