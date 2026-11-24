package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Each module's section of a book page as a bodiless facility whose card is one list, the page's name as its source,
 * so the book shows and presses it like any card. Rows are read in the level the colony's book is used in.
 */
public final class PanelFacilities {
    private PanelFacilities() {
    }

    public static void install() {
        Operations.register(PanelFacilities::find);
    }

    public static UUID id(ResourceLocation owner, ColonyPanel.Page page) {
        return UUID.nameUUIDFromBytes(("folkways_core:panel/" + owner + "/" + page.name())
            .getBytes(StandardCharsets.UTF_8));
    }

    /** Every module's section of this page, in registration order; the book shows those with rows. */
    public static List<Section> of(Colony colony, ColonyPanel.Page page) {
        List<Section> sections = new ArrayList<>();
        Enrollments.panels(colony).forEach((owner, panel) -> sections.add(new Section(colony, owner, page, panel)));
        return sections;
    }

    static Optional<Section> find(Colony colony, UUID id) {
        for (ColonyPanel.Page page : ColonyPanel.Page.values()) {
            for (Section section : of(colony, page)) {
                if (section.id().equals(id)) {
                    return Optional.of(section);
                }
            }
        }
        return Optional.empty();
    }

    public record Section(Colony colony, ResourceLocation owner, ColonyPanel.Page page, ColonyPanel panel)
        implements Operating, Facility {

        public String source() {
            return page.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public UUID id() {
            return PanelFacilities.id(owner, page);
        }

        @Override
        public Facility facility() {
            return this;
        }

        @Override
        public Component title() {
            return Component.translatableWithFallback(
                "folkways.settings.scope." + owner.getNamespace() + "." + owner.getPath(), owner.toString());
        }

        @Override
        public Card card() {
            return new Card(List.of(new Card.Element.Rows(source(), Optional.empty())));
        }

        // A section is set nowhere and run nowhere; its rows are read wherever the colony is loaded.
        @Override
        public WorldPos site() {
            return WorldPos.of(net.minecraft.world.level.Level.OVERWORLD, net.minecraft.core.BlockPos.ZERO);
        }

        @Override
        public Map<String, Routine.Use> routines() {
            return Map.of();
        }

        @Override
        public List<Trigger> triggers() {
            return List.of();
        }

        @Override
        public Map<String, Reading> readings(ServerLevel level) {
            return Map.of();
        }

        @Override
        public Optional<ServerLevel> loaded(MinecraftServer server) {
            return Optional.ofNullable(server.overworld());
        }

        @Override
        public List<Card.Row> rows(ServerLevel level, String source, Map<String, Value> readings) {
            return source.equals(source()) ? panel.rows(page, colony.view(level)) : List.of();
        }

        @Override
        public Map<String, Value> parameters() {
            return Map.of();
        }

        @Override
        public Map<String, Boolean> switches() {
            return Map.of();
        }

        @Override
        public List<String> running() {
            return List.of();
        }

        @Override
        public Optional<Recent> recent() {
            return Optional.empty();
        }

        @Override
        public Optional<String> disabled(String routine, Map<String, Value> enabledWhen) {
            return Optional.empty();
        }

        @Override
        public List<DefinedFacilities.Problem> run(ServerPlayer by, String routine, Map<String, Value> arguments) {
            return panel.act(by, routine, arguments)
                .orElseGet(() -> List.of(new DefinedFacilities.Problem("routine", "manual", routine)));
        }

        @Override
        public List<DefinedFacilities.Problem> setParameter(String parameter, Value value) {
            return List.of(new DefinedFacilities.Problem("parameters." + parameter, "missing", parameter));
        }

        @Override
        public List<DefinedFacilities.Problem> toggle(String trigger, boolean enabled) {
            return List.of(new DefinedFacilities.Problem("triggers." + trigger, "background", trigger));
        }
    }
}
