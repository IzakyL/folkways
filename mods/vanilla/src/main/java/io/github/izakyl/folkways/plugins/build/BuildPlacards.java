package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.front.api.Placard;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.HolderLookup;

final class BuildPlacards {
    private static final String NEEDS = "folkways.card.build.needs";
    private static final ResourceLocation BUILDING_GLYPH = ResourceLocation.fromNamespaceAndPath(
        BuildContent.BUILDING.getNamespace(), "doing/" + BuildContent.BUILDING.getPath());
    private static final String PROGRESS = "folkways.page.build.progress";
    private static final String GROWING = "folkways.page.build.growing";
    private static final String STUCK = "folkways.page.build.growing.stuck";

    private BuildPlacards() {
    }

    static Placard.Outline outline(Hint hint) {
        return switch (hint) {
            case Hint.Zone zone -> new Placard.Outline.Box(zone.min(), zone.max());
            case Hint.Path path -> new Placard.Outline.Path(path.points());
        };
    }

    static List<Line> building(Line title, Optional<BuildOrderView> seen, Map<ItemSpec, Long> owed, HolderLookup.Provider registries) {
        List<Line> lines = new ArrayList<>();
        lines.add(title);
        seen.ifPresent(view -> {
            lines.add(Line.said(new Notice(PROGRESS, List.of(Notice.count(view.placed()),
                Notice.count(view.total())))));
            lines.add(Line.bar(view.placed(), view.total()));
        });
        if (owed.isEmpty()) {
            return lines;
        }
        lines.add(Line.told(needs(owed, Line.Goods.MOST_STACKS, registries)));
        return lines;
    }

    /** The building glyph, or "Still needs" where it is not drawn, and the most owed of the build's needs. */
    static Sentence needs(Map<ItemSpec, Long> owed, int most, HolderLookup.Provider registries) {
        return Sentence.wares(Sentence.glyph(BUILDING_GLYPH, new Notice(NEEDS, List.of())), owed.entrySet().stream()
            .sorted(Map.Entry.<ItemSpec, Long>comparingByValue().reversed())
            .limit(most)
            .map(entry -> Sentence.Token.Ware.of(Goods.display(entry.getKey()), entry.getValue(), registries))
            .toList());
    }

    static List<Line> waiting(Growing one) {
        Component said = one.stuck().isEmpty()
            ? Component.translatable(GROWING, one.growth().round() + 1)
            : Component.translatable(STUCK, one.growth().round() + 1, one.stuckText());
        return List.of(Line.literal(one.name()), Line.text(said));
    }
}
