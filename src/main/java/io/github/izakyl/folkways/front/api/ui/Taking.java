package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.front.api.Ghost;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** What an offer's page does with the shape it was opened over, once the player has decided. */
@OnlyIn(Dist.CLIENT)
public interface Taking {

    /** The offer was taken: the shape is let go and the window closes. */
    void done();

    /**
     * The window closes with the shape still drawn and these blocks shown standing in it, to be looked over and
     * come back to; they stay until another preview, the offer taken, or the shape changed or let go.
     */
    void preview(List<Ghost> ghosts);

    /** Whether a preview this offer made over this same shape is standing in the world now. */
    boolean previewed();
}
