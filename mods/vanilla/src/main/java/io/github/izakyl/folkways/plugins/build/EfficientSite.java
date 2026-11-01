package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A site that raises itself, as an add-on makes it: everything an ordinary site hands over to one that builds on its
 * own. It is the whole of what the two sides share, so the add-on may live in a jar of its own while the site it
 * raises is one the colony knows how to staff.
 */
public interface EfficientSite {

    /** Takes the order up: what of it is already met, then the plan for the rest. */
    void start(ServerLevel level, Set<BlockPos> met, Set<Joint> metJoints);

    /** One turn of the site: hands asked for and kept company. True when it has something new to say of the work. */
    boolean tick(ServerLevel level);

    /** Every cell met and every joint made: the order is built. */
    boolean complete();

    /** Gives the order up: every hand is turned away and the site hears no more. */
    void close();
}
