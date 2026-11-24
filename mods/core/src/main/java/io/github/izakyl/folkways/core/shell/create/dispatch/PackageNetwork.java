package io.github.izakyl.folkways.core.shell.create.dispatch;

import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.work.api.BlockSeats;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public interface PackageNetwork extends BlockSeats.Adapter {

    public record Supply(ItemStack item, long count) {
    }

    // The order a parcel was packed for; parcels sent by hand carry none.
    public record Parcel(List<ItemStack> contents, OptionalInt order) {

        public Parcel {
            contents = List.copyOf(contents);
        }
    }

    public static PackageNetwork absent() {
        return AbsentPackageNetwork.INSTANCE;
    }

    public boolean isPort(BlockState state);

    public default boolean exists(MinecraftServer server, UUID network) {
        return true;
    }

    // The network this block belongs to: a linked block by its own link, the rest by the links along their chains.
    Optional<UUID> networkAt(ServerLevel level, BlockPos pos);

    // The network a stock link or a stock ticker is on: the book takes a network on only by one of these.
    public default Optional<UUID> linkerAt(ServerLevel level, BlockPos pos) {
        return Optional.empty();
    }

    // The chain conveyor a block stands for: itself, or the one a frogport hangs on.
    public default Optional<BlockPos> chainAt(ServerLevel level, BlockPos pos) {
        return Optional.empty();
    }

    // Whether this is a frogport, on a chain or not.
    public default boolean frogport(ServerLevel level, BlockPos pos) {
        return false;
    }

    public default boolean isPostbox(BlockState state) {
        return false;
    }

    // Every chain conveyor joined by chains to this one.
    public default Set<BlockPos> chainNet(ServerLevel level, BlockPos chain) {
        return Set.of();
    }

    // Every frogport in this level hanging on one of these chains, whether or not it takes parcels.
    public default List<BlockPos> frogportsOn(ServerLevel level, Set<BlockPos> chains) {
        return List.of();
    }

    // The ports a network's parcels set out from: frogports on its chains, or postboxes by its packagers.
    public default List<BlockPos> sendersOf(ServerLevel level, UUID network) {
        return List.of();
    }

    public default Optional<CreateLayout> layoutOf(ServerLevel level, UUID network) {
        return Optional.empty();
    }

    public default List<BlockPos> portsOn(ServerLevel level, Set<BlockPos> chains) {
        return List.of();
    }

    public default boolean unpacks(ServerLevel level, BlockPos port) {
        return false;
    }

    public default Optional<BlockPos> chainOf(ServerLevel level, BlockPos port) {
        return Optional.empty();
    }

    public default double chainSpeed(ServerLevel level, BlockPos chain) {
        return 0.0D;
    }

    public default OptionalInt chainTicks(ServerLevel level, List<BlockPos> starts, BlockPos end) {
        return OptionalInt.empty();
    }

    public default int packingTicks(ServerLevel level, CreateLayout layout) {
        return 0;
    }

    // The containers the network's packagers pack from: its stock lies in these, and only it answers for them.
    public default List<BlockPos> containersOf(ServerLevel level, UUID network) {
        return List.of();
    }

    public default boolean containersComplete(ServerLevel level, UUID network) {
        return true;
    }

    // A live slot view opened only by the shared inventory observer.
    public default Container inventoryAt(ServerLevel level, BlockPos container) {
        return null;
    }

    // Takes `count` of the goods out of the container by hand: all of them, or nothing when fewer are there.
    public default List<ItemStack> takeOut(ServerLevel level, BlockPos container, Predicate<ItemStack> goods, long count) {
        return List.of();
    }

    record TrainArrival(UUID train, int arrives, long storageCarriages) {
    }

    // Transfer an accepted order from the packager's output to the postbox's inventory.
    public default void post(ServerLevel level, BlockPos packager, BlockPos postbox, IntPredicate acceptsOrder) {
    }

    public default List<BlockPos> railPortsReached(ServerLevel level, Collection<BlockPos> senders) {
        return List.of();
    }

    public default List<TrainArrival> trainsBetween(ServerLevel level, BlockPos from, BlockPos to,
            Function<UUID, List<TransitNetwork.Stop>> timetable) {
        return List.of();
    }

    // The station a postbox is set to, by name.
    public default Optional<String> stationOf(ServerLevel level, BlockPos postbox) {
        return Optional.empty();
    }

    public default Optional<Component> trainName(MinecraftServer server, UUID train) {
        return Optional.empty();
    }

    // Takes a parcel of that order out of the postbox and hands it to one of the network's packagers, which unpacks it
    // into its container. A packager takes one parcel at a time, so the rest wait to be given back in turn.
    public default boolean giveBack(ServerLevel level, UUID network, BlockPos postbox, int order) {
        return false;
    }

    // The packager a resident packs at to send by hand from this port: one on no network, under a frogport or beside
    // a postbox set to a station. A packager the network packs at is the network's to send from.
    public default Optional<BlockPos> packerAt(ServerLevel level, BlockPos port) {
        return Optional.empty();
    }

    // Whether what is sent from this port goes by train.
    public default boolean mails(ServerLevel level, BlockPos port) {
        return false;
    }

    public default OptionalInt throwTicks(ServerLevel level, BlockPos from, BlockPos to) {
        return OptionalInt.empty();
    }

    // A parcel of these goods for that address under the colony's own order number, if they fit in one.
    public default Optional<ItemStack> pack(List<ItemStack> goods, String address, int order) {
        return Optional.empty();
    }

    // Puts a parcel into the port, which throws it onto the chain, or holds it for a train calling there.
    public default boolean send(ServerLevel level, BlockPos port, ItemStack parcel) {
        return false;
    }

    public boolean mayAdministrate(MinecraftServer server, UUID network, ServerPlayer player);

    public List<Supply> summaryOf(MinecraftServer server, UUID network);

    public String addressOf(ServerLevel level, BlockPos at);

    // The address that reaches this one port whatever it is called; blank when nothing can be sent there.
    public default String routeOf(ServerLevel level, BlockPos at) {
        return addressOf(level, at);
    }

    public boolean portOpen(ServerLevel level, BlockPos at);

    public List<Parcel> parcelsAt(ServerLevel level, BlockPos at);

    // A parcel is only ever taken whole, so one parcel feeds exactly one collection.
    public List<ItemStack> collectFrom(ServerLevel level, BlockPos at, int order);

    public Optional<List<ItemStack>> clearOne(ServerLevel level, BlockPos at, Predicate<Parcel> stray);

    public List<BlockPos> desksOf(ServerLevel level, UUID network);

    public List<BlockPos> seatsBeside(ServerLevel level, BlockPos at);

    // Too much already queued to pack; an order placed now would be turned away.
    public default boolean busy(MinecraftServer server, UUID network) {
        return false;
    }

    // Places an order only at a packager drawing from the specified shelf.
    public OptionalInt requestFrom(ServerLevel level, UUID network, BlockPos source, String address,
                                   ItemStack item, long count);
}
