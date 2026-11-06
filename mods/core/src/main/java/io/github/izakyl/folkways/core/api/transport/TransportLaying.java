package io.github.izakyl.folkways.core.api.transport;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Node;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

// Only nodes added by this selected leg may be modified. Every operation belongs to the same rollback mark.
public interface TransportLaying {

    void add(Node node);

    // Supplies a declared need from the preceding leg, never by looking for another source of goods.
    boolean feed(UUID consumer);

    boolean transfer(UUID maker, UUID consumer, ItemSpec goods, long count);

    void flow(UUID from, UUID to, ItemSpec goods, long count);

    // Orders two of this leg's nodes where no goods pass between them, as a parcel sent before it is collected.
    boolean before(UUID first, UUID then);

    void together(UUID one, UUID other);

    boolean reserve(ResourceLocation resource, ResourceLocation unit, UUID owner, long amount, long capacity);

    boolean hold(Hold hold);

    // The node that hands the goods over at the far end, into the pack of whoever carries them on.
    boolean output(UUID maker);

    boolean direct(TransportQuote quote);
}
