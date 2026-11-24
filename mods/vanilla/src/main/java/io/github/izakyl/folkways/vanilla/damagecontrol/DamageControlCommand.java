package io.github.izakyl.folkways.vanilla.damagecontrol;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Asks for what damage control has marked in the colony's protected areas, while the book is bound to it. */
public record DamageControlCommand(UUID colony) implements CustomPacketPayload {
    /** The area of a mark that stands in none: a worker away fetching what a repair or a refill needs. */
    public static final UUID NONE = new UUID(0, 0);
    public static final Type<DamageControlCommand> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "damage_control_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DamageControlCommand> CODEC = StreamCodec.of(
        (buffer, packet) -> buffer.writeUUID(packet.colony()), buffer -> new DamageControlCommand(buffer.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
