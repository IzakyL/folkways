package io.github.izakyl.folkways.plugins.dispatch;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

final class AbsentPackageNetwork implements PackageNetwork {

    static final AbsentPackageNetwork INSTANCE = new AbsentPackageNetwork();

    private AbsentPackageNetwork() {
    }

    @Override
    public boolean isPort(BlockState state) {
        return false;
    }

    @Override
    public Optional<UUID> networkAt(ServerLevel level, BlockPos pos) {
        return Optional.empty();
    }

    @Override
    public boolean mayAdministrate(MinecraftServer server, UUID network, ServerPlayer player) {
        return false;
    }

    @Override
    public List<Supply> summaryOf(MinecraftServer server, UUID network) {
        return List.of();
    }

    @Override
    public String addressOf(ServerLevel level, BlockPos at) {
        return "";
    }

    @Override
    public boolean portOpen(ServerLevel level, BlockPos at) {
        return false;
    }

    @Override
    public List<Parcel> parcelsAt(ServerLevel level, BlockPos at) {
        return List.of();
    }

    @Override
    public List<ItemStack> collectFrom(ServerLevel level, BlockPos at, int order) {
        return List.of();
    }

    @Override
    public Optional<List<ItemStack>> clearOne(ServerLevel level, BlockPos at, Predicate<Parcel> stray) {
        return Optional.empty();
    }

    @Override
    public List<BlockPos> desksOf(ServerLevel level, UUID network) {
        return List.of();
    }

    @Override
    public List<BlockPos> keeperSeats(ServerLevel level, BlockPos desk) {
        return List.of();
    }

    @Override
    public boolean sit(ServerLevel level, BlockPos seat, Entity body) {
        return false;
    }

    @Override
    public Optional<UUID> sitting(ServerLevel level, BlockPos seat) {
        return Optional.empty();
    }

    @Override
    public OptionalInt request(MinecraftServer server, UUID network, String address, List<Supply> wanted) {
        return OptionalInt.empty();
    }
}
