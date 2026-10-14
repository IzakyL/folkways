package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.DraftRefusal;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Drafting;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Growth;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Round;
import io.github.izakyl.folkways.plugins.build.draft.World;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Rounds {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private Rounds() {
    }

    /**
     * Draws the site's next round over the chunks it reaches as they stand now, on a drafting thread; what it drew
     * comes back on the server's thread.
     */
    static CompletableFuture<Round> draw(ServerLevel level, Growing one) {
        Optional<Pattern> found = Patterns.find(one.pattern()).filter(Pattern::grows);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(new Round.Stuck(new Drawn.Refused(DraftRefusal.PATTERN_FAILED,
                "no growing pattern called " + one.pattern() + " is loaded")));
        }
        Pattern pattern = found.get();
        World world = pattern.capture(level, one.hint(), one.growth());
        return onServer(level, Drafting.grow(pattern, new Commission(one.hint(),
            Chosen.load(pattern.knobs(), one.settings()), world, one.facing(), one.roundSeed(), one.growth())));
    }

    // What a drafting thread hands back is taken up on the server's thread; once the server has stopped, it is not.
    static <T> CompletableFuture<T> onServer(ServerLevel level, CompletableFuture<T> drawing) {
        MinecraftServer server = level.getServer();
        return drawing.thenApplyAsync(Function.identity(), task -> {
            if (!server.isStopped()) {
                server.execute(task);
            }
        });
    }

    /** Logs what went wrong in taking up a drawing, which would otherwise vanish into its future. */
    static void logged(CompletableFuture<?> taken) {
        taken.whenComplete((done, broken) -> {
            if (broken != null) {
                LOGGER.error("taking up a drawing failed", broken);
            }
        });
    }

    static Optional<Blueprint> blueprintOf(ServerLevel level, Growing one, Draft draft) {
        return blueprintOf(level, one.pattern().getPath() + " #" + (one.growth().round() + 1), draft);
    }

    static Optional<Blueprint> blueprintOf(ServerLevel level, String name, Draft draft) {
        return StructureNbt.toBlueprint(
            DraftTemplate.of(draft, SharedConstants.getCurrentVersion().getDataVersion().getVersion()),
            level.registryAccess(), UUID.randomUUID(), name, level.getGameTime(), StructureNbt.AirMeaning.EXCAVATE,
            BlueprintBook.maxCaptureBlocks());
    }

    sealed interface Once {

        record Drafted(Blueprint blueprint, BlockPos corner) implements Once {
        }

        record Refused(Component why) implements Once {
        }
    }

    static CompletableFuture<Once> drawOnce(ServerLevel level, Pattern pattern, Hint hint, Settings settings,
            Direction facing) {
        World world = pattern.capture(level, hint, Growth.first(hint));
        return onServer(level, Drafting.draw(pattern, new Commission(hint, settings, world, facing,
            Patterns.seedOf(pattern, hint)))).thenApply(drawn -> blueprinted(level, pattern, drawn));
    }

    private static Once blueprinted(ServerLevel level, Pattern pattern, Drawn drawn) {
        if (drawn instanceof Drawn.Refused refused) {
            return new Once.Refused(refused.component());
        }
        Drawn.Ready ready = (Drawn.Ready) drawn;
        return blueprintOf(level, pattern.id().getPath(), ready.draft())
            .<Once>map(blueprint -> new Once.Drafted(blueprint, ready.corner()))
            .orElseGet(() -> new Once.Refused(
                Component.translatable("folkways.blueprint.too_large", BlueprintBook.maxCaptureBlocks())));
    }
}
