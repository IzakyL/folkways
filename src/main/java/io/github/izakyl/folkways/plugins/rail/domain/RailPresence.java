package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RailPresence implements Facing {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final int CAB_REACH = Mth.ceil(Reach.PLAYER_BLOCK_REACH);

    private static final Notice RUNNING = new Notice("folkways.page.rail.running", List.of());

    private final Colony colony;

    private final Sweep sweep;

    private final Roster roster = new Roster();

    private final Map<UUID, ConductorSeats> conducting = new LinkedHashMap<>();

    private volatile TransitSnapshot seen = TransitSnapshot.empty();

    private final Map<Berth, Stances.Cells> platforms = new HashMap<>();

    private String said = "";

    RailPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, RailContent.ID, 40);
        roster.load(Reader.of(colony.kept(RailContent.ID)));
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
        sweep.tick(server, this::refresh, this::goals);
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
        Set<UUID> running = new LinkedHashSet<>();
        for (TransitSnapshot.Line line : taken.lines()) {
            if (platformOf(line).isEmpty()) {
                continue;
            }
            running.add(line.train());
            conducting.computeIfAbsent(line.train(), train -> new ConductorSeats(train, sweep::nudge))
                .reconcile(cabs.getOrDefault(line.train(), List.of()));
        }
        conducting.keySet().retainAll(running);
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        for (ConductorSeats seats : conducting.values()) {
            seats.goals(view.level(), goals);
        }
        return goals;
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
        conducting.clear();
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

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(RailContent.PAGE.id())) {
            return Optional.empty();
        }
        TransitSnapshot taken = seen;
        List<Board.Row> rows = new ArrayList<>();
        for (UUID train : roster.all()) {
            rows.add(rowFor(train, lineOf(taken, train)));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.rail.taken",
                Component.literal(Integer.toString(rows.size())))),
            rows,
            Optional.of(Component.translatable("folkways.page.rail.empty"))));
    }

    private static TransitSnapshot.Line lineOf(TransitSnapshot taken, UUID train) {
        for (TransitSnapshot.Line line : taken.lines()) {
            if (line.train().equals(train)) {
                return line;
            }
        }
        return null;
    }

    private static Board.Row rowFor(UUID train, TransitSnapshot.Line line) {
        ItemStack icon = new ItemStack(Items.MINECART);
        if (line == null) {
            return new Board.Row(icon, Component.literal(shortId(train)),
                Component.translatable("folkways.page.rail.idle"), List.of());
        }
        Component where = line.currentStation()
            .map(station -> Component.translatable("folkways.page.rail.stopped", station))
            .orElseGet(() -> Component.translatable("folkways.page.rail.running"));
        Component detail = line.conducted()
            ? where
            : Component.translatable("folkways.page.rail.driverless", where);
        List<Board.Act> acts = new ArrayList<>();
        platformOf(line).ifPresent(pos -> acts.add(new Board.Act.Ping(pos.cell())));
        return new Board.Row(icon, Component.literal(String.join(" → ", line.stations())), detail, acts)
            .told(toldOf(line));
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
