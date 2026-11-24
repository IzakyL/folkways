package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.colony.SchemaCards;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Cards for the colony’s enrolled AE2/RS2 network devices. */
public final class NetworkPanel {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "network_devices");

    public static final Schema.Setting FILTER = new Schema.Setting.Items("filter", "folkways.settings.network.filter",
        ResourceLocation.withDefaultNamespace("hopper"), List.of());

    private static final ResourceLocation STORE = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "store");
    private static final String DEVICE_LINK = "network_device";
    private static final String LESS = "cpus_less";
    private static final String MORE = "cpus_more";
    private static final String RESET = "cpus_reset";
    private static final List<String> FLAGS = List.of("put", "take", "order", "commission");
    private static final List<Function<Colony, Optional<Side>>> SIDES = new CopyOnWriteArrayList<>();

    private NetworkPanel() {
    }

    // What the AE2/RS2 network device's kind allows at all, from the network_access block tags.
    public record Ceiling(boolean put, boolean take, boolean craft) {
    }

    // An AE2/RS2 network as the colony sees it now: its enrolled access points, how many CPUs (RS2: tasks) it has and
    // allows by default, and how many the colony's own orders occupy.
    public record Network(List<WorldPos> networkDevices, int total, int byDefault, int used, boolean tasks) {
        public Network {
            networkDevices = List.copyOf(networkDevices);
        }
    }

    public record Probe(WorldPos at, ItemStack icon, Ceiling ceiling, Optional<Component> unusable,
                        Optional<Network> network) {
    }

    /** One integration's view of the colony's enrolled AE2/RS2 network devices. */
    public interface Side {

        Optional<Probe> probe(ServerLevel level, BlockPos at);

        List<Probe> enrolled(ColonyView view);

        NetworkDeviceSettings settings();

        // Called after a change, so rules and offers follow it without waiting for the next sweep.
        void changed(ServerLevel level);
    }

    public static void install(IEventBus bus) {
        bus.addListener(Registering.class, event -> {
            event.enrollment(ID, new Enrollment(List.of(), new Schema(List.of(FILTER))));
            event.holding(STORE, colony -> Optional.of(new HoldingKind() {
                @Override
                public List<Card.Element> elements() {
                    return List.of(new Card.Element.Rows(DEVICE_LINK, Optional.empty()));
                }

                @Override
                public List<Card.Row> rows(Held held, String source, ColonyView view) {
                    if (!source.equals(DEVICE_LINK) || !held.site().in(view.level())) {
                        return List.of();
                    }
                    return link(colony, view.level(), held.site().block(view.level())).stream().toList();
                }
            }));
        });
        Operations.register((colony, id) -> devices(colony).stream().filter(device -> device.id().equals(id)).findFirst());
        Operations.listing(NetworkPanel::devices);
    }

    public static void side(Function<Colony, Optional<Side>> source) {
        SIDES.add(source);
    }

    public static int limit(NetworkDeviceSettings settings, Network network) {
        return settings.cpus(network.networkDevices(), network.total(), network.byDefault());
    }

    private static List<Device> devices(Colony colony) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of();
        }
        List<Device> devices = new ArrayList<>();
        for (Side side : sides(colony)) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Probe probe : side.enrolled(colony.view(level))) {
                    devices.add(new Device(colony, side, probe));
                }
            }
        }
        return List.copyOf(devices);
    }

    private static List<Side> sides(Colony colony) {
        return SIDES.stream().flatMap(source -> source.apply(colony).stream()).toList();
    }

    /** A link from a held storage block to the controls for its published network device. */
    private static Optional<Card.Row> link(Colony colony, ServerLevel level, BlockPos at) {
        for (Side side : sides(colony)) {
            Optional<Probe> probe = side.probe(level, at);
            if (probe.isPresent()) {
                Device device = new Device(colony, side, probe.get());
                return Optional.of(Card.Row.of(probe.get().icon(),
                    Line.text(Component.translatable("folkways.settings.scope.folkways_core.network_devices")),
                    List.of()).with(new Card.Act.Open(device.id(), label("folkways.action.open"))));
            }
        }
        return Optional.empty();
    }

    private record Device(Colony colony, Side side, Probe probe) implements Operating, Facility {

        @Override
        public ResourceLocation kind() {
            return ID;
        }

        @Override
        public UUID id() {
            return UUID.nameUUIDFromBytes((ID + "/" + colony.id() + "/" + site()).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Facility facility() {
            return this;
        }

        @Override
        public FacilityExtent extent(ServerLevel level) {
            return Facilities.container(level, site()).map(container -> container.extent(level))
                .orElse(FacilityExtent.EMPTY);
        }

        @Override
        public WorldPos site() {
            return probe.at();
        }

        @Override
        public Component title() {
            BlockPos at = site().cell();
            return Component.translatable("folkways.panel.network.device", probe.icon().getHoverName(),
                at.getX(), at.getY(), at.getZ());
        }

        @Override
        public Optional<ResourceLocation> icon() {
            return Optional.of(BuiltInRegistries.ITEM.getKey(probe.icon().getItem()));
        }

        @Override
        public Card card() {
            List<Card.Element> elements = new ArrayList<>();
            elements.add(new Card.Element.Lines());
            elements.add(new Card.Element.Heading(label("folkways.panel.network.access"), Card.Layout.ROW));
            for (String key : FLAGS) {
                if (offers(probe.ceiling(), key)) {
                    elements.add(new Card.Element.Flag(key, Optional.empty(),
                        Optional.of(label("folkways.panel.network." + key)), true));
                }
            }
            elements.add(new Card.Element.Heading(label("folkways.panel.network.filter.section"), Card.Layout.ROW));
            elements.add(new Card.Element.Choice("filter_mode", List.of("folkways_core:filter/except",
                "folkways_core:filter/only"), List.of(), List.of(), Optional.empty(),
                Optional.of(label("folkways.panel.filter.mode")), Optional.empty(), Optional.empty(), Map.of()));
            elements.addAll(SchemaCards.elements(new Schema(List.of(FILTER))));
            elements.add(new Card.Element.Note(label("folkways.panel.network.filter.detail")));
            if (probe.network().isPresent()) {
                elements.add(new Card.Element.Heading(label("folkways.panel.network.network")));
                elements.add(new Card.Element.Rows("share", Optional.empty()));
                String unit = probe.network().get().tasks() ? "tasks" : "cpus";
                elements.add(new Card.Element.Note(label("folkways.panel.network." + unit + ".detail")));
            }
            return new Card(elements);
        }

        @Override
        public List<Line> lines(ServerLevel level, Map<String, Value> readings) {
            List<Line> lines = new ArrayList<>();
            probe.unusable().ifPresent(why -> lines.add(Line.text(why)));
            var device = side.settings().at(site());
            int count = NetworkDeviceSettings.goods(device.filters()).stream()
                .mapToInt(spec -> Goods.members(spec).size()).sum();
            lines.add(Line.text(Component.translatable(device.only() ? "folkways.panel.network.filter.only.count"
                : "folkways.panel.network.filter.except.count", count)));
            return List.copyOf(lines);
        }

        @Override
        public List<Card.Row> rows(ServerLevel level, String source, Map<String, Value> readings) {
            if (!source.equals("share") || probe.network().isEmpty()) {
                return List.of();
            }
            Network network = probe.network().get();
            NetworkDeviceSettings settings = side.settings();
            String unit = network.tasks() ? "tasks" : "cpus";
            return List.of(Card.Row.of(ItemStack.EMPTY, Line.text(cpus(settings, network, limit(settings, network), unit)),
                List.of(Line.text(Component.translatable("folkways.panel.network.device_count",
                    network.networkDevices().size()))))
                .with(action(LESS, "less", "redstone_torch"))
                .with(action(MORE, "more", "glowstone_dust"))
                .with(action(RESET, "reset", "clock")));
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
            return site().level(server).filter(level -> level.isLoaded(site().block(level)));
        }

        @Override
        public Map<String, Value> parameters() {
            var settings = side.settings().at(site());
            Map<String, Value> values = new LinkedHashMap<>();
            for (String key : FLAGS) {
                if (offers(probe.ceiling(), key)) {
                    values.put(key, Value.of(enabled(settings, key)));
                }
            }
            values.put(FILTER.key(), new Value.Items(settings.filters()));
            values.put("filter_mode", new Value.Text(settings.only() ? "folkways_core:filter/only"
                : "folkways_core:filter/except"));
            return Map.copyOf(values);
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
            Optional<ServerLevel> level = loaded(by.serverLevel().getServer());
            Optional<Network> network = level.flatMap(in -> side.probe(in, site().block(in))).flatMap(Probe::network);
            if (network.isEmpty() || !List.of(LESS, MORE, RESET).contains(routine)) {
                return List.of(new DefinedFacilities.Problem("routine", "manual", routine));
            }
            Network share = network.get();
            NetworkDeviceSettings settings = side.settings();
            int now = limit(settings, share);
            settings.cpus(share.networkDevices(), routine.equals(RESET) ? Optional.empty() : Optional.of(
                Math.clamp(now + (routine.equals(MORE) ? 1 : -1), 0, share.total())));
            side.changed(level.orElseThrow());
            return List.of();
        }

        @Override
        public List<DefinedFacilities.Problem> setParameter(String key, Value value) {
            Optional<ServerLevel> level = Optional.ofNullable(ServerLifecycleHooks.getCurrentServer())
                .flatMap(this::loaded);
            if (level.flatMap(in -> side.probe(in, site().block(in))).isEmpty()) {
                return List.of(new DefinedFacilities.Problem(key, "unloaded", key));
            }
            if (card().parameter(key).filter(parameter -> parameter.accepts(value)).isEmpty()) {
                return List.of(new DefinedFacilities.Problem(key, "parameter", key));
            }
            NetworkDeviceSettings settings = side.settings();
            var before = settings.at(site());
            var next = switch (value) {
                case Value.Items items -> before.filtering(items.filters());
                case Value.Bool flag -> enabled(before, key) == flag.on() ? before : before.toggled(key);
                case Value.Text text -> before.only() == text.value().equals("folkways_core:filter/only")
                    ? before : before.toggled("filter_mode");
                default -> before;
            };
            if (!before.equals(next)) {
                settings.set(site(), next);
                side.changed(level.orElseThrow());
            }
            return List.of();
        }

        @Override
        public List<DefinedFacilities.Problem> toggle(String trigger, boolean enabled) {
            return List.of(new DefinedFacilities.Problem("triggers." + trigger, "background", trigger));
        }
    }

    private static boolean enabled(NetworkDeviceSettings.NetworkDevice settings, String key) {
        return switch (key) {
            case "put" -> settings.put();
            case "take" -> settings.take();
            case "order" -> settings.order();
            case "commission" -> settings.commission();
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static Card.Label label(String key) {
        return new Card.Label(key, true);
    }

    private static Card.Act.Run action(String routine, String label, String icon) {
        return new Card.Act.Run(routine, Map.of(), label("folkways.panel.network." + label),
            Optional.of(ResourceLocation.withDefaultNamespace(icon)));
    }

    private static boolean offers(Ceiling ceiling, String what) {
        return switch (what) {
            case "put" -> ceiling.put();
            case "take" -> ceiling.take();
            case "order" -> ceiling.craft();
            default -> true;
        };
    }

    private static Component cpus(NetworkDeviceSettings settings, Network network, int limit, String unit) {
        MutableComponent said = Component.translatable("folkways.panel.network." + unit, limit, network.total(),
            network.used());
        if (settings.defaultCpus(network.networkDevices())) {
            said.append(Component.translatable("folkways.panel.network.default"));
        }
        return said;
    }

    // Whether residents may order or be offered these goods through this AE2/RS2 network device.
    public static boolean admits(NetworkDeviceSettings settings, WorldPos at, ItemSpec goods) {
        return settings.at(at).admits(at, goods);
    }
}
