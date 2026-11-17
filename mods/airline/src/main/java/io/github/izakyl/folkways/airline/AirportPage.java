package io.github.izakyl.folkways.airline;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.front.api.Books;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class AirportPage implements Draw {
    private static final String KEY = "folkways.airline.airport.";
    private final BlockPos fixed;
    private final TextField name = new TextField();
    private static final List<String> PAGE_FIELDS = List.of("scanning", "pending", "incomplete", "catapults");
    private final AirportMap map = new AirportMap();
    private BlockPos selected;
    private BlockPos requested;
    private CompoundTag outgoing = new CompoundTag();
    private int sequence;
    private Airport lastAirport;
    private boolean lastScanning;
    private long lastFacilities = -40;
    private CompoundTag lastReading = new CompoundTag();
    private String drawnName;
    private BlockPos drawnTower;

    public AirportPage() {
        this(null);
    }

    public AirportPage(BlockPos fixed) {
        this.fixed = fixed;
    }

    @Override
    public UIElement build(Player player) {
        name.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        name.setTextRegexValidator(".{0,64}");
        name.textFieldStyle(style -> style.placeholder(Rows.placeholder(Component.translatable(KEY + "name"))));
        Tokens.name(name, KEY + "name");
        Button save = new Button().setText(Component.translatable(KEY + "rename"))
            .setOnClick(event -> queueRename());
        save.layout(layout -> layout.widthAuto().flexShrink(0));
        Tokens.name(save, KEY + "rename");
        Button refresh = button("rescan", () -> selected(player).ifPresent(pos -> Pages.levelOf(player)
            .ifPresent(level -> Airports.request(level, pos))));
        // The whole body is the chart; what used to be listed beneath it is in its tooltips.
        Rows.fills(map).layout(layout -> layout.widthPercent(100));
        Tokens.name(map, KEY + "map");
        UIElement header = Rows.strip();
        if (fixed == null) {
            header.addChildren(button("previous", () -> change(player, -1)));
        }
        header.addChildren(name, save);
        if (fixed == null) {
            header.addChildren(button("next", () -> change(player, 1)));
        }
        header.addChildren(refresh);
        UIElement page = Rows.fills(Rows.page()).addChildren(header, map);
        var renaming = DataBindingBuilder.tagC2S(request -> {
            if (request instanceof CompoundTag compound) {
                applyRename(player, compound);
            }
        }).build();
        renaming.setRemoteDataSource(IDataSource.of(ignored -> {}, () -> outgoing));
        page.addSyncValue(renaming.getSyncValue());
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(this::fill).build().getSyncValue());
        return page;
    }

    private Button button(String action, Runnable run) {
        Button button = new Button().setText(Component.translatable(KEY + action))
            .setOnServerClick(event -> run.run());
        button.layout(layout -> layout.widthAuto().flexShrink(0));
        Tokens.name(button, KEY + action);
        return button;
    }

    private List<BlockPos> held(Player player, ServerLevel level) {
        return Pages.colonyOf(player).map(colony -> colony.view(level).blocks(AirportColony.TOWER).stream()
            .filter(pos -> Airports.validTower(level, pos)).map(pos -> Airports.at(level, pos)
                .map(Airport::tower).orElse(pos)).distinct().sorted(BlockPos::compareTo).toList()).orElseGet(List::of);
    }

    private Optional<BlockPos> selected(Player player) {
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (level.isEmpty()) {
            return Optional.empty();
        }
        if (fixed != null) {
            return Airports.validTower(level.get(), fixed) && player.distanceToSqr(fixed.getCenter()) <= 64
                ? Optional.of(fixed) : Optional.empty();
        }
        List<BlockPos> towers = held(player, level.get());
        Optional<BlockPos> site = Pages.siteOf(player).flatMap(pos -> Airports.at(level.get(), pos)
            .map(Airport::tower)).filter(towers::contains);
        if (site.isPresent()) {
            return site;
        }
        if (selected == null || !towers.contains(selected)) {
            selected = towers.isEmpty() ? null : towers.getFirst();
        }
        return Optional.ofNullable(selected);
    }

    private void change(Player player, int direction) {
        Pages.levelOf(player).ifPresent(level -> {
            List<BlockPos> towers = held(player, level);
            if (!towers.isEmpty() && fixed == null && Pages.siteOf(player).isEmpty()) {
                int index = Math.max(0, towers.indexOf(selected));
                selected = towers.get(Math.floorMod(index + direction, towers.size()));
            }
        });
    }

    private void queueRename() {
        if (drawnTower == null) {
            return;
        }
        CompoundTag request = new CompoundTag();
        request.putLong("tower", drawnTower.asLong());
        request.putString("before", drawnName);
        request.putString("name", name.getText());
        request.putInt("sequence", ++sequence);
        outgoing = request;
    }

    boolean applyRename(Player player, CompoundTag request) {
        Optional<BlockPos> tower = selected(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (!request.contains("tower") || tower.isEmpty() || level.isEmpty()
                || !Airports.at(level.get(), tower.get()).map(Airport::tower)
                    .filter(BlockPos.of(request.getLong("tower"))::equals).isPresent()
                || Airports.at(level.get(), tower.get()).filter(airport -> airport.name().equals(
                    request.getString("before"))).isEmpty()) {
            return false;
        }
        return rename(player, request.getString("name"));
    }

    boolean rename(Player player, String next) {
        Optional<BlockPos> tower = selected(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (tower.isEmpty() || level.isEmpty() || !canRename(player, tower.get())) {
            return false;
        }
        Airports.rename(level.get(), tower.get(), next);
        return true;
    }

    private static boolean canRename(Player player, BlockPos pos) {
        if (!(player instanceof ServerPlayer server) || !Airports.validTower(server.serverLevel(), pos)) {
            return false;
        }
        WorldPos cell = WorldPos.of(server.serverLevel(), pos);
        for (Colony colony : Colonies.all(server.getServer())) {
            if (colony.claimAt(cell).isPresent()) {
                return Books.keeps(server, colony.id());
            }
        }
        return true;
    }

    private Tag reading(Player player) {
        Optional<ServerLevel> level = Pages.levelOf(player);
        Optional<BlockPos> tower = selected(player);
        if (level.isEmpty() || tower.isEmpty()) {
            return new CompoundTag();
        }
        if (!tower.get().equals(requested)) {
            Airports.request(level.get(), tower.get());
            requested = tower.get();
        }
        Airport airport = Airports.at(level.get(), tower.get()).orElseThrow();
        boolean scanning = Airports.scanning(level.get(), tower.get());
        if (!airport.equals(lastAirport) || scanning != lastScanning) {
            lastAirport = airport;
            lastScanning = scanning;
            lastReading = AirportData.write(airport);
            lastFacilities = -40;
            lastReading.putBoolean("scanning", scanning);
            lastReading.putBoolean("pending", AirportRegistry.of(level.get()).pending(tower.get()));
            lastReading.putBoolean("incomplete", airport.flaws().stream()
                .anyMatch(problem -> problem.kind() == AirportFlaw.UNLOADED));
        }
        if (level.get().getGameTime() - lastFacilities >= 40) {
            lastFacilities = level.get().getGameTime();
            var accessories = new ListTag();
            String prefix = WorldSpaces.at(level.get(), airport.tower())
                + "/runway/";
            for (var site : LandingSites.in(level.get())) {
                if (!site.id().startsWith(prefix)) {
                    continue;
                }
                for (var catapult : site.catapults()) {
                    CompoundTag row = new CompoundTag();
                    row.putLong("threshold", site.position().block(level.get()).below().asLong());
                    row.putString("start", catapult.start().cell().toShortString());
                    row.putString("controller", catapult.controller().cell().toShortString());
                    row.putInt("length", catapult.length());
                    row.putInt("power", catapult.power());
                    row.putDouble("speed", catapult.shuttleExitSpeed());
                    accessories.add(row);
                }
            }
            lastReading = lastReading.copy();
            lastReading.put("catapults", accessories);
        }
        return lastReading;
    }

    private void fill(Tag value) {
        CompoundTag tag = value instanceof CompoundTag compound ? compound : new CompoundTag();
        if (!tag.contains("tower")) {
            map.show(null, "", new ListTag());
            name.setValue("", false);
            drawnTower = null;
            drawnName = null;
            return;
        }
        // The reading carries the page's own fields beside the survey; AirportData reads the survey alone.
        CompoundTag survey = tag.copy();
        for (String key : PAGE_FIELDS) {
            survey.remove(key);
        }
        Airport airport;
        try {
            airport = AirportData.read(survey);
        } catch (RuntimeException invalid) {
            map.show(null, "", new ListTag());
            return;
        }
        String state = tag.getBoolean("pending") ? "pending" : tag.getBoolean("scanning") ? "scanning"
            : tag.getBoolean("incomplete") ? "incomplete" : "cached";
        if (!airport.name().equals(drawnName) || !airport.tower().equals(drawnTower)) {
            name.setValue(airport.name(), false);
            drawnName = airport.name();
            drawnTower = airport.tower();
        }
        map.show(airport, state, tag.getList("catapults", Tag.TAG_COMPOUND));
    }
}
