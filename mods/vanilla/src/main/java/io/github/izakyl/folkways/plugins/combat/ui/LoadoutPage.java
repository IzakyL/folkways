package io.github.izakyl.folkways.plugins.combat.ui;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.inventory.InventorySnapshot;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemQuery;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.SignLine;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.api.ui.Windows;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.combat.weapon.ObservationGear;
import io.github.izakyl.folkways.plugins.combat.weapon.ProfileShortages;
import io.github.izakyl.folkways.plugins.patrol.PatrolStaffing;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Commands carry the profile revision shown to the player; a stale editor cannot overwrite another keeper. */
public final class LoadoutPage implements Draw {
    private static final String KEY = "folkways.loadouts.";
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
        profiles.layout(layout -> layout.width(136).flexShrink(0));
        Tokens.name(profiles, KEY + "profiles");
        name = new TextField();
        name.setText("");
        name.textFieldStyle(style -> style.placeholder(Rows.placeholder(text("name"))));
        name.layout(layout -> layout.widthPercent(100).minWidth(0));
        Tokens.name(name, KEY + "name");
        UIElement actions = Rows.strip();
        actions.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(2));
        actions.addChildren(
            button("create", () -> send(named(request("create")))),
            button("rename", () -> send(named(request("rename")))),
            button("duplicate", () -> send(request("duplicate"))),
            button("delete", () -> send(request("delete"))));
        profiles.addChildren(Rows.heading(text("profiles")), name, actions,
            pager("profiles", () -> profileOffset -= 4, () -> profileOffset += 4));
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
            profiles.addChildren(button);
        }
        UIElement details = Rows.page();
        details.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).width(0));
        Tokens.name(details, KEY + "details");
        Button adding = button("add", () -> {
        });
        adding.setOnClick(event -> addMenu(adding, items));
        details.addChildren(summary, Rows.strip().addChildren(
            adding,
            button("assign_residents", () -> assignments.open())),
            Rows.note(text("help")),
            pager("entries", () -> entryOffset -= 6, () -> entryOffset += 6));
        UIElement table = Rows.table();
        table.layout(layout -> layout.minWidth(500));
        for (int n = 0; n < 6; n++) {
            int slot = n;
            UIElement icon = Rows.icon(ItemStack.EMPTY);
            Label label = Rows.name(Component.empty());
            label.layout(layout -> layout.minWidth(65));
            Label status = Rows.value(Component.empty());
            status.layout(layout -> layout.width(54).flexShrink(0));
            status.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL).adaptiveWidth(false));
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
            save.layout(layout -> layout.width(18).paddingAll(0));
            UIElement supply = Rows.strip().addChildren(low, save);
            supply.layout(layout -> layout.widthAuto().flexShrink(0).gapAll(2));
            UIElement row = Rows.row().addChildren(icon, label, status, amount, supply,
                button("up." + n, () -> send(entry(slot, "up"))),
                button("down." + n, () -> send(entry(slot, "down"))),
                button("remove." + n, () -> send(entry(slot, "remove"))),
                button("edit." + n, () -> pick(content, items, entry(slot, "edit"))));
            row.layout(layout -> layout.width(500).gapAll(2));
            Tokens.name(row, KEY + "entry." + n);
            entryRows.add(row);
            entryLabels.add(label);
            entryStatuses.add(status);
            entryIcons.add(icon);
            supplyControls.add(supply);
            table.addChildren(row);
        }
        var entries = Rows.wideBox();
        entries.layout(layout -> layout.height(160).flexShrink(0));
        Tokens.name(entries, KEY + "entry_list");
        entries.addScrollViewChild(table);
        details.addChildren(entries, notice);
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
                    layout.width(136);
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
            pager("residents", () -> residentOffset -= 8, () -> residentOffset += 8), assignmentNotice);
        assignments = desk.add("loadout_residents", text("assign_residents"), body);
    }

    private void chooseResident(UIElement host, int slot) {
        ListTag residents = shown.getList("residents", Tag.TAG_COMPOUND);
        if (slot >= residents.size()) {
            return;
        }
        CompoundTag resident = residents.getCompound(slot).copy();
        Dialog dialog = new Dialog();
        UIElement list = Rows.page();
        list.addChildren(assignmentChoice(dialog, resident, null));
        ListTag choices = shown.getList("assignmentChoices", Tag.TAG_COMPOUND);
        for (Tag value : choices) {
            list.addChildren(assignmentChoice(dialog, resident, (CompoundTag) value));
        }
        showMenu(dialog, host, text("assign_residents"), list, choices.size() + 1);
    }

    private Button assignmentChoice(Dialog dialog, CompoundTag resident, CompoundTag profile) {
        Button choose = new Button().setText(profile == null ? text("none")
            : Component.literal(profile.getString("name")));
        choose.layout(layout -> layout.widthPercent(100).flexShrink(0));
        limit(choose);
        choose.style(style -> style.tooltips(profile == null ? text("none")
            : Component.literal(profile.getString("name"))));
        Tokens.name(choose, KEY + "choose." + (profile == null ? "none" : profile.getUUID("id")));
        choose.setOnClick(event -> {
            CompoundTag request = new CompoundTag();
            request.putString("action", "assign_resident");
            request.putUUID("resident", resident.getUUID("id"));
            if (resident.hasUUID("assigned")) {
                request.putUUID("expected", resident.getUUID("assigned"));
            }
            if (profile != null) {
                request.putUUID("profile", profile.getUUID("id"));
                request.putLong("revision", profile.getLong("revision"));
            }
            send(request);
            dialog.close();
        });
        return choose;
    }

    private void addMenu(UIElement host, ItemPicker.Sending items) {
        CompoundTag base = request("add");
        Dialog dialog = new Dialog();
        UIElement list = Rows.page();
        list.addChildren(menuAdd(dialog, host, items, base, "weapon", null),
            button("weapon_variants", () -> {
                dialog.close();
                pickWeapon(host, base.copy());
            }), menuAdd(dialog, host, items, base, "supply", null),
            button("observation_gear", () -> {
                dialog.close();
                pickObservationGear(host, base.copy());
            }));
        for (EquipmentSlot slot :
            List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            list.addChildren(menuAdd(dialog, host, items, base, "armor", slot));
        }
        showMenu(dialog, host, text("add"), list, 8);
    }

    private Button menuAdd(Dialog dialog, UIElement host, ItemPicker.Sending items, CompoundTag base,
            String kind, EquipmentSlot slot) {
        Button add = button(slot == null ? kind : slot.getName(), () -> {
            CompoundTag request = base.copy();
            request.putString("kind", kind.toUpperCase(Locale.ROOT));
            if (slot != null) {
                request.putString("slot", slot.getName());
            }
            dialog.close();
            pick(host, items, request);
        });
        if (slot != null) {
            add.setText(text("armor").copy().append(" · ").append(text(slot.getName())));
        }
        return add;
    }

    private void showMenu(Dialog dialog, UIElement host, Component title, UIElement list, int count) {
        dialog.titleBar.addChildren(new Label().setText(title));
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(list);
        dialog.addContent(Rows.fills(Rows.page()).addChildren(scroll));
        Button close = button("close", dialog::close);
        dialog.addButton(close);
        ChoicePicker.show(dialog, host, 210, Math.min(260, 64 + count * 26));
    }

    private TextField number() {
        TextField field = new TextField();
        field.setNumbersOnlyInt(0, 4096);
        field.setText("1");
        field.layout(layout -> layout.width(32).flexShrink(0));
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

    private UIElement pager(String kind, Runnable previous, Runnable next) {
        // Paging is local UI state; requests still carry the row identities and server revision.
        return Rows.strip()
            .addChildren(
            button(
            "previous." + kind,
            () -> {
                previous.run();
                sendPage(kind);
            }),
            button(
            "next." + kind,
            () -> {
                next.run();
                sendPage(kind);
            }));
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
        List<ItemPicker.Offer> offers = new ArrayList<>();
        for (int n = 0; n < choices.size(); n++) {
            CompoundTag choice = choices.getCompound(n);
            ItemStack stack =
                ItemStack.parseOptional(
                Minecraft.getInstance().level.registryAccess(), choice.getCompound("stack"));
            offers.add(
                new ItemPicker.Offer(
                ItemFilter.item(ResourceLocation.fromNamespaceAndPath("folkways", "weapon/" + n)),
                stack.getHoverName(),
                stack));
        }
        ItemPicker.among(
            host,
            text("weapon_variants"),
            offers,
            List.of(),
            filters -> {
                List<ItemSpec> specs = new ArrayList<>();
                for (ItemFilter filter : filters) {
                    int index = Integer.parseInt(filter.id().getPath().substring("weapon/".length()));
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
        List<ItemPicker.Offer> offers = ObservationGear.choices().stream()
            .map(stack -> new ItemPicker.Offer(
                ItemFilter.item(stack.getItem().builtInRegistryHolder().key().location()),
                stack.getHoverName(), stack)).toList();
        ItemPicker.among(content, text("observation_gear"), offers, List.of(), picked -> {
            if (!picked.isEmpty()) {
                ListTag choices = new ListTag();
                picked.forEach(filter -> choices.add(StringTag.valueOf(filter.id().toString())));
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
                    profiles.replace(new LoadoutProfile(profile.id(), request.getString("name"), profile.entries()));
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
                        ItemSpec spec = decode(player, request);
                        LoadoutProfile.Kind kind = LoadoutProfile.Kind.valueOf(request.getString("kind"));
                        profiles.add(
                            profile.id(),
                            switch (kind) {
                                case WEAPON -> LoadoutProfile.Entry.weapon(spec);
                                case SUPPLY -> LoadoutProfile.Entry.supply(spec, 1, 1);
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
                LoadoutProfiles.withoutPins(request.getCompound("items")))
            .getOrThrow();
    }

    private void requireResident(Colony colony, UUID id) {
        if (colony.holdingOf(id).isEmpty()
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
            tag.put(
                "summary",
                Pages.text(
                player.registryAccess(),
                shortages.complete() ? text(
                    "summary", current.name(), shortages.assigned(), shortages.incomplete(), shortages.unloaded())
                    : text("inventory_pending")));
            Component staffing = Component.translatable("folkways.staffing.shortage", Component.literal(current.name()),
                PatrolStaffing.of(colony).shortageFor(current.id()));
            tag.put("summary", Pages.text(player.registryAccess(), Pages.readText(player.registryAccess(),
                tag.get("summary")).copy().append(" · ").append(staffing)));
            entryOffset = page(entryOffset, current.entries().size(), 6);
            for (LoadoutProfile.Entry entry :
                current.entries().subList(entryOffset, Math.min(current.entries().size(), entryOffset + 6))) {
                CompoundTag row = new CompoundTag();
                row.putUUID("id", entry.id());
                Component label = text(entry.kind().name().toLowerCase(Locale.ROOT));
                if (entry.slot().isPresent()) {
                    label = text(entry.slot().get().getName());
                }
                ItemStack display = Goods.display(entry.items());
                row.put("stack", display.saveOptional(server.registryAccess()));
                row.put("label", Pages.text(player.registryAccess(), display.getHoverName()));
                row.put("status", Pages.text(player.registryAccess(), !entry.available() ? text("invalid_entry")
                    : !shortages.complete() ? text("inventory_pending")
                    : text("missing_short", shortages.missing().getOrDefault(entry.id(), 0),
                        shortages.unavailableStock().getOrDefault(entry.id(), 0))));
                row.put("detail", Pages.text(player.registryAccess(), label.copy().append(" · ").append(
                    shortages.complete() ? text("missing", shortages.missing().getOrDefault(entry.id(), 0),
                        shortages.unavailableStock().getOrDefault(entry.id(), 0)) : text("inventory_pending"))));
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
        summary.setText(Pages.readText(player.registryAccess(), tag.get("summary")));
        notice.setText(Pages.readText(player.registryAccess(), tag.get("message")));
        assignmentNotice.setText(Pages.readText(player.registryAccess(), tag.get("message")));
        ListTag profiles = tag.getList("profiles", Tag.TAG_COMPOUND);
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
        for (int n = 0; n < 6; n++) {
            boolean visible = n < entries.size();
            entryRows.get(n).setDisplay(visible);
            if (!visible) {
                continue;
            }
            CompoundTag row = entries.getCompound(n);
            entryLabels.get(n).setText(Pages.readText(player.registryAccess(), row.get("label")));
            Component detail = Pages.readText(player.registryAccess(), row.get("detail"));
            entryLabels.get(n).style(style -> style.tooltips(detail, Component.literal(row.getString("spec"))));
            entryStatuses.get(n).setText(Pages.readText(player.registryAccess(), row.get("status")));
            entryStatuses.get(n).style(style -> style.tooltips(detail));
            ItemStack stack = ItemStack.parseOptional(player.registryAccess(), row.getCompound("stack"));
            entryIcons.get(n).style(style -> style.background(new ItemStackTexture(stack)).tooltips(detail));
            boolean supply = row.getString("kind").equals("SUPPLY");
            supplyControls.get(n).setDisplay(supply);
            amountFields.get(n).setActive(supply);
            if (n >= old.size()
                || !row.getUUID("id").equals(old.getCompound(n).getUUID("id"))
                || row.getInt("target") != old.getCompound(n).getInt("target")
                || row.getInt("threshold") != old.getCompound(n).getInt("threshold")) {
                amountFields.get(n).setText(Integer.toString(row.getInt("target")));
                thresholdFields.get(n).setText(Integer.toString(row.getInt("threshold")));
            }
        }
        ListTag residents = tag.getList("residents", Tag.TAG_COMPOUND);
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
}
