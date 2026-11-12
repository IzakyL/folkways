package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
public final class TerminalPreferences {

    public interface Menu {
        void folkways$refresh();
    }

    private static final String KEY = "folkways_network_recipes";
    private static volatile boolean visible = true;

    private TerminalPreferences() {
    }

    public static boolean visible(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound("PlayerPersisted");
        return !data.contains(KEY) || data.getBoolean(KEY);
    }

    public static void set(ServerPlayer player, boolean show) {
        CompoundTag data = player.getPersistentData().getCompound("PlayerPersisted");
        data.putBoolean(KEY, show);
        player.getPersistentData().put("PlayerPersisted", data);
        if (player.containerMenu instanceof Menu menu) {
            menu.folkways$refresh();
        }
    }

    public static boolean visible() {
        return visible;
    }

    public static void request() {
        PacketDistributor.sendToServer(new Setting(true, true));
    }

    public static void toggle() {
        PacketDistributor.sendToServer(new Setting(!visible, false));
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysCoreMod.NETWORK_VERSION).playBidirectional(Setting.TYPE, Setting.CODEC,
            (setting, context) -> {
                if (context.player() instanceof ServerPlayer player) {
                    if (!setting.query()) {
                        set(player, setting.visible());
                    }
                    context.reply(new Setting(visible(player), false));
                } else {
                    visible = setting.visible();
                }
            });
    }

    public record Setting(boolean visible, boolean query) implements CustomPacketPayload {
        public static final Type<Setting> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("folkways_core", "network_recipes"));
        public static final StreamCodec<ByteBuf, Setting> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, Setting::visible, ByteBufCodecs.BOOL, Setting::query, Setting::new);

        @Override
        public Type<Setting> type() {
            return TYPE;
        }
    }
}
