package io.github.izakyl.folkways.core.card.net;

import io.github.izakyl.folkways.core.card.api.CardCodec;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A press on a facility's card, from whichever window shows it. */
public record CardPress(long request, UUID colony, UUID facility, CardOrders.Press press) implements CustomPacketPayload {
    public static final Type<CardPress> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "card_press"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CardPress> STREAM_CODEC =
        StreamCodec.ofMember(CardPress::encode, CardPress::decode);
    private static final int MAX = 256;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf b) {
        b.writeVarLong(request);
        b.writeUUID(colony);
        b.writeUUID(facility);
        b.writeVarInt(press.index());
        b.writeUtf(press.name(), MAX);
        switch (press) {
            case CardOrders.Press.Run ignored -> b.writeByte(0);
            case CardOrders.Press.Pick pick -> {
                b.writeByte(1);
                CardCodec.value(b, java.util.Optional.of(pick.picked()));
            }
            case CardOrders.Press.Choose choose -> {
                b.writeByte(2);
                b.writeVarInt(choose.option());
            }
            case CardOrders.Press.Step step -> {
                b.writeByte(3);
                b.writeBoolean(step.direction() > 0);
            }
            case CardOrders.Press.Set set -> {
                b.writeByte(4);
                CardCodec.value(b, java.util.Optional.of(set.value()));
            }
            case CardOrders.Press.Toggle toggle -> {
                b.writeByte(5);
                b.writeBoolean(toggle.on());
            }
            case CardOrders.Press.Act act -> {
                b.writeByte(6);
                b.writeVarInt(act.row());
                b.writeVarInt(act.act());
            }
        }
    }

    private static CardPress decode(RegistryFriendlyByteBuf b) {
        long request = b.readVarLong();
        UUID colony = b.readUUID();
        UUID facility = b.readUUID();
        int index = b.readVarInt();
        String name = b.readUtf(MAX);
        CardOrders.Press press = switch (b.readByte()) {
            case 0 -> new CardOrders.Press.Run(index, name);
            case 1 -> new CardOrders.Press.Pick(index, name, CardCodec.value(b).orElseThrow());
            case 2 -> new CardOrders.Press.Choose(index, name, b.readVarInt());
            case 3 -> new CardOrders.Press.Step(index, name, b.readBoolean() ? 1 : -1);
            case 4 -> new CardOrders.Press.Set(index, name, CardCodec.value(b).orElseThrow());
            case 5 -> new CardOrders.Press.Toggle(index, name, b.readBoolean());
            case 6 -> new CardOrders.Press.Act(index, name, b.readVarInt(), b.readVarInt());
            default -> throw new IllegalArgumentException("invalid card press");
        };
        return new CardPress(request, colony, facility, press);
    }
}
