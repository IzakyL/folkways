package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Node;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the plan lends a {@link Carrying} rule while it lays one transfer: the plan as it stands this round, and the
 * one growth the transfer's work goes into, which the rule grows and takes back from but never sees whole.
 */
public interface Laying {

    // ---- the plan as it stands ----

    Node node(UUID id);

    Set<Stash> stores();

    // Where one may stand to reach into the store.
    Set<Stand> around(Stash where);

    // Whether no one could get to any of `around` the store.
    boolean cutOff(Stash where, Set<Stand> around);

    // Where the work may stand and still reach into the store: none when it cannot.
    Set<Stand> reaching(UUID node, Stash where);

    // Every store the work reaches into from wherever it may stand.
    Set<Stash> closure(UUID node);

    // The stores the work puts what it makes into.
    List<Stash> putsInto(UUID node);

    // Room in the store for more of the goods, after everything booked into and out of it.
    long roomFor(Stash where, ItemSpec concrete);

    // Whether the store may feed the work the goods at all.
    boolean feeds(Stash from, UUID consumer, ItemSpec concrete);

    // The most of the goods one carry takes.
    long perTrip(ItemSpec concrete);

    // The goods lying in the store that no other work has booked yet, up to `count` of them.
    Manifest lying(Stash where, ItemSpec concrete, long count);

    // ---- growing ----

    void add(Node node);

    void book(Resource on, UUID owner, long amount);

    void flow(Flow flow);

    // One resident does both.
    void together(UUID one, UUID other);

    // Where the work may stand, narrowed to these.
    void stand(UUID node, Set<Stand> stands);

    // What the work picks up into the pack it is done from, or puts down out of it.
    void carry(UUID node, Amount amount);

    // A store the work takes goods out of.
    void from(UUID node, Stash where);

    // A store the work puts goods into.
    void into(UUID node, Stash where);

    // Goods handed on into the pack the work is done from, for it to take.
    void hand(UUID node, Manifest goods);

    // Who must do the work.
    void by(UUID node, UUID worker);

    // ---- keeping it whole ----

    // How far the growth has got: what it gains after, the rule can take back.
    interface Mark {
    }

    Mark mark();

    // The work laid since the mark, as it still stands.
    List<UUID> laidSince(Mark mark);

    void rollback(Mark mark);

    // Whether someone is fit to do the work, with all it must now be done together with.
    boolean fits(UUID node);

    // And with room in their pack for it as their pack is now.
    boolean fitsNow(UUID node);

    // Why what the rule laid falls short, when it does.
    void because(Why why);
}
