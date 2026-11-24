package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.ZoneRing;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * What the colony holds under the book's delegations, as facilities: a zone, a path, a block or a point. Each is set
 * through its delegation's settings, shows what the kinds registered for its delegation make of it, and can be let go
 * from its card.
 */
public final class HeldFacilities {
    /** The routine every held thing offers: letting it go. */
    public static final String RELEASE = "release";

    private HeldFacilities() {
    }

    public static void install() {
        Operations.register(HeldFacilities::find);
        Operations.listing(HeldFacilities::all);
    }

    public static Optional<Held> find(Colony colony, UUID id) {
        return colony.claim(id)
            .filter(holding -> site(holding).isPresent())
            .flatMap(holding -> Enrollments.delegation(holding.owner())
                .map(delegation -> new Held(colony, holding, delegation)));
    }

    /** The block the colony holds at a clicked cell, as its facility: a container, a workstation, a bed. */
    public static Optional<Held> at(ServerLevel level, Colony colony, net.minecraft.core.BlockPos clicked) {
        return ColonyGround.heldAt(level, colony, ColonySites.anchor(level, clicked))
            .or(() -> ColonyGround.heldAt(level, colony, clicked))
            .flatMap(holding -> find(colony, holding.id()));
    }

    /** Every held thing of the colony that is a facility. */
    public static List<Held> all(Colony colony) {
        List<Held> held = new ArrayList<>();
        for (Claim holding : colony.claims()) {
            if (site(holding).isPresent()) {
                Enrollments.delegation(holding.owner()).ifPresent(delegation ->
                    held.add(new Held(colony, holding, delegation)));
            }
        }
        return held;
    }

    private static Optional<WorldPos> site(Claim holding) {
        return switch (holding.what()) {
            case Claimed.Area area -> area.covered().stream().findFirst();
            case Claimed.Line line -> line.points().stream().findFirst();
            case Claimed.Block block -> Optional.of(block.cell());
            case Claimed.Point point -> Optional.of(point.cell());
            default -> Optional.empty();
        };
    }

    public record Held(Colony colony, Claim holding, Delegation delegation) implements Operating, Facility {

        @Override
        public ResourceLocation kind() {
            return delegation.id();
        }

        @Override
        public UUID id() {
            return holding.id();
        }

        @Override
        public Facility facility() {
            return this;
        }

        private List<HoldingKind> kinds() {
            return Enrollments.kinds(colony, delegation.id());
        }

        /** The held thing as its kinds see it. */
        public HoldingKind.Held view() {
            ColonyFront front = ColonyFront.of(colony);
            return new HoldingKind.Held(colony, holding.id(), delegation.id(), site(),
                holding.what() instanceof Claimed.Area ? front.zone(holding.id()).map(zone -> zone) : Optional.empty(),
                holding.what() instanceof Claimed.Line ? front.path(holding.id()).map(path -> path) : Optional.empty(),
                front.drawn(holding.id()).orElseGet(ColonySettings::empty));
        }

        @Override
        public FacilityExtent extent(ServerLevel level) {
            var kinds = kinds();
            if (kinds.isEmpty() || !site().in(level)) {
                return FacilityExtent.EMPTY;
            }
            var elements = new LinkedHashSet<FacilityExtent.Element>();
            var held = view();
            for (HoldingKind kind : kinds) {
                elements.addAll(kind.extent(held, level).elements());
            }
            return new FacilityExtent(elements);
        }

        @Override
        public Component title() {
            return Component.translatable("folkways.delegation." + delegation.id().getNamespace() + "."
                + delegation.id().getPath());
        }

        @Override
        public Optional<net.minecraft.resources.ResourceLocation> icon() {
            return Optional.of(delegation.icon());
        }

        @Override
        public Card card() {
            List<Card.Element> elements = new ArrayList<>();
            elements.add(new Card.Element.Lines());
            elements.addAll(SchemaCards.elements(delegation.schema()));
            kinds().forEach(kind -> elements.addAll(kind.elements()));
            if (releasable()) {
                elements.add(new Card.Element.Button(RELEASE, Optional.empty(), Map.of(),
                    Optional.of(new Card.Label("folkways.selection.release", true))));
            }
            return new Card(elements);
        }

        private boolean releasable() {
            return holding.what() instanceof Claimed.Area || holding.what() instanceof Claimed.Line;
        }

        @Override
        public WorldPos site() {
            return HeldFacilities.site(holding).orElseThrow();
        }

        @Override
        public Map<String, Routine.Use> routines() {
            return Map.of();
        }

        @Override
        public List<Trigger> triggers() {
            return card().elements().stream().filter(Card.Element.Button.class::isInstance)
                .map(button -> (Trigger) new Trigger.Manual(((Card.Element.Button) button).routine())).toList();
        }

        @Override
        public Map<String, Reading> readings(ServerLevel level) {
            return Map.of();
        }

        @Override
        public Optional<ServerLevel> loaded(MinecraftServer server) {
            return site().level(server).filter(level -> level.isLoaded(site().cell()));
        }

        @Override
        public Map<String, Value> parameters() {
            Card card = card();
            Map<String, Value> values = new LinkedHashMap<>(ColonyFront.of(colony).drawn(holding.id())
                .map(ColonySettings::parameters).orElse(Map.of()));
            HoldingKind.Held view = view();
            for (Card.Element.Parameter parameter : card.parameters()) {
                for (HoldingKind kind : kinds()) {
                    var kept = kind.parameter(view, parameter.param());
                    if (kept.isPresent()) {
                        values.put(parameter.param(), kept.get());
                        break;
                    }
                }
            }
            return card.withFallbacks(values);
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
            HoldingKind.Held view = view();
            for (HoldingKind kind : kinds()) {
                var done = kind.act(view, by, routine, arguments);
                if (done.isPresent()) {
                    return done.get();
                }
            }
            if (routine.equals(RELEASE) && releasable()) {
                return ColonyFront.of(colony).erase(holding.id()) ? List.of()
                    : List.of(new DefinedFacilities.Problem("routine", "refused", routine));
            }
            return List.of(new DefinedFacilities.Problem("routine", "manual", routine));
        }

        @Override
        public List<DefinedFacilities.Problem> setParameter(String parameter, Value value) {
            var element = card().parameter(parameter);
            if (element.isEmpty()) {
                return List.of(new DefinedFacilities.Problem("parameters." + parameter, "missing", parameter));
            }
            if (!element.get().accepts(value)) {
                return List.of(new DefinedFacilities.Problem("parameters." + parameter, "parameter", parameter));
            }
            HoldingKind.Held view = view();
            for (HoldingKind kind : kinds()) {
                var done = kind.setParameter(view, parameter, value);
                if (done.isPresent()) {
                    return done.get();
                }
            }
            Map<String, Value> next = new LinkedHashMap<>(ColonyFront.of(colony).drawn(holding.id())
                .map(ColonySettings::parameters).orElse(Map.of()));
            next.put(parameter, value);
            ColonyFront.of(colony).setDrawn(holding.id(), ColonySettings.ofParameters(next));
            return List.of();
        }

        @Override
        public List<DefinedFacilities.Problem> toggle(String trigger, boolean enabled) {
            return List.of(new DefinedFacilities.Problem("triggers." + trigger, "background", trigger));
        }

        @Override
        public List<Line> lines(ServerLevel level, Map<String, Value> readings) {
            ColonyView view = colony.view(level);
            HoldingKind.Held held = view();
            List<Line> lines = new ArrayList<>();
            kinds().forEach(kind -> lines.addAll(kind.lines(held, view)));
            held.zone().ifPresent(zone -> lines.addAll(ColonyStalls.lines(colony, level, zone.cells()::contains)));
            return lines;
        }

        @Override
        public List<Card.Row> rows(ServerLevel level, String source, Map<String, Value> readings) {
            ColonyView view = colony.view(level);
            HoldingKind.Held held = view();
            List<Card.Row> rows = new ArrayList<>();
            kinds().forEach(kind -> rows.addAll(kind.rows(held, source, view)));
            return rows;
        }

        /** The rings the kinds draw around it. */
        public List<ZoneRing> rings(ColonyView view) {
            HoldingKind.Held held = view();
            List<ZoneRing> rings = new ArrayList<>();
            kinds().forEach(kind -> rings.addAll(kind.rings(held, view)));
            return rings;
        }
    }
}
