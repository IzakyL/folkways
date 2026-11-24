package io.github.izakyl.folkways.core.command.combat.net;

import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.net.CardOrders;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.command.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.Realm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * The command card's way into player facilities. A button runs its routine and a toggle switches its trigger through
 * {@link DefinedFacilities}. The caller has already checked that the player acts for the colony. Only controls
 * shown on the facility's card can be pressed.
 */
public final class FacilityOrders {
    static final String ERROR = "folkways.rts.facility.error.";

    private FacilityOrders() {
    }

    static Vehicles.Result apply(ServerPlayer player, Colony colony, UnitRequest request) {
        Operating operating = Operations.find(colony, request.unit()).orElse(null);
        if (operating == null || operating.facility().site().level(player.getServer())
                .filter(player.serverLevel()::equals).isEmpty()) {
            return refused("missing");
        }
        String name = request.weapon();
        Entity hull = player.serverLevel().getEntity(request.unit());
        var provider = hull == null ? Optional.<Vehicles.Provider>empty() : Vehicles.of(hull);
        var ownership = CombatSelectionOwnership.of(colony);
        if (provider.isPresent() && (!UnitOrders.available(player, colony, hull)
                || !ownership.selectedVehicle(player, hull))) {
            return Vehicles.Result.refused("unauthorized");
        }
        var card = operating.card().elements();
        if (request.operation() == UnitRequest.Operation.FACILITY_TARGET
                && (request.index() < 0 || request.index() >= card.size()
                    || !(card.get(request.index()) instanceof Card.Element.Target))) {
            return refused("hidden");
        }
        CardOrders.Press press = switch (request.operation()) {
            case FACILITY_TARGET -> {
                int index = request.index();
                var target = (Card.Element.Target) card.get(index);
                Optional<Value> value = picked(player.serverLevel(), target.pick(), request);
                if (value.isEmpty()) {
                    yield null;
                }
                yield new CardOrders.Press.Pick(index, name, value.get());
            }
            case FACILITY_CHOICE -> new CardOrders.Press.Choose(request.index(), name, request.seat());
            case FACILITY_PARAMETER -> new CardOrders.Press.Step(request.index(), name, request.seat());
            case FACILITY_RUN -> new CardOrders.Press.Run(indexOf(card, element ->
                element instanceof Card.Element.Button button && button.routine().equals(name)), name);
            default -> request.index() != 0 && request.index() != 1 ? new CardOrders.Press.Run(-1, name)
                : new CardOrders.Press.Toggle(indexOf(card, element ->
                    element instanceof Card.Element.Toggle toggle && toggle.trigger().equals(name)), name,
                    request.index() == 1);
        };
        if (press == null) {
            return refused("pick");
        }
        Optional<Notice> refusal = CardOrders.apply(player, operating, press);
        if (refusal.isEmpty()) {
            return Vehicles.Result.success();
        }
        Notice notice = refusal.get();
        String key = notice.key();
        return key.startsWith("disabled.") ? new Vehicles.Result(false, ERROR + key)
            : key.startsWith(CardOrders.REASON) ? new Vehicles.Result(false, key, notice.args())
            : refused(key);
    }

    private static int indexOf(List<Card.Element> card, java.util.function.Predicate<Card.Element> wanted) {
        for (int index = 0; index < card.size(); index++) {
            if (wanted.test(card.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static Optional<Value> picked(ServerLevel level, Card.Pick pick, UnitRequest request) {
        var order = request.order();
        if (order.target().isPresent() && order.points().isEmpty()) {
            return order.target().map(level::getEntity).filter(Entity::isAlive)
                .filter(entity -> pick.acceptsEntity(entity instanceof ItemEntity))
                .map(entity -> new Value.Text(entity.getUUID().toString()));
        }
        if (order.target().isEmpty() && order.points().size() == 2 && pick == Card.Pick.BOX) {
            var first = order.points().get(0);
            var second = order.points().get(1);
            if (!loaded(level, first) || !loaded(level, second)) {
                return Optional.empty();
            }
            var a = first.cell();
            var b = second.cell();
            return Optional.of(Value.of(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())));
        }
        if (order.target().isEmpty() && order.points().size() == 1 && pick.acceptsPosition()) {
            var point = order.points().getFirst();
            if (point.realm().equals(new Realm.Dimension(level.dimension()))
                && level.isInWorldBounds(point.cell()) && level.hasChunkAt(point.cell())) {
                var cell = point.cell();
                return Optional.of(Value.of(cell.getX(), cell.getY(), cell.getZ()));
            }
        }
        return Optional.empty();
    }

    private static boolean loaded(ServerLevel level, io.github.izakyl.folkways.core.world.api.WorldPos point) {
        return point.realm().equals(new Realm.Dimension(level.dimension()))
            && level.isInWorldBounds(point.cell()) && level.hasChunkAt(point.cell());
    }

    private static Vehicles.Result refused(String reason) {
        return new Vehicles.Result(false, ERROR + switch (reason) {
            case "missing", "hidden", "manual", "unloaded", "refused", "background", "changed", "pick", "parameter" -> reason;
            default -> "invalid";
        });
    }

    /** The colony's facilities in the player's level, as units the card can select. */
    static List<UnitSnapshot.Unit> units(ServerPlayer player, Colony colony) {
        List<UnitSnapshot.Unit> units = new ArrayList<>();
        DefinedFacilities.of(colony).ifPresent(service -> {
            for (FacilityDefinition facility : service.facilities()) {
                if (facility.site().level(player.getServer()).filter(level -> level == player.serverLevel()).isEmpty()) {
                    continue;
                }
                var runtime = service.runtime(facility.id());
                if (runtime.filter(definition -> !definition.listed()).isPresent()) {
                    continue;
                }
                var triggers = service.triggerStates(facility.name());
                Map<String, Value> parameters = new LinkedHashMap<>();
                for (var element : facility.card().elements()) {
                    if (element instanceof Card.Element.Parameter parameter) {
                        Optional.ofNullable(facility.parameters().get(parameter.param()))
                            .ifPresent(value -> parameters.put(parameter.param(), value));
                    }
                }
                units.add(new UnitSnapshot.Unit(facility.id(), UnitSnapshot.Kind.FACILITY,
                    runtime.map(definition -> definition.title()).orElseGet(() -> Component.literal(facility.name())),
                    facility.anchor(), Optional.of(colony.id()), List.of(), List.of(),
                    new Vehicles.Capabilities(false, Vehicles.Airframe.GROUND, false, List.of()),
                    new Vehicles.Status(0, 0, 0, 0, Map.of(), ""), Map.of(), Map.of(), Optional.empty(), false,
                    Vehicles.Altitude.DEFAULT, Optional.of(new UnitSnapshot.FacilityUnit(facility.extent(), facility.card(),
                        triggers, parameters))));
            }
        });
        return units;
    }

    /** Whether a selected id names one of the colony's facilities in the player's level. */
    public static boolean selectable(ServerPlayer player, Colony colony, UUID id) {
        return DefinedFacilities.of(colony).flatMap(service -> service.facility(id))
            .flatMap(facility -> facility.site().level(player.getServer()))
            .filter(level -> level == player.serverLevel()).isPresent();
    }

    /** What the selecting player sees live: only the readings the card names, disabled buttons, the runs. */
    public static Optional<CardStatus> status(MinecraftServer server, Colony colony, UUID id) {
        return Operations.find(colony, id).map(operating -> CardStatus.of(server, colony, operating));
    }
}
