package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.DraftRefusal;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Round;
import io.github.izakyl.folkways.plugins.build.draft.World;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

final class Rounds {

    private Rounds() {
    }

    static Round draw(ServerLevel level, Growing one) {
        Optional<Pattern> found = Patterns.find(one.pattern()).filter(Pattern::grows);
        if (found.isEmpty()) {
            return new Round.Stuck(new Drawn.Refused(DraftRefusal.PATTERN_FAILED,
                "no growing pattern called " + one.pattern() + " is loaded"));
        }
        Pattern pattern = found.get();
        return pattern.growOn(new Commission(one.hint(), Chosen.load(pattern.knobs(), one.settings()),
            World.of(level), one.facing(), one.roundSeed(), one.growth()));
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

    static Once drawOnce(ServerLevel level, Pattern pattern, Hint hint, Settings settings, Direction facing) {
        Drawn drawn = pattern.drawOn(new Commission(hint, settings, World.of(level), facing,
            Patterns.seedOf(pattern, hint)));
        if (drawn instanceof Drawn.Refused refused) {
            return new Once.Refused(Component.translatable(refused.why().translationKey(), refused.detail()));
        }
        Drawn.Ready ready = (Drawn.Ready) drawn;
        return blueprintOf(level, pattern.id().getPath(), ready.draft())
            .<Once>map(blueprint -> new Once.Drafted(blueprint, ready.corner()))
            .orElseGet(() -> new Once.Refused(
                Component.translatable("folkways.blueprint.too_large", BlueprintBook.maxCaptureBlocks())));
    }
}
