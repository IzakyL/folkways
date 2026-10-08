package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RosterSyncPacket(List<UUID> trains, Map<UUID, TrainCrew> crews) implements CustomPacketPayload {

    private static final int MAX_TRAINS = 512;
    private static final int MAX_DRIVERS = 8;
    private static final int MAX_NAME = 64;

    public static final Type<RosterSyncPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "roster_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RosterSyncPacket> STREAM_CODEC =
        StreamCodec.ofMember(RosterSyncPacket::encode, RosterSyncPacket::decode);

    public RosterSyncPacket {
        trains = List.copyOf(trains);
        crews = Map.copyOf(crews);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        int size = Math.min(trains.size(), MAX_TRAINS);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            buffer.writeUUID(trains.get(index));
        }
        List<UUID> named = new ArrayList<>(crews.keySet());
        int aboard = Math.min(named.size(), MAX_TRAINS);
        buffer.writeVarInt(aboard);
        for (int index = 0; index < aboard; index++) {
            UUID train = named.get(index);
            TrainCrew crew = crews.get(train);
            buffer.writeUUID(train);
            int drivers = Math.min(crew.drivers().size(), MAX_DRIVERS);
            buffer.writeVarInt(drivers);
            for (int seat = 0; seat < drivers; seat++) {
                buffer.writeUtf(crew.drivers().get(seat), MAX_NAME);
            }
            buffer.writeVarInt(crew.seatsTaken());
            buffer.writeVarInt(crew.seatsTotal());
            buffer.writeBoolean(crew.fault().isPresent());
            crew.fault().ifPresent(fault -> {
                buffer.writeUtf(fault.key(), MAX_NAME);
                buffer.writeUtf(fault.stop(), MAX_NAME);
            });
        }
    }

    private static RosterSyncPacket decode(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<UUID> trains = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID train = buffer.readUUID();
            if (index < MAX_TRAINS) {
                trains.add(train);
            }
        }
        int aboard = buffer.readVarInt();
        Map<UUID, TrainCrew> crews = new LinkedHashMap<>();
        for (int index = 0; index < aboard; index++) {
            UUID train = buffer.readUUID();
            int drivers = buffer.readVarInt();
            List<String> names = new ArrayList<>();
            for (int seat = 0; seat < drivers; seat++) {
                String name = buffer.readUtf(MAX_NAME);
                if (seat < MAX_DRIVERS) {
                    names.add(name);
                }
            }
            int taken = buffer.readVarInt();
            int total = buffer.readVarInt();
            Optional<TimetableFault> fault = buffer.readBoolean()
                ? Optional.of(new TimetableFault(buffer.readUtf(MAX_NAME), buffer.readUtf(MAX_NAME)))
                : Optional.empty();
            if (index < MAX_TRAINS && taken >= 0 && taken <= total) {
                crews.put(train, new TrainCrew(names, taken, total, fault));
            }
        }
        return new RosterSyncPacket(trains, crews);
    }
}
