package io.github.izakyl.folkways.plugins.supply;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

final class SupplyNetwork {
    private SupplyNetwork() {
    }

    record Configure(BlockPos at, long revision, boolean all, List<ItemFilter> filters) implements CustomPacketPayload {
        static final Type<Configure> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("folkways", "supply_config"));
        static final StreamCodec<RegistryFriendlyByteBuf, Configure> CODEC = new StreamCodec<>() {
            public Configure decode(RegistryFriendlyByteBuf buf) {
                BlockPos at = buf.readBlockPos();
                long revision = buf.readLong();
                boolean all = buf.readBoolean();
                int count = buf.readVarInt();
                if (count < 0 || count > SupplyBox.MOST_RULES) throw new IllegalArgumentException("too many supply rules");
                List<ItemFilter> filters = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    filters.add(ItemFilter.read(buf.readUtf(SupplyBox.MOST_RULE_LENGTH + 1))
                        .orElseThrow(() -> new IllegalArgumentException("invalid supply rule")));
                }
                return new Configure(at, revision, all, List.copyOf(filters));
            }
            public void encode(RegistryFriendlyByteBuf buf, Configure packet) {
                buf.writeBlockPos(packet.at());
                buf.writeLong(packet.revision());
                buf.writeBoolean(packet.all());
                buf.writeVarInt(packet.filters().size());
                packet.filters().forEach(rule -> buf.writeUtf(rule.write(), SupplyBox.MOST_RULE_LENGTH + 1));
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(FolkwaysMod.NETWORK_VERSION).playToServer(Configure.TYPE, Configure.CODEC,
            (packet, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) configure(player, packet);
            }));
    }

    static boolean configure(net.minecraft.world.entity.player.Player player, Configure packet) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return false;
        if (!player.isCreative() || !level.isLoaded(packet.at())
                || !(level.getBlockEntity(packet.at()) instanceof SupplyBox box)
                || box.revision() != packet.revision()) return false;
        var world = WorldSpaces.world(level, WorldSpaces.at(level, packet.at()));
        if (world.isEmpty() || player.distanceToSqr(world.get()) > 64) return false;
        box.configure(packet.all(), packet.filters());
        return true;
    }
}
