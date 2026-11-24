package io.github.izakyl.folkways.vanilla.create.rail.domain;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Cadence;
import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.shell.create.rail.domain.Berth;
import io.github.izakyl.folkways.core.shell.create.rail.domain.Boarding;
import io.github.izakyl.folkways.core.shell.create.rail.domain.SeatRef;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TrainCrew;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetworks;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitSnapshot;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.ListedFacility;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RailPresence {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final int CAB_REACH = Mth.ceil(Reach.PLAYER_BLOCK_REACH);

    private static final Notice RUNNING = new Notice("folkways.page.rail.running", List.of());

    private final Colony colony;

    private final Cadence.Cron cadence;
    private boolean swept;

    private final Roster roster = new Roster();

    private List<Cab> conducting = List.of();

    private volatile TransitSnapshot seen = TransitSnapshot.empty();

    private final Map<Berth, Stances.Cells> platforms = new HashMap<>();

    private String said = "";

    RailPresence(Colony colony) {
        this.colony = colony;
        this.cadence = Cadence.claim(colony, 40);
        Reader kept = Reader.of(colony.kept(RailContent.ID));
        if (!kept.keys().isEmpty()) {
            roster.load(kept);
        }
    }

    public boolean take(UUID train) {
        if (!roster.take(train)) {
            return false;
        }
        remember();
        return true;
    }

    public boolean release(UUID train) {
        if (!roster.drop(train)) {
            return false;
        }
        remember();
        return true;
    }

    public boolean holds(UUID train) {
        return roster.holds(train);
    }

    public List<UUID> taken() {
        return roster.all();
    }

    public Map<UUID, TrainCrew> crews() {
        Map<UUID, TrainCrew> aboard = new LinkedHashMap<>();
        for (TransitSnapshot.Line line : seen.lines()) {
            aboard.put(line.train(), line.crew());
        }
        return aboard;
    }

    public TransitSnapshot seen() {
        return seen;
    }

    public void tick(MinecraftServer server) {
        if (!swept || cadence.due(server)) {
            swept = true;
            refresh(server);
        }
    }

    void refresh(MinecraftServer server) {
        TransitNetwork network = TransitNetworks.get();
        boolean scrapped = false;
        for (UUID train : roster.all()) {
            if (network.isAvailable() && !network.exists(server, train)) {
                scrapped |= roster.drop(train);
            }
        }
        if (scrapped) {
            remember();
        }
        TransitSnapshot taken = TransitSnapshot.capture(server, Set.copyOf(roster.all()), platforms);
        seen = taken;
        report(taken);
        Map<UUID, List<Cab>> cabs = cabsIn(server, taken);
        Map<SeatRef, Cab> wanted = new LinkedHashMap<>();
        for (TransitSnapshot.Line line : taken.lines()) {
            ConductorSeats.occupiedCabs(server, network, line.train(), colony.id())
                .forEach(cab -> wanted.put(cab.seat(), cab));
            if (platformOf(line).isPresent()) {
                cabs.getOrDefault(line.train(), List.of()).forEach(cab -> wanted.putIfAbsent(cab.seat(), cab));
            }
        }
        conducting = List.copyOf(wanted.values());
    }

    List<Post> posts(ServerLevel level) {
        return conducting.stream().filter(cab -> cab.at().in(level))
            .map(cab -> ConductorSeats.post(level, cab)).toList();
    }

    private Map<UUID, List<Cab>> cabsIn(MinecraftServer server, TransitSnapshot taken) {
        Set<ResourceKey<Level>> ours = new LinkedHashSet<>();
        List<ServerLevel> levels = new ArrayList<>();
        for (ColonyView view : colony.views(server)) {
            if (ours.add(view.dimension())) {
                levels.add(view.level());
            }
        }
        Map<UUID, List<Cab>> cabs = new LinkedHashMap<>();
        for (TransitSnapshot.Line line : taken.lines()) {
            for (TransitSnapshot.Vacancy vacancy : line.vacancies()) {
                for (ServerLevel level : levels) {
                    if (!vacancy.at().in(level)) {
                        continue;
                    }
                    Boarding.footings(level, vacancy.at().cell(), CAB_REACH).ifPresent(stances ->
                        cabs.computeIfAbsent(vacancy.train(), train -> new ArrayList<>())
                            .add(new Cab(vacancy.train(), vacancy.seat(), vacancy.forward(),
                                vacancy.at(), stances)));
                }
            }
        }
        return cabs;
    }

    private void report(TransitSnapshot taken) {
        String line = taken.describe();
        if (!line.equals(said)) {
            said = line;
            LOGGER.info("{}", line);
        }
    }

    public void closed(Closing why) {
        conducting = List.of();
        platforms.clear();
        seen = TransitSnapshot.empty();
        if (why == Closing.RAZED) {
            roster.clear();
            remember();
        }
    }

    private void remember() {
        colony.keep(RailContent.ID, roster.save());
    }

    Optional<TrainFacility> operation(UUID id) {
        return holds(id) ? Optional.of(new TrainFacility(id, this)) : Optional.empty();
    }

    List<TrainFacility> operations() {
        return roster.all().stream().map(id -> new TrainFacility(id, this)).toList();
    }

    record TrainFacility(UUID id, RailPresence presence) implements ListedFacility {

        @Override
        public ResourceLocation kind() {
            return RailContent.ID;
        }

        @Override
        public Component title() {
            TransitSnapshot.Line line = lineOf(presence.seen, id);
            return Component.literal(line == null || line.stations().isEmpty()
                ? shortId(id) : String.join(" → ", line.stations()));
        }

        @Override
        public Optional<ResourceLocation> icon() {
            return Optional.of(ResourceLocation.withDefaultNamespace("minecart"));
        }

        @Override
        public WorldPos site() {
            TransitSnapshot.Line line = lineOf(presence.seen, id);
            return line == null ? ListedFacility.super.site()
                : platformOf(line).orElseGet(ListedFacility.super::site);
        }

        @Override
        public List<Line> lines(ServerLevel level, Map<String, Value> readings) {
            TransitSnapshot.Line line = lineOf(presence.seen, id);
            if (line == null) {
                return List.of(Line.text(Component.translatable("folkways.page.rail.idle")));
            }
            Component where = line.currentStation()
                .map(station -> Component.translatable("folkways.page.rail.stopped", station))
                .orElseGet(() -> Component.translatable("folkways.page.rail.running"));
            Component detail = line.conducted()
                ? where : Component.translatable("folkways.page.rail.driverless", where);
            return List.of(Line.text(detail), Line.told(toldOf(line)));
        }
    }

    private static TransitSnapshot.Line lineOf(TransitSnapshot taken, UUID train) {
        for (TransitSnapshot.Line line : taken.lines()) {
            if (line.train().equals(train)) {
                return line;
            }
        }
        return null;
    }

    // Where the train is in pictures: boarding at a station or driving between them, and "✗ driving" with no driver.
    private static Sentence toldOf(TransitSnapshot.Line line) {
        List<Sentence.Token> tokens = new ArrayList<>();
        if (!line.conducted()) {
            tokens.add(Sentence.glyph("lacks"));
            tokens.add(Sentence.doing(RailContent.DRIVING));
        }
        line.currentStation().ifPresentOrElse(station -> {
            tokens.add(Sentence.doing(RailContent.BOARDING));
            tokens.add(Sentence.word(Notice.text(station)));
        }, () -> tokens.add(line.conducted() ? Sentence.doing(RailContent.DRIVING) : Sentence.word(RUNNING)));
        return new Sentence(tokens);
    }

    private static Optional<WorldPos> platformOf(TransitSnapshot.Line line) {
        return line.currentStation().map(line.platforms()::get)
            .or(() -> line.stations().stream().map(line.platforms()::get)
                .filter(Objects::nonNull).findFirst());
    }

    private static String shortId(UUID train) {
        return train.toString().substring(0, 8);
    }
}
