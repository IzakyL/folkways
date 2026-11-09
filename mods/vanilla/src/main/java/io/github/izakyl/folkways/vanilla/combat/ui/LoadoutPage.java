package io.github.izakyl.folkways.vanilla.combat.ui;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.front.api.notice.Notice;
import io.github.izakyl.folkways.core.front.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Pane;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.SignLine;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.api.ui.Windows;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.vanilla.combat.weapon.ObservationGear;
import io.github.izakyl.folkways.vanilla.combat.weapon.ProfileShortages;
import io.github.izakyl.folkways.vanilla.patrol.PatrolStaffing;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Commands carry the profile revision shown to the player; a stale editor cannot overwrite another keeper. */
public final class LoadoutPage implements Draw {
    private static final String KEY = "folkways.loadouts.";
    private static final int GAP = Rows.GAP;
    private static final int PROFILES_WIDTH = 150;
    // Entry columns: kind, icon, item, status, amounts, then the row's buttons.
    private static final int KIND_WIDTH = 48, STATUS_WIDTH = 56, NUMBER_WIDTH = 30, SAVE_WIDTH = 18;
    private static final int AMOUNTS_WIDTH = NUMBER_WIDTH * 2 + SAVE_WIDTH + 4;
    // Replace and remove are worded while the list has room and shrink to a glyph when it has not.
    private static final int ROW_BUTTON = 44;
    private static final int ITEM_WIDTH = 36;
    private static final int TABLE_WIDTH = tableWidth(ROW_BUTTON), COMPACT_WIDTH = tableWidth(Rows.ACT_SIZE);
    // Past this an item name only pushes its status away from it.
    private static final int TABLE_MAX_WIDTH = 640;
    private static final int HEADER_HEIGHT = 14, SCROLLBAR = 8;
    // The section red of Rows.heading: something the keeper has to act on.
    private static final int ALERT = 0x9A3324;
    private UUID context;
    private UUID selected;
    private Component message = Component.empty();
    private CompoundTag shown = new CompoundTag();
    private String sent = "";
    private ListTag weaponChoices = new ListTag();
    private long weaponChoicesAt = Long.MIN_VALUE;
    private int sequence;
    private int profileOffset, entryOffset, residentOffset;
    private final List<Button> profileButtons = new ArrayList<>();
    private final List<UIElement> entryRows = new ArrayList<>();
    private final List<Label> entryLabels = new ArrayList<>(), entryStatuses = new ArrayList<>();
    private final List<UIElement> entryIcons = new ArrayList<>(), supplyControls = new ArrayList<>();
    private final List<Label> entryKinds = new ArrayList<>(), figures = new ArrayList<>();
    private final List<Button> upButtons = new ArrayList<>(), downButtons = new ArrayList<>();
    private final List<Button> editButtons = new ArrayList<>(), removeButtons = new ArrayList<>();
    private UIElement headerButtons;
    private boolean compact;
    private final Label profileRange = range(), entryRange = range(), residentRange = range();
    private final Label profilesEmpty = Rows.note(text("no_profiles"));
    private final Label entriesEmpty = Rows.note(text("no_entries"));
    private final Label help = Rows.note(text("help"));
    private UIElement profilePager, entryPager, residentPager;
    private Button adding;
    private ScrollerView entries;
    private Label title;
    private final List<UIElement> residentRows = new ArrayList<>(), residentJobs = new ArrayList<>();
    private final List<Label> residentNames = new ArrayList<>();
    private Pane assignments;
    private final Label assignmentNotice = Rows.note(Component.empty());
    private final List<Button> residentButtons = new ArrayList<>();
    private final Label summary = Rows.note(Component.empty());
    private final Label notice = Rows.note(Component.empty());
    private ProfileShortages shortage;
    private UUID shortageProfile;
    private InventorySnapshot.Version shortageInventory;
    private long shortageAt, shortageRevision;
    private TextField name;
    private final List<TextField> amountFields = new ArrayList<>(), thresholdFields = new ArrayList<>();

    public LoadoutPage() {
    }

    LoadoutPage(UUID colony) {
        context = colony;
    }

    @Override
    public UIElement build(Player player) {
        context = Pages.colonyOf(player).map(Colony::id).orElse(null);
        UIElement content = Rows.page();
        var binding =
            DataBindingBuilder.stringC2S(
            raw -> {
                if (!(player instanceof ServerPlayer server)) {
                    return;
                }
                try {
                    apply(server, TagParser.parseTag(raw.substring(raw.indexOf('\n') + 1)));
                } catch (Exception rejected) {
                    message = text("rejected");
                }
            })
            .build();
        binding.setRemoteDataSource(IDataSource.of(ignored -> {}, () -> sent));
        content.addSyncValue(binding.getSyncValue());
        ItemPicker.Sending items =
            new ItemPicker.Sending(
            content,
            (key, filters) -> {
                if (!(player instanceof ServerPlayer server)) {
                    return;
                }
                try {
                    CompoundTag request = TagParser.parseTag(key);
                    List<ItemSpec> specs = new ArrayList<>();
                    for (ItemFilter filter : filters) {
                        switch (filter.kind()) {
                            case ITEM -> specs.add(ItemSpec.of(filter.id()));
                            case TAG -> specs.add(ItemSpec.of(TagKey.create(Registries.ITEM, filter.id())));
                            case QUERY -> ItemQuery.parse(filter.text())
                                .ifPresent(query -> query.items()
                                    .forEach(item -> specs.add(ItemSpec.of(item))));
                        }
                    }
                    if (specs.isEmpty()) {
                        throw new IllegalArgumentException("empty entry");
                    }
                    request.put("items", ItemSpec.anyOf(specs).save(server.registryAccess()));
                    apply(server, request);
                } catch (Exception rejected) {
                    message = text("rejected");
                }
            });
        UIElement profiles = Rows.page();
        profiles.layout(layout -> layout.width(PROFILES_WIDTH).flexShrink(0));
        Tokens.name(profiles, KEY + "profiles");
        // One profile to a line beside the details; laid side by side above them, so the list costs one line.
        UIElement list = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .widthPercent(100).gapAll(GAP));
        profiles.addChildren(Rows.heading(text("profiles")), list);
        for (int n = 0; n < 4; n++) {
            int slot = n;
            Button button = button("profile." + n, () -> {
                ListTag rows = shown.getList("profiles", Tag.TAG_COMPOUND);
                if (slot < rows.size()) {
                    CompoundTag request = request("select");
                    request.putUUID("profile", rows.getCompound(slot).getUUID("id"));
                    send(request);
                }
            });
            button.layout(layout -> layout.widthPercent(100).flexShrink(1).minWidth(0));
            limit(button);
            profileButtons.add(button);
            list.addChildren(button);
        }
        profilesEmpty.setDisplay(false);
        profilePager = pager("profiles", () -> profileOffset -= 4, () -> profileOffset += 4, profileRange);
        name = new TextField();
        name.setText("");
        name.textFieldStyle(style -> style.placeholder(Rows.placeholder(text("name"))));
        name.layout(layout -> layout.widthPercent(100).minWidth(0));
        Tokens.name(name, KEY + "name");
        UIElement actions = Rows.strip();
        actions.layout(layout -> layout.widthAuto().flexWrap(FlexWrap.WRAP).gapAll(2));
        actions.addChildren(
            button("create", () -> send(named(request("create")))),
            button("rename", () -> send(named(request("rename")))),
            button("duplicate", () -> send(request("duplicate"))),
            button("delete", () -> send(request("delete"))));
        UIElement editing = Rows.group(text("edit_profile"));
        UIElement editor = Rows.strip().addChildren(name, actions);
        editor.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(GAP));
        profiles.addChildren(profilesEmpty, profilePager, editing, editor);
        UIElement details = Rows.page();
        details.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).width(0));
        Tokens.name(details, KEY + "details");
        UIElement heading = Rows.title(text("unselected"));
        title = (Label) heading.getChildren().getFirst();
        title.layout(layout -> layout.flexShrink(1).minWidth(0).maxWidth(260));
        title.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
        for (int n = 0; n < 4; n++) {
            Label figure = Rows.figure(Component.empty(), Component.empty());
            figure.setDisplay(false);
            figures.add(figure);
            heading.addChildren(figure);
        }
        adding = button("add", () -> {
        });
        adding.setOnClick(event -> addMenu(adding, items));
        entryPager = pager("entries", () -> entryOffset -= 6, () -> entryOffset += 6, entryRange);
        entryPager.layout(layout -> layout.widthAuto().flexShrink(0));
        UIElement tools = Rows.strip().addChildren(adding, button("assign_residents", () -> assignments.open()),
            Rows.spacer(), entryPager);
        tools.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(GAP));
        details.addChildren(heading, summary, tools);
        UIElement table = Rows.table();
        table.layout(layout -> layout.width(TABLE_WIDTH));
        table.addChildren(header());
        for (int n = 0; n < 6; n++) {
            int slot = n;
            Label kind = column(KIND_WIDTH);
            kind.textStyle(style -> style.textColor(Rows.QUIET));
            UIElement icon = Rows.icon(ItemStack.EMPTY);
            Label label = Rows.name(Component.empty());
            label.layout(layout -> layout.minWidth(ITEM_WIDTH));
            label.setOverflowVisible(false);
            Label status = column(STATUS_WIDTH);
            TextField amount = number(), low = number();
            amountFields.add(amount);
            thresholdFields.add(low);
            amount.style(style -> style.tooltips(text("target")));
            low.style(style -> style.tooltips(text("threshold")));
            Tokens.name(amount, KEY + "target." + n);
            Tokens.name(low, KEY + "threshold." + n);
            Button save = button("amount." + n, () -> {
                CompoundTag request = entry(slot, "amount");
                try {
                    request.putInt("target", Integer.parseInt(amount.getText()));
                    request.putInt("threshold", Integer.parseInt(low.getText()));
                    send(request);
                } catch (NumberFormatException ignored) {
                }
            });
            save.setText(Component.literal("✓"));
            save.style(style -> style.tooltips(text("amount")));
            save.layout(layout -> layout.width(SAVE_WIDTH).paddingAll(0));
            // Weapons and armour keep the cell empty, so every row's buttons line up under one another.
            UIElement supply = Rows.strip().addChildren(amount, low, save);
            supply.layout(layout -> layout.widthAuto().flexShrink(0).gapAll(2));
            UIElement amounts = cell(AMOUNTS_WIDTH).addChildren(supply);
            Button up = arrow("up." + n, () -> send(entry(slot, "up")));
            Button down = arrow("down." + n, () -> send(entry(slot, "down")));
            Button edit = button("edit." + n, () -> pick(content, items, entry(slot, "edit")));
            Button remove = button("remove." + n, () -> send(entry(slot, "remove")));
            for (Button press : List.of(edit, remove)) {
                press.layout(layout -> layout.width(ROW_BUTTON).paddingHorizontal(0));
                limit(press);
            }
            edit.style(style -> style.tooltips(text("edit")));
            remove.style(style -> style.tooltips(text("remove")));
            editButtons.add(edit);
            removeButtons.add(remove);
            UIElement row = Rows.row().addChildren(kind, icon, label, status, amounts, up, down, edit, remove);
            row.layout(layout -> layout.gapAll(GAP));
            Tokens.name(row, KEY + "entry." + n);
            entryRows.add(row);
            entryKinds.add(kind);
            entryLabels.add(label);
            entryStatuses.add(status);
            entryIcons.add(icon);
            supplyControls.add(supply);
            upButtons.add(up);
            downButtons.add(down);
            table.addChildren(row);
        }
        entries = Rows.wideBox();
        entries.layout(layout -> layout.height(boxHeight(0)).flexShrink(0));
        Tokens.name(entries, KEY + "entry_list");
        entries.addScrollViewChild(table);
        // Rows span the list when it is wide enough and keep their columns when it is not, scrolling sideways.
        entries.addEventListener(UIEvents.LAYOUT_CHANGED, event -> {
            // Some slack: a table exactly as wide as the list feeds back into the stacked layout and collapses it.
            float space = entries.getContentWidth() - SCROLLBAR;
            compact(space < TABLE_WIDTH);
            float room = Math.clamp(space, compact ? COMPACT_WIDTH : TABLE_WIDTH, TABLE_MAX_WIDTH);
            if (Math.abs(table.getSizeWidth() - room) > 0.5f) {
                table.layout(layout -> layout.width(room));
            }
        });
        details.addChildren(entries, entriesEmpty, help, notice);
        UIElement panes = Rows.strip().addChildren(profiles, details);
        panes.layout(layout -> layout.alignItems(AlignItems.FLEX_START));
        boolean[] stacked = {false};
        panes.addEventListener(UIEvents.LAYOUT_CHANGED, event -> {
            boolean narrow = panes.getContentWidth() < 520;
            if (narrow == stacked[0]) {
                return;
            }
            stacked[0] = narrow;
            panes.layout(layout -> layout.flexDirection(narrow ? FlexDirection.COLUMN : FlexDirection.ROW));
            profiles.layout(layout -> {
                if (narrow) {
                    layout.widthPercent(100);
                } else {
                    layout.width(PROFILES_WIDTH);
                }
            });
            list.layout(layout -> layout.flexDirection(narrow ? FlexDirection.ROW : FlexDirection.COLUMN)
                .flexWrap(narrow ? FlexWrap.WRAP : FlexWrap.NO_WRAP));
            for (Button button : profileButtons) {
                button.layout(layout -> {
                    if (narrow) {
                        layout.width(PROFILES_WIDTH - 30);
                    } else {
                        layout.widthPercent(100);
                    }
                });
            }
            // The name field and its buttons share a line when stacked; the group label would only repeat them.
            editing.setDisplay(!narrow);
            name.layout(layout -> {
                if (narrow) {
                    layout.width(PROFILES_WIDTH).flexGrow(1);
                } else {
                    layout.widthPercent(100).flexGrow(0);
                }
            });
            details.layout(layout -> {
                if (narrow) {
                    layout.widthPercent(100);
                } else {
                    layout.width(0);
                }
            });
        });
        content.addChildren(panes);
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(content);
        scroll.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(value -> fill(player, value)).build().getSyncValue());
        return scroll;
    }

    @Override
    public void windows(Player player, Windows desk) {
        UIElement table = Rows.table();
        table.layout(layout -> layout.minWidth(270));
        for (int n = 0; n < 8; n++) {
            int slot = n;
            Label name = Rows.name(Component.empty());
            name.layout(layout -> layout.width(70).flexGrow(0));
            UIElement job = SignLine.of();
            job.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
            Button choose = button("resident." + n, () -> chooseResident(residentButtons.get(slot), slot));
            choose.layout(layout -> layout.width(96).flexShrink(0));
            limit(choose);
            UIElement row = Rows.row().addChildren(name, job, choose);
            residentNames.add(name);
            residentJobs.add(job);
            residentButtons.add(choose);
            residentRows.add(row);
            table.addChildren(row);
        }
        var scroll = Rows.fills(Rows.wideBox());
        scroll.addScrollViewChild(table);
        UIElement body = Rows.fills(Rows.page()).addChildren(scroll,
            residentPager = pager("residents", () -> residentOffset -= 8, () -> residentOffset += 8, residentRange),
            assignmentNotice);
        assignments = desk.add("loadout_residents", text("assign_residents"), body);
    }

    private void chooseResident(UIElement host, int slot) {
        ListTag residents = shown.getList("residents", Tag.TAG_COMPOUND);
        if (slot >= residents.size()) {
            return;
        }
        CompoundTag resident = residents.getCompound(slot).copy();
        ListTag choices = shown.getList("assignmentChoices", Tag.TAG_COMPOUND).copy();
        List<Picker.Leaf> options = new ArrayList<>();
        options.add(new Picker.Leaf("none", "", text("none"), KEY + "choose.none"));
        for (Tag value : choices) {
            CompoundTag profile = (CompoundTag) value;
            String id = profile.getUUID("id").toString();
            options.add(new Picker.Leaf(id, "", Component.literal(profile.getString("name")), KEY + "choose." + id));
        }
        Picker picker = new Picker(KEY + "assignment", false).immediate(true).size(210, 260);
        picker.fill(options);
        picker.open(host, text("assign_residents"), Set.of(), picked -> {
            String key = picked.getFirst();
            CompoundTag request = new CompoundTag();
            request.putString("action", "assign_resident");
            request.putUUID("resident", resident.getUUID("id"));
            if (resident.hasUUID("assigned")) {
                request.putUUID("expected", resident.getUUID("assigned"));
            }
            if (!key.equals("none")) {
                CompoundTag profile = choices.stream().map(value -> (CompoundTag) value)
                    .filter(value -> value.getUUID("id").toString().equals(key)).findFirst().orElseThrow();
                request.putUUID("profile", profile.getUUID("id"));
                request.putLong("revision", profile.getLong("revision"));
            }
            send(request);
        });
    }

    private void addMenu(UIElement host, ItemPicker.Sending items) {
        CompoundTag base = request("add");
        List<EquipmentSlot> slots = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET);
        List<Picker.Leaf> options = new ArrayList<>();
        for (String kind : List.of("weapon", "weapon_variants", "supply", "mount", "horse_armor", "observation_gear")) {
            options.add(new Picker.Leaf(kind, "", text(kind), KEY + kind));
        }
        for (EquipmentSlot slot : slots) {
            options.add(new Picker.Leaf(slot.getName(), "", text("armor").copy().append(" · ")
                .append(text(slot.getName())), KEY + slot.getName()));
        }
        Picker picker = new Picker(KEY + "adding", false).immediate(true).size(210, 260);
        picker.fill(options);
        picker.open(host, text("add"), Set.of(), picked -> {
            String kind = picked.getFirst();
            CompoundTag request = base.copy();
            switch (kind) {
                case "weapon_variants" -> pickWeapon(host, request);
                case "observation_gear" -> pickObservationGear(host, request);
                case "mount" -> {
                    request.putString("kind", "MOUNT");
                    send(request);
                }
                default -> {
                    Optional<EquipmentSlot> slot = slots.stream().filter(value -> value.getName().equals(kind))
                        .findFirst();
                    request.putString("kind", slot.isPresent() ? "ARMOR" : kind.toUpperCase(Locale.ROOT));
                    slot.ifPresent(value -> request.putString("slot", value.getName()));
                    pick(host, items, request);
                }
            }
        });
    }

    private TextField number() {
        TextField field = new TextField();
        field.setNumbersOnlyInt(0, 4096);
        field.setText("1");
        field.layout(layout -> layout.width(NUMBER_WIDTH).flexShrink(0));
        return field;
    }

    private CompoundTag named(CompoundTag request) {
        request.putString("name", name.getText());
        return request;
    }

    private Button button(String action, Runnable click) {
        String label = action.contains(".") ? action.substring(0, action.indexOf('.')) : action;
        Button button = new Button().setText(text(label)).setOnClick(event -> click.run());
        button.layout(layout -> layout.widthAuto().flexShrink(0));
        Tokens.name(button, KEY + action);
        return button;
    }

    private void limit(Button button) {
        button.setOverflowVisible(false);
        button.text.layout(layout -> layout.widthPercent(100).height(12).flexShrink(1).minWidth(0));
        button.textStyle(style -> style.adaptiveWidth(false).adaptiveHeight(false).textWrap(TextWrap.HOVER_ROLL));
    }

    // Previous, "1–6 / 9", next; hidden while everything fits on one page.
    private UIElement pager(String kind, Runnable previous, Runnable next, Label range) {
        // Paging is local UI state; requests still carry the row identities and server revision.
        UIElement pager = Rows.strip().addChildren(
            button("previous." + kind, () -> {
                previous.run();
                sendPage(kind);
            }),
            range,
            button("next." + kind, () -> {
                next.run();
                sendPage(kind);
            }));
        pager.layout(layout -> layout.gapAll(GAP));
        pager.setDisplay(false);
        return pager;
    }

    private static Label range() {
        Label range = Rows.value(Component.empty());
        range.textStyle(style -> style.textColor(Rows.QUIET));
        return range;
    }

    private static void page(UIElement pager, Label range, int offset, int count, int size) {
        pager.setDisplay(count > size);
        range.setText(Component.literal((offset + 1) + "–" + Math.min(count, offset + size) + " / " + count));
    }

    private Button arrow(String action, Runnable click) {
        Button arrow = button(action, click);
        arrow.layout(layout -> layout.width(Rows.ACT_SIZE).paddingAll(0));
        return arrow;
    }

    private static Label column(int width) {
        Label label = new Label();
        label.layout(layout -> layout.width(width).flexShrink(0));
        label.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL).adaptiveWidth(false));
        label.setOverflowVisible(false);
        return label;
    }

    private static UIElement cell(int width) {
        return new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .width(width).heightPercent(100).flexShrink(0).alignItems(AlignItems.CENTER));
    }

    // Names the columns once, so a row reads "kind, item, status, target, minimum" without hovering.
    private UIElement header() {
        UIElement header = Rows.row();
        header.removeClass("folkways_row");
        header.layout(layout -> layout.height(HEADER_HEIGHT).gapAll(GAP));
        Label kind = column(KIND_WIDTH), item = Rows.name(text("column.item")), status = column(STATUS_WIDTH);
        kind.setText(text("column.kind"));
        status.setText(text("column.status"));
        Label target = column(NUMBER_WIDTH + 2), low = column(NUMBER_WIDTH + SAVE_WIDTH + 2);
        target.setText(text("column.target"));
        low.setText(text("column.threshold"));
        target.style(style -> style.tooltips(text("target")));
        low.style(style -> style.tooltips(text("threshold")));
        UIElement amounts = cell(AMOUNTS_WIDTH).addChildren(target, low);
        UIElement icon = cell(Rows.ICON_SIZE);
        item.layout(layout -> layout.minWidth(ITEM_WIDTH));
        for (Label label : List.of(kind, item, status, target, low)) {
            label.textStyle(style -> style.textColor(Rows.QUIET));
        }
        // The row buttons take what is left, so their column needs no title.
        headerButtons = cell(buttonsWidth(ROW_BUTTON));
        UIElement buttons = headerButtons;
        return header.addChildren(kind, icon, item, status, amounts, buttons);
    }

    private static int buttonsWidth(int rowButton) {
        return Rows.ACT_SIZE * 2 + rowButton * 2 + GAP * 3;
    }

    private static int tableWidth(int rowButton) {
        return KIND_WIDTH + Rows.ICON_SIZE + ITEM_WIDTH + STATUS_WIDTH + AMOUNTS_WIDTH + buttonsWidth(rowButton) + GAP * 5;
    }

    private void compact(boolean narrow) {
        if (narrow == compact) {
            return;
        }
        compact = narrow;
        int width = narrow ? Rows.ACT_SIZE : ROW_BUTTON;
        for (int n = 0; n < editButtons.size(); n++) {
            editButtons.get(n).setText(narrow ? Component.literal("✎") : text("edit"));
            removeButtons.get(n).setText(narrow ? Component.literal("✕") : text("remove"));
            for (Button press : List.of(editButtons.get(n), removeButtons.get(n))) {
                press.layout(layout -> layout.width(width));
            }
        }
        headerButtons.layout(layout -> layout.width(buttonsWidth(width)));
    }

    private static float boxHeight(int rows) {
        return HEADER_HEIGHT + rows * (Rows.ROW_HEIGHT + 1) + 1 + SCROLLBAR + 2;
    }

    private void sendPage(String kind) {
        CompoundTag request = request("page");
        request.putString("kind", kind);
        request.putInt(
            "offset",
            kind.equals("profiles") ? profileOffset : kind.equals("entries") ? entryOffset : residentOffset);
        send(request);
    }

    private CompoundTag request(String action) {
        CompoundTag request = new CompoundTag();
        request.putString("action", action);
        if (shown.hasUUID("profile")) {
            request.putUUID("profile", shown.getUUID("profile"));
        }
        request.putLong("revision", shown.getLong("revision"));
        return request;
    }

    private CompoundTag entry(int slot, String action) {
        CompoundTag request = request(action);
        ListTag rows = shown.getList("entries", Tag.TAG_COMPOUND);
        if (slot < rows.size()) {
            request.putUUID("entry", rows.getCompound(slot).getUUID("id"));
        }
        return request;
    }

    private void send(CompoundTag request) {
        sent = ++sequence + "\n" + request;
    }

    private void pick(UIElement host, ItemPicker.Sending sending, CompoundTag request) {
        ItemPicker.many(
            host, text("items"), List.of(), false, true, filters -> sending.send(request.toString(), filters));
    }

    private void pickWeapon(UIElement host, CompoundTag request) {
        request.putString("kind", "WEAPON");
        ListTag choices = shown.getList("weaponChoices", Tag.TAG_COMPOUND);
        List<Picker.Row> offers = new ArrayList<>();
        for (int n = 0; n < choices.size(); n++) {
            CompoundTag choice = choices.getCompound(n);
            ItemStack stack =
                ItemStack.parseOptional(
                Minecraft.getInstance().level.registryAccess(), choice.getCompound("stack"));
            offers.add(
                new Picker.Row(
                Integer.toString(n),
                stack.getHoverName(), stack,
                stack.getItem().builtInRegistryHolder().key().location().getNamespace(),
                stack.getItem().builtInRegistryHolder().tags().map(tag -> tag.location().toString()).toList()));
        }
        Picker.openMany(
            host,
            text("weapon_variants"),
            offers,
            Set.of(),
            "folkways.selector",
            filters -> {
                List<ItemSpec> specs = new ArrayList<>();
                for (String filter : filters) {
                    int index = Integer.parseInt(filter);
                    specs.add(
                        ItemSpec.CODEC
                        .parse(
                        Minecraft.getInstance()
                        .level
                        .registryAccess()
                        .createSerializationContext(NbtOps.INSTANCE),
                        choices.getCompound(index).get("items"))
                        .getOrThrow());
                }
                if (!specs.isEmpty()) {
                    request.put(
                        "items", ItemSpec.anyOf(specs).save(Minecraft.getInstance().level.registryAccess()));
                    send(request);
                }
            });
    }

    private void pickObservationGear(UIElement content, CompoundTag request) {
        request.putString("action", "observation_gear");
        List<Picker.Row> offers = ObservationGear.choices().stream()
            .map(stack -> new Picker.Row(
                stack.getItem().builtInRegistryHolder().key().location().toString(),
                stack.getHoverName(), stack)).toList();
        Picker.openMany(content, text("observation_gear"), offers, Set.of(), "folkways.selector", picked -> {
            if (!picked.isEmpty()) {
                ListTag choices = new ListTag();
                picked.forEach(filter -> choices.add(StringTag.valueOf(filter)));
                request.put("choices", choices);
                send(request);
            }
        });
    }

    private Optional<Colony> colony(Player player) {
        return Pages.colonyOf(player).filter(found -> found.id().equals(context));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    public boolean apply(ServerPlayer player, CompoundTag request) {
        if (!player.getServer().isSameThread() || colony(player).isEmpty()) {
            message = text("rejected");
            return false;
        }
        Colony colony = colony(player).orElseThrow();
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        try {
            String action = request.getString("action");
            if (action.equals("create")) {
                selected = profiles.create(request.getString("name")).id();
                entryOffset = 0;
            } else if (action.equals("select")) {
                selected = profiles.get(request.getUUID("profile")).orElseThrow().id();
                entryOffset = 0;
            } else if (action.equals("page")) {
                int offset = Math.clamp(request.getInt("offset"), 0, 100000);
                switch (request.getString("kind")) {
                    case "profiles" -> profileOffset = offset;
                    case "entries" -> entryOffset = offset;
                    case "residents" -> residentOffset = offset;
                }
            } else if (action.equals("assign_resident")) {
                UUID id = request.getUUID("resident");
                requireResident(colony, id);
                Optional<UUID> expected = request.hasUUID("expected")
                    ? Optional.of(request.getUUID("expected")) : Optional.empty();
                if (!profiles.assigned(id).map(LoadoutProfile::id).equals(expected)) {
                    throw new IllegalArgumentException("stale assignment");
                }
                Optional<UUID> target = Optional.empty();
                if (request.hasUUID("profile")) {
                    LoadoutProfile profile = profiles.get(request.getUUID("profile")).orElseThrow();
                    if (request.getLong("revision") != profiles.revision(profile.id())) {
                        throw new IllegalArgumentException("stale profile");
                    }
                    target = Optional.of(profile.id());
                }
                profiles.assign(List.of(id), target);
                shortage = null;
            } else {
                LoadoutProfile profile = profiles.get(request.getUUID("profile")).orElseThrow();
                if (request.getLong("revision") != profiles.revision(profile.id())) {
                    throw new IllegalArgumentException("stale profile");
                }
                switch (action) {
                    case "rename" ->
                    profiles.replace(
                        new LoadoutProfile(
                        profile.id(),
                        request.getString("name"),
                        profile.entries()));
                    case "duplicate" -> selected = profiles.duplicate(profile.id()).id();
                    case "delete" -> {
                        profiles.delete(profile.id());
                        if (profile.id().equals(selected)) {
                            selected = null;
                        }
                    }
                    case "observation_gear" -> {
                        List<ItemSpec> choices = new ArrayList<>();
                        ListTag requested = request.getList("choices", Tag.TAG_STRING);
                        for (Tag value : requested) {
                            ItemStack gear = ObservationGear.choices().stream()
                                .filter(stack -> stack.getItem().builtInRegistryHolder().key().location().toString()
                                    .equals(value.getAsString()))
                                .findFirst().orElseThrow();
                            choices.add(ItemSpec.of(gear.getItem()));
                        }
                        if (choices.isEmpty()) {
                            throw new IllegalArgumentException("empty observation gear");
                        }
                        profiles.add(profile.id(), LoadoutProfile.Entry.supply(ItemSpec.anyOf(choices), 1, 1));
                    }
                    case "add" -> {
                        LoadoutProfile.Kind kind = LoadoutProfile.Kind.valueOf(request.getString("kind"));
                        ItemSpec spec = kind == LoadoutProfile.Kind.MOUNT ? ItemSpec.of(Items.SADDLE)
                            : decode(player, request);
                        profiles.add(
                            profile.id(),
                            switch (kind) {
                                case WEAPON -> LoadoutProfile.Entry.weapon(spec);
                                case SUPPLY -> LoadoutProfile.Entry.supply(spec, 1, 1);
                                case MOUNT -> LoadoutProfile.Entry.mount();
                                case HORSE_ARMOR -> LoadoutProfile.Entry.horseArmor(spec);
                                case ARMOR ->
                                    LoadoutProfile.Entry.armor(
                                    EquipmentSlot.byName(request.getString("slot")), spec);
                            });
                    }
                    default -> edit(player, profiles, profile, request);
                }
            }
            message = text("saved");
            return true;
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException rejected) {
            message = text("rejected");
            return false;
        }
    }

    private static ItemSpec decode(ServerPlayer player, CompoundTag request) {
        return ItemSpec.CODEC
            .parse(player.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                request.getCompound("items"))
            .getOrThrow();
    }

    private void requireResident(Colony colony, UUID id) {
        if (colony.claimOf(id).isEmpty()
            || colony.residents().stream()
            .noneMatch(resident -> resident.id().equals(id) && resident.kind().equals(PersonBody.ID))) {
            throw new IllegalArgumentException("resident outside colony");
        }
    }

    private void edit(ServerPlayer player, LoadoutProfiles profiles, LoadoutProfile profile, CompoundTag request) {
        List<LoadoutProfile.Entry> entries = new ArrayList<>(profile.entries());
        LoadoutProfile.Entry entry =
            entries.stream().filter(row -> row.id().equals(request.getUUID("entry"))).findFirst().orElseThrow();
        int index = entries.indexOf(entry);
        switch (request.getString("action")) {
            case "remove" -> entries.remove(index);
            case "up", "down" -> {
                int to = index + (request.getString("action").equals("up") ? -1 : 1);
                if (to < 0 || to >= entries.size()) {
                    throw new IllegalArgumentException("entry boundary");
                }
                Collections.swap(entries, index, to);
            }
            case "edit", "amount" -> {
                ItemSpec spec = entry.items();
                int target = entry.target(), threshold = entry.threshold();
                switch (request.getString("action")) {
                    case "edit" -> spec = decode(player, request);
                    case "amount" -> {
                        if (entry.kind() != LoadoutProfile.Kind.SUPPLY) {
                            throw new IllegalArgumentException("not a supply");
                        }
                        target = request.getInt("target");
                        threshold = request.getInt("threshold");
                    }
                }
                entries.set(
                    index,
                    new LoadoutProfile.Entry(entry.id(), entry.kind(), spec, entry.slot(), target, threshold));
            }
            default -> throw new IllegalArgumentException("unknown action");
        }
        profiles.replace(new LoadoutProfile(profile.id(), profile.name(), entries));
    }

    private Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        tag.put("message", Pages.text(player.registryAccess(), message));
        if (!(player instanceof ServerPlayer server) || colony(player).isEmpty()) {
            return tag;
        }
        Colony colony = colony(player).orElseThrow();
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        List<LoadoutProfile> all = profiles.all();
        profileOffset = page(profileOffset, all.size(), 4);
        tag.putInt("profileCount", all.size());
        ListTag profileRows = new ListTag();
        for (LoadoutProfile profile : all.subList(profileOffset, Math.min(all.size(), profileOffset + 4))) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", profile.id());
            row.putString("name", profile.name());
            row.putBoolean("selected", profile.id().equals(selected));
            profileRows.add(row);
        }
        tag.put("profiles", profileRows);
        if (weaponChoicesAt == Long.MIN_VALUE || server.serverLevel().getGameTime() >= weaponChoicesAt + 20) {
            weaponChoicesAt = server.serverLevel().getGameTime();
            weaponChoices = new ListTag();
            for (var provider : LoadoutRegistry.entries().values()) {
                for (ItemStack stack : provider.weaponChoices()) {
                    if (!provider.accepts(stack)) {
                        continue;
                    }
                    CompoundTag choice = new CompoundTag();
                    choice.put("stack", stack.save(server.registryAccess()));
                    choice.put("items", provider.profileWeapon(stack).save(server.registryAccess()));
                    weaponChoices.add(choice);
                }
            }
        }
        tag.put("weaponChoices", weaponChoices.copy());
        ListTag entries = new ListTag();
        Optional<LoadoutProfile> profile = profiles.get(selected);
        if (profile.isPresent()) {
            LoadoutProfile current = profile.get();
            tag.putUUID("profile", current.id());
            tag.putLong("revision", profiles.revision(current.id()));
            long now = server.serverLevel().getGameTime();
            if (shortage == null
                || !current.id().equals(shortageProfile)
                || profiles.revision(current.id()) != shortageRevision
                || !colony.inventory().snapshot().version().equals(shortageInventory)
                || now >= shortageAt + 20) {
                shortage = ProfileShortages.read(colony, current, server.getServer());
                shortageProfile = current.id();
                shortageAt = now;
                shortageRevision = profiles.revision(current.id());
                shortageInventory = colony.inventory().snapshot().version();
            }
            ProfileShortages shortages = shortage;
            tag.putString("name", current.name());
            tag.putBoolean("complete", shortages.complete());
            tag.putInt("assigned", shortages.assigned());
            tag.putInt("incomplete", shortages.incomplete());
            tag.putInt("unloaded", shortages.unloaded());
            tag.putInt("staffing", PatrolStaffing.of(colony).shortageFor(current.id()));
            if (!shortages.complete()) {
                tag.put("summary", Pages.text(player.registryAccess(), text("inventory_pending")));
            }
            tag.putInt("entryCount", current.entries().size());
            entryOffset = page(entryOffset, current.entries().size(), 6);
            for (LoadoutProfile.Entry entry :
                current.entries().subList(entryOffset, Math.min(current.entries().size(), entryOffset + 6))) {
                CompoundTag row = new CompoundTag();
                row.putUUID("id", entry.id());
                // The kind's own name; armour adds its slot. A new kind needs only its lang key.
                Component kind = text(entry.kind().name().toLowerCase(Locale.ROOT));
                if (entry.slot().isPresent()) {
                    kind = text("kind_slot", kind, text(entry.slot().get().getName()));
                }
                int missing = shortages.missing().getOrDefault(entry.id(), 0);
                int stock = shortages.unavailableStock().getOrDefault(entry.id(), 0);
                ItemStack display = Goods.display(entry.items());
                row.put("stack", display.saveOptional(server.registryAccess()));
                row.put("kind_label", Pages.text(player.registryAccess(), kind));
                row.put("label", Pages.text(player.registryAccess(), display.getHoverName()));
                row.put("status", Pages.text(player.registryAccess(), status(entry, shortages, missing, stock)));
                row.put("detail", Pages.text(player.registryAccess(), kind.copy().append(" · ").append(
                    !shortages.complete() ? text("inventory_pending")
                    : entry.kind() == LoadoutProfile.Kind.MOUNT ? text("mount_shortage", shortages.missingHorses(),
                        shortages.unavailableHorses(), missing, stock)
                    : text("missing", missing, stock))));
                row.putString("spec", entry.items().describe());
                row.putString("kind", entry.kind().name());
                row.putInt("target", entry.target());
                row.putInt("threshold", entry.threshold());
                entries.add(row);
            }
        }
        tag.put("entries", entries);
        var residents = colony.residents().stream().filter(resident -> resident.kind().equals(PersonBody.ID)).toList();
        residentOffset = page(residentOffset, residents.size(), 8);
        tag.putInt("residentCount", residents.size());
        ListTag residentRows = new ListTag();
        for (var resident : residents.subList(residentOffset, Math.min(residents.size(), residentOffset + 8))) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", resident.id());
            Sentence job = Sentence.of(Sentence.word(new Notice(KEY + "unloaded", List.of())));
            Component residentName = Component.literal(resident.id().toString().substring(0, 8));
            for (var level : server.getServer().getAllLevels()) {
                if (level.getEntity(resident.id()) instanceof ResidentEntity body) {
                    residentName = body.getDisplayName();
                    job = body.doing().map(Sentence::of).orElseGet(() -> Sentence.of(
                        Sentence.word(new Notice("folkways.resident.unassigned", List.of()))));
                    break;
                }
            }
            row.put("name", Pages.text(player.registryAccess(), residentName));
            row.put("job", job.save());
            profiles.assigned(resident.id()).ifPresent(assigned -> row.putUUID("assigned", assigned.id()));
            row.put("assignment", Pages.text(player.registryAccess(), profiles.assigned(resident.id())
                .<Component>map(assigned -> Component.literal(assigned.name())).orElseGet(() -> text("none"))));
            residentRows.add(row);
        }
        tag.put("residents", residentRows);
        ListTag assignmentChoices = new ListTag();
        for (LoadoutProfile choice : all) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", choice.id());
            row.putString("name", choice.name());
            row.putLong("revision", profiles.revision(choice.id()));
            assignmentChoices.add(row);
        }
        tag.put("assignmentChoices", assignmentChoices);
        tag.put("message", Pages.text(player.registryAccess(), message));
        tag.putInt("profileOffset", profileOffset);
        tag.putInt("entryOffset", entryOffset);
        tag.putInt("residentOffset", residentOffset);
        return tag;
    }

    // One short word a row can show in full: ready, what residents still need, what the warehouse cannot cover.
    private static Component status(LoadoutProfile.Entry entry, ProfileShortages shortages, int missing, int stock) {
        if (!entry.available()) {
            return text("invalid_entry").copy().withColor(ALERT);
        }
        if (!shortages.complete()) {
            return text("status.pending").copy().withColor(Rows.QUIET & 0xFFFFFF);
        }
        // A rider's horse comes from the pastures, not the stores: no free tame horse is the thing to fix first.
        if (entry.kind() == LoadoutProfile.Kind.MOUNT && shortages.unavailableHorses() > 0) {
            return text("status.no_horse").copy().withColor(ALERT);
        }
        if (missing == 0) {
            return text("status.ready").copy().withColor(Rows.QUIET & 0xFFFFFF);
        }
        if (stock == 0) {
            return text("status.need", missing);
        }
        // The warehouse cannot cover it: the one figure the keeper must act on. The tooltip has both.
        return text("status.short", stock).copy().withColor(ALERT);
    }

    private static int page(int offset, int count, int size) {
        return Math.clamp(offset, 0, Math.max(0, count - 1) / size * size);
    }

    private void fill(Player player, Tag value) {
        if (!(value instanceof CompoundTag tag)) {
            return;
        }
        CompoundTag previous = shown;
        shown = tag;
        if (tag.hasUUID("profile")
            && (!previous.hasUUID("profile")
            || !tag.getUUID("profile").equals(previous.getUUID("profile"))
            || !tag.getString("name").equals(previous.getString("name")))) {
            name.setText(tag.getString("name"));
        }
        profileOffset = tag.getInt("profileOffset");
        entryOffset = tag.getInt("entryOffset");
        residentOffset = tag.getInt("residentOffset");
        boolean chosen = tag.hasUUID("profile");
        title.setText((chosen ? Component.literal(tag.getString("name")) : text("unselected"))
            .copy().withStyle(ChatFormatting.BOLD));
        title.style(style -> style.tooltips(Component.literal(tag.getString("name"))));
        boolean counted = chosen && tag.getBoolean("complete");
        figure(0, counted, "figure.assigned", tag.getInt("assigned"));
        figure(1, counted, "figure.incomplete", tag.getInt("incomplete"));
        figure(2, counted, "figure.unloaded", tag.getInt("unloaded"));
        figure(3, chosen && (counted || tag.getInt("staffing") > 0), "figure.staffing", tag.getInt("staffing"));
        Component note = chosen ? Pages.readText(player.registryAccess(), tag.get("summary")) : text("choose_profile");
        summary.setText(note);
        summary.setDisplay(!note.getString().isEmpty());
        adding.setDisplay(chosen);
        notice.setText(Pages.readText(player.registryAccess(), tag.get("message")));
        assignmentNotice.setText(Pages.readText(player.registryAccess(), tag.get("message")));
        ListTag profiles = tag.getList("profiles", Tag.TAG_COMPOUND);
        profilesEmpty.setDisplay(profiles.isEmpty());
        page(profilePager, profileRange, profileOffset, tag.getInt("profileCount"), 4);
        for (int n = 0; n < profileButtons.size(); n++) {
            Button button = profileButtons.get(n);
            button.setDisplay(n < profiles.size());
            if (n < profiles.size()) {
                button.setText(
                    Component.literal(
                    (profiles.getCompound(n).getBoolean("selected") ? "✓ " : "")
                    + profiles.getCompound(n).getString("name")));
                Component profileName = Component.literal(profiles.getCompound(n).getString("name"));
                button.style(style -> style.tooltips(profileName));
            }
        }
        ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND), old = previous.getList("entries", Tag.TAG_COMPOUND);
        int count = tag.getInt("entryCount");
        page(entryPager, entryRange, entryOffset, count, 6);
        this.entries.setDisplay(!entries.isEmpty());
        this.entries.layout(layout -> layout.height(boxHeight(entries.size())));
        entriesEmpty.setDisplay(chosen && entries.isEmpty());
        help.setDisplay(entries.stream().anyMatch(row -> ((CompoundTag) row).getString("kind").equals("SUPPLY")));
        for (int n = 0; n < 6; n++) {
            boolean visible = n < entries.size();
            entryRows.get(n).setDisplay(visible);
            if (!visible) {
                continue;
            }
            CompoundTag row = entries.getCompound(n);
            Component detail = Pages.readText(player.registryAccess(), row.get("detail"));
            entryKinds.get(n).setText(Pages.readText(player.registryAccess(), row.get("kind_label")));
            entryKinds.get(n).style(style -> style.tooltips(detail));
            entryLabels.get(n).setText(Pages.readText(player.registryAccess(), row.get("label")));
            entryLabels.get(n).style(style -> style.tooltips(detail, Component.literal(row.getString("spec"))));
            entryStatuses.get(n).setText(Pages.readText(player.registryAccess(), row.get("status")));
            entryStatuses.get(n).style(style -> style.tooltips(detail));
            ItemStack stack = ItemStack.parseOptional(player.registryAccess(), row.getCompound("stack"));
            entryIcons.get(n).style(style -> style.background(new ItemStackTexture(stack)).tooltips(detail));
            boolean supply = row.getString("kind").equals("SUPPLY");
            supplyControls.get(n).setDisplay(supply);
            amountFields.get(n).setActive(supply);
            upButtons.get(n).setActive(entryOffset + n > 0);
            downButtons.get(n).setActive(entryOffset + n < count - 1);
            if (n >= old.size()
                || !row.getUUID("id").equals(old.getCompound(n).getUUID("id"))
                || row.getInt("target") != old.getCompound(n).getInt("target")
                || row.getInt("threshold") != old.getCompound(n).getInt("threshold")) {
                amountFields.get(n).setText(Integer.toString(row.getInt("target")));
                thresholdFields.get(n).setText(Integer.toString(row.getInt("threshold")));
            }
        }
        ListTag residents = tag.getList("residents", Tag.TAG_COMPOUND);
        if (residentPager != null) {
            page(residentPager, residentRange, residentOffset, tag.getInt("residentCount"), 8);
        }
        for (int n = 0; n < residentButtons.size(); n++) {
            residentRows.get(n).setDisplay(n < residents.size());
            if (n < residents.size()) {
                CompoundTag row = residents.getCompound(n);
                residentNames.get(n).setText(Pages.readText(player.registryAccess(), row.get("name")));
                SignLine.show(residentJobs.get(n), Sentence.load(row.getList("job", Tag.TAG_COMPOUND)));
                Component assignment = Pages.readText(player.registryAccess(), row.get("assignment"));
                residentButtons.get(n).setText(assignment);
                residentButtons.get(n).style(style -> style.tooltips(assignment));
            }
        }
    }

    // "Assigned 2" on the title line; hidden until the figure is known.
    private void figure(int index, boolean shown, String key, int value) {
        Label figure = figures.get(index);
        figure.setDisplay(shown);
        figure.setText(text(key).copy().withColor(Rows.QUIET & 0xFFFFFF).append(" ")
            .append(Component.literal(Integer.toString(value))
                .withColor((index > 0 && value > 0 ? ALERT : Rows.INK) & 0xFFFFFF)));
    }
}
