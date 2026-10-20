package example.folkways;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.OwnerSave;
import io.github.izakyl.folkways.core.api.colony.OwnerSaves;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Compiles with the API attachment, never the full Core jar or its implementation classes. */
public final class OwnerSaveConsumer {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("example", "state");
    private static final OwnerSave SAVE = new OwnerSave(OWNER, List.of(payload -> payload), payload -> {
        if (payload.contains("value") && !payload.contains("value", 3)) {
            throw new IllegalArgumentException("value must be an integer");
        }
    });

    public void declare(Declaring event) {
        event.save(SAVE);
    }

    public Optional<CompoundTag> read(Colony colony) {
        return colony.kept(SAVE);
    }

    public void write(Colony colony, CompoundTag payload) {
        colony.keep(SAVE, payload);
    }

    public Optional<OwnerSave> registered() {
        return OwnerSaves.of(OWNER);
    }
}
