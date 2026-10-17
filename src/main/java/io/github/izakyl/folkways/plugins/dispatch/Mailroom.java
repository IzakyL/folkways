package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * The colony's books on the parcels it sends by train: each waits, a real parcel, in the postbox it set out from, and
 * the books say which train may take it from there, and from which station. Kept with the colony, so a booking
 * outlasts a restart; a parcel no longer in its postbox is off the books.
 */
final class Mailroom {

    private static final String TAG_HELD = "held";

    // The parcels of one order in one postbox: the postbox they set out from and the one they are bound for, and the
    // train they are booked on, boarding at `station`.
    record Consignment(UUID id, int order, UUID network, WorldPos from, WorldPos to, Optional<UUID> train,
                       String station) {

        Consignment booked(Optional<PackageNetwork.Route> route) {
            return new Consignment(id, order, network, from, to, route.map(PackageNetwork.Route::train),
                route.map(PackageNetwork.Route::station).orElse(""));
        }

        boolean boards(UUID train, String station) {
            return this.train.filter(train::equals).isPresent() && this.station.equals(station);
        }

        CompoundTag save() {
            Writer written = Writer.of().uuid("id", id).integer("order", order).uuid("network", network)
                .blob("from", from.save()).blob("to", to.save()).uuid("train", train);
            return written.string("station", station).tag();
        }

        static Optional<Consignment> load(Reader reader) {
            Optional<UUID> id = reader.uuid("id");
            Optional<Integer> order = reader.integer("order");
            Optional<UUID> network = reader.uuid("network");
            Optional<WorldPos> from = reader.child("from").flatMap(WorldPos::load);
            Optional<WorldPos> to = reader.child("to").flatMap(WorldPos::load);
            if (id.isEmpty() || order.isEmpty() || network.isEmpty() || from.isEmpty() || to.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Consignment(id.get(), order.get(), network.get(), from.get(), to.get(),
                reader.uuid("train"), reader.string("station").orElse("")));
        }
    }

    private final List<Consignment> held = new ArrayList<>();

    synchronized void take(Consignment parcel) {
        held.add(parcel);
    }

    synchronized List<Consignment> all() {
        return List.copyOf(held);
    }

    synchronized List<Consignment> ofOrder(int order) {
        return held.stream().filter(parcel -> parcel.order() == order).toList();
    }

    synchronized boolean holds(WorldPos from, int order) {
        return held.stream().anyMatch(parcel -> parcel.order() == order && parcel.from().equals(from));
    }

    synchronized List<Consignment> in(ServerLevel level) {
        return held.stream().filter(parcel -> parcel.from().in(level)).toList();
    }

    synchronized boolean replace(Consignment was, Consignment now) {
        int at = held.indexOf(was);
        if (at < 0 || was.equals(now)) {
            return false;
        }
        held.set(at, now);
        return true;
    }

    synchronized boolean drop(Consignment parcel) {
        return held.remove(parcel);
    }

    synchronized void clear() {
        held.clear();
    }

    synchronized CompoundTag save() {
        return Writer.of().children(TAG_HELD, held, Consignment::save).tag();
    }

    synchronized void load(Reader reader) {
        held.clear();
        reader.children(TAG_HELD).forEach(one -> Consignment.load(one).ifPresent(held::add));
    }
}
