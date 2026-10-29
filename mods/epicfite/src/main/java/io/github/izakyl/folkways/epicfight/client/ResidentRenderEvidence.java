package io.github.izakyl.folkways.epicfight.client;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Bounded observations written only after the real patched renderer returns, for client acceptance checks. */
@OnlyIn(Dist.CLIENT)
public final class ResidentRenderEvidence {
    public record Draw(long sequence, long gameTick, ResourceLocation mesh, ResourceLocation texture,
            String sourceRenderer, float entryScale) {
    }

    private static final Map<UUID, Draw> LAST = new LinkedHashMap<>();
    private static long sequence;

    private ResidentRenderEvidence() {
    }

    static synchronized void drawn(ResidentEntity resident, ResourceLocation mesh, ResourceLocation texture,
            String sourceRenderer, float entryScale) {
        LAST.put(resident.getUUID(), new Draw(++sequence, resident.level().getGameTime(), mesh, texture,
            sourceRenderer, entryScale));
        while (LAST.size() > 256) {
            LAST.remove(LAST.keySet().iterator().next());
        }
    }

    public static synchronized Optional<Draw> last(ResidentEntity resident) {
        return Optional.ofNullable(LAST.get(resident.getUUID()));
    }

    static synchronized void clear() {
        LAST.clear();
    }
}
