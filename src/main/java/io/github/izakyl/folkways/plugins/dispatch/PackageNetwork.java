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

public interface PackageNetwork {

    record Supply(ItemStack item, long count) {
    }

    // The order a parcel was packed for; parcels sent by hand carry none.
    record Parcel(List<ItemStack> contents, OptionalInt order) {

        public Parcel {
            contents = List.copyOf(contents);
        }
    }

    static PackageNetwork absent() {
        return AbsentPackageNetwork.INSTANCE;
    }

    boolean isPort(BlockState state);

    default boolean exists(MinecraftServer server, UUID network) {
        return true;
    }

    // The network this block belongs to: a linked block by its own link, the rest by the links along their chains.
    Optional<UUID> networkAt(ServerLevel level, BlockPos pos);

    // Whether the book should read this block as a piece of some package network rather than as a member.
    default boolean partOfNetwork(ServerLevel level, BlockPos pos) {
        return networkAt(level, pos).isPresent();
    }

    // The pick-up points a network reaches: every port along its chains, less the ones it sends from.
    default List<BlockPos> portsOf(ServerLevel level, UUID network) {
        return List.of();
    }

    boolean mayAdministrate(MinecraftServer server, UUID network, ServerPlayer player);

    List<Supply> summaryOf(MinecraftServer server, UUID network);

    // What keeps the network from the shape the colony orders through; the colony orders nothing from it meanwhile.
    record Flaw(NetworkFlaw kind, BlockPos at) {
    }

    default Optional<Flaw> flawOf(ServerLevel level, UUID network) {
        return Optional.empty();
    }

    // Ticks from placing an order to its parcels standing at this port, reckoned from how the network is laid out.
    default OptionalInt transitTicks(ServerLevel level, UUID network, BlockPos port) {
        return OptionalInt.empty();
    }

    String addressOf(ServerLevel level, BlockPos at);

    // The address that reaches this one port whatever it is called; blank when nothing can be sent there.
    default String routeOf(ServerLevel level, BlockPos at) {
        return addressOf(level, at);
    }

    boolean portOpen(ServerLevel level, BlockPos at);

    List<Parcel> parcelsAt(ServerLevel level, BlockPos at);

    // A parcel is only ever taken whole, so one parcel feeds exactly one collection.
    List<ItemStack> collectFrom(ServerLevel level, BlockPos at, int order);

    Optional<List<ItemStack>> clearOne(ServerLevel level, BlockPos at, Predicate<Parcel> stray);

    List<BlockPos> desksOf(ServerLevel level, UUID network);

    List<BlockPos> keeperSeats(ServerLevel level, BlockPos desk);

    boolean sit(ServerLevel level, BlockPos seat, Entity body);

    Optional<UUID> sitting(ServerLevel level, BlockPos seat);

    // Too much already queued to pack; an order placed now would be turned away.
    default boolean busy(MinecraftServer server, UUID network) {
        return false;
    }

    OptionalInt request(MinecraftServer server, UUID network, String address, List<Supply> wanted);
}
