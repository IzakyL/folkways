package io.github.izakyl.folkways.core.command.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.card.client.CommandButton;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.command.combat.net.ClientUnits;
import io.github.izakyl.folkways.core.command.combat.net.UnitSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The rows of the selection's windows. The command card has a movement group fitted to the selection's airframes
 * (and, for aircraft, the altitude band), then a framed crew group (board, disembark, profile, release when idle);
 * health and energy are drawn in the world. The details window holds what is set once and rarely read: seats, weapon rules, stock and members.
 */
final class UnitPanel implements UnitWindow.Panel {
    static final String KEY = "folkways.rts.unit.";
    private static final int GOOD = 0xFF557A46;
    private static final int BAD = 0xFF9A3324;
    private static final int RED = 0x9A3324;
    private static final int RANGE_STEP = 16;
    private static final int RANGE_MAX = 256;
    private static final float TITLE_AND_FRAME = 22 + 22;
    private static final int FACES = 8;
    private static final float FACE = 18;
    private static final int GAP = 2;
    // Wide enough for the release setting inside the framed crew group.
    private static final float MIN_WIDTH = 164;
    // The window's frame and the scroll view's padding around the card's rows.
    private static final float FRAME_WIDTH = 34;
    private static final Map<Vehicles.Action, net.minecraft.world.item.Item> ICONS = Map.of(
        Vehicles.Action.LAND, Items.FEATHER, Vehicles.Action.LOITER, Items.COMPASS,
        Vehicles.Action.DECOY, Items.FIREWORK_STAR, Vehicles.Action.SPRINT, Items.SUGAR);
    private static final String EDIT_PROFILES = "edit_profiles";

    private final UnitWindow owner;
    private final Optional<UnitSnapshot.Unit> focus;
    private final UnitSnapshot.Unit unit;
    private final List<UnitSnapshot.Unit> vehicles;
    private final Set<UUID> people;
    private final List<UnitSnapshot.Resident> residents;
    private ScrollerView scroller;
    private float height;
    private int buttons;
    private final Map<String, Runnable> actions = new java.util.HashMap<>();

    UnitPanel(UnitWindow owner, Optional<UnitSnapshot.Unit> focus, List<UnitSnapshot.Unit> vehicles,
              Set<UUID> people) {
        this.owner = owner;
        this.focus = focus;
        this.unit = focus.orElse(null);
        this.vehicles = vehicles;
        this.people = people;
        this.residents = ClientUnits.snapshot().map(UnitSnapshot::residents).orElse(List.of());
    }

    @Override
    public Component title() {
        if (unit != null && unit.kind() == UnitSnapshot.Kind.SQUAD) {
            return Component.translatable(KEY + "squad_title", unit.members().size());
        }
        if (unit != null) {
            return unit.name();
        }
        return Component.translatable(KEY + "selected", people.size() + vehicles.size());
    }

    @Override
    public float preferredHeight() {
        return height + TITLE_AND_FRAME;
    }

    public float scroll() {
        return scroller == null ? 0 : scroller.verticalScroller.getValue();
    }

    public void scroll(float value) {
        if (scroller != null && value > 0) {
            scroller.verticalScroller.setValue(value);
        }
    }

    // ---------------------------------------------------------------- command card

    @Override
    public UIElement commands() {
        UIElement page = Rows.page();
        page.addChildren(faces());
        UnitWindow.Host host = owner.host();
        boolean anyVehicle = !vehicles.isEmpty();

        // Where to go comes first and depends on what is selected: ground vehicles drive, helicopters hover and land,
        // fixed wing loiter and land. Only what every selected vehicle can do is offered.
        UIElement movement = Rows.strip();
        movement.layout(layout -> layout.gapAll(GAP).flexWrap(FlexWrap.NO_WRAP));
        Tokens.name(movement, KEY + "movement");
        for (String order : orders()) {
            movement.addChildren(switch (order) {
                case "move" -> movement("move", Items.LEATHER_BOOTS, CombatController.Command.MOVE, false, host);
                case "attack" -> movement("attack", Items.IRON_SWORD, CombatController.Command.ATTACK, true, host);
                case "stop" -> stopButton(host);
                default -> special(Vehicles.Action.valueOf(order.toUpperCase(Locale.ROOT)), host);
            });
        }
        // A squad keeps settings of its own; a vehicle's come from its profile, picked in the crew group.
        if (unit != null && unit.kind() == UnitSnapshot.Kind.SQUAD) {
            CommandButton more = icon("details", Items.BOOK, Component.translatable(KEY + "details"),
                Component.translatable(KEY + "details.tip"));
            if (owner.detailsOpen()) {
                more.addClass("folkways_chosen");
            }
            act(more, "details", owner::toggleDetails);
            movement.addChildren(more);
        }
        if (!movement.getChildren().isEmpty()) {
            page.addChildren(movement);
            buttons = Math.max(buttons, movement.getChildren().size());
            grow(CommandButton.SIZE + 5);
        }
        if (anyVehicle && vehicles.stream().allMatch(vehicle -> vehicle.capabilities().flying())) {
            page.addChildren(altitude());
        }
        // Weapons (strikes on a point, a line or an area) will get a group of their own here, between where to go
        // and who rides.

        // Who rides: its own framed group, apart from the orders above.
        UIElement crew = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .widthPercent(100).paddingAll(3).gapAll(3).flexShrink(0));
        crew.addClass("folkways_card");
        Tokens.name(crew, KEY + "crew");
        UIElement seats = Rows.strip();
        seats.layout(layout -> layout.gapAll(GAP).flexWrap(FlexWrap.NO_WRAP));
        CommandButton board = icon("board", Items.MINECART, Component.translatable(KEY + "board"),
            Component.translatable(people.isEmpty() ? KEY + "board.tip" : KEY + "board.pick.tip"));
        if (host.armed() == CombatController.Command.BOARD) {
            board.addClass("folkways_chosen");
        }
        act(board, "board", () -> {
            if (people.isEmpty()) {
                vehicles.forEach(vehicle -> owner.track(ClientUnits.board(vehicle.id(), List.of()), vehicle.id()));
            } else {
                host.arm(CombatController.Command.BOARD);
            }
        });
        seats.addChildren(board);
        if (anyVehicle) {
            CommandButton leave = icon("disembark", Items.OAK_DOOR, Component.translatable(KEY + "disembark"),
                Component.translatable(KEY + "disembark.tip"));
            leave.setActive(vehicles.stream().anyMatch(vehicle -> vehicle.seats().stream()
                .anyMatch(seat -> seat.occupant().isPresent())));
            act(leave, "disembark", () -> vehicles.forEach(vehicle ->
                owner.track(ClientUnits.leave(vehicle.id(), -1), vehicle.id())));
            seats.addChildren(leave);
            CommandButton giveUp = icon("release_ownership", Items.WHITE_BANNER,
                Component.translatable(KEY + "release_ownership"),
                Component.translatable(KEY + "release_ownership.tip"));
            giveUp.setActive(vehicles.stream().allMatch(vehicle -> vehicle.owner().isPresent()));
            act(giveUp, "release_ownership", () -> vehicles.forEach(vehicle ->
                owner.track(ClientUnits.releaseOwnership(vehicle.id()), vehicle.id())));
            seats.addChildren(giveUp);
        }
        if (vehicles.size() == 1) {
            seats.addChildren(profileButton(vehicles.getFirst()));
        }
        crew.addChildren(seats);
        buttons = Math.max(buttons, seats.getChildren().size());
        // The framed group's padding and gap around its row.
        grow(CommandButton.SIZE + 6 + 5);
        if (anyVehicle) {
            Toggle release = new Toggle().setText(Component.translatable(KEY + "release_idle"));
            Tokens.name(release, KEY + "release_idle");
            release.setOn(vehicles.stream().allMatch(UnitSnapshot.Unit::releaseWhenIdle), false);
            release.style(style -> style.tooltips(Component.translatable(KEY + "release_idle.tip")));
            release.setOnToggleChanged(on -> vehicles.forEach(vehicle ->
                owner.track(ClientUnits.releaseWhenIdle(vehicle.id(), on), vehicle.id())));
            crew.addChildren(release);
            grow(18);
        }
        page.addChildren(crew);
        // The page's own padding above and below.
        grow(12);
        scroller = UnitWindow.scroller();
        scroller.addScrollViewChild(page);
        Tokens.name(scroller, KEY + "body");
        return scroller;
    }

    /** The card is as wide as its longest row of buttons, and never narrower than the release setting's label. */
    @Override
    public float preferredWidth() {
        float row = buttons * (CommandButton.SIZE + GAP) - GAP;
        float faces = Math.min(FACES, people.size() + vehicles.size()) * (FACE + 2) + 16;
        return Math.max(MIN_WIDTH, Math.max(row, faces) + FRAME_WIDTH);
    }

    @Override
    public boolean press(String token) {
        Runnable action = actions.get(token);
        if (action == null) {
            return false;
        }
        action.run();
        return true;
    }

    private CommandButton icon(String name, net.minecraft.world.item.Item item, Component title, Component tip) {
        CommandButton press = new CommandButton(new ItemStack(item)).key(CombatKeys.CARD.get(name));
        Tokens.name(press, KEY + name);
        press.style(style -> style.tooltips(named(name, title), tip));
        return press;
    }

    /** The button's name with its hotkey, as the title line of its tooltip. */
    private static Component named(String name, Component title) {
        var key = CombatKeys.CARD.get(name);
        return key == null ? title : title.copy().append(Component.literal(" [").append(key.getTranslatedKeyMessage())
            .append("]").withStyle(ChatFormatting.GRAY));
    }

    /** What a left click on the button does, also run by its hotkey. */
    private void act(CommandButton press, String name, Runnable action) {
        if (press.isActive()) {
            actions.put(name, action);
        }
        press.setOnClick(event -> {
            if (event.button == 0) {
                action.run();
            }
        });
    }

    /**
     * Move or Attack: a left click arms it for the next world click; a right click makes it what a plain
     * right-click on the ground does, marked by a gold frame. M and G arm Move and Attack from the keyboard.
     */
    private CommandButton movement(String name, net.minecraft.world.item.Item item, CombatController.Command command,
                                   boolean attack, UnitWindow.Host host) {
        boolean byDefault = host.attackMoveDefault() == attack;
        CommandButton press = icon(name, item, Component.translatable(KEY + name),
            Component.translatable(KEY + name + ".tip"));
        press.byDefault(byDefault);
        press.style(style -> style.tooltips(named(name, Component.translatable(KEY + name)),
            Component.translatable(KEY + name + ".tip"),
            Component.translatable(byDefault ? KEY + "default.on" : KEY + "default.set")
                .withStyle(ChatFormatting.GOLD)));
        if (host.armed() == command) {
            press.addClass("folkways_chosen");
        }
        act(press, name, () -> host.arm(command));
        press.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (event.button == 1) {
                host.attackMoveDefault(attack);
            }
        });
        return press;
    }

    /** The selection at a glance: one face per resident or vehicle, a click narrows the selection to it. */
    private UIElement faces() {
        UIElement strip = Rows.strip();
        strip.layout(layout -> layout.gapAll(2).minHeight(FACE));
        List<UUID> shown = new ArrayList<>();
        vehicles.forEach(vehicle -> shown.add(vehicle.id()));
        shown.addAll(people);
        for (UUID id : shown.subList(0, Math.min(FACES, shown.size()))) {
            boolean vehicle = vehicles.stream().anyMatch(entry -> entry.id().equals(id));
            Button face = new Button().noText();
            face.addClass("folkways_nav");
            face.layout(layout -> layout.width(FACE).height(FACE).paddingAll(0).flexShrink(0));
            Tokens.name(face, KEY + "face." + id);
            face.addChildren(new UnitFace(id, (int) FACE, false));
            Component name = vehicle ? vehicles.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .map(UnitSnapshot.Unit::name).orElse(Component.empty()) : occupantName(id);
            face.style(style -> style.tooltips(name, Component.translatable(KEY + "member.select")));
            face.setOnClick(event -> {
                if (vehicle) {
                    owner.host().selectVehicle(id);
                } else {
                    owner.host().selectResidents(Set.of(id));
                }
            });
            strip.addChildren(face);
        }
        if (shown.size() > FACES) {
            Label more = Rows.value(Component.literal("+" + (shown.size() - FACES)));
            more.textStyle(style -> style.textColor(Rows.QUIET));
            strip.addChildren(more);
        }
        if (unit != null && unit.kind() == UnitSnapshot.Kind.SQUAD || vehicles.isEmpty()) {
            Component orders = owner.host().orders();
            strip.style(style -> style.tooltips(orders));
        }
        grow(FACE + 5);
        return strip;
    }

    /**
     * The movement orders every selected vehicle's airframe offers, in card order; residents alone move like
     * ground vehicles. Boost and decoys follow when every selected vehicle has them.
     */
    private List<String> orders() {
        if (vehicles.isEmpty()) {
            return List.of("move", "attack", "stop");
        }
        List<String> shown = new ArrayList<>(List.of("move", "attack", "stop", "loiter", "land", "sprint", "decoy"));
        for (UnitSnapshot.Unit vehicle : vehicles) {
            List<String> offered = new ArrayList<>(switch (vehicle.capabilities().airframe()) {
                case GROUND -> List.of("move", "attack", "stop");
                case HELICOPTER -> List.of("move", "attack", "stop", "land");
                case FIXED_WING -> List.of("loiter", "land");
            });
            for (Vehicles.Action extra : List.of(Vehicles.Action.SPRINT, Vehicles.Action.DECOY)) {
                if (vehicle.capabilities().actions().contains(extra)) {
                    offered.add(lower(extra));
                }
            }
            offered.removeIf(order -> !order.equals("move") && !order.equals("attack") && !order.equals("stop")
                && !vehicle.capabilities().actions().contains(Vehicles.Action.valueOf(order.toUpperCase(Locale.ROOT))));
            shown.retainAll(offered);
        }
        return shown;
    }

    /** Stop: a ground vehicle halts, a helicopter hovers where it is at its current height. */
    private CommandButton stopButton(UnitWindow.Host host) {
        boolean hover = !vehicles.isEmpty() && vehicles.stream()
            .allMatch(vehicle -> vehicle.capabilities().airframe() == Vehicles.Airframe.HELICOPTER);
        CommandButton stop = icon("stop", Items.BARRIER, Component.translatable(KEY + "stop"),
            Component.translatable(hover ? KEY + "stop.hover.tip" : KEY + "stop.tip"));
        act(stop, "stop", host::stop);
        return stop;
    }

    /** Loiter arms a world click; Land, Boost and Decoy act at once on every selected vehicle. */
    private CommandButton special(Vehicles.Action action, UnitWindow.Host host) {
        String name = "op." + lower(action);
        CommandButton press = icon(name, ICONS.get(action), Component.translatable(KEY + name),
            Component.translatable(KEY + name + ".tip"));
        if (action == Vehicles.Action.LOITER) {
            if (host.armed() == CombatController.Command.LOITER) {
                press.addClass("folkways_chosen");
            }
            act(press, name, () -> host.arm(CombatController.Command.LOITER));
        } else {
            act(press, name, () -> special(press, action));
        }
        return press;
    }

    /** Low, Cruise or High over the terrain, shown when every selection is an aircraft; set per vehicle. */
    private UIElement altitude() {
        UIElement line = Rows.strip();
        line.layout(layout -> layout.gapAll(1).height(16).flexShrink(0));
        Tokens.name(line, KEY + "altitude");
        // Three segments read as one control; the bands name themselves, the tooltip says what they hold.
        UIElement bands = line;
        for (Vehicles.Altitude band : Vehicles.Altitude.values()) {
            String name = "altitude." + lower(band);
            Button press = button(Component.translatable(KEY + name), KEY + name);
            press.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).height(16));
            press.style(style -> style.tooltips(Component.translatable(KEY + "altitude").append(": ")
                .append(Component.translatable(KEY + name)), Component.translatable(KEY + name + ".tip")));
            if (vehicles.stream().allMatch(vehicle -> vehicle.altitude() == band)) {
                press.addClass("folkways_chosen");
            }
            press.setOnClick(event -> vehicles.forEach(vehicle ->
                owner.track(ClientUnits.altitude(vehicle.id(), band), vehicle.id())));
            bands.addChildren(press);
        }
        grow(16 + 5);
        return line;
    }

    private CommandButton profileButton(UnitSnapshot.Unit vehicle) {
        Optional<VehicleProfile> bound = ClientUnits.vehicleProfile(vehicle.id());
        CommandButton change = icon("profile.change", Items.NAME_TAG, Component.translatable(KEY + "profile.button"),
            Component.empty());
        List<VehicleProfile> offered = vehicle.vehicle().map(readiness -> ClientUnits.vehicleProfiles().stream()
            .filter(profile -> profile.type().equals(readiness.type())).toList()).orElse(List.of());
        List<Integer> missing = vehicle.vehicle().map(Vehicles.Readiness::missingSeats).orElse(List.of());
        Component current = Component.translatable(KEY + "profile_fold", bound
            .<Component>map(VehicleProfile::displayName).orElse(Component.translatable(KEY + "profile.none")));
        if (missing.isEmpty()) {
            change.style(style -> style.tooltips(Component.translatable(KEY + "profile.button"), current,
                Component.translatable(KEY + "profile.change.tip")));
        } else {
            change.style(style -> style.tooltips(Component.translatable(KEY + "profile.button"), current,
                Component.translatable(KEY + "crew.short", missing.size()).withColor(RED),
                Component.translatable(KEY + "profile.change.tip")));
        }
        change.setOnClick(event -> {
            List<Picker.Row> rows = new ArrayList<>(offered.stream().map(profile -> new Picker.Row(
                profile.id().toString(), profile.displayName(), ItemStack.EMPTY)).toList());
            rows.add(new Picker.Row(EDIT_PROFILES, Component.translatable(KEY + "profile.edit")
                .withStyle(ChatFormatting.ITALIC), ItemStack.EMPTY));
            owner.popup(Picker.open(change, Component.translatable(KEY + "profile.change"), rows,
                bound.map(profile -> profile.id().toString()).orElse(null), picked -> {
                    if (EDIT_PROFILES.equals(picked)) {
                        ClientUnits.editVehicleProfiles();
                    } else {
                        owner.track(ClientUnits.bindVehicleProfile(vehicle.id(), UUID.fromString(picked)),
                            vehicle.id());
                    }
                }));
        });
        return change;
    }

    private void special(UIElement anchor, Vehicles.Action action) {
        if (action == Vehicles.Action.LAND && vehicles.size() == 1) {
            UnitSnapshot.Unit vehicle = vehicles.getFirst();
            var airports = ClientUnits.snapshot().stream().flatMap(snapshot -> snapshot.landingSites().stream())
                .map(site -> site.airport()).distinct().toList();
            if (airports.size() > 1) {
                List<Picker.Row> rows = new ArrayList<>();
                rows.add(new Picker.Row("home", Component.translatable(KEY + "home_airport"), ItemStack.EMPTY));
                airports.forEach(airport -> rows.add(new Picker.Row(airport.id(),
                    Component.literal(airport.name()), ItemStack.EMPTY)));
                owner.popup(Picker.open(anchor, Component.translatable(KEY + "op.land"), rows, "home",
                    picked -> order(vehicle, action, picked.equals("home") ? Optional.empty() : Optional.of(picked))));
                return;
            }
        }
        vehicles.forEach(vehicle -> order(vehicle, action, Optional.empty()));
    }

    private void order(UnitSnapshot.Unit vehicle, Vehicles.Action action, Optional<String> airport) {
        owner.track(ClientUnits.order(vehicle.id(), new Vehicles.Order(action, List.of(), -1, Optional.empty(),
            airport)), vehicle.id());
    }

    // ---------------------------------------------------------------- details

    public UIElement details() {
        UIElement page = Rows.page();
        page.addChildren(heading(Component.translatable(KEY + "members", unit.members().size())));
        UIElement table = Rows.table();
        for (UUID member : unit.members()) {
            table.addChildren(member(member));
        }
        page.addChildren(table);
        grow(unit.members().size() * (Rows.ROW_HEIGHT + 1) + 5);
        if (!unit.weapons().isEmpty()) {
            page.addChildren(heading(Component.translatable(KEY + "rules")));
            for (Map.Entry<String, Component> weapon : unit.weapons().entrySet()) {
                UIElement rules = card();
                Label name = Rows.name(weapon.getValue());
                rules.addChildren(line(name));
                rule(rules, weapon.getKey(), unit.weaponRules().getOrDefault(weapon.getKey(), WeaponRules.DEFAULT));
                page.addChildren(rules);
                grow(20);
            }
        }
        scroller = UnitWindow.scroller();
        scroller.addScrollViewChild(page);
        Tokens.name(scroller, KEY + "details.body");
        return scroller;
    }

    // ---------------------------------------------------------------- squad

    private UIElement member(UUID member) {
        Button row = new Button().noText();
        row.addClass("folkways_nav");
        row.layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100).height(Rows.ROW_HEIGHT)
            .alignItems(AlignItems.CENTER).gapAll(Rows.GAP).paddingAll(2).flexShrink(0));
        Tokens.name(row, KEY + "member." + member);
        Entity body = CombatController.seen(member);
        Component name = residents.stream().filter(resident -> resident.id().equals(member)).findFirst()
            .map(UnitSnapshot.Resident::name)
            .orElse(body == null ? Component.translatable(KEY + "unknown") : body.getDisplayName());
        Label who = Rows.name(name);
        row.addChildren(new UnitFace(member, 18, false), who);
        if (body instanceof LivingEntity living) {
            Label health = Rows.value(Component.translatable(KEY + "hp", Mth.ceil(living.getHealth()),
                Mth.ceil(living.getMaxHealth())));
            health.textStyle(style -> style.textColor(living.getHealth() / Math.max(1, living.getMaxHealth()) > .5F
                ? Rows.QUIET : BAD));
            row.addChildren(health);
        }
        row.style(style -> style.tooltips(Component.translatable(KEY + "member.select")));
        row.setOnClick(event -> owner.host().selectResidents(Set.of(member)));
        return row;
    }

    // ---------------------------------------------------------------- shared

    /** Mode, range limit and indirect fire for one weapon key, sent as one whole rule on every change. */
    private void rule(UIElement card, String key, WeaponRules.Rule rule) {
        String token = KEY + "rule." + key.replace(':', '.').replace('/', '.');
        UIElement modes = Rows.strip();
        modes.layout(layout -> layout.gapAll(2));
        for (WeaponRules.Mode mode : WeaponRules.Mode.values()) {
            Button press = button(Component.translatable(KEY + "rule." + lower(mode)), token + "." + lower(mode));
            press.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
            press.style(style -> style.tooltips(Component.translatable(KEY + "rule." + lower(mode) + ".tip")));
            if (mode == rule.mode()) {
                press.addClass("folkways_chosen");
            }
            press.setOnClick(event -> send(key, new WeaponRules.Rule(mode, rule.range(), rule.indirect())));
            modes.addChildren(press);
        }
        card.addChildren(modes);
        Button less = Rows.act();
        less.setText(Component.literal("-"));
        less.enableText();
        Tokens.name(less, token + ".closer");
        less.setActive(rule.range() > 0);
        less.setOnClick(event -> send(key, new WeaponRules.Rule(rule.mode(),
            Math.max(0, rule.range() - RANGE_STEP), rule.indirect())));
        Label range = Rows.value(rule.range() == 0 ? Component.translatable(KEY + "range.any")
            : Component.translatable(KEY + "range.blocks", (int) rule.range()));
        range.layout(layout -> layout.width(40).flexShrink(0));
        range.textStyle(style -> style.textAlignHorizontal(com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.CENTER));
        Tokens.name(range, token + ".range");
        Button more = Rows.act();
        more.setText(Component.literal("+"));
        more.enableText();
        Tokens.name(more, token + ".farther");
        more.setActive(rule.range() < RANGE_MAX);
        more.setOnClick(event -> send(key, new WeaponRules.Rule(rule.mode(),
            rule.range() == 0 ? RANGE_STEP : Math.min(RANGE_MAX, rule.range() + RANGE_STEP), rule.indirect())));
        Button indirect = button(Component.translatable(rule.indirect() ? KEY + "indirect.on" : KEY + "indirect.off"),
            token + ".indirect");
        if (rule.indirect()) {
            indirect.addClass("folkways_chosen");
        }
        indirect.style(style -> style.tooltips(Component.translatable(KEY + "indirect.tip")));
        indirect.setOnClick(event -> send(key, new WeaponRules.Rule(rule.mode(), rule.range(), !rule.indirect())));
        // The range stepper explains itself on hover instead of spending a label's width.
        for (UIElement part : List.of(less, range, more)) {
            part.style(style -> style.tooltips(Component.translatable(KEY + "range")));
        }
        card.addChildren(line(less, range, more, Rows.spacer(), indirect));
        grow(20 + 22 + 22 + 8 + 5);
    }

    private void send(String key, WeaponRules.Rule rule) {
        owner.track(ClientUnits.weaponRule(unit.id(), key, rule), unit.id());
    }

    private Component occupantName(UUID id) {
        Entity body = CombatController.seen(id);
        Component name = residents.stream().filter(resident -> resident.id().equals(id)).findFirst()
            .map(UnitSnapshot.Resident::name)
            .orElse(body == null ? Component.translatable(KEY + "unknown") : body.getDisplayName());
        return name;
    }

    static Component action(String action) {
        return Component.translatable(action);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static UIElement line(UIElement... children) {
        UIElement line = Rows.strip();
        line.layout(layout -> layout.minHeight(16).gapAll(Rows.GAP));
        return line.addChildren(children);
    }

    private static UIElement card() {
        UIElement card = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .widthPercent(100).paddingAll(4).gapAll(3).flexShrink(0));
        card.addClass("folkways_card");
        return card;
    }

    private UIElement heading(Component text) {
        grow(18 + 5);
        return Rows.heading(text);
    }

    private static Button button(Component text, String token) {
        Button press = new Button().setText(text);
        press.layout(layout -> layout.flexShrink(0));
        Tokens.name(press, token);
        return press;
    }

    private void grow(float by) {
        height += by;
    }
}
