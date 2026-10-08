package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

// What the book draws on a colony: the areas and lines it holds for the plugins that drew them, and the settings
// a player chose for each, and for the colony as a whole. Where they lie is the colony's; the settings are kept here.
public final class ColonyFront implements FrontView {

    public static final ResourceLocation OWNER =
        Fronts.OWNER;

    private static final String TAG_DRAWN = "drawn";
    private static final String TAG_SETTINGS = "settings";

    private static final Map<UUID, ColonyFront> HELD = new ConcurrentHashMap<>();

    private final Colony colony;
    private final Map<UUID, ColonySettings> drawn = new LinkedHashMap<>();
    private final Map<ResourceLocation, ColonySettings> settings = new LinkedHashMap<>();

    private ColonyFront(Colony colony) {
        this.colony = colony;
        settings.putAll(ColonySchemas.fresh());
    }

    public static ColonyFront of(Colony colony) {
        ColonyFront held = HELD.get(colony.id());
        if (held != null && held.colony == colony) {
            return held;
        }
        ColonyFront afresh = read(colony);
        HELD.put(colony.id(), afresh);
        return afresh;
    }

    public static void forget(UUID colony) {
        HELD.remove(colony);
    }

    public static void forgetAll() {
        HELD.clear();
    }

    public long stocked(MinecraftServer server, ItemSpec what) {
        return ColonyStocks.available(colony, server, what);
    }

    public List<ColonyZone> zonesIn(ServerLevel level) {
        List<ColonyZone> found = new ArrayList<>();
        for (Holding holding : colony.holdings()) {
            if (holding.what() instanceof Held.Area) {
                ColonyZone.in(holding, level, settingsOf(holding)).ifPresent(found::add);
            }
        }
        return List.copyOf(found);
    }

    // Every zone, each in the level its first cell lies in.
    public List<ColonyZone> zones() {
        List<ColonyZone> found = new ArrayList<>();
        for (Holding holding : colony.holdings()) {
            resolved(holding).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    public Optional<ColonyZone> zone(UUID zone) {
        return colony.holding(zone).flatMap(this::resolved);
    }

    private Optional<ColonyZone> resolved(Holding holding) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !(holding.what() instanceof Held.Area)) {
            return Optional.empty();
        }
        return holding.what().cells().getFirst().level(server)
            .flatMap(level -> ColonyZone.in(holding, level, settingsOf(holding)));
    }

    public Optional<ColonyZone> addZone(ServerLevel level, ResourceLocation delegation, Set<BlockPos> cells,
            ColonySettings chosen) {
        Set<WorldPos> covered = new LinkedHashSet<>(cells.size());
        cells.forEach(cell -> covered.add(WorldPos.of(level, cell)));
        return colony.hold(delegation, new Held.Area(covered)).flatMap(holding -> {
            drawn.put(holding.id(), chosen);
            write();
            return ColonyZone.in(holding, level, chosen);
        });
    }

    public List<ColonyPath> paths() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of();
        }
        List<ColonyPath> found = new ArrayList<>();
        for (Holding holding : colony.holdings()) {
            if (holding.what() instanceof Held.Line) {
                ColonyPath.of(holding, server, settingsOf(holding)).ifPresent(found::add);
            }
        }
        return List.copyOf(found);
    }

    public Optional<ColonyPath> path(UUID id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return colony.holding(id).flatMap(holding -> ColonyPath.of(holding, server, settingsOf(holding)));
    }

    public Optional<ColonyPath> addPath(ServerLevel level, ResourceLocation delegation, List<BlockPos> points,
            ColonySettings chosen) {
        List<WorldPos> line = new ArrayList<>(points.size());
        points.forEach(point -> line.add(WorldPos.of(level, point)));
        return colony.hold(delegation, new Held.Line(line)).flatMap(holding -> {
            drawn.put(holding.id(), chosen);
            write();
            return ColonyPath.of(holding, level.getServer(), chosen);
        });
    }

    // Replaces the settings drawn on a zone or a path.
    public boolean setDrawn(UUID id, ColonySettings chosen) {
        Optional<Holding> holding = colony.holding(id);
        if (holding.isEmpty() || chosen.equals(settingsOf(holding.get()))) {
            return false;
        }
        drawn.put(id, chosen);
        write();
        return true;
    }

    // Lets go a zone or a path the book drew.
    public boolean erase(UUID id) {
        Optional<Holding> holding = colony.holding(id)
            .filter(held -> held.what() instanceof Held.Area || held.what() instanceof Held.Line);
        if (holding.isEmpty()) {
            return false;
        }
        colony.release(id, Release.LET_GO);
        drawn.remove(id);
        write();
        return true;
    }

    private ColonySettings settingsOf(Holding holding) {
        ColonySettings chosen = drawn.get(holding.id());
        return chosen != null ? chosen : ColonySettings.byDefault(ColonySchemas.ofDelegation(holding.owner()));
    }

    public ColonySettings settings(ResourceLocation scope) {
        ColonySettings held = settings.get(scope);
        return held == null ? ColonySettings.empty() : held;
    }

    public boolean setSetting(ResourceLocation scope, String key, ColonySettings.Value value) {
        ColonySettings held = settings.get(scope);
        if (held == null) {
            return false;
        }
        ColonySettings.Value standing = held.valueOf(key).orElse(null);
        if (standing == null || standing.getClass() != value.getClass()) {
            return false;
        }
        ColonySettings next = held.with(key, value).conformedTo(ColonySchemas.of(scope));
        if (next.equals(held)) {
            return false;
        }
        settings.put(scope, next);
        write();
        return true;
    }

    private void write() {
        drawn.keySet().removeIf(id -> colony.holding(id).isEmpty());
        colony.keep(OWNER, save());
    }

    private CompoundTag save() {
        Writer writer = Writer.of();
        Writer byHolding = writer.child(TAG_DRAWN);
        drawn.forEach((id, held) -> byHolding.blob(id.toString(), held.save()));
        Writer scopes = writer.child(TAG_SETTINGS);
        settings.forEach((scope, held) -> scopes.blob(scope.toString(), held.save()));
        return writer.tag();
    }

    private static ColonyFront read(Colony colony) {
        ColonyFront front = new ColonyFront(colony);
        Reader reader = Reader.of(colony.kept(OWNER));
        reader.child(TAG_DRAWN).ifPresent(byHolding -> {
            for (String key : byHolding.keys()) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException notAnId) {
                    continue;
                }
                Optional<Holding> holding = colony.holding(id);
                if (holding.isEmpty()) {
                    continue;
                }
                ResourceLocation delegation = holding.get().owner();
                byHolding.child(key).map(ColonySettings::load).ifPresent(loaded -> front.drawn.put(id,
                    Enrollments.delegation(delegation).isPresent()
                        ? loaded.conformedTo(ColonySchemas.ofDelegation(delegation)) : loaded));
            }
        });
        reader.child(TAG_SETTINGS).ifPresent(scopes -> front.settings.replaceAll(
            (scope, byDefault) -> scopes.child(scope.toString())
                .map(ColonySettings::load)
                .map(loaded -> loaded.conformedTo(ColonySchemas.of(scope)))
                .orElse(byDefault)));
        return front;
    }
}
