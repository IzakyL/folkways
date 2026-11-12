package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.notice.Attempt;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;

/** The page an enrolled AE2 or RS2 device opens on: what residents may do there, and the colony's CPU share. */
public final class NetworkPanel {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "network_devices");

    public static final Schema.Setting FILTER = new Schema.Setting.Items("filter", "folkways.settings.network.filter",
        ResourceLocation.withDefaultNamespace("hopper"), List.of());

    public static final PageKind PAGE = PageKind.boarded(ID, "folkways.panel.network",
        ResourceLocation.withDefaultNamespace("ender_chest"));

    private static final String ACT = "network:";
    private static final String LESS = ACT + "cpus_less";
    private static final String MORE = ACT + "cpus_more";
    private static final String RESET = ACT + "cpus_reset";
    private static final List<String> TOGGLES = List.of("put", "take", "order", "commission", "filter_mode");
    private static final List<Function<Colony, Optional<Side>>> SIDES = new CopyOnWriteArrayList<>();

    private NetworkPanel() {
    }

    // What the device's kind allows at all, from the network_access block tags.
    public record Ceiling(boolean put, boolean take, boolean craft) {
    }

    // A network as the colony sees it now: its enrolled devices, how many CPUs (RS2: concurrent tasks) it has and
    // allows by default, and how many the colony's own orders occupy.
    public record Network(List<WorldPos> devices, int total, int byDefault, int used, boolean tasks) {
        public Network {
            devices = List.copyOf(devices);
        }
    }

    public record Probe(WorldPos at, ItemStack icon, Ceiling ceiling, Optional<Component> unusable,
                        Optional<Network> network) {
    }

    /** One integration's view of the colony's enrolled devices. */
    public interface Side {

        Optional<Probe> probe(ServerLevel level, BlockPos at);

        List<Probe> enrolled(ColonyView view);

        DeviceSettings settings();

        // Called after a change, so rules and offers follow it without waiting for the next sweep.
        void changed(ServerLevel level);
    }

    public static void install(IEventBus bus) {
        bus.addListener(Registering.class, event -> {
            event.enrollment(ID, new Enrollment(List.of(), new Schema(List.of(FILTER)), List.of(PAGE)));
            event.facing(ID, colony -> Optional.of(new Panel(colony)));
        });
    }

    public static void side(Function<Colony, Optional<Side>> source) {
        SIDES.add(source);
    }

    public static int limit(DeviceSettings settings, Network network) {
        return settings.cpus(network.devices(), network.total(), network.byDefault());
    }

    private record Found(Side side, Probe probe) {
    }

    private static final class Panel implements Facing {

        private final Colony colony;

        Panel(Colony colony) {
            this.colony = colony;
        }

        private List<Side> sides() {
            return SIDES.stream().flatMap(source -> source.apply(colony).stream()).toList();
        }

        private Optional<Found> found(ResourceLocation page, BlockPos at, ColonyView view) {
            if (!page.equals(ID)) {
                return Optional.empty();
            }
            for (Side side : sides()) {
                Optional<Probe> probe = side.probe(view.level(), at);
                if (probe.isPresent()) {
                    return Optional.of(new Found(side, probe.get()));
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Board> boardAt(ResourceLocation page, BlockPos at, ColonyView view) {
            return found(page, at, view).map(found -> device(found.probe(), found.side().settings()));
        }

        @Override
        public Optional<Board> board(ResourceLocation page, ColonyView view) {
            if (!page.equals(ID)) {
                return Optional.empty();
            }
            List<Board.Row> rows = new ArrayList<>();
            int count = 0;
            for (Side side : sides()) {
                Map<List<WorldPos>, List<Probe>> networks = new LinkedHashMap<>();
                for (Probe probe : side.enrolled(view)) {
                    networks.computeIfAbsent(probe.network().map(Network::devices).orElse(List.of(probe.at())),
                        ignored -> new ArrayList<>()).add(probe);
                    count++;
                }
                networks.values().forEach(group -> rows.addAll(overview(group, side.settings(), view.level())));
            }
            return Optional.of(new Board(List.of(new Board.Figure("folkways.panel.network.devices",
                Component.literal(Integer.toString(count)))), rows,
                Optional.of(Component.translatable("folkways.panel.network.none"))));
        }

        @Override
        public Attempt act(ResourceLocation page, String actionKey, Optional<BlockPos> at, ColonyView view) {
            Optional<Found> found = at.flatMap(pos -> found(page, pos, view));
            if (found.isEmpty()) {
                return Attempt.refused(NetworkRefusal.NOT_A_DEVICE);
            }
            Probe probe = found.get().probe();
            DeviceSettings settings = found.get().side().settings();
            DeviceSettings.Device device = settings.at(probe.at());
            if (actionKey.startsWith(ACT) && TOGGLES.contains(actionKey.substring(ACT.length()))) {
                String what = actionKey.substring(ACT.length());
                if (!offers(probe.ceiling(), what)) {
                    return Attempt.refused(NetworkRefusal.NOT_ALLOWED);
                }
                settings.set(probe.at(), device.toggled(what));
            } else if (probe.network().isPresent() && List.of(LESS, MORE, RESET).contains(actionKey)) {
                Network network = probe.network().get();
                int now = limit(settings, network);
                settings.cpus(network.devices(), actionKey.equals(RESET) ? Optional.empty() : Optional.of(
                    Math.max(0, Math.min(network.total(), now + (actionKey.equals(MORE) ? 1 : -1)))));
            } else {
                return Attempt.refused(NetworkRefusal.NOT_ALLOWED);
            }
            found.get().side().changed(view.level());
            return Attempt.went();
        }

        @Override
        public Optional<List<ItemFilter>> itemFiltersAt(ResourceLocation page, BlockPos at, String key,
                ColonyView view) {
            return found(page, at, view).filter(found -> key.equals(FILTER.key()))
                .map(found -> found.side().settings().at(found.probe().at()).filters());
        }

        @Override
        public Optional<Attempt> setItemFiltersAt(ResourceLocation page, BlockPos at, String key,
                List<ItemFilter> filters, ColonyView view) {
            return found(page, at, view).filter(found -> key.equals(FILTER.key())).map(found -> {
                DeviceSettings settings = found.side().settings();
                settings.set(found.probe().at(), settings.at(found.probe().at()).filtering(filters));
                found.side().changed(view.level());
                return Attempt.went();
            });
        }
    }

    private static boolean offers(Ceiling ceiling, String what) {
        return switch (what) {
            case "put" -> ceiling.put();
            case "take" -> ceiling.take();
            case "order" -> ceiling.craft();
            default -> true;
        };
    }

    // Laid out like a store's own page: the switches first, then the item filter, then what the network allows.
    static Board device(Probe probe, DeviceSettings settings) {
        DeviceSettings.Device device = settings.at(probe.at());
        List<Board.Row> rows = new ArrayList<>();
        rows.add(Board.Row.heading(Component.translatable("folkways.panel.network.access")));
        probe.unusable().ifPresent(why -> rows.add(Board.Row.of(ItemStack.EMPTY, why, Component.empty())
            .note().wide()));
        if (probe.ceiling().take()) {
            rows.add(toggle("take", device.take(), "hopper"));
        }
        if (probe.ceiling().put()) {
            rows.add(toggle("put", device.put(), "chest"));
        }
        if (probe.ceiling().craft()) {
            rows.add(toggle("order", device.order(), "crafting_table"));
        }
        rows.add(toggle("commission", device.commission(), "writable_book"));
        rows.add(Board.Row.heading(Component.translatable("folkways.panel.network.filter.section")));
        rows.add(new Board.Row(ItemStack.EMPTY, Component.empty(), Component.translatable(device.only()
                ? "folkways.panel.network.filter.only.count" : "folkways.panel.network.filter.except.count",
            filtered(device)), List.of(new Board.Act.Edit(FILTER.key()))).field().wide());
        rows.add(new Board.Row(ItemStack.EMPTY, Component.empty(), Component.empty(),
            List.of(new Board.Act.Do(ACT + "filter_mode", device.only()
                ? "folkways.panel.network.filter.mode.only" : "folkways.panel.network.filter.mode.except",
                icon(device.only() ? "hopper" : "iron_bars")))).actions().wide());
        rows.add(Board.Row.of(ItemStack.EMPTY,
            Component.translatable("folkways.panel.network.filter.detail"), Component.empty()).note().wide());
        List<Board.Figure> figures = new ArrayList<>();
        probe.network().ifPresent(network -> {
            rows.add(Board.Row.heading(Component.translatable("folkways.panel.network.network")));
            int limit = limit(settings, network);
            String unit = network.tasks() ? "tasks" : "cpus";
            rows.add(Board.Row.of(ItemStack.EMPTY, cpus(settings, network, limit, unit), Component.empty())
                .note().wide());
            rows.add(new Board.Row(ItemStack.EMPTY, Component.empty(), Component.empty(), List.of(
                new Board.Act.Do(LESS, "folkways.panel.network.less", icon("redstone_torch")),
                new Board.Act.Do(MORE, "folkways.panel.network.more", icon("glowstone_dust")),
                new Board.Act.Do(RESET, "folkways.panel.network.reset", icon("clock")))).actions().wide());
            rows.add(Board.Row.of(ItemStack.EMPTY,
                Component.translatable("folkways.panel.network." + unit + ".detail"), Component.empty())
                .note().wide());
            figures.add(new Board.Figure("folkways.panel.network.devices",
                Component.literal(Integer.toString(network.devices().size()))));
        });
        return new Board(figures, rows, Optional.empty()).form();
    }

    private static Component cpus(DeviceSettings settings, Network network, int limit, String unit) {
        MutableComponent said = Component.translatable("folkways.panel.network." + unit, limit, network.total(),
            network.used());
        if (settings.defaultCpus(network.devices())) {
            said.append(Component.translatable("folkways.panel.network.default"));
        }
        return said;
    }

    private static List<Board.Row> overview(List<Probe> group, DeviceSettings settings, ServerLevel level) {
        List<Board.Row> rows = new ArrayList<>();
        Optional<Network> network = group.getFirst().network();
        // A heading shows no detail, so the network's share follows its name.
        MutableComponent heading = Component.translatable("folkways.panel.network.group", group.size());
        network.ifPresent(found -> heading.append(" · ")
            .append(cpus(settings, found, limit(settings, found), found.tasks() ? "tasks" : "cpus")));
        rows.add(Board.Row.heading(heading));
        for (Probe probe : group) {
            DeviceSettings.Device device = settings.at(probe.at());
            MutableComponent detail = Component.empty();
            for (String what : List.of("take", "put", "order", "commission")) {
                if (offers(probe.ceiling(), what)) {
                    boolean on = switch (what) {
                        case "put" -> device.put();
                        case "take" -> device.take();
                        case "order" -> device.order();
                        default -> device.commission();
                    };
                    detail.append(Component.translatable("folkways.panel.network." + what + (on ? ".on" : ".off")))
                        .append(" ");
                }
            }
            if (device.admit(probe.at()).isPresent()) {
                detail.append(Component.translatable(device.only() ? "folkways.panel.network.filter.only.count"
                    : "folkways.panel.network.filter.except.count", filtered(device)));
            }
            BlockPos cell = probe.at().cell();
            rows.add(new Board.Row(probe.icon(), Component.translatable("folkways.panel.network.device",
                probe.icon().getHoverName(), cell.getX(), cell.getY(), cell.getZ()),
                probe.unusable().orElse(detail), WorldSpaces.storage(level, probe.at())
                    .map(pos -> List.<Board.Act>of(new Board.Act.Ping(pos))).orElse(List.of())).wide());
        }
        return rows;
    }

    // How many items the filter names, counted as the store page counts them.
    private static int filtered(DeviceSettings.Device device) {
        return DeviceSettings.goods(device.filters()).stream().mapToInt(spec -> Goods.members(spec).size()).sum();
    }

    private static Board.Row toggle(String what, boolean enabled, String item) {
        return new Board.Row(ItemStack.EMPTY, Component.empty(), Component.empty(),
            List.of(new Board.Act.Do(ACT + what, "folkways.panel.network." + what + (enabled ? ".on" : ".off"),
                icon(item)))).toggle(enabled);
    }

    private static ResourceLocation icon(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    // Whether residents may order or be offered these goods through this device.
    public static boolean admits(DeviceSettings settings, WorldPos at, ItemSpec goods) {
        return settings.at(at).admits(at, goods);
    }
}
