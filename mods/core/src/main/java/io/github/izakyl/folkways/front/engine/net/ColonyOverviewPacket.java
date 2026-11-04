package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.front.api.Placard;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public record ColonyOverviewPacket(
    List<BlockPos> members,
    List<ZoneSnapshot> zones,
    List<Ghost> ghosts,
    List<CompoundTag> paths,
    List<Placards.Note> notes,
    List<Placard> placards,
    List<CompoundTag> recipes
) implements CustomPacketPayload {
    public ColonyOverviewPacket(List<BlockPos> members, List<ZoneSnapshot> zones, List<Ghost> ghosts,
            List<CompoundTag> paths, List<Placards.Note> notes, List<Placard> placards) {
        this(members, zones, ghosts, paths, notes, placards, List.of());
    }

    private static final int MAX_POSITIONS = 4096;
    private static final int MAX_ZONES = 512;
    static final int MAX_GHOSTS = 2048;
    public static final Type<ColonyOverviewPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "colony_overview")
    );
    private static final StreamCodec<ByteBuf, Ghost> GHOST = StreamCodec.composite(
        BlockPos.STREAM_CODEC, Ghost::pos,
        ByteBufCodecs.VAR_INT.map(Block::stateById, Block::getId), Ghost::state,
        ByteBufCodecs.COMPOUND_TAG.map(StructurePayload::new, StructurePayload::tag), Ghost::payload,
        UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Ghost::order,
        ByteBufCodecs.VAR_INT, Ghost::payloadHash,
        Ghost::new);
    private static final StreamCodec<RegistryFriendlyByteBuf, ColonyOverviewPacket> BASE = StreamCodec.composite(
        SyncCodecs.capped(BlockPos.STREAM_CODEC, MAX_POSITIONS), ColonyOverviewPacket::members,
        SyncCodecs.capped(ZoneSnapshot.STREAM_CODEC, MAX_ZONES), ColonyOverviewPacket::zones,
        SyncCodecs.capped(GHOST, MAX_GHOSTS), ColonyOverviewPacket::ghosts,
        SyncCodecs.capped(ByteBufCodecs.COMPOUND_TAG, MAX_ZONES), ColonyOverviewPacket::paths,
        SyncCodecs.capped(Placards.NOTE, MAX_ZONES), ColonyOverviewPacket::notes,
        SyncCodecs.capped(Placards.PLACARD, Placards.MAX_PLACARDS), ColonyOverviewPacket::placards,
        ColonyOverviewPacket::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyOverviewPacket> STREAM_CODEC = StreamCodec.composite(
        BASE, packet -> packet,
        SyncCodecs.capped(ByteBufCodecs.COMPOUND_TAG, MAX_ZONES), ColonyOverviewPacket::recipes,
        (base, recipes) -> new ColonyOverviewPacket(base.members(), base.zones(), base.ghosts(), base.paths(),
            base.notes(), base.placards(), recipes));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
