package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Reject incompatible declarations before LDLib can bind cells/events by registration index. */
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
public final class VocationOrder implements ICustomConfigurationTask {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "vocation_order");
    private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(ID);

    @SubscribeEvent
    public static void registerPayload(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysMod.NETWORK_VERSION).configurationBidirectional(
            Order.TYPE, Order.STREAM_CODEC, VocationOrder::received);
    }

    @SubscribeEvent
    public static void registerTask(RegisterConfigurationTasksEvent event) {
        event.register(new VocationOrder());
    }

    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        sender.accept(new Order(Vocations.ids()));
    }

    @Override
    public ConfigurationTask.Type type() {
        return TASK;
    }

    static void received(Order order, IPayloadContext context) {
        List<ResourceLocation> local = Vocations.ids();
        if (!local.equals(order.ids())) {
            context.disconnect(Component.translatable("folkways.network.vocation_mismatch",
                order.ids().toString(), local.toString()));
        } else if (context.flow().isClientbound()) {
            context.reply(new Order(local));
        } else {
            context.finishCurrentTask(TASK);
        }
    }

    public record Order(List<ResourceLocation> ids) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Order> TYPE = new CustomPacketPayload.Type<>(ID);
        public static final StreamCodec<FriendlyByteBuf, Order> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), Order::ids, Order::new);

        public Order {
            ids = List.copyOf(ids);
        }

        @Override
        public CustomPacketPayload.Type<Order> type() {
            return TYPE;
        }
    }
}
