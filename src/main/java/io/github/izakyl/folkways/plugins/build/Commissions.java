package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class Commissions {

    public record Said(boolean filed, Component message) {
    }

    private Commissions() {
    }

    public static Said commission(ServerLevel level, Colony colony, ResourceLocation id, CompoundTag hint,
            CompoundTag settings, Direction facing, String siteName, Optional<UUID> playerId, String playerName) {
        Optional<BuildPresence> site = BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class));
        Optional<Pattern> pattern = Patterns.find(id);
        Optional<Hint> drawn = Hint.load(hint);
        if (site.isEmpty() || pattern.isEmpty() || drawn.isEmpty() || !pattern.get().accepts(drawn.get())) {
            return new Said(false, Component.translatable("folkways.draft.unknown", id.toString()));
        }
        return file(level, site.get(), pattern.get(), drawn.get(), Chosen.load(pattern.get().knobs(), settings),
            facing, siteName, playerId, playerName);
    }

    static Said file(ServerLevel level, BuildPresence site, Pattern pattern, Hint hint, Chosen settings,
            Direction facing, String siteName, Optional<UUID> playerId, String playerName) {
        if (!pattern.grows()) {
            Rounds.Once once = Rounds.drawOnce(level, pattern, hint, settings, facing);
            if (!(once instanceof Rounds.Once.Drafted drafted)) {
                return new Said(false, ((Rounds.Once.Refused) once).why());
            }
            return site.file(level, drafted.blueprint(), drafted.corner(), siteName, playerId, playerName,
                    pattern.id(), hint)
                .map(order -> new Said(true, Component.translatable("folkways.blueprint.placed", order.name())))
                .orElseGet(() -> new Said(false,
                    Component.translatable("folkways.blueprint.too_many", BlueprintBook.MAX_BUILD_ORDERS)));
        }
        Growing begun = begun(level, pattern, hint, settings, facing, siteName, playerId, playerName);
        Optional<Component> refused = site.grow(level, begun);
        return refused.map(why -> new Said(false, why))
            .orElseGet(() -> new Said(true, Component.translatable("folkways.draft.grow.placed",
                site.growingName(begun.id()).orElse(""))));
    }

    static Growing begun(ServerLevel level, Pattern pattern, Hint hint, Chosen settings, Direction facing,
            String siteName, Optional<UUID> playerId, String playerName) {
        return Growing.begun(pattern.id(), siteName, hint, settings.save(), facing, Patterns.seedOf(pattern, hint),
            WorldPos.of(level, hint.anchor()), playerId, playerName);
    }
}
