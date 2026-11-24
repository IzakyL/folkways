package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.vanilla.build.draft.Chosen;
import io.github.izakyl.folkways.vanilla.build.draft.Drafting;
import io.github.izakyl.folkways.vanilla.build.draft.Drawn;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Pattern;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class Commissions {

    public record Said(boolean filed, Component message) {
    }

    /** Draw a saved recipe again using the current world and installed libraries. */
    public static CompletableFuture<Drawn> redraw(
            ServerLevel level, CompoundTag recipe) {
        var saved = Drafted.load(Reader.of(recipe)).orElseThrow();
        return Rounds.onServer(level, Drafting.prepare(saved::resolve)).thenCompose(resolved -> {
            Pattern pattern = resolved.orElseThrow();
            return Rounds.onServer(level, Rounds.draw(pattern, saved.hint(),
                Chosen.load(pattern.knobs(), saved.settings()), saved.facing(), pattern.capture(level, saved.hint())));
        });
    }

    private Commissions() {
    }

    /** Draws the pattern over the mark and files it; what the colony said comes back on the server's thread. */
    public static CompletableFuture<Said> commission(ServerLevel level, Colony colony, ResourceLocation id, CompoundTag hint,
            CompoundTag settings, Direction facing, String siteName, Optional<UUID> playerId, String playerName) {
        Optional<BuildPresence> site = BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class));
        Optional<Pattern> pattern = Patterns.find(id);
        Optional<Hint> drawn = Hint.load(hint);
        if (site.isEmpty() || pattern.isEmpty() || drawn.isEmpty() || !pattern.get().accepts(drawn.get())) {
            return CompletableFuture.completedFuture(
                new Said(false, Component.translatable("folkways.draft.unknown", id.toString())));
        }
        return file(level, site.get(), pattern.get(), drawn.get(), Chosen.load(pattern.get().knobs(), settings),
            facing, siteName, playerId, playerName, Optional.empty());
    }

    static CompletableFuture<Said> file(ServerLevel level, BuildPresence site, Pattern pattern, Hint hint,
            Chosen settings, Direction facing, String siteName, Optional<UUID> playerId, String playerName,
            Optional<String> source) {
        return Rounds.drawOnce(level, pattern, hint, settings, facing).thenApply(once -> {
            if (!(once instanceof Rounds.Once.Drafted drafted)) {
                return new Said(false, ((Rounds.Once.Refused) once).why());
            }
            return site.file(level, drafted.blueprint(), drafted.corner(), siteName, playerId, playerName,
                    pattern.id(), hint, settings.save(), facing, source)
                .map(order -> new Said(true, Component.translatable("folkways.blueprint.placed", order.name())))
                .orElseGet(() -> new Said(false,
                    Component.translatable("folkways.blueprint.too_many", BlueprintBook.MAX_BUILD_ORDERS)));
        });
    }
}
