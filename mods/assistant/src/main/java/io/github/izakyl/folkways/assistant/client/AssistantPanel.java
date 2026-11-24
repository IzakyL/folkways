package io.github.izakyl.folkways.assistant.client;

import com.lowdragmc.lowdraglib2.gui.texture.SDFRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.assistant.tool.api.ConfirmationQueue;
import io.github.izakyl.folkways.core.front.api.ui.DeskLaunchers;
import io.github.izakyl.folkways.core.front.api.ui.Pane;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.client.IconTooltips;
import io.github.izakyl.jpi.ai.Subscription;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

final class AssistantPanel {

    private static final ResourceLocation STYLESHEET =
            ResourceLocation.fromNamespaceAndPath("folkways_assistant", "lss/assistant.lss");

    // The desk's paper and the oak that edges its tooltips (LedgerTooltips), so the assistant sits with the cards.
    private static final int SURFACE = 0xFFEADCB6;

    private static final int EDGE = 0xFF6E4A2C;

    private static final int GAP = Rows.GAP;

    private static final int ICON_BUTTON = 16;

    private static final int REVIEW_WIDTH = 150;

    private static final int REVIEW_HEIGHT = 190;

    private static AssistantPanel active;

    private static final Map<DeskLaunchers.Context, WeakReference<AssistantPanel>> PANELS = new WeakHashMap<>();

    private final AssistantSession session = AssistantSession.current();

    private final ScrollerView scroller = Rows.fills(Rows.box());

    private Review review;

    private ConfirmationQueue.Pending showing;

    // Decisions whose window the player closed; they stay marked in the world and referenced in the transcript.
    private final Set<ConfirmationQueue.Pending> dismissed = Collections.newSetFromMap(new IdentityHashMap<>());

    private UIElement chat;

    // Folds the player opened stay open while the transcript redraws around them.
    private final Set<Component> unfolded = Collections.newSetFromMap(new IdentityHashMap<>());

    // Gathered runs the player opened, by their first run.
    private final Set<Component> spread = Collections.newSetFromMap(new IdentityHashMap<>());

    private Subscription subscription;

    private final Label status = Rows.text(Component.empty());

    private Screen screen;

    private Pane chatWindow;

    private Pane settingsWindow;

    private Pane historyWindow;

    private final ScrollerView historyList = Rows.fills(Rows.box());

    private final DeskLaunchers.Context desk;

    private Button send;

    private Button stop;

    private Button settingsButton;

    private Composer input;

    // Words to put in the input once it is drawn and can take the cursor, for a panel opened to be asked something.
    private String begun;

    // Whether the transcript was last drawn while the assistant worked, so gathered runs drop their live mark when
    // it stops without another word.
    private boolean drawnRunning;

    private AssistantPanel(DeskLaunchers.Context desk) {
        this.desk = desk;
    }

    static void open(DeskLaunchers.Context desk) {
        WeakReference<AssistantPanel> reference = PANELS.get(desk);
        AssistantPanel panel = reference == null ? null : reference.get();
        if (panel == null) {
            panel = new AssistantPanel(desk);
            PANELS.put(desk, new WeakReference<>(panel));
            AssistantPanel created = panel;
            panel.chatWindow = desk.window("folkways.assistant", Component.translatable("folkways.assistant.title"),
                    320, 300, created::build);
            panel.chatWindow.onClose(created::closed);
        }
        if (active != panel) {
            close();
            active = panel;
        }
        panel.screen = Minecraft.getInstance().screen;
        panel.chatWindow.open();
        if (panel.subscription == null) {
            panel.subscription = panel.session.subscribe(panel::render);
        }
        panel.render();
    }

    /**
     * Opens the chat as its launcher does, with the player's sentence begun in the input and the cursor after it, so
     * they go on typing.
     */
    static void ask(String begun) {
        DeskLaunchers.open(AssistantClient.LAUNCHER);
        if (active != null) {
            active.begun = begun;
            active.begin();
        }
    }

    // The input takes focus only once its screen is up, which may be a tick after the panel opens.
    private void begin() {
        if (begun == null || input == null || Minecraft.getInstance().screen != screen || !chatWindow.isOpen()) {
            return;
        }
        if (!input.getText().equals(begun)) {
            input.setText(begun);
        }
        input.setCursor(begun.length());
        input.setSelection(begun.length(), begun.length());
        input.focus();
        if (input.isFocused()) {
            begun = null;
        }
    }

    private void closed() {
        if (review != null) {
            review.pane.close();
        }
        if (historyWindow != null) {
            historyWindow.close();
        }
        if (settingsWindow != null) {
            settingsWindow.close();
        }
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        if (active == this) {
            active = null;
        }
    }

    private void openHistory() {
        if (historyWindow == null) {
            historyWindow = desk.window("folkways.assistant.history",
                    Component.translatable("folkways.assistant.history"), 260, 240, pane -> {
                        dress(pane);
                        historyList.viewContainer(container -> container.layout(layout -> layout
                                .gapAll(1).paddingAll(GAP + 2)));
                        return Rows.fills(new UIElement().layout(layout -> layout
                                .flexDirection(FlexDirection.COLUMN).widthPercent(100)))
                                .addChildren(rule(), historyList);
                    });
            historyWindow.beside(chatWindow);
        }
        historyList.clearAllScrollViewChildren();
        var saved = session.savedSessions();
        if (saved.isEmpty()) {
            historyList.addScrollViewChild(quiet(Rows.text(
                    Component.translatable("folkways.assistant.history_empty"))));
        }
        var format = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withZone(java.time.ZoneId.systemDefault());
        for (var item : saved) {
            Component title = item.title().isBlank() ? Component.translatable("folkways.assistant.history_untitled")
                    : Component.literal(item.title());
            var time = format.format(java.time.Instant.ofEpochMilli(item.updated()));
            Button open = Rows.wearing();
            open.addClass("folkways_assistant_row");
            open.layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(GAP * 2)
                    .paddingVertical(4).flexShrink(0));
            Label name = Rows.name(title);
            name.textStyle(style -> style.textWrap(TextWrap.HIDE));
            open.addChildren(name, quiet(Rows.value(Component.literal(time))));
            open.setOnClick(event -> {
                if (session.reopen(item.id())) {
                    historyWindow.close();
                } else {
                    session.note(Component.translatable("folkways.assistant.history_error"));
                }
            });
            Tokens.name(open, "folkways.assistant.history." + item.id());
            historyList.addScrollViewChild(open);
        }
        historyWindow.open();
    }

    private void openSettings() {
        if (settingsWindow == null) {
            settingsWindow = desk.window("folkways.assistant.preferences",
                    Component.translatable("folkways.assistant.settings"), 360, 300, pane -> {
                        pane.offsetCloseTooltip();
                        dress(pane);
                        var preferences = new AssistantPreferences(session);
                        var box = Rows.fills(Rows.box());
                        box.viewContainer(container -> container.layout(layout -> layout
                                .paddingAll(GAP * 2).paddingRight(GAP)));
                        box.addScrollViewChild(preferences.build());
                        return Rows.fills(new UIElement().layout(layout -> layout
                                .flexDirection(FlexDirection.COLUMN).widthPercent(100))).addChildren(rule(), box);
                    });
            settingsWindow.beside(chatWindow);
            settingsWindow.onClose(() -> setChosen(settingsButton, false));
        }
        settingsWindow.open();
        setChosen(settingsButton, true);
    }

    static void checkScreen() {
        PendingMarks.tick(active != null && Minecraft.getInstance().screen == active.screen
                && active.chatWindow.isOpen());
        if (active == null) {
            return;
        }
        active.begin();
        active.send.setDisplay(!active.session.running());
        active.stop.setDisplay(active.session.running());
        active.status.setText(Component.translatable(active.session.running()
                ? "folkways.assistant.working" : "folkways.assistant.ready"));
        boolean shown = Minecraft.getInstance().screen == active.screen && active.chatWindow.isOpen();
        if (!shown && active.subscription != null) {
            active.subscription.close();
            active.subscription = null;
        } else if (shown && active.subscription == null) {
            active.subscription = active.session.subscribe(active::render);
            active.render();
        } else if (shown && active.drawnRunning != active.session.running()) {
            active.render();
        }
    }

    static void close() {
        if (active != null && active.subscription != null) {
            active.subscription.close();
        }
        active = null;
    }

    private UIElement build(Pane pane) {
        dress(pane);
        scroller.viewContainer(container -> {
            container.layout(layout -> layout.gapAll(GAP * 2).paddingVertical(GAP * 2).paddingHorizontal(GAP * 2));
            container.addEventListener(UIEvents.LAYOUT_CHANGED, event -> {
                if (!container.getChildren().isEmpty()) {
                    scroller.scrollToChild(container.getChildren().getLast());
                }
            });
        });
        input = new Composer();
        input.addClass("folkways_assistant_input");
        input.textFieldStyle(style -> style.placeholder(Component.translatable("folkways.assistant.input")));
        Tokens.name(input, "folkways.assistant.input");
        input.layout(layout -> layout.width(0).flexGrow(1).flexShrink(1).minWidth(0).height(14).marginBottom(1));
        Runnable submit = () -> {
            String prompt = input.getText().trim();
            if (!prompt.isEmpty() && !session.running()) {
                session.ask(prompt);
                input.setText("");
            }
        };
        input.onSubmit(submit);
        send = iconButton("folkways.assistant.send", "send", submit);
        send.addClass("folkways_assistant_primary");
        send.layout(layout -> layout.paddingAll(0));
        stop = iconButton("folkways.assistant.stop", "stop", session::stop);
        stop.addClass("folkways_assistant_primary");
        stop.layout(layout -> layout.paddingAll(0));
        quiet(status);
        status.layout(layout -> layout.paddingHorizontal(GAP * 2 + 2));
        Tokens.name(status, "folkways.assistant.status");
        Button fresh = iconButton("folkways.assistant.new", "new", session::fresh);
        IconTooltips.install(fresh, Component.translatable("folkways.assistant.fresh"));
        settingsButton = iconButton("folkways.assistant.settings", "settings", this::openSettings);
        pane.offsetCloseTooltip();
        pane.titleAction(fresh);
        pane.titleAction(iconButton("folkways.assistant.history", "history", this::openHistory));
        pane.titleAction(settingsButton);
        // The send button keeps to the last line as the words grow upward.
        UIElement composer = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.FLEX_END).gapAll(GAP).flexShrink(0).marginHorizontal(GAP * 2)
                .marginBottom(GAP * 2)).addClass("folkways_assistant_composer");
        composer.addChildren(input, send, stop);
        chat = Rows.fills(new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).gapAll(GAP)));
        chat.addChildren(rule(), scroller, status, composer);
        return chat;
    }

    private void render() {
        drawnRunning = session.running();
        send.setDisplay(!session.running());
        stop.setDisplay(session.running());
        scroller.clearAllScrollViewChildren();
        var entries = session.entries();
        var pending = session.confirmations();
        // Runs in a row gather into one line; anything said between them, or a decision awaited, closes the line.
        List<Integer> runs = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Component line = entries.get(index);
            String role = session.role(index);
            if (role.equals("tool")) {
                runs.add(index);
                continue;
            }
            if (!role.equals("user") && line.getString().isBlank()) {
                continue;
            }
            if (!runs.isEmpty()) {
                scroller.addScrollViewChild(runs(entries, runs, false));
                runs.clear();
            }
            if (role.equals("user")) {
                scroller.addScrollViewChild(bubble(line, index));
                continue;
            }
            Label text = Rows.text(line);
            entry(text, role, index);
            if (!role.equals("assistant")) {
                quiet(text);
            }
            scroller.addScrollViewChild(text);
        }
        if (!runs.isEmpty()) {
            scroller.addScrollViewChild(runs(entries, runs, session.running() && pending.isEmpty()));
        }
        dismissed.retainAll(pending);
        if (!pending.isEmpty()) {
            var subjects = Component.empty();
            for (var decision : pending) {
                if (!subjects.getSiblings().isEmpty()) {
                    subjects.append(" · ");
                }
                subjects.append(PendingMarks.headline(decision.subject()));
            }
            Label waiting = Rows.text(Component.translatable("folkways.assistant.awaiting", subjects));
            waiting.layout(layout -> layout.width(0).flexGrow(1).flexShrink(1));
            quiet(waiting);
            UIElement mark = new UIElement().layout(layout -> layout.width(2).flexShrink(0))
                    .addClass("folkways_assistant_accent");
            scroller.addScrollViewChild(new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                    .widthPercent(100).gapAll(GAP + 2).flexShrink(0).alignItems(AlignItems.STRETCH))
                    .addChildren(mark, waiting));
        }
        review(pending);
    }

    private record Review(Pane pane, UIElement body) {
    }

    private void review(List<ConfirmationQueue.Pending> pending) {
        if (!chatWindow.isOpen()) {
            return;
        }
        var next = pending.stream().filter(decision -> !dismissed.contains(decision)).findFirst().orElse(null);
        if (next == null) {
            showing = null;
            if (review != null) {
                review.pane.close();
                review.body.clearAllChildren();
            }
            return;
        }
        if (review == null) {
            UIElement body = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                    .widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0));
            Pane pane = desk.window("folkways.assistant.review.0",
                    Component.translatable("folkways.assistant.review"), REVIEW_WIDTH, REVIEW_HEIGHT, window -> {
                        dress(window);
                        return body;
                    });
            pane.beside(chatWindow);
            pane.onDismiss(() -> {
                if (showing != null) {
                    dismissed.add(showing);
                    render();
                }
            });
            review = new Review(pane, body);
        }
        if (showing != next) {
            showing = next;
            review.pane.title(PendingMarks.headline(next.subject()));
            fill(review, next);
        }
        if (!review.pane.isOpen()) {
            review.pane.open();
        }
    }

    // A decision's window: each change in its own words, a way to find it in the world, and the two answers.
    private void fill(Review review, ConfirmationQueue.Pending decision) {
        review.body.clearAllChildren();
        ScrollerView list = Rows.fills(Rows.box());
        list.viewContainer(container -> container.layout(layout -> layout.gapAll(GAP * 2).paddingAll(GAP * 2)
                .paddingRight(GAP)));
        // The words scroll on their own; what the player presses stays put below them, so pressing never scrolls
        // the start of the description out of sight.
        UIElement controls = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).gapAll(GAP).flexShrink(0).paddingHorizontal(GAP * 2).paddingBottom(GAP));
        boolean single = decision.changes().size() == 1;
        boolean first = true;
        for (var change : decision.changes()) {
            Label words = Rows.text(change.brief());
            if (first) {
                Tokens.name(words, "folkways.assistant.confirmation.summary");
                first = false;
            }
            UIElement item = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                    .widthPercent(100).gapAll(GAP).flexShrink(0)).addChildren(words);
            if (change.place().isPresent()) {
                Button locate = Rows.wearing();
                locate.addClass("folkways_assistant_outline");
                locate.layout(layout -> layout.flexDirection(FlexDirection.ROW).gapAll(GAP).paddingLeft(4));
                locate.addChildren(icon("locate", 9), Rows.worn(Component.translatable("folkways.assistant.locate")));
                locate.setOnClick(event -> {
                    float[] clear = clearOfWindows();
                    PendingMarks.locate(change, clear[0], clear[1]);
                });
                Tokens.name(locate, "folkways.assistant.locate");
                UIElement where = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW).flexShrink(0))
                        .addChildren(locate);
                if (single) {
                    controls.addChildren(where);
                } else {
                    item.addChildren(where);
                }
            }
            list.addScrollViewChild(item);
        }
        var player = Minecraft.getInstance().player;
        if (player != null && player.isCreative()
                && decision.changes().stream().anyMatch(ConfirmationQueue.Change::builds)) {
            controls.addChildren(buildChoice(decision));
        }
        Button confirm = button("folkways.assistant.confirm", () -> decision.decision().complete(true));
        confirm.addClass("folkways_assistant_primary");
        Button reject = button("folkways.assistant.reject", () -> decision.decision().complete(false));
        reject.addClass("folkways_assistant_outline");
        UIElement answers = Rows.actions(reject, confirm);
        answers.layout(layout -> layout.flexShrink(0).paddingAll(GAP * 2).paddingTop(GAP + 2));
        review.body.addChildren(rule(), list, controls, rule(), answers);
    }

    // In creative a build may be set down at once, as placing a blueprint offers, or handed to the colony; the
    // wording is the placement's own.
    private UIElement buildChoice(ConfirmationQueue.Pending decision) {
        Button now = choice("folkways.draft.raise");
        Button colony = choice("folkways.blueprint.handoff");
        Tokens.name(now, "folkways.assistant.raise");
        Tokens.name(colony, "folkways.assistant.handoff");
        Runnable shown = () -> {
            setChosen(now, decision.raise().get());
            setChosen(colony, !decision.raise().get());
        };
        now.setOnClick(event -> {
            decision.raise().set(true);
            shown.run();
        });
        colony.setOnClick(event -> {
            decision.raise().set(false);
            shown.run();
        });
        for (Button choice : List.of(colony, now)) {
            choice.addClass("folkways_assistant_outline");
            choice.layout(layout -> layout.widthPercent(100).flexShrink(0));
        }
        shown.run();
        return new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100)
                .gapAll(GAP).flexShrink(0)).addChildren(colony, now);
    }

    // A choice drops its pressed look as soon as the pointer leaves, so only the chosen one stays marked.
    private static Button choice(String key) {
        Button choice = Rows.wearing();
        choice.layout(layout -> layout.justifyContent(AlignContent.CENTER));
        choice.addChildren(Rows.worn(Component.translatable(key)));
        return choice;
    }

    // Where a located change shows clear of the assistant's windows: the middle of the widest gap beside them, and
    // that gap's share of the screen's width.
    private float[] clearOfWindows() {
        float width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        float left = chat.getPositionX();
        float right = left + chat.getSizeWidth();
        if (review != null && review.pane.isOpen() && review.body.getSizeWidth() > 0) {
            left = Math.min(left, review.body.getPositionX());
            right = Math.max(right, review.body.getPositionX() + review.body.getSizeWidth());
        }
        float before = Math.max(0, left);
        float after = Math.max(0, width - right);
        if (Math.max(before, after) < 40 || width <= 0) {
            return new float[] {0.5f, 1f};
        }
        return before >= after ? new float[] {before / 2 / width, before / width}
                : new float[] {(right + after / 2) / width, after / width};
    }

    // The player's own words sit to the right in a soft bubble, as wide as they need and no wider than most of a line.
    private UIElement bubble(Component line, int index) {
        Component said = line.getContents() instanceof TranslatableContents translated
                && translated.getKey().equals("folkways.assistant.you") && translated.getArgs().length == 1
                ? argument(translated.getArgs()[0]) : line;
        Label text = Rows.text(said);
        entry(text, "user", index);
        float width = Minecraft.getInstance().font.width(said) + 13;
        UIElement bubble = new UIElement().layout(layout -> layout.width(width).maxWidthPercent(85).flexShrink(1))
                .addClass("folkways_assistant_bubble").addChildren(text);
        return new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100)
                .justifyContent(AlignContent.FLEX_END).flexShrink(0)).addChildren(bubble);
    }

    private static Component argument(Object value) {
        return value instanceof Component component ? component : Component.literal(String.valueOf(value));
    }

    // Runs in a row read as one quiet line: how many ran, how many failed and whether more are coming. Unfolded, each
    // run keeps its own line beneath it. A lone run that is over needs no gathering and shows as itself.
    private UIElement runs(List<Component> entries, List<Integer> indices, boolean live) {
        if (indices.size() == 1 && !live) {
            return toolRun(entries.get(indices.getFirst()), indices.getFirst());
        }
        Component first = entries.get(indices.getFirst());
        int failures = 0;
        for (int index : indices) {
            Component result = session.result(index);
            if (result != null && failed(result)) {
                failures++;
            }
        }
        boolean open = spread.contains(first);
        Label count = quiet(Rows.worn(Component.translatable("folkways.assistant.runs", indices.size())));
        Tokens.name(count, "folkways.assistant.runs.count");
        Button fold = Rows.wearing();
        fold.addClass("folkways_assistant_row");
        fold.layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(GAP)
                .flexShrink(0).marginLeft(-GAP));
        fold.addChildren(icon(open ? "expanded" : "collapsed", 5), count);
        if (failures > 0) {
            Label failed = Rows.worn(Component.translatable("folkways.assistant.runs_failed", failures));
            failed.addClass("folkways_assistant_failed");
            Tokens.name(failed, "folkways.assistant.runs.failed");
            fold.addChildren(faint(Rows.worn(Component.literal("·"))), failed);
        }
        if (live) {
            Label going = Rows.worn(Component.translatable("folkways.assistant.runs_live"));
            going.addClass("folkways_assistant_live");
            Tokens.name(going, "folkways.assistant.runs.live");
            fold.addChildren(faint(Rows.worn(Component.literal("·"))), going);
        }
        fold.setOnClick(event -> {
            if (!spread.remove(first)) {
                spread.add(first);
            }
            render();
        });
        IconTooltips.install(fold, Component.translatable("folkways.assistant.show_runs"));
        Tokens.name(fold, "folkways.assistant.runs");
        UIElement head = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).flexShrink(0)).addChildren(fold);
        UIElement group = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).gapAll(GAP).flexShrink(0)).addChildren(head);
        if (open) {
            UIElement list = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                    .widthPercent(100).gapAll(GAP).flexShrink(0).paddingLeft(GAP * 2 + 2));
            for (int index : indices) {
                list.addChildren(toolRun(entries.get(index), index));
            }
            group.addChildren(list);
        }
        return group;
    }

    // A tool run is one quiet line: a fold for its script, its name and what came of it.
    private UIElement toolRun(Component line, int index) {
        Label name = Rows.worn(line);
        entry(name, "tool", index);
        quiet(name);
        String script = session.script(index);
        UIElement head = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).alignItems(AlignItems.FLEX_START).gapAll(GAP).flexShrink(0));
        UIElement run = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).gapAll(GAP).flexShrink(0)).addChildren(head);
        if (script == null) {
            head.addChildren(name);
        } else {
            boolean open = unfolded.contains(line);
            Button fold = Rows.wearing();
            fold.addClass("folkways_assistant_row");
            fold.layout(layout -> layout.flexDirection(FlexDirection.ROW).gapAll(GAP).flexShrink(0)
                    .marginLeft(-GAP));
            fold.addChildren(icon(open ? "expanded" : "collapsed", 5), name);
            fold.setOnClick(event -> {
                if (!unfolded.remove(line)) {
                    unfolded.add(line);
                }
                render();
            });
            IconTooltips.install(fold, Component.translatable("folkways.assistant.show_script"));
            Tokens.name(fold, "folkways.assistant.show_script");
            head.addChildren(fold);
            if (open) {
                Label source = Rows.text(Component.literal(script).withStyle(style -> style.withFont(
                        ResourceLocation.withDefaultNamespace("uniform"))));
                run.addChildren(new UIElement().layout(layout -> layout.widthPercent(100).flexShrink(0))
                        .addClass("folkways_assistant_code").addChildren(source));
            }
        }
        Component result = session.result(index);
        if (result != null) {
            Label outcome = Rows.text(result);
            outcome.layout(layout -> layout.width(0).flexGrow(1).paddingTop(script == null ? 0 : 2));
            outcome.addClass(failed(result) ? "folkways_assistant_failed" : "folkways_assistant_faint");
            head.addChildren(outcome);
        }
        return run;
    }

    private static boolean failed(Component result) {
        return result.getContents() instanceof TranslatableContents translated
                && (translated.getKey().startsWith("folkways.assistant.script_error")
                || translated.getKey().equals("folkways.assistant.script_error.line")
                || translated.getKey().equals("folkways.assistant.script_error.message"));
    }

    private static void entry(Label label, String role, int index) {
        label.addClass("folkways_assistant_" + role);
        Tokens.name(label, "folkways.assistant.entry." + index);
    }

    static <T extends Label> T faint(T label) {
        label.addClass("folkways_assistant_faint");
        return label;
    }

    static <T extends Label> T quiet(T label) {
        label.addClass("folkways_assistant_quiet");
        return label;
    }

    // A section heading in the assistant's own windows, in place of the desk's red ink.
    static Label section(Component text) {
        Label label = Rows.text(text);
        label.addClass("folkways_assistant_section");
        label.layout(layout -> layout.marginTop(GAP));
        return label;
    }

    static UIElement rule() {
        return new UIElement().layout(layout -> layout.widthPercent(100).height(1).flexShrink(0))
                .addClass("folkways_assistant_rule");
    }

    static void dress(Pane pane) {
        pane.dress("folkways_assistant_window", STYLESHEET, SDFRectTexture.of(SURFACE).setRadius(4).setStroke(2)
                .setBorderColor(EDGE));
    }

    private static void setChosen(Button button, boolean chosen) {
        if (chosen) {
            button.addClass("folkways_chosen");
        } else {
            button.removeClass("folkways_chosen");
        }
    }

    static TextField field(String key, String value) {
        TextField field = new TextField().setText(value);
        field.layout(layout -> layout.widthPercent(100).height(20).flexShrink(0));
        field.textFieldStyle(style -> style.placeholder(Component.translatable(key)));
        Tokens.name(field, key);
        return field;
    }

    static Button iconButton(String key, String icon, Runnable action) {
        Button button = Rows.wearing();
        button.setOnClick(event -> action.run());
        button.layout(layout -> layout.width(ICON_BUTTON).height(ICON_BUTTON).flexShrink(0).paddingAll(0)
                .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        button.addChildren(icon(icon, 9));
        IconTooltips.install(button, Component.translatable(key));
        Tokens.name(button, key);
        return button;
    }

    // Icons are drawn a texel to a unit, so their strokes match the font's.
    static UIElement icon(String name, int size) {
        return new UIElement().layout(layout -> layout.width(size).height(size).flexShrink(0))
                .style(style -> style.background(SpriteTexture.of(
                        "folkways_assistant:textures/gui/assistant/" + name + ".png")));
    }

    static Button button(String key, Runnable action) {
        Button button = new Button().setText(Component.translatable(key)).setOnClick(event -> action.run());
        Tokens.name(button, key);
        return button;
    }
}
