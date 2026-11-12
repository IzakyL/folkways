package io.github.izakyl.folkways.core.equipment.internal;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.equipment.api.GearGroup;
import io.github.izakyl.folkways.core.equipment.api.GearSlot;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.front.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Pane;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.SignLine;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.api.ui.Windows;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One profile at a time, laid out like a character's inventory: armour slots left of a dressed figure, hands and
 * mount right of it, accessory groups beside them, and the carried goods below. Profiles are listed on the left and
 * fold above the board on a narrow page. The server half is {@link EquipmentDesk}.
 */
public final class EquipmentPage implements Draw {
    private static final String KEY = EquipmentDesk.KEY;
    private static final int GAP = Rows.GAP;
    private static final int SLOT = 22, FIGURE_WIDTH = 72, PROFILES_WIDTH = 96, CHIP_WIDTH = 96;
    // Below this the profile list folds above the board; below the second the slots lose their names.
    private static final float STACKED = 340, BARE = 250;
    // The thread from a parent's slot down past what it holds, and the red of a count past its slots.
    private static final int THREAD = 0xFFB49D70, ALERT = 0xFF9A3324;
    private static final float ENTRY_HEIGHT = 170;
    private final EquipmentDesk desk;
    private CompoundTag shown = new CompoundTag();
    private String sent = "";
    private int sequence;
    private boolean client;
    private UUID focused;
    private ResourceLocation accessoryGroup;
    private UIElement profileList, details, empty, figureRow, chips, accessories, accessoryChoice;
    private UIElement editorBody, boardHost, wornTrees;
    private Button riding;
    private String gearSchema = "";
    private Button createButton;
    private TextField name;
    private Label footer;
    private final Label notice = Rows.note(Component.empty());
    private final Map<ResourceLocation, UIElement> slotElements = new LinkedHashMap<>();
    private final Map<ResourceLocation, EquipmentFaces.Shown> slotFaces = new LinkedHashMap<>();
    private final List<UIElement> ridingRows = new ArrayList<>();
    private final List<Label> slotNames = new ArrayList<>();
    private Map<ResourceLocation, ItemStack> dressed = new LinkedHashMap<>();
    private ItemPicker.Sending items;
    private Pane assignments, editor;
    private final List<UIElement> residentRows = new ArrayList<>(), residentJobs = new ArrayList<>();
    private final List<Label> residentNames = new ArrayList<>();
    private final List<Button> residentButtons = new ArrayList<>();
    private final Label residentRange = Rows.value(Component.empty());
    private UIElement residentPager;
    private final Label assignmentNotice = Rows.note(Component.empty());
    // The editor covers the page's own notice, so a refusal is repeated under the entry being edited.
    private final Label editorNotice = Rows.note(Component.empty());
    private TextField target, threshold;
    private Button removeButton;
    private UUID armed;
    private UIElement editorIcon, amounts, moves;
    private EquipmentFaces.Shown editorFace;
    private Label editorName, editorStatus;

    public EquipmentPage() {
        this(null);
    }

    EquipmentPage(UUID colony) {
        desk = new EquipmentDesk(colony);
    }

    public boolean apply(ServerPlayer player, CompoundTag request) {
        return desk.apply(player, request);
    }

    /** What the page would show the player now; read on the server. */
    public Tag reading(Player player) {
        return desk.reading(player);
    }

    private static Component text(String key, Object... args) {
        return EquipmentDesk.text(key, args);
    }

    @Override
    public UIElement build(Player player) {
        desk.context(Pages.colonyOf(player).map(Colony::id).orElse(null));
        client = player.level().isClientSide();
        UIElement content = Rows.page();
        var binding = DataBindingBuilder.stringC2S(raw -> {
            if (player instanceof ServerPlayer server) {
                try {
                    desk.apply(server, TagParser.parseTag(raw.substring(raw.indexOf('\n') + 1)));
                } catch (Exception rejected) {
                    desk.apply(server, new CompoundTag());
                }
            }
        }).build();
        binding.setRemoteDataSource(IDataSource.of(ignored -> { }, () -> sent));
        content.addSyncValue(binding.getSyncValue());
        items = new ItemPicker.Sending(content, (key, filters) -> {
            if (player instanceof ServerPlayer server) {
                try {
                    CompoundTag request = TagParser.parseTag(key);
                    if (request.getBoolean("each")) {
                        // Each pick becomes its own child entry, as each disc or upgrade fills its own slot.
                        ListTag each = new ListTag();
                        filters.forEach(filter -> each.add(spec(List.of(filter)).save(server.registryAccess())));
                        request.put("each", each);
                    } else {
                        request.put("items", spec(filters).save(server.registryAccess()));
                    }
                    desk.apply(server, request);
                } catch (Exception rejected) {
                    desk.apply(server, new CompoundTag());
                }
            }
        });
        UIElement profiles = Rows.page();
        profiles.layout(layout -> layout.width(PROFILES_WIDTH).flexShrink(0).gapAll(2));
        Tokens.name(profiles, KEY + "profiles");
        profileList = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .widthPercent(100).gapAll(2));
        Button create = button("create", () -> send(named(request("create"),
            text("new_name", shown.getList("profiles", Tag.TAG_COMPOUND).size() + 1).getString())));
        create.layout(layout -> layout.widthPercent(100));
        createButton = create;
        profileList.addChildren(create);
        profiles.addChildren(Rows.heading(text("profiles")), profileList);
        details = Rows.page();
        details.layout(layout -> layout.widthPercent(100).minWidth(0));
        Tokens.name(details, KEY + "details");
        boardHost = new UIElement();
        boardHost.addChildren(board());
        wornTrees = Rows.page();
        details.addChildren(header(), boardHost, wornTrees, carriedHeading(), chips(), footer =
            Rows.note(Component.empty()),
            notice);
        empty = Rows.note(text("choose_profile"));
        UIElement right = Rows.page().addChildren(empty, details);
        right.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).width(0));
        UIElement panes = Rows.strip().addChildren(profiles, right);
        panes.layout(layout -> layout.alignItems(AlignItems.FLEX_START).gapAll(GAP * 2));
        boolean[] stacked = {false}, bare = {false};
        panes.addEventListener(UIEvents.LAYOUT_CHANGED, event -> {
            float width = panes.getContentWidth();
            boolean narrow = width > 0 && width < STACKED;
            if (narrow != stacked[0]) {
                stacked[0] = narrow;
                panes.layout(layout -> layout.flexDirection(narrow ? FlexDirection.COLUMN : FlexDirection.ROW));
                profiles.layout(layout -> {
                    if (narrow) {
                        layout.widthPercent(100);
                    } else {
                        layout.width(PROFILES_WIDTH);
                    }
                });
                profileList.layout(layout -> layout.flexDirection(narrow ? FlexDirection.ROW : FlexDirection.COLUMN)
                    .flexWrap(narrow ? FlexWrap.WRAP : FlexWrap.NO_WRAP));
                right.layout(layout -> {
                    if (narrow) {
                        layout.widthPercent(100);
                    } else {
                        layout.width(0);
                    }
                });
                profileButtons(narrow);
            }
            boolean thin = width > 0 && (narrow ? width : width - PROFILES_WIDTH - GAP * 2) < BARE;
            if (thin != bare[0]) {
                bare[0] = thin;
                slotNames.forEach(label -> label.setDisplay(!thin));
            }
        });
        content.addChildren(panes);
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(content);
        scroll.addSyncValue(DataBindingBuilder.tagS2C(() -> desk.reading(player))
            .onSyncReceived(value -> fill(player, value)).build().getSyncValue());
        return scroll;
    }

    // The profile's name, kept editable in place, its figures, and what can be done with it.
    private UIElement header() {
        name = new TextField();
        name.setText("");
        name.textFieldStyle(style -> style.placeholder(Rows.placeholder(text("name"))));
        name.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(60).maxWidth(200));
        Tokens.name(name, KEY + "name");
        Button rename = button("rename", () -> send(named(request("rename"), name.getText())));
        rename.setText(Component.literal("✓"));
        rename.style(style -> style.tooltips(text("rename")));
        rename.layout(layout -> layout.width(Rows.ACT_SIZE).paddingAll(0));
        UIElement line = Rows.strip().addChildren(name, rename);
        line.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(GAP));
        figureRow = Rows.strip();
        figureRow.layout(layout -> layout.widthAuto().flexShrink(1).flexWrap(FlexWrap.WRAP).gapAll(GAP + 2));
        UIElement actions = Rows.strip().addChildren(figureRow, Rows.spacer(),
            button("duplicate", () -> send(request("duplicate"))),
            danger(button("delete", () -> send(request("delete")))),
            button("assign_residents", () -> assignments.open()));
        actions.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(GAP));
        return Rows.page().addChildren(line, actions);
    }

    private static Button danger(Button button) {
        button.addClass("folkways_danger");
        return button;
    }

    // Slots stand either side of the figure as in a character's inventory; accessory groups share a column.
    private UIElement board() {
        UIElement left = column(AlignItems.FLEX_END), right = column(AlignItems.FLEX_START);
        riding = button("riding", () -> {
            CompoundTag request = request("riding");
            request.putBoolean("riding", !shown.getBoolean("riding"));
            send(request);
        });
        right.addChildren(riding);
        accessories = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .flexWrap(FlexWrap.WRAP).width(SLOT * 3 + 4).gapAll(2));
        List<GearGroup> accessoryGroups = new ArrayList<>();
        for (GearGroup group : GearSlots.groups()) {
            List<GearSlot> slots = GearSlots.in(group.id());
            if (slots.isEmpty()) {
                continue;
            }
            switch (group.placement()) {
                case LEFT -> slots.forEach(slot -> left.addChildren(named(slot, true)));
                case RIGHT -> slots.forEach(slot -> right.addChildren(named(slot, false)));
                case ACCESSORY -> {
                    accessoryGroups.add(group);
                    slots.forEach(slot -> accessories.addChildren(slot(slot)));
                }
            }
            if (group.placement() != GearGroup.Placement.ACCESSORY) {
                (group.placement() == GearGroup.Placement.LEFT ? left : right).addChildren(new UIElement()
                    .layout(layout -> layout.height(2)));
            }
        }
        UIElement figure = new UIElement().layout(layout -> layout.width(FIGURE_WIDTH).alignSelf(AlignItems.STRETCH)
            .minHeight(SLOT * 4 + 6).flexShrink(0));
        figure.addClass("folkways_card");
        Tokens.name(figure, KEY + "figure");
        if (client) {
            dressed = EquipmentFaces.dressed(figure);
        }
        UIElement board = Rows.strip().addChildren(left, figure, right);
        board.layout(layout -> layout.alignItems(AlignItems.FLEX_START).gapAll(GAP + 2).widthAuto());
        if (!accessoryGroups.isEmpty()) {
            accessoryGroup = accessoryGroups.getFirst().id();
            Button choose = button("accessory_group", () -> { });
            choose.setOnClick(event -> chooseAccessories(choose, accessoryGroups));
            accessoryChoice = choose;
            UIElement extra = Rows.page().addChildren(choose, accessories);
            extra.layout(layout -> layout.widthAuto().gapAll(2));
            board.addChildren(extra);
            showAccessories(accessoryGroups);
        }
        return Rows.strip().addChildren(board);
    }

    private static UIElement column(AlignItems align) {
        return new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN).gapAll(2)
            .alignItems(align).flexShrink(0));
    }

    // A slot with its name on the side away from the figure; the name folds away on a narrow page.
    private UIElement named(GearSlot gear, boolean leftOfFigure) {
        Label label = Rows.value(gear.name());
        label.textStyle(style -> style.textColor(Rows.QUIET));
        slotNames.add(label);
        UIElement line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.CENTER).gapAll(GAP));
        UIElement slot = slot(gear);
        if (gear.riding()) {
            line.layout(layout -> layout.marginLeft(GAP));
            line.setDisplay(shown.getBoolean("riding"));
            ridingRows.add(line);
        }
        return leftOfFigure ? line.addChildren(label, slot) : line.addChildren(slot, label);
    }

    private UIElement slot(GearSlot gear) {
        UIElement slot = new UIElement().layout(layout -> layout.width(SLOT).height(SLOT).flexShrink(0));
        Tokens.name(slot, KEY + "slot." + gear.id().getNamespace() + "." + gear.id().getPath());
        slot.style(style -> style.tooltips(gear.name()));
        if (client) {
            EquipmentFaces.Shown face = EquipmentFaces.slot(slot);
            face.empty = gear.emptyIcon().orElse(null);
            face.atlas = gear.atlasIcon();
            slotFaces.put(gear.id(), face);
        }
        slot.addEventListener(UIEvents.CLICK, event -> slotClicked(slot, gear));
        slotElements.put(gear.id(), slot);
        return slot;
    }

    private void chooseAccessories(UIElement host, List<GearGroup> groups) {
        List<Picker.Row> rows = groups.stream().map(group -> new Picker.Row(group.id().toString(), group.name(),
            ItemStack.EMPTY)).toList();
        Picker.open(host, text("accessories"), rows, accessoryGroup.toString(), picked -> {
            accessoryGroup = ResourceLocation.parse(picked);
            showAccessories(groups);
        });
    }

    private void showAccessories(List<GearGroup> groups) {
        groups.stream().filter(group -> group.id().equals(accessoryGroup)).findFirst().ifPresent(group ->
            ((Button) accessoryChoice).setText(text("accessories").copy().append(" · ").append(group.name())
                .append(" ▾")));
        slotElements.forEach((id, element) -> GearSlots.slot(id).ifPresent(gear -> {
            if (GearSlots.group(gear.group()).filter(group -> group.placement() == GearGroup.Placement.ACCESSORY)
                .isPresent()) {
                element.setDisplay(gear.group().equals(accessoryGroup));
            }
        }));
    }

    private UIElement carriedHeading() {
        UIElement line = Rows.heading(text("carried"));
        Button add = button("add", this::inventoryPicker);
        add.style(style -> style.tooltips(text("add_root")));
        line.addChildren(add);
        line.layout(layout -> layout.height(Rows.ACT_SIZE).gapAll(GAP));
        return line;
    }

    private void inventoryPicker() {
        CompoundTag request = request("add");
        ListTag variants = shown.getList("weaponChoices", Tag.TAG_COMPOUND);
        if (variants.isEmpty()) {
            pick(request, text("items"), EquipmentPage::rootable);
            return;
        }
        List<Picker.Row> rows = List.of(new Picker.Row("items", text("pick_items"), ItemStack.EMPTY),
            new Picker.Row("variants", text("weapon_variants"), ItemStack.EMPTY));
        Picker.open(details, text("items"), rows, null, picked -> {
            if (picked.equals("variants")) {
                pickVariant(details, request, variants);
            } else {
                pick(request, text("items"), EquipmentPage::rootable);
            }
        });
    }

    private UIElement chips() {
        chips = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100)
            .gapAll(3));
        Tokens.name(chips, KEY + "carried_list");
        return chips;
    }

    private static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    // Every item can be ordinary personal inventory, including loose storage items and upgrades.
    private static boolean rootable(Item item) {
        return true;
    }

    private static List<ResourceLocation> kinds(CompoundTag row) {
        return row.getList("kinds", Tag.TAG_STRING).stream().map(Tag::getAsString).map(ResourceLocation::parse)
            .toList();
    }

    private static Predicate<Item> holdable(CompoundTag parent) {
        List<ResourceLocation> kinds = kinds(parent);
        return item -> !kinds.isEmpty() && kinds.stream().allMatch(kind -> EquipmentKits.acceptsChild(kind, id(item)));
    }

    // Entries a parent holds, in profile order; an entry whose parent is gone stands at the top level.
    private static Map<UUID, List<Integer>> children(ListTag carried) {
        Set<UUID> present = new HashSet<>();
        carried.forEach(row -> present.add(((CompoundTag) row).getUUID("id")));
        Map<UUID, List<Integer>> children = new LinkedHashMap<>();
        for (int n = 0; n < carried.size(); n++) {
            CompoundTag row = carried.getCompound(n);
            UUID parent = row.hasUUID("parent") && present.contains(row.getUUID("parent")) ? row.getUUID("parent")
                : null;
            children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(n);
        }
        return children;
    }

    private void carriedList(ListTag carried, HolderLookup.Provider registries) {
        chips.clearAllChildren();
        if (carried.isEmpty()) {
            chips.addChildren(Rows.note(text("no_carried")));
            return;
        }
        Map<UUID, List<Integer>> children = children(carried);
        level(chips, children.getOrDefault(null, List.of()), carried, children, registries);
    }

    // One level of the tree: plain entries share wrapping lines, and each parent stands whole with what it holds.
    private void level(UIElement into, List<Integer> members, ListTag carried, Map<UUID, List<Integer>> children,
                       HolderLookup.Provider registries) {
        UIElement line = null;
        for (int n : members) {
            CompoundTag row = carried.getCompound(n);
            if (row.contains("slots_total") || children.containsKey(row.getUUID("id"))) {
                line = null;
                into.addChildren(group(row, n, carried, children, registries));
                continue;
            }
            if (line == null) {
                line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                    .flexWrap(FlexWrap.WRAP).widthPercent(100).gapAll(3));
                into.addChildren(line);
            }
            line.addChildren(chip(row, n, registries, false));
        }
    }

    // A parent's own line with its add button, then what it holds, set in under a thread from its slot.
    private UIElement group(CompoundTag row, int index, ListTag carried, Map<UUID, List<Integer>> children,
                            HolderLookup.Provider registries) {
        UIElement head = Rows.strip().addChildren(chip(row, index, registries, true));
        head.layout(layout -> layout.gapAll(GAP));
        if (row.contains("slots_total")) {
            int used = row.getInt("slots_used"), total = row.getInt("slots_total");
            Component label = Pages.readText(registries, row.get("label"));
            Button add = button("add_child." + index, () -> pick(child(row), text("add_to", label), holdable(row)));
            add.setText(text("add"));
            boolean room = used < total;
            add.setActive(room);
            add.style(style -> style.tooltips(room ? text("add_child", label)
                : text("child_full", label, Pages.readText(registries, row.get("slots_name")))));
            head.addChildren(add);
        }
        UIElement inside = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .flexGrow(1).flexShrink(1).minWidth(0).gapAll(3));
        List<Integer> held = children.getOrDefault(row.getUUID("id"), List.of());
        if (held.isEmpty()) {
            Label none = Rows.note(text("holds_nothing"));
            none.layout(layout -> layout.paddingTop(1));
            inside.addChildren(none);
        } else {
            level(inside, held, carried, children, registries);
        }
        UIElement thread = new UIElement().layout(layout -> layout.width(1).alignSelf(AlignItems.STRETCH)
            .flexShrink(0).marginLeft(SLOT / 2 + 1));
        thread.style(style -> style.background(new ColorRectTexture(THREAD)));
        UIElement body = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100)
            .gapAll(SLOT / 2 - 1)).addChildren(thread, inside);
        UIElement group = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .widthPercent(100).gapAll(3)).addChildren(head, body);
        Tokens.name(group, KEY + "carried_group." + index);
        return group;
    }

    private CompoundTag child(CompoundTag parent) {
        CompoundTag request = request("add");
        request.putUUID("parent", parent.getUUID("id"));
        request.putBoolean("each", true);
        return request;
    }

    // A carried entry: its item in a slot, its name, and how many are kept and when they are restocked. A parent's
    // line counts its slots instead; an entry past its parent's slots says it has no room.
    private UIElement chip(CompoundTag row, int index, HolderLookup.Provider registries, boolean wide) {
        UIElement tile = new UIElement().layout(layout -> layout.width(SLOT).height(SLOT).flexShrink(0));
        if (client) {
            EquipmentFaces.Shown face = EquipmentFaces.slot(tile);
            face.stack = ItemStack.parseOptional(registries, row.getCompound("stack"));
            face.mark = EquipmentDesk.State.valueOf(row.getString("state")).color;
        }
        Label label = Rows.name(Pages.readText(registries, row.get("label")));
        Component slots = slots(row, registries);
        boolean over = row.contains("slots_total") && row.getInt("slots_used") > row.getInt("slots_total");
        Label amount = Rows.value(row.getBoolean("beyond") ? text("no_room")
            : row.contains("slots_total") ? slots : Component.literal(row.getInt("target") + " / "
                + row.getInt("threshold")));
        amount.textStyle(style -> style.textColor(row.getBoolean("beyond") || over ? ALERT : Rows.QUIET));
        UIElement words = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .flexGrow(1).flexShrink(1).minWidth(0).gapAll(0)).addChildren(label, amount);
        UIElement chip = new UIElement().layout(layout -> {
            layout.flexDirection(FlexDirection.ROW).height(SLOT + 4).alignItems(AlignItems.CENTER).gapAll(3)
                .paddingAll(1).paddingRight(3);
            if (wide) {
                layout.flexGrow(1).flexShrink(1).minWidth(0);
            } else {
                layout.width(CHIP_WIDTH).flexGrow(1).maxWidth(CHIP_WIDTH * 3 / 2);
            }
        });
        chip.addClass("folkways_row");
        chip.setOverflowVisible(false);
        chip.addChildren(tile, words);
        Component status = Pages.readText(registries, row.get("status"));
        Component amounts = row.contains("slots_total") ? slots
            : text("amounts", row.getInt("target"), row.getInt("threshold"));
        chip.style(style -> style.tooltips(Pages.readText(registries, row.get("label")), amounts, status,
            Component.literal(row.getString("spec")).withStyle(ChatFormatting.DARK_GRAY)));
        UUID id = row.getUUID("id");
        chip.addEventListener(UIEvents.CLICK, event -> edit(id));
        Tokens.name(chip, KEY + "carried." + index);
        return chip;
    }

    private static Component slots(CompoundTag row, HolderLookup.Provider registries) {
        return text("slots_figure", Pages.readText(registries, row.get("slots_name")), row.getInt("slots_used"),
            row.getInt("slots_total"));
    }

    private void slotClicked(UIElement host, GearSlot gear) {
        if (!shown.hasUUID("profile")) {
            return;
        }
        Optional<CompoundTag> filled = slotRow(gear.id());
        if (filled.isPresent()) {
            edit(filled.get().getUUID("id"));
            return;
        }
        CompoundTag request = request("slot");
        request.putString("slot", gear.id().toString());
        choose(host, gear, request);
    }

    // Named slots use their real rules. Weapon variants are offered by the inventory picker.
    private void choose(UIElement host, GearSlot gear, CompoundTag request) {
        pick(request, gear.name(), fits(gear));
    }

    // A slot's picker offers only what can be worn there.
    private Predicate<Item> fits(GearSlot gear) {
        CompoundTag choices = shown.getCompound("slot_choices");
        if (choices.contains(gear.id().toString())) {
            Set<String> allowed = new HashSet<>(choices.getList(gear.id().toString(), Tag.TAG_STRING).stream()
                .map(Tag::getAsString).toList());
            return item -> allowed.contains(id(item).toString());
        }
        return item -> gear.fits(new ItemStack(item));
    }

    private void pickVariant(UIElement host, CompoundTag request, ListTag choices) {
        var registries = Minecraft.getInstance().level.registryAccess();
        List<Picker.Row> offers = new ArrayList<>();
        for (int n = 0; n < choices.size(); n++) {
            ItemStack stack = ItemStack.parseOptional(registries, choices.getCompound(n).getCompound("stack"));
            offers.add(new Picker.Row(Integer.toString(n), stack.getHoverName(), stack,
                stack.getItem().builtInRegistryHolder().key().location().getNamespace(),
                stack.getItem().builtInRegistryHolder().tags().map(tag -> tag.location().toString()).toList()));
        }
        Picker.openMany(host, text("weapon_variants"), offers, Set.of(), "folkways.selector", picked -> {
            List<ItemSpec> specs = new ArrayList<>();
            for (String key : picked) {
                specs.add(ItemSpec.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),
                    choices.getCompound(Integer.parseInt(key)).get("items")).getOrThrow());
            }
            if (!specs.isEmpty()) {
                request.put("items", ItemSpec.anyOf(specs).save(registries));
                send(request);
            }
        });
    }

    private void pick(CompoundTag request) {
        ItemPicker.many(details, text("items"), List.of(), false, true,
            filters -> items.send(request.toString(), filters));
    }

    private void pick(CompoundTag request, Component title, Predicate<Item> shown) {
        ItemPicker.many(details, title, List.of(), shown, true, filters -> items.send(request.toString(), filters));
    }

    private static ItemSpec spec(List<ItemFilter> filters) {
        List<ItemSpec> specs = new ArrayList<>();
        for (ItemFilter filter : filters) {
            switch (filter.kind()) {
                case ITEM -> specs.add(ItemSpec.of(filter.id()));
                case TAG -> specs.add(ItemSpec.of(TagKey.create(Registries.ITEM, filter.id())));
                case QUERY -> ItemQuery.parse(filter.text())
                    .ifPresent(query -> query.items().forEach(item -> specs.add(ItemSpec.of(item))));
            }
        }
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("empty entry");
        }
        return ItemSpec.anyOf(specs);
    }

    @Override
    public void windows(Player player, Windows desk) {
        windowsResidents(desk);
        editorBody = editorBody();
        // Sized to the editor's few lines rather than a page-sized window of empty paper.
        editor = desk.add("equipment_entry", text("entry"), editorBody, Windows.WIDTH, ENTRY_HEIGHT);
        editor.onDismiss(() -> focused = null);
    }

    private void windowsResidents(Windows desk) {
        UIElement table = Rows.table();
        table.layout(layout -> layout.minWidth(270));
        for (int n = 0; n < EquipmentDesk.RESIDENT_PAGE; n++) {
            int index = n;
            Label resident = Rows.name(Component.empty());
            resident.layout(layout -> layout.width(70).flexGrow(0));
            UIElement job = SignLine.of();
            job.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
            Button choose = button("resident." + n, () -> chooseResident(residentButtons.get(index), index));
            choose.layout(layout -> layout.width(96).flexShrink(0));
            limit(choose);
            UIElement row = Rows.row().addChildren(resident, job, choose);
            residentNames.add(resident);
            residentJobs.add(job);
            residentButtons.add(choose);
            residentRows.add(row);
            table.addChildren(row);
        }
        var scroll = Rows.fills(Rows.wideBox());
        scroll.addScrollViewChild(table);
        residentRange.textStyle(style -> style.textColor(Rows.QUIET));
        residentPager = Rows.strip().addChildren(
            button("previous", () -> page(-EquipmentDesk.RESIDENT_PAGE)), residentRange,
            button("next", () -> page(EquipmentDesk.RESIDENT_PAGE)));
        residentPager.layout(layout -> layout.gapAll(GAP));
        residentPager.setDisplay(false);
        UIElement body = Rows.fills(Rows.page()).addChildren(scroll, residentPager, assignmentNotice);
        assignments = desk.add("equipment_residents", text("assign_title"), body);
    }

    private void page(int by) {
        CompoundTag request = request("page");
        request.putInt("offset", Math.max(0, shown.getInt("residentOffset") + by));
        send(request);
    }

    // The entry editor: replace or remove a slot's item; a carried entry also keeps its amounts and place.
    private UIElement editorBody() {
        editorIcon = new UIElement().layout(layout -> layout.width(SLOT).height(SLOT).flexShrink(0));
        if (client) {
            editorFace = EquipmentFaces.slot(editorIcon);
        }
        editorName = Rows.name(Component.empty());
        editorStatus = Rows.note(Component.empty());
        UIElement identity = Rows.strip().addChildren(editorIcon, editorName);
        target = number(1);
        threshold = number(0);
        Tokens.name(target, KEY + "target");
        Tokens.name(threshold, KEY + "threshold");
        Button save = button("amount", () -> {
            CompoundTag request = entry("amount");
            try {
                request.putInt("target", Integer.parseInt(target.getText()));
                request.putInt("threshold", Integer.parseInt(threshold.getText()));
                send(request);
            } catch (NumberFormatException ignored) {
            }
        });
        Label keep = Rows.value(text("target")), low = Rows.value(text("threshold"));
        amounts = Rows.strip().addChildren(keep, target, low, threshold, save);
        amounts.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(GAP));
        moves = Rows.strip().addChildren(button("left", () -> send(entry("left"))),
            button("right", () -> send(entry("right"))));
        moves.layout(layout -> layout.widthAuto().gapAll(2));
        removeButton = danger(button("remove", () -> {
            int inside = descendants(focused);
            if (inside > 0 && !focused.equals(armed)) {
                armed = focused;
                removeButton.setText(text("remove_confirm", inside));
                return;
            }
            send(entry("remove"));
            editor.close();
        }));
        UIElement buttons = Rows.strip().addChildren(button("edit", this::replace), removeButton, Rows.spacer(),
            moves);
        buttons.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(GAP));
        UIElement body = Rows.page().addChildren(identity, editorStatus, amounts, buttons, editorNotice);
        body.layout(layout -> layout.minWidth(220));
        return body;
    }

    private void replace() {
        Optional<CompoundTag> row = entryRow(focused);
        if (row.isEmpty()) {
            return;
        }
        CompoundTag request = entry("edit");
        Optional<GearSlot> gear = row.get().contains("slot")
            ? GearSlots.slot(ResourceLocation.parse(row.get().getString("slot"))) : Optional.empty();
        if (gear.isPresent()) {
            choose(editorBody, gear.get(), request);
            return;
        }
        Optional<CompoundTag> parent = row.get().hasUUID("parent") ? entryRow(row.get().getUUID("parent"))
            : Optional.empty();
        pick(request, text("items"), parent.map(EquipmentPage::holdable).orElse(EquipmentPage::rootable));
    }

    private void edit(UUID entry) {
        focused = entry;
        showEditor(true);
        editor.open();
    }

    private void showEditor(boolean reset) {
        Optional<CompoundTag> found = entryRow(focused);
        if (found.isEmpty() || editorName == null) {
            if (editor != null && editor.isOpen() && focused != null) {
                editor.close();
            }
            return;
        }
        CompoundTag row = found.get();
        var registries = Minecraft.getInstance().level.registryAccess();
        Component label = Pages.readText(registries, row.get("label"));
        editor.title(label);
        editorName.setText(label.copy().withStyle(ChatFormatting.BOLD));
        Component status = Pages.readText(registries, row.get("status"));
        editorStatus.setText(row.contains("slots_total") ? slots(row, registries).copy().append(" · ").append(status)
            : status);
        if (editorFace != null) {
            editorFace.stack = ItemStack.parseOptional(registries, row.getCompound("stack"));
            editorFace.mark = EquipmentDesk.State.valueOf(row.getString("state")).color;
        }
        boolean carried = !row.contains("slot");
        // A parent is always one physical item; its slots, not an amount, are what can change.
        amounts.setDisplay(carried && !row.contains("slots_total"));
        moves.setDisplay(carried);
        int inside = descendants(focused);
        if (reset || armed != null && !armed.equals(focused)) {
            armed = null;
        }
        if (armed == null) {
            removeButton.setText(inside > 0 ? text("remove_with", inside) : text("remove"));
        }
        removeButton.style(style -> style.tooltips(inside > 0 ? text("remove_cascade", inside) : text("remove")));
        if (reset && carried) {
            target.setText(Integer.toString(row.getInt("target")));
            threshold.setText(Integer.toString(row.getInt("threshold")));
        }
    }

    // How many entries go with this one when it is removed: everything it holds, at any depth.
    private int descendants(UUID entry) {
        if (entry == null) {
            return 0;
        }
        int count = 0;
        for (Tag value : shown.getList("carried", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (row.hasUUID("parent") && row.getUUID("parent").equals(entry)) {
                count += 1 + descendants(row.getUUID("id"));
            }
        }
        return count;
    }

    private Optional<CompoundTag> slotRow(ResourceLocation slot) {
        return shown.getList("slots", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
            .filter(row -> row.getString("slot").equals(slot.toString())).findFirst();
    }

    private Optional<CompoundTag> entryRow(UUID entry) {
        if (entry == null) {
            return Optional.empty();
        }
        for (String list : List.of("slots", "carried")) {
            for (Tag row : shown.getList(list, Tag.TAG_COMPOUND)) {
                if (((CompoundTag) row).getUUID("id").equals(entry)) {
                    return Optional.of((CompoundTag) row);
                }
            }
        }
        return Optional.empty();
    }

    private void chooseResident(UIElement host, int index) {
        ListTag residents = shown.getList("residents", Tag.TAG_COMPOUND);
        if (index >= residents.size()) {
            return;
        }
        CompoundTag resident = residents.getCompound(index).copy();
        ListTag choices = shown.getList("profiles", Tag.TAG_COMPOUND).copy();
        List<Picker.Leaf> options = new ArrayList<>();
        options.add(new Picker.Leaf("none", "", text("none"), KEY + "choose.none"));
        for (Tag value : choices) {
            CompoundTag profile = (CompoundTag) value;
            String id = profile.getUUID("id").toString();
            options.add(new Picker.Leaf(id, "", Component.literal(profile.getString("name")), KEY + "choose." + id));
        }
        // A profile's key is its UUID: the picker's default tooltip would show it raw, so the rows carry none.
        Picker picker = new Picker(KEY + "assignment", false).immediate(true).size(210, 260)
            .tooltips(leaf -> new Component[0]);
        picker.fill(options);
        Component who = Pages.readText(Minecraft.getInstance().level.registryAccess(), resident.get("name"));
        picker.open(host, text("profile_for", who), Set.of(), picked -> {
            String key = picked.getFirst();
            CompoundTag request = new CompoundTag();
            request.putString("action", "assign_resident");
            request.putUUID("resident", resident.getUUID("id"));
            if (resident.hasUUID("assigned")) {
                request.putUUID("expected", resident.getUUID("assigned"));
            }
            if (!key.equals("none")) {
                CompoundTag profile = choices.stream().map(CompoundTag.class::cast)
                    .filter(value -> value.getUUID("id").toString().equals(key)).findFirst().orElseThrow();
                request.putUUID("profile", profile.getUUID("id"));
                request.putLong("revision", profile.getLong("revision"));
            }
            send(request);
        });
    }

    private TextField number(int least) {
        TextField field = new TextField();
        field.setNumbersOnlyInt(least, 4096);
        field.setText(Integer.toString(least));
        field.layout(layout -> layout.width(36).flexShrink(0));
        return field;
    }

    private Button button(String action, Runnable click) {
        String label = action.contains(".") ? action.substring(0, action.indexOf('.')) : action;
        Button button = new Button().setText(text(label)).setOnClick(event -> click.run());
        button.layout(layout -> layout.widthAuto().flexShrink(0));
        Tokens.name(button, KEY + action);
        return button;
    }

    private static void limit(Button button) {
        button.setOverflowVisible(false);
        button.text.layout(layout -> layout.widthPercent(100).height(12).flexShrink(1).minWidth(0));
        button.textStyle(style -> style.adaptiveWidth(false).adaptiveHeight(false).textWrap(TextWrap.HOVER_ROLL));
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

    private static CompoundTag named(CompoundTag request, String name) {
        request.putString("name", name);
        return request;
    }

    private CompoundTag entry(String action) {
        CompoundTag request = request(action);
        if (focused != null) {
            request.putUUID("entry", focused);
        }
        return request;
    }

    private void send(CompoundTag request) {
        sent = ++sequence + "\n" + request;
    }

    private boolean stackedProfiles;

    private void profileButtons(boolean narrow) {
        stackedProfiles = narrow;
        for (UIElement child : profileList.getChildren()) {
            child.layout(layout -> {
                if (narrow && child == createButton) {
                    layout.widthAuto();
                } else if (narrow) {
                    layout.width(PROFILES_WIDTH - 12);
                } else {
                    layout.widthPercent(100);
                }
            });
        }
    }

    private void fill(Player player, Tag value) {
        if (!(value instanceof CompoundTag tag)) {
            return;
        }
        CompoundTag previous = shown;
        shown = tag;
        var registries = player.registryAccess();
        String schema = GearSlots.all().stream().map(gear -> gear.id() + ":" + gear.name().getString())
            .collect(java.util.stream.Collectors.joining(";"));
        if (!schema.equals(gearSchema)) {
            gearSchema = schema;
            slotElements.clear();
            slotFaces.clear();
            slotNames.clear();
            ridingRows.clear();
            boardHost.clearAllChildren();
            boardHost.addChildren(board());
        }
        riding.setText(text("riding").copy().append(shown.getBoolean("riding") ? " ☑" : " ☐"));
        ridingRows.forEach(row -> row.setDisplay(shown.getBoolean("riding")));
        boolean chosen = tag.hasUUID("profile");
        if (chosen && (!previous.hasUUID("profile") || !tag.getUUID("profile").equals(previous.getUUID("profile"))
            || !tag.getString("name").equals(previous.getString("name")))) {
            name.setText(tag.getString("name"));
        }
        if (!tag.getList("profiles", Tag.TAG_COMPOUND).equals(previous.getList("profiles", Tag.TAG_COMPOUND))
            || chosen != previous.hasUUID("profile")
            || chosen && !tag.getUUID("profile").equals(previous.getUUID("profile"))) {
            profileList.clearAllChildren();
            ListTag profiles = tag.getList("profiles", Tag.TAG_COMPOUND);
            for (int n = 0; n < profiles.size(); n++) {
                CompoundTag row = profiles.getCompound(n);
                UUID id = row.getUUID("id");
                Button button = button("profile." + n, () -> {
                    CompoundTag request = request("select");
                    request.putUUID("profile", id);
                    send(request);
                });
                button.setText(Component.literal(row.getString("name")));
                button.style(style -> style.tooltips(Component.literal(row.getString("name")),
                    text("figure.assigned").copy().append(" " + row.getInt("assigned"))));
                limit(button);
                if (chosen && id.equals(tag.getUUID("profile"))) {
                    button.addClass("folkways_chosen");
                }
                profileList.addChildren(button);
            }
            profileList.addChildren(createButton);
            profileButtons(stackedProfiles);
        }
        details.setDisplay(chosen);
        empty.setDisplay(!chosen);
        figureRow.clearAllChildren();
        for (Tag line : tag.getList("figures", Tag.TAG_COMPOUND)) {
            figureRow.addChildren(Rows.value(Pages.readText(registries, ((CompoundTag) line).get("text"))));
        }
        dressed.clear();
        slotElements.forEach((id, element) -> {
            Optional<CompoundTag> row = slotRow(id);
            Component slotName = GearSlots.slot(id).map(GearSlot::name).orElse(Component.empty());
            EquipmentFaces.Shown face = slotFaces.get(id);
            ItemStack stack = row.map(found -> ItemStack.parseOptional(registries, found.getCompound("stack")))
                .orElse(ItemStack.EMPTY);
            if (face != null) {
                face.stack = stack;
                face.mark = row.map(found -> EquipmentDesk.State.valueOf(found.getString("state")).color).orElse(0);
            }
            if (!stack.isEmpty()) {
                dressed.put(id, stack);
            }
            if (row.isPresent()) {
                // Avoid repeating a slot name when its item has the same name.
                Component worn = Pages.readText(registries, row.get().get("label"));
                element.style(style -> style.tooltips(worn.getString().equals(slotName.getString())
                    ? new Component[] {worn, Pages.readText(registries, row.get().get("status"))}
                    : new Component[] {slotName, worn, Pages.readText(registries, row.get().get("status"))}));
            } else {
                element.style(style -> style.tooltips(slotName, text("empty_slot")));
            }
        });
        ListTag carried = tag.getList("carried", Tag.TAG_COMPOUND);
        if (!carried.equals(previous.getList("carried", Tag.TAG_COMPOUND))
            || !tag.getList("slots", Tag.TAG_COMPOUND).equals(previous.getList("slots", Tag.TAG_COMPOUND))
            || chips.getChildren().isEmpty()) {
            ListTag inventory = new ListTag();
            ListTag trees = new ListTag();
            tag.getList("slots", Tag.TAG_COMPOUND).forEach(row -> {
                if (((CompoundTag) row).contains("slots_total")) {
                    trees.add(row.copy());
                }
            });
            carried.forEach(value1 -> {
                CompoundTag row = (CompoundTag) value1;
                if (row.hasUUID("parent")) {
                    trees.add(row.copy());
                } else {
                    inventory.add(row.copy());
                }
            });
            carriedList(inventory, registries);
            wornTrees.clearAllChildren();
            Map<UUID, List<Integer>> nested = children(trees);
            level(wornTrees, nested.getOrDefault(null, List.of()), trees, nested, registries);
        }
        footer.setText(Pages.readText(registries, tag.get("footer")));
        notice.setText(Pages.readText(registries, tag.get("message")));
        assignmentNotice.setText(Pages.readText(registries, tag.get("message")));
        editorNotice.setText(Pages.readText(registries, tag.get("message")));
        if (editor != null && editor.isOpen()) {
            Optional<CompoundTag> now = entryRow(focused), was = Optional.empty();
            for (String list : List.of("slots", "carried")) {
                for (Tag row : previous.getList(list, Tag.TAG_COMPOUND)) {
                    if (focused != null && ((CompoundTag) row).getUUID("id").equals(focused)) {
                        was = Optional.of((CompoundTag) row);
                    }
                }
            }
            showEditor(now.isPresent() && (was.isEmpty() || now.get().getInt("target") != was.get().getInt("target")
                || now.get().getInt("threshold") != was.get().getInt("threshold")));
        }
        residents(registries, tag);
    }

    private void residents(HolderLookup.Provider registries, CompoundTag tag) {
        ListTag residents = tag.getList("residents", Tag.TAG_COMPOUND);
        int count = tag.getInt("residentCount"), offset = tag.getInt("residentOffset");
        if (residentPager != null) {
            residentPager.setDisplay(count > EquipmentDesk.RESIDENT_PAGE);
            residentRange.setText(Component.literal((offset + 1) + "–"
                + Math.min(count, offset + EquipmentDesk.RESIDENT_PAGE) + " / " + count));
        }
        for (int n = 0; n < residentButtons.size(); n++) {
            residentRows.get(n).setDisplay(n < residents.size());
            if (n < residents.size()) {
                CompoundTag row = residents.getCompound(n);
                residentNames.get(n).setText(Pages.readText(registries, row.get("name")));
                SignLine.show(residentJobs.get(n), Sentence.load(row.getList("job", Tag.TAG_COMPOUND)));
                Component assignment = Pages.readText(registries, row.get("assignment"));
                residentButtons.get(n).setText(assignment);
                residentButtons.get(n).style(style -> style.tooltips(assignment));
            }
        }
    }
}
