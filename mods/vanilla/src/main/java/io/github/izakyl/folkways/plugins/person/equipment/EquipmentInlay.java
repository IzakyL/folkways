package io.github.izakyl.folkways.plugins.person.equipment;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.front.api.ui.Inlay;
import io.github.izakyl.folkways.front.api.ui.Nook;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private Button ammoAuto;
    private Label selection;
    private Label current;
    private Label ammo;
    private Label ammoAutoInfo;
    private Label recovery;
    private Label status;
    private Label feedback;
    private UIElement manual;
    private UIElement plansPage;
    private boolean showingPlans;
    private String template = "melee_guard";
    private String scope = "self";
    private DragPayload pressedCell;
    private double pressedX;
    private double pressedY;
    private Button pressedElement;
    private ItemStack dragPreview = ItemStack.EMPTY;
    private double dragDistance;
    private boolean dragging;
    private com.lowdragmc.lowdraglib2.gui.ui.ModularUI pressedUi;
    private Object pressedScreen;
    private boolean windowWasActive;
    private Button manualTab;
    private Button plansTab;
    private Button templatePrevious;
    private Button templateNext;
    private Label templateName;
    private Label templateContents;
    private Label planGoal;
    private Label planStatus;
    private Label planResults;
    private Label shield;
    private TextField templateInput;
    private Button templateSave;
    private Button planPreview;
    private Button planApply;
    private Button planRefill;
    private Button planCancel;
    private final List<Button> scopes = new ArrayList<>();
    private final List<Label> previewRows = new ArrayList<>();
    private UIElement previewList;

    @Override
    public UIElement build(Nook nook) {
        page = Rows.page();
        Tokens.name(page, "folkways.resident.equipment");
        page.setDisplay(false);
        // LDLib2 drag events are client-side preview events. Only a validated drop below sends
        // the existing one-shot, server-checked equip action.
        page.addEventListener(UIEvents.MOUSE_MOVE, this::moveDrag, true);
        page.addEventListener(UIEvents.MOUSE_UP, this::endPress, true);
        page.addEventListener(UIEvents.DRAG_END, event -> clearPress(), true);
        page.addEventListener(UIEvents.TICK, event -> tickPress());
        page.addEventListener(UIEvents.REMOVED, event -> cancelPress());
        page.addEventListener(UIEvents.MUI_CHANGED, event -> cancelPress());
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
        ammoAuto = button("ammo_auto", "ammo_auto.enabled", () -> {
            CompoundTag ammunition = snapshot.getCompound("plans").getCompound("ammunition");
            CompoundTag action = action("ammo_auto");
            action.putBoolean("value", !ammunition.getBoolean("enabled"));
            action.putString("scope", "self");
            send(action);
        });
        ammoAutoInfo = Rows.note(Component.empty());
        recovery = Rows.note(Component.empty());
        Tokens.name(ammoAuto, "folkways.equipment.ammo_auto");
        Tokens.name(ammoAutoInfo, "folkways.equipment.ammo_auto.state");
        Tokens.name(recovery, "folkways.resident.recovery");
        packGrid = inventoryGrid();
        playerGrid = inventoryGrid();
        Tokens.name(packGrid, "folkways.equipment.resident_pack");
        Tokens.name(playerGrid, "folkways.equipment.player_pack");
        manual = Rows.page().addChildren(Rows.note(word("hint")), equipment, selection,
            Rows.strip().addChildren(lock, unequip), fill, current, ammo,
            choices, ammoFill, ammoAuto, ammoAutoInfo, recovery,
            Rows.heading(word("resident_pack")), packGrid,
            Rows.heading(word("player_pack")), Rows.note(word("player_near")), playerGrid);
        manualTab = button("tab.manual", "plan.manual", () -> { showingPlans = false; refresh(); });
        plansTab = button("tab.plans", "plan.open", () -> { showingPlans = true; refresh(); });
        manualTab.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        plansTab.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        plansPage = buildPlans();
        shield = Rows.note(Component.empty());
        Tokens.name(shield, "folkways.equipment.shield");
        content = Rows.page().addChildren(Rows.strip().addChildren(manualTab, plansTab), status, feedback,
            shield, manual, plansPage);
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
        return action(kind, selected);
    }

    private CompoundTag action(String kind, String targetSlot) {
        CompoundTag action = new CompoundTag();
        for (String key : List.of("resident", "ticket")) {
            if (snapshot.contains(key)) { action.put(key, snapshot.get(key).copy()); }
        }
        action.putString("kind", kind);
        action.putString("slot", targetSlot);
        return action;
    }

    private void send(CompoundTag action) {
        if (pending || !snapshot.getBoolean("editable") || !snapshot.contains("resident") || !snapshot.contains("ticket")) { return; }
        action.putInt("sequence", ++sequence);
        outbound = action;
        pending = true;
        refresh();
    }

    private void equip(String source, int index, String targetSlot) {
        CompoundTag action = action("equip", targetSlot);
        action.putString("source", source);
        action.putInt("index", index);
        send(action);
    }

    private void beginPress(UIEvent event, Button sourceElement, String source, CompoundTag entry) {
        if (event.button != 0 || pending || !snapshot.getBoolean("editable")
                || !snapshot.hasUUID("resident") || !snapshot.hasUUID("ticket")
                || !snapshot.contains("resident")) {
            return;
        }
        ItemStack stack = stack(entry.getCompound("stack"));
        if (stack.isEmpty() || !entry.getBoolean("usable") || entry.getBoolean("borrowed")
                || entry.getBoolean("remote") || source.equals("player") && !snapshot.getBoolean("near")) {
            return;
        }
        cancelPress();
        pressedCell = new DragPayload(snapshot.getUUID("resident"), snapshot.getUUID("ticket"),
            source, entry.getInt("index"));
        pressedElement = sourceElement;
        dragPreview = stack.copy();
        dragDistance = 0;
        pressedX = event.x;
        pressedY = event.y;
        dragging = false;
        pressedUi = page.getModularUI();
        pressedScreen = Client.screen();
        windowWasActive = Client.windowActive();
    }

    private void moveDrag(UIEvent event) {
        if (pressedCell == null || dragging) { return; }
        if (!gestureCurrent() || pressedUi.getLastMouseDownButton() != 0 || !current(pressedCell)
                || pending || !snapshot.getBoolean("editable")) {
            cancelPress();
            return;
        }
        dragDistance = Math.hypot(event.x - pressedX, event.y - pressedY);
        if (dragDistance >= 5) {
            dragging = true;
            pressedElement.startDrag(pressedCell, new ItemStackTexture(dragPreview));
        }
    }

    private void endPress(UIEvent event) {
        if (pressedCell == null) { return; }
        if (!dragging) {
            if (event.button == 0 && event.target != null
                    && (event.target == pressedElement || pressedElement.isAncestorOf(event.target))
                    && gestureCurrent() && current(pressedCell)) {
                equip(pressedCell.source(), pressedCell.index(), selected);
            }
            clearPress();
        }
        // A drag remains live until DRAG_END, which follows DRAG_PERFORM when a slot accepts it.
    }

    private void drop(UIEvent event, String targetSlot) {
        if (event.dragHandler == null) { return; }
        Object value = event.dragHandler.getDraggingObject();
        if (value instanceof DragPayload payload && dragging && payload.equals(pressedCell)
                && gestureCurrent() && current(payload) && !pending
                && snapshot.getBoolean("editable") && !slot(targetSlot).getBoolean("busy")) {
            // ModularUI has already cleared its pressed button before DRAG_PERFORM. Let its normal
            // release finish the library drag, and detach our preview before send() refreshes widgets.
            clearPress();
            equip(payload.source(), payload.index(), targetSlot);
        }
        event.stopPropagation();
    }

    private boolean current(DragPayload payload) {
        return snapshot.hasUUID("resident") && snapshot.hasUUID("ticket")
            && payload.resident().equals(snapshot.getUUID("resident"))
            && payload.ticket().equals(snapshot.getUUID("ticket"));
    }

    private void clearPress() {
        pressedCell = null;
        pressedElement = null;
        dragPreview = ItemStack.EMPTY;
        dragDistance = 0;
        dragging = false;
        pressedUi = null;
        pressedScreen = null;
        windowWasActive = false;
    }

    private boolean gestureCurrent() {
        return pressedUi != null && page.getModularUI() == pressedUi && Client.screen() == pressedScreen
            && !(windowWasActive && !Client.windowActive());
    }

    private void tickPress() {
        if (pressedCell == null) { return; }
        if (!gestureCurrent() || pressedUi.getLastMouseDownButton() != 0) {
            cancelPress();
        } else {
            windowWasActive = Client.windowActive();
        }
    }

    private void cancelPress() {
        var ui = pressedUi;
        var source = pressedElement;
        clearPress();
        if (ui != null && source != null && ui.getDragHandler().isDragging()
                && ui.getDragHandler().dragSource == source) {
            ui.getDragHandler().stopDrag();
        }
    }

    private void refresh() {
        boolean present = snapshot.contains("resident");
        if (pressedCell != null && (!present || !current(pressedCell) || pending || !expanded || showingPlans)) {
            cancelPress();
        }
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
        CompoundTag ammunition = snapshot.getCompound("plans").getCompound("ammunition");
        boolean autoEnabled = ammunition.getBoolean("enabled");
        boolean declared = ammunition.getBoolean("declared");
        boolean automatic = ammunition.getBoolean("automatic");
        String ammoStatus = ammunition.getString("status");
        if (ammoStatus.isEmpty()) { ammoStatus = "none"; }
        ammoAuto.setText(word(autoEnabled ? "ammo_auto.enabled" : "ammo_auto.disabled"));
        ammoAuto.setActive(!pending && editable);
        ammoAuto.style(style -> style.tooltips(word("ammo_auto.hint")));
        Component ammoAvailability = word(!declared ? "ammo_auto.unavailable"
            : !automatic ? "ammo_auto.unsupported" : "ammo_auto.state." + ammoStatus);
        Component weapon = ammunition.getString("weapon").isEmpty()
            ? word("empty") : Client.itemName(ammunition.getString("weapon"));
        Component ammoItem = ammunition.getString("item").isEmpty()
            ? word("empty") : Client.itemName(ammunition.getString("item"));
        ammoAutoInfo.setText(word("ammo_auto.summary", ammoAvailability, weapon, ammoItem,
            ammunition.getInt("carried"), ammunition.getInt("loaded"), ammunition.getInt("total"),
            ammunition.getInt("target"), ammunition.getInt("needed")));
        String recoveryKey = snapshot.getString("recovery");
        recovery.setText(recoveryKey.isEmpty() ? Component.empty() : Component.translatable(recoveryKey));
        recovery.setDisplay(!recoveryKey.isEmpty());
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
        manual.setDisplay(!showingPlans);
        plansPage.setDisplay(showingPlans);
        manualTab.setText(word("plan.manual").copy().withColor(!showingPlans ? 0x226544 : Rows.INK));
        plansTab.setText(word("plan.open").copy().withColor(showingPlans ? 0x226544 : Rows.INK));
        int cooldown = snapshot.getInt("shieldCooldown");
        shield.setText(word(cooldown > 0 ? "shield.cooldown" : snapshot.getBoolean("shieldGuard") ? "shield.guarding" : "shield.ready",
            (cooldown + 19) / 20));
        shield.setDisplay(stack(slot("offhand").getCompound("stack")).canPerformAction(
            net.neoforged.neoforge.common.ItemAbilities.SHIELD_BLOCK));
        refreshPlans();
        refreshCells(packGrid, pack, snapshot.getList("pack", Tag.TAG_COMPOUND), "resident");
        refreshCells(playerGrid, player, snapshot.getList("player", Tag.TAG_COMPOUND), "player");
    }

    private UIElement buildPlans() {
        templatePrevious = button("plan.previous", "previous", () -> cycleTemplate(-1));
        templateNext = button("plan.next", "next", () -> cycleTemplate(1));
        templatePrevious.layout(layout -> layout.width(28));
        templateNext.layout(layout -> layout.width(28));
        templatePrevious.setText(Component.literal("‹"));
        templateNext.setText(Component.literal("›"));
        templatePrevious.style(style -> style.tooltips(word("plan.previous")));
        templateNext.style(style -> style.tooltips(word("plan.next")));
        templateName = Rows.name(Component.empty());
        templateContents = Rows.note(Component.empty());
        planGoal = Rows.text(Component.empty());
        planStatus = Rows.note(Component.empty());
        Tokens.name(templateName, "folkways.equipment.plan.template");
        Tokens.name(planStatus, "folkways.equipment.plan.status");
        UIElement recipients = grid();
        for (String name : List.of("self", "selection", "group")) {
            Button choice = button("plan.scope." + name, "plan.scope." + name, () -> { scope = name; refresh(); });
            choice.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
            scopes.add(choice);
            recipients.addChildren(choice);
        }
        planPreview = button("plan.preview", "plan.preview", () -> send(planAction("template_preview")));
        planApply = button("plan.apply", "plan.apply", () -> send(planAction("template_apply")));
        planRefill = button("plan.refill", "plan.refill", () -> send(planAction("refill")));
        planRefill.style(style -> style.tooltips(word("plan.refill_conditions")));
        planCancel = button("plan.cancel", "plan.cancel", () -> send(planAction("refill_cancel")));
        templateInput = new TextField();
        templateInput.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        templateInput.setTextRegexValidator(".{0,48}");
        templateInput.textFieldStyle(style -> style.placeholder(word("plan.name")));
        templateInput.setTextResponder(ignored -> refresh());
        Tokens.name(templateInput, "folkways.equipment.plan.name");
        templateSave = button("plan.save", "plan.save", () -> {
            CompoundTag action = action("template_save");
            action.putString("name", templateInput.getValue());
            send(action);
        });
        previewList = Rows.page();
        planResults = Rows.note(word("plan.results"));
        Tokens.name(previewList, "folkways.equipment.plan.results");
        var results = Rows.box();
        results.layout(layout -> layout.height(110).flexShrink(0));
        results.addScrollViewChild(previewList);
        return Rows.page().addChildren(Rows.note(word("plan.hint")),
            Rows.strip().addChildren(templatePrevious, templateName, templateNext), templateContents,
            Rows.strip().addChildren(templateInput, templateSave), recipients,
            Rows.strip().addChildren(planPreview, planApply), planGoal, planStatus,
            Rows.strip().addChildren(planRefill, planCancel), Rows.note(word("plan.refill_hint")), planResults, results);
    }

    private CompoundTag planAction(String kind) {
        CompoundTag action = action(kind);
        action.putString("template", template);
        action.putString("scope", scope);
        return action;
    }

    private void cycleTemplate(int step) {
        ListTag choices = snapshot.getCompound("plans").getList("templates", Tag.TAG_COMPOUND);
        if (choices.isEmpty()) { return; }
        int current = 0;
        for (int index = 0; index < choices.size(); index++) {
            if (choices.getCompound(index).getString("id").equals(template)) { current = index; break; }
        }
        template = choices.getCompound(Math.floorMod(current + step, choices.size())).getString("id");
        refresh();
    }

    private static Component templateName(CompoundTag entry) {
        if (entry.isEmpty()) { return word("plan.status.none"); }
        return entry.getBoolean("builtin") ? word("plan.template." + entry.getString("id")) : Component.literal(entry.getString("name"));
    }

    private void refreshPlans() {
        CompoundTag plans = snapshot.getCompound("plans");
        ListTag choices = plans.getList("templates", Tag.TAG_COMPOUND);
        CompoundTag chosen = new CompoundTag();
        for (int index = 0; index < choices.size(); index++) {
            if (choices.getCompound(index).getString("id").equals(template)) { chosen = choices.getCompound(index); break; }
        }
        if (chosen.isEmpty() && !choices.isEmpty()) { chosen = choices.getCompound(0); template = chosen.getString("id"); }
        templateName.setText(templateName(chosen));
        templatePrevious.setActive(choices.size() > 1);
        templateNext.setActive(choices.size() > 1);
        var contents = Component.empty();
        CompoundTag targets = chosen.getCompound("targets");
        for (String slot : SLOTS) {
            if (!targets.contains(slot)) { continue; }
            if (!contents.getString().isEmpty()) { contents.append(" · "); }
            contents.append(word("plan.target", word("slot." + slot), Client.itemName(targets.getString(slot))));
        }
        if (!chosen.isEmpty()) { contents.append(" · ").append(word("ammo_target", chosen.getInt("ammoTarget"))); }
        templateContents.setText(contents);
        boolean editable = snapshot.getBoolean("editable") && !pending;
        CompoundTag available = plans.getCompound("scopes");
        for (int index = 0; index < scopes.size(); index++) {
            String name = List.of("self", "selection", "group").get(index);
            CompoundTag members = available.getCompound(name);
            scopes.get(index).setText(Component.literal(scope.equals(name) ? "● " : "")
                .append(word("plan.scope." + name, members.getInt("count"))));
            scopes.get(index).setActive(!pending);
            var names = Component.empty();
            ListTag people = members.getList("members", Tag.TAG_COMPOUND);
            for (int at = 0; at < people.size(); at++) {
                if (at > 0) { names.append(", "); }
                names.append(people.getCompound(at).getString("name"));
            }
            scopes.get(index).style(style -> style.tooltips(word("plan.members", names), word("plan.limit")));
        }
        CompoundTag selectedScope = available.getCompound(scope);
        boolean batch = editable && selectedScope.getInt("count") > 0 && !selectedScope.getBoolean("limited");
        planPreview.setActive(batch && !chosen.isEmpty());
        planApply.setActive(batch && !chosen.isEmpty());
        planRefill.setActive(batch);
        planCancel.setActive(batch);
        templateSave.setActive(editable && !templateInput.getValue().isBlank() && choices.size() < 18);
        CompoundTag goal = plans.getCompound("goal");
        planGoal.setText(word("plan.goal", templateName(goal.contains("id") ? goal : new CompoundTag())));
        planStatus.setText(Component.translatable(goal.getString("status")));
        ListTag preview = plans.getList("preview", Tag.TAG_COMPOUND);
        String lastScope = plans.getString("previewScope");
        boolean frozenPreview = List.of("template_preview", "plan_select").contains(plans.getString("previewKind"));
        planResults.setText(lastScope.isEmpty() ? word("plan.results")
            : word(frozenPreview ? "plan.preview_results_scope" : "plan.results_scope",
                word("plan.scope." + lastScope, preview.size())));
        if (previewRows.size() != preview.size()) {
            previewList.clearAllChildren(); previewRows.clear();
            for (int index = 0; index < preview.size(); index++) {
                Label row = Rows.text(Component.empty()); previewRows.add(row); previewList.addChildren(row);
            }
        }
        for (int index = 0; index < preview.size(); index++) {
            CompoundTag row = preview.getCompound(index);
            var line = Component.literal(row.getString("name") + ": ")
                .append(Component.translatable(row.getString("status")));
            ListTag missing = row.getList("missing", Tag.TAG_COMPOUND);
            for (int at = 0; at < missing.size(); at++) {
                CompoundTag demand = missing.getCompound(at);
                line.append(" · ").append(Client.itemName(demand.getString("item")))
                    .append(" ×" + demand.getInt("count"));
            }
            previewRows.get(index).setText(line);
        }
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

    private static UIElement inventoryGrid() {
        UIElement grid = grid();
        // Keep the familiar 9-column inventory shape when there is room, while allowing the
        // layout to wrap earlier on a narrow screen.
        grid.layout(layout -> layout.widthPercent(100).maxWidth(9 * 24 + 8 * 2).flexShrink(1));
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

    private record DragPayload(UUID resident, UUID ticket, String source, int index) { }

    // RegisteringUi also runs on dedicated servers. Only received client snapshots may touch ClientLevel.
    private static final class Client {
        private static Object screen() { return Minecraft.getInstance().screen; }
        private static boolean windowActive() { return Minecraft.getInstance().isWindowActive(); }
        private static Component itemName(String id) {
            var key = net.minecraft.resources.ResourceLocation.tryParse(id);
            return key == null ? Component.literal(id) : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key)).getHoverName();
        }
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
            row.addEventListener(UIEvents.DRAG_PERFORM, event -> drop(event, name));
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
            press.addEventListener(UIEvents.MOUSE_DOWN, event -> beginPress(event, press, source, entry));
            press.setOnClick(event -> {
                // Button activation is immediate on mouse-down. Defer pointer use until release so
                // a drag never mutates either inventory before a slot accepts the drop.
                if (pressedElement != press) { equip(source, entry.getInt("index"), selected); }
            });
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
