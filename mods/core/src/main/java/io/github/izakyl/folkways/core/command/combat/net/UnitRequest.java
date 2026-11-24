package io.github.izakyl.folkways.core.command.combat.net;

import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded, typed client intent. The request number correlates the authoritative acknowledgement. */
public record UnitRequest(long request, UUID colony, UUID unit, Operation operation, List<UUID> residents,
                          int seat, int index, Vehicles.Order order, String weapon, WeaponRules.Rule rule, Optional<VehicleProfile> profile)
    implements CustomPacketPayload {
    public enum Operation { ROSTER, BOARD, ASSIGN, LEAVE, ORDER, APPEND, CYCLE, WEAPON, RULE, PROFILE_SAVE, PROFILE_DELETE, PROFILE_BIND, RELEASE_IDLE,
        ALTITUDE, RELEASE_OWNERSHIP, FACILITY_RUN, FACILITY_TOGGLE, FACILITY_TARGET, FACILITY_PARAMETER, FACILITY_CHOICE }

    public static final Type<UnitRequest> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "unit_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UnitRequest> STREAM_CODEC =
        StreamCodec.ofMember(UnitRequest::encode, UnitRequest::decode);

    public UnitRequest {
        residents = List.copyOf(residents);
        if (request < 0 || residents.size() > CombatPackets.MAX_MEMBERS
            || order.points().size() > CombatPackets.MAX_POINTS
            || weapon.length() > 256) {
            throw new IllegalArgumentException("invalid unit request");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarLong(request);
        buffer.writeUUID(colony);
        buffer.writeUUID(unit);
        buffer.writeEnum(operation);
        CombatSelectionPacket.writeIds(buffer, residents);
        buffer.writeVarInt(seat);
        buffer.writeVarInt(index);
        buffer.writeEnum(order.action());
        buffer.writeVarInt(order.points().size());
        order.points().forEach(point -> point.write(buffer));
        buffer.writeVarInt(order.cycleStart());
        buffer.writeBoolean(order.target().isPresent());
        order.target().ifPresent(buffer::writeUUID);
        buffer.writeBoolean(order.airport().isPresent());
        order.airport().ifPresent(airport -> buffer.writeUtf(airport, 256));
        buffer.writeUtf(weapon, 256);
        buffer.writeEnum(rule.mode());
        buffer.writeDouble(rule.range());
        buffer.writeBoolean(rule.indirect());
        buffer.writeBoolean(profile.isPresent());
        profile.ifPresent(value -> buffer.writeNbt(value.save(buffer.registryAccess())));
    }

    private static UnitRequest decode(RegistryFriendlyByteBuf buffer) {
        long request = buffer.readVarLong();
        UUID colony = buffer.readUUID();
        UUID unit = buffer.readUUID();
        Operation operation = buffer.readEnum(Operation.class);
        List<UUID> residents = CombatSelectionPacket.readIds(buffer, CombatPackets.MAX_MEMBERS);
        int seat = buffer.readVarInt();
        int index = buffer.readVarInt();
        Vehicles.Action action = buffer.readEnum(Vehicles.Action.class);
        int count = buffer.readVarInt();
        if (count < 0 || count > CombatPackets.MAX_POINTS) {
            throw new IllegalArgumentException("too many waypoints");
        }
        List<WorldPos> points = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            points.add(WorldPos.read(buffer));
        }
        Vehicles.Order order = new Vehicles.Order(action, points, buffer.readVarInt(),
            buffer.readBoolean() ? Optional.of(buffer.readUUID()) : Optional.empty(),
            buffer.readBoolean() ? Optional.of(buffer.readUtf(256)) : Optional.empty());
        return new UnitRequest(request, colony, unit, operation, residents, seat, index, order,
            buffer.readUtf(256), new WeaponRules.Rule(buffer.readEnum(WeaponRules.Mode.class),
                buffer.readDouble(), buffer.readBoolean()),
            buffer.readBoolean() ? Optional.of(VehicleProfile.load(buffer.readNbt(), buffer.registryAccess()))
                : Optional.empty());
    }
}
