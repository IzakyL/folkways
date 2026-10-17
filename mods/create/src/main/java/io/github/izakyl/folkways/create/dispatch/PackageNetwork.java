package io.github.izakyl.folkways.create.dispatch;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

    // The network a stock link or a stock ticker is on: the book takes a network on only by one of these.
    default Optional<UUID> linkerAt(ServerLevel level, BlockPos pos) {
        return Optional.empty();
    }

    // The chain conveyor a block stands for: itself, or the one a frogport hangs on.
    default Optional<BlockPos> chainAt(ServerLevel level, BlockPos pos) {
        return Optional.empty();
    }

    // Whether this is a frogport, on a chain or not.
    default boolean frogport(ServerLevel level, BlockPos pos) {
        return false;
    }

    default boolean isPostbox(BlockState state) {
        return false;
    }

    // Every chain conveyor joined by chains to this one.
    default Set<BlockPos> chainNet(ServerLevel level, BlockPos chain) {
        return Set.of();
    }

    // Every frogport in this level hanging on one of these chains, whether or not it takes parcels.
    default List<BlockPos> frogportsOn(ServerLevel level, Set<BlockPos> chains) {
        return List.of();
    }

    // The ports a network's parcels set out from: frogports on its chains, or postboxes by its packagers.
    default List<BlockPos> sendersOf(ServerLevel level, UUID network) {
        return List.of();
    }

    // The pick-up points a network reaches: every port along its chains, less the ones it sends from and the ones that
    // hand what they catch on into a container; or every postbox one of the `trains` brings its parcels to.
    default List<BlockPos> portsOf(ServerLevel level, UUID network, Predicate<UUID> trains) {
        return List.of();
    }

    // The containers the network's packagers pack from: its stock lies in these, and only it answers for them.
    default List<BlockPos> containersOf(ServerLevel level, UUID network) {
        return List.of();
    }

    // What lies in one of the network's containers now.
    default List<ItemStack> heldIn(ServerLevel level, BlockPos container) {
        return List.of();
    }

    // Takes `count` of the goods out of the container by hand: all of them, or nothing when fewer are there.
    default List<ItemStack> takeOut(ServerLevel level, BlockPos container, Predicate<ItemStack> goods, long count) {
        return List.of();
    }

    // The train a parcel goes by from one postbox to another, the station it is loaded at, and when it would be there.
    record Route(UUID train, String station, int arrives) {
    }

    // The postboxes a network that mails by train sends from: what it packs waits in these for a train.
    default List<BlockPos> postsOf(ServerLevel level, UUID network) {
        return List.of();
    }

    // Hands each parcel a packager of the network holds for one of the `ours` orders into the postbox beside it, to
    // wait there for a train: a postbox takes nothing from a packager of itself, as a frogport does.
    default void post(ServerLevel level, UUID network, IntPredicate ours) {
    }

    // The soonest of the `trains` to carry a parcel between the two postboxes.
    default Optional<Route> route(ServerLevel level, BlockPos from, BlockPos to, Predicate<UUID> trains) {
        return Optional.empty();
    }

    // The station a postbox is set to, by name.
    default Optional<String> stationOf(ServerLevel level, BlockPos postbox) {
        return Optional.empty();
    }

    default Optional<Component> trainName(MinecraftServer server, UUID train) {
        return Optional.empty();
    }

    // Takes a parcel of that order out of the postbox and hands it to one of the network's packagers, which unpacks it
    // into its container. A packager takes one parcel at a time, so the rest wait to be given back in turn.
    default boolean giveBack(ServerLevel level, UUID network, BlockPos postbox, int order) {
        return false;
    }

    // The packager a resident packs at to send by hand from this port: one on no network, under a frogport or beside
    // a postbox set to a station. A packager the network packs at is the network's to send from.
    default Optional<BlockPos> packerAt(ServerLevel level, BlockPos port) {
        return Optional.empty();
    }

    // Whether what is sent from this port goes by train.
    default boolean mails(ServerLevel level, BlockPos port) {
        return false;
    }

    // The ports a parcel sent by hand from this port reaches, by chain or by one of the `trains`, and how long it takes
    // to get to one.
    default List<BlockPos> reachedFrom(ServerLevel level, BlockPos port, Predicate<UUID> trains) {
        return List.of();
    }

    default OptionalInt handTicks(ServerLevel level, BlockPos from, BlockPos to, Predicate<UUID> trains) {
        return OptionalInt.empty();
    }

    // A parcel of these goods for that address under the colony's own order number, if they fit in one.
    default Optional<ItemStack> pack(List<ItemStack> goods, String address, int order) {
        return Optional.empty();
    }

    // Puts a parcel into the port, which throws it onto the chain, or holds it for a train calling there.
    default boolean send(ServerLevel level, BlockPos port, ItemStack parcel) {
        return false;
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
    default OptionalInt transitTicks(ServerLevel level, UUID network, BlockPos port, Predicate<UUID> trains) {
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
