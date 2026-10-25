package io.github.izakyl.folkways.plugins.person.equipment;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.front.api.ui.Inlay;
import io.github.izakyl.folkways.front.api.ui.Nook;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Real inventory management inside the resident detail window, backed by server snapshots. */
public final class EquipmentInlay implements Inlay {
    private static final String KEY = "folkways.equipment.";
    private static final List<String> SLOTS = List.of("mainhand", "offhand", "head", "chest", "legs", "feet");
    private final List<Slot> slots = new ArrayList<>();
    private final List<Cell> pack = new ArrayList<>();
    private final List<Cell> player = new ArrayList<>();
    private final List<Button> targets = new ArrayList<>();
    private CompoundTag snapshot = new CompoundTag();
    private CompoundTag outbound = new CompoundTag();
    private int sequence;
    private int heard;
    private boolean pending;
    private boolean expanded;
    private String selected = "mainhand";
    private UIElement page;
    private UIElement content;
    private UIElement packGrid;
    private UIElement playerGrid;
    private Button heading;
    private Button lock;
    private Button unequip;
    private Button fill;
    private Button ammoFill;
    private Label selection;
    private Label current;
    private Label ammo;
    private Label status;
    private Label feedback;

    @Override
    public UIElement build(Nook nook) {
        page = Rows.page();
        Tokens.name(page, "folkways.resident.equipment");
        page.setDisplay(false);
        heading = button("open", "open", () -> { expanded = !expanded; refresh(); });
        selection = Rows.text(Component.empty());
        current = Rows.note(Component.empty());
        ammo = Rows.text(Component.empty());
        status = Rows.note(Component.empty());
        feedback = Rows.note(Component.empty());
        Tokens.name(selection, "folkways.equipment.selection");
        Tokens.name(current, "folkways.equipment.current_tool");
        Tokens.name(ammo, "folkways.equipment.ammo_count");
        Tokens.name(status, "folkways.equipment.availability");
        Tokens.name(feedback, "folkways.equipment.result");

        UIElement equipment = Rows.page();
        for (String name : SLOTS) {
            Slot slot = new Slot(name);
            slots.add(slot);
            equipment.addChildren(slot.row);
        }
        lock = button("lock", "lock", () -> {
            CompoundTag action = action("lock");
            action.putBoolean("value", !slot(selected).getBoolean("locked"));
            send(action);
        });
        unequip = button("unequip", "unequip", () -> send(action("unequip")));
        fill = button("fill", "fill", () -> send(action("fill")));
        UIElement choices = Rows.strip();
        for (int count : List.of(8, 16, 32)) {
            Button target = button("ammo_target." + count, "ammo_target", () -> {
                CompoundTag action = action("ammo_target");
                action.putInt("value", count);
                send(action);
            });
            target.setText(Component.literal(Integer.toString(count)));
            target.style(style -> style.tooltips(word("ammo_target", count)));
            targets.add(target);
            choices.addChildren(target);
        }
        ammoFill = button("ammo_fill", "ammo_fill", () -> send(action("ammo_fill")));
        packGrid = grid();
        playerGrid = grid();
        Tokens.name(packGrid, "folkways.equipment.resident_pack");
        Tokens.name(playerGrid, "folkways.equipment.player_pack");
        content = Rows.page().addChildren(Rows.note(word("hint")), equipment, selection,
            Rows.strip().addChildren(lock, unequip), fill, current, ammo,
            choices, ammoFill, status, feedback, Rows.heading(word("resident_pack")), packGrid,
            Rows.heading(word("player_pack")), Rows.note(word("player_near")), playerGrid);
        page.addChildren(heading, content);

        var requests = DataBindingBuilder.tagC2S(tag -> {
            if (!(tag instanceof CompoundTag action) || action.getInt("sequence") <= heard) { return; }
            heard = action.getInt("sequence");
            if (nook.viewer().orElse(null) instanceof ServerPlayer actor) {
                person(nook).ifPresent(resident -> ResidentEquipment.act(actor, resident, action));
            }
        }).build();
        requests.setRemoteDataSource(IDataSource.of(ignored -> { }, () -> outbound));
        page.addSyncValue(requests.getSyncValue());
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(nook))
            .onSyncReceived(tag -> {
                snapshot = tag instanceof CompoundTag data ? data.copy() : new CompoundTag();
                if (snapshot.getInt("ack") >= sequence) { pending = false; }
                refresh();
            }).build().getSyncValue());
        return page;
    }

    private CompoundTag reading(Nook nook) {
        CompoundTag data = new CompoundTag();
        if (nook.viewer().orElse(null) instanceof ServerPlayer actor) {
            data = person(nook).map(resident -> ResidentEquipment.read(actor, resident)).orElseGet(CompoundTag::new);
        }
        data.putInt("ack", heard);
        return data;
    }

    private static Optional<ResidentEntity> person(Nook nook) {
        return nook.subject().filter(ResidentEntity.class::isInstance).map(ResidentEntity.class::cast);
    }

    private CompoundTag action(String kind) {
        CompoundTag action = new CompoundTag();
        for (String key : List.of("resident", "ticket")) {
            if (snapshot.contains(key)) { action.put(key, snapshot.get(key).copy()); }
        }
        action.putString("kind", kind);
        action.putString("slot", selected);
        return action;
    }

    private void send(CompoundTag action) {
        if (pending || !snapshot.getBoolean("editable") || !snapshot.contains("resident") || !snapshot.contains("ticket")) { return; }
        action.putInt("sequence", ++sequence);
        outbound = action;
        pending = true;
        refresh();
    }

    private void equip(String source, CompoundTag entry) {
        CompoundTag action = action("equip");
        action.putString("source", source);
        action.putInt("index", entry.getInt("index"));
        send(action);
    }

    private void refresh() {
        boolean present = snapshot.contains("resident");
        page.setDisplay(present);
        content.setDisplay(expanded && present);
        heading.setText(word(expanded ? "close" : "open"));
        if (!present) { return; }
        for (Slot slot : slots) { slot.refresh(); }
        CompoundTag worn = slot(selected);
        boolean busy = worn.getBoolean("busy");
        boolean editable = snapshot.getBoolean("editable");
        boolean ready = !pending && editable && !busy;
        boolean empty = stack(worn.getCompound("stack")).isEmpty();
        selection.setText(word("selected", word("slot." + selected)));
        lock.setText(word(worn.getBoolean("locked") ? "unlock" : "lock"));
        lock.setActive(ready);
        unequip.setActive(ready && !empty);
        fill.setActive(!pending && editable);
        ItemStack tool = stack(snapshot.getCompound("current"));
        current.setText(word("current", tool.isEmpty() ? word("empty") : tool.getHoverName()));
        current.style(style -> style.tooltips(word("current_hint")));
        ammo.setText(word("ammo", snapshot.getInt("ammoCount"), snapshot.getInt("ammoTarget"), snapshot.getInt("loadedAmmo")));
        for (int index = 0; index < targets.size(); index++) {
            int count = List.of(8, 16, 32).get(index);
            targets.get(index).setText(Component.literal((snapshot.getInt("ammoTarget") == count ? "● " : "") + count));
            targets.get(index).setActive(!pending && editable && snapshot.getInt("ammoTarget") != count);
        }
        ammoFill.setActive(!pending && editable && snapshot.getBoolean("near"));
        status.setText(word(!editable ? "result.forbidden" : pending ? "pending" : snapshot.getBoolean("busy") ? "busy"
            : snapshot.getBoolean("near") ? "near" : "far"));
        String result = snapshot.getString("result");
        feedback.setText(result.isEmpty() ? Component.empty() : Component.translatable(result,
            java.util.Arrays.stream(snapshot.getIntArray("resultArgs")).boxed().toArray()));
        feedback.setDisplay(!result.isEmpty());
        refreshCells(packGrid, pack, snapshot.getList("pack", Tag.TAG_COMPOUND), "resident");
        refreshCells(playerGrid, player, snapshot.getList("player", Tag.TAG_COMPOUND), "player");
    }

    private CompoundTag slot(String name) {
        ListTag list = snapshot.getList("slots", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (name.equals(entry.getString("slot"))) { return entry; }
        }
        return new CompoundTag();
    }

    // Capacity changes alone rebuild this grid. Ordinary inventory updates keep all widgets and focus stable.
    private void refreshCells(UIElement grid, List<Cell> cells, ListTag list, String source) {
        if (cells.size() != list.size()) {
            grid.clearAllChildren();
            cells.clear();
            for (int index = 0; index < list.size(); index++) {
                Cell cell = new Cell(source);
                cells.add(cell);
                grid.addChildren(cell.press);
            }
        }
        for (int index = 0; index < list.size(); index++) { cells.get(index).refresh(list.getCompound(index)); }
    }

    private static UIElement grid() {
        UIElement grid = Rows.strip();
        grid.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(2));
        return grid;
    }

    private Button button(String token, String key, Runnable clicked) {
        Button button = new Button().setText(word(key)).setOnClick(event -> clicked.run());
        button.layout(layout -> layout.height(20).flexShrink(0));
        Tokens.name(button, "folkways.equipment." + token);
        return button;
    }

    private static Component word(String key, Object... arguments) {
        return Component.translatable(KEY + key, arguments);
    }

    private static ItemStack stack(CompoundTag tag) {
        return Client.stack(tag);
    }

    private static Component[] tooltip(ItemStack stack, Component reason) {
        return Client.tooltip(stack, reason);
    }

    // RegisteringUi also runs on dedicated servers. Only received client snapshots may touch ClientLevel.
    private static final class Client {
        private static ItemStack stack(CompoundTag tag) {
            var level = Minecraft.getInstance().level;
            return tag.isEmpty() || level == null ? ItemStack.EMPTY
                : ItemStack.parse(level.registryAccess(), tag).orElse(ItemStack.EMPTY);
        }

        private static Component[] tooltip(ItemStack stack, Component reason) {
            Minecraft minecraft = Minecraft.getInstance();
            List<Component> lines = stack.isEmpty() ? new ArrayList<>(List.of(word("empty")))
                : new ArrayList<>(stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL));
            if (!stack.isEmpty()) { lines.add(word("count", stack.getCount())); }
            lines.add(reason);
            return lines.toArray(Component[]::new);
        }
    }

    private final class Slot {
        private final String name;
        private final Button choose;
        private final UIElement icon = Rows.icon(ItemStack.EMPTY);
        private final Label item = Rows.name(Component.empty());
        private final UIElement row;

        private Slot(String name) {
            this.name = name;
            choose = button("slot." + name, "slot." + name, () -> { selected = name; refresh(); });
            choose.layout(layout -> layout.width(98));
            Tokens.name(item, "folkways.equipment.worn." + name);
            row = Rows.strip().addChildren(choose, icon, item);
        }

        private void refresh() {
            CompoundTag entry = slot(name);
            ItemStack stack = stack(entry.getCompound("stack"));
            icon.style(style -> style.background(new ItemStackTexture(stack)));
            choose.setText(Component.literal(name.equals(selected) ? "● " : "").append(word("slot." + name)));
            item.setText((stack.isEmpty() ? word("empty") : stack.getHoverName().copy())
                .copy().append(entry.getBoolean("locked") ? word("locked_suffix") : Component.empty())
                .append(entry.getBoolean("busy") ? word("busy_suffix") : Component.empty()));
            item.style(style -> style.tooltips(tooltip(stack, word("slot." + name))));
        }
    }

    private final class Cell {
        private final String source;
        private CompoundTag entry = new CompoundTag();
        private final Button press;
        private final UIElement icon = Rows.icon(ItemStack.EMPTY);

        private Cell(String source) {
            this.source = source;
            press = Rows.act();
            press.layout(layout -> layout.width(24).height(24));
            press.addChildren(icon);
            press.setOnClick(event -> equip(source, entry));
        }

        private void refresh(CompoundTag entry) {
            this.entry = entry.copy();
            ItemStack stack = stack(entry.getCompound("stack"));
            icon.style(style -> style.background(new ItemStackTexture(stack)));
            Tokens.name(press, "folkways.equipment." + source + "." + entry.getInt("index"));
            boolean remote = source.equals("player") && !snapshot.getBoolean("near") || entry.getBoolean("remote");
            boolean borrowed = entry.getBoolean("borrowed");
            boolean personal = entry.getBoolean("personal");
            boolean usable = entry.getBoolean("usable");
            boolean busy = slot(selected).getBoolean("busy");
            press.setActive(!pending && snapshot.getBoolean("editable") && !busy && !remote && !stack.isEmpty() && !borrowed && usable);
            Component reason = word(remote ? "far" : borrowed ? "borrowed" : !usable && !personal ? "cargo"
                : !usable ? "incompatible" : busy ? "busy" : "equip_hint", word("slot." + selected));
            press.style(style -> style.tooltips(tooltip(stack, reason)));
        }
    }
}
