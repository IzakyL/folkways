package io.github.izakyl.folkways.assistant.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.jpi.auth.Credential;
import io.github.izakyl.jpi.mc.JpiMini;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class AssistantPreferences {

    private final AssistantSession session;

    private final List<AssistantSettings.Entry> entries = new ArrayList<>();

    private final Map<String, String> keys = new HashMap<>();

    private final Set<String> deleted = new HashSet<>();

    private final UIElement entryList = Rows.table();

    private final UIElement choices = Rows.strip()
            .layout(layout -> layout.flexWrap(FlexWrap.WRAP).flexShrink(0));

    private final Label providerLabel = Rows.text(Component.empty());

    private String selectedId;

    private final Set<String> approvals = new HashSet<>();

    private final Toggle images = new Toggle();

    private final TextField url = AssistantPanel.field("folkways.assistant.base_url", "");

    private final TextField model = AssistantPanel.field("folkways.assistant.model", "");

    private final TextField key = AssistantPanel.field("folkways.assistant.api_key", "");

    private final Label endpoint = AssistantPanel.quiet(Rows.text(Component.empty()));

    private final Label keyHint = AssistantPanel.quiet(Rows.text(Component.translatable("folkways.assistant.key_hint")));

    private String provider;

    AssistantPreferences(AssistantSession session) {
        this.session = session;
        try {
            AssistantSettings.Config saved = AssistantSettings.loadConfig();
            entries.addAll(saved.entries());
            selectedId = saved.selectedEntryId();
            provider = saved.active().provider();
            approvals.addAll(saved.autoApprove());
        } catch (IOException | RuntimeException failure) {
            AssistantSettings.Entry entry = AssistantSettings.Entry.from(
                    AssistantSettings.preset("openai-compatible", Set.of()));
            entries.add(entry);
            selectedId = entry.id();
            provider = entry.provider();
            session.note(Component.translatable("folkways.assistant.config_error"));
        }
    }

    UIElement build() {
        choices.setDisplay(false);
        for (String choice : List.of("deepseek", "anthropic", "google", "openai-compatible")) {
            Button button = AssistantPanel.button("folkways.assistant.provider." + choice, () -> add(choice));
            button.layout(layout -> layout.widthAuto().flexShrink(0));
            button.addClass("folkways_assistant_outline");
            choices.addChildren(button);
        }
        key.setFormatter(value -> Component.literal("*".repeat(value.length())));
        UIElement categories = Rows.table();
        Tokens.name(images, "folkways.assistant.image_input");
        UIElement imageRow = Rows.row().addChildren(
                Rows.name(Component.translatable("folkways.assistant.image_input")), images);
        for (String category : List.of("settings", "work", "residents", "drawings")) {
            Toggle toggle = new Toggle();
            Tokens.name(toggle, "folkways.assistant.auto." + category);
            toggle.setOn(approvals.contains(category), false);
            toggle.setOnToggleChanged(on -> {
                if (on) {
                    approvals.add(category);
                } else {
                    approvals.remove(category);
                }
            });
            Component label = category.equals("settings") ? Component.translatable("folkways.assistant.auto_settings")
                    : Component.translatable("folkways.assistant.auto",
                            Component.translatable("folkways.assistant.category." + category));
            categories.addChildren(Rows.row().addChildren(Rows.name(label), toggle));
        }
        fill();
        UIElement form = Rows.table();
        form.layout(layout -> layout.gapAll(4));
        Button add = AssistantPanel.button("folkways.assistant.add_entry",
                () -> choices.setDisplay(!choices.isDisplayed()));
        add.addClass("folkways_assistant_outline");
        Button save = AssistantPanel.button("folkways.assistant.save", this::save);
        save.addClass("folkways_assistant_primary");
        return form.addChildren(entryList, add, choices, providerLabel, endpoint, url,
                AssistantPanel.quiet(Rows.text(Component.translatable("folkways.assistant.model"))), model, key,
                keyHint, imageRow,
                AssistantPanel.section(Component.translatable("folkways.assistant.auto_section")), categories,
                Rows.actions(save));
    }

    private AssistantSettings.Entry selected() {
        return entries.stream().filter(entry -> entry.id().equals(selectedId)).findFirst().orElseThrow();
    }

    private void remember() {
        AssistantSettings.Entry old = selected();
        int index = entries.indexOf(old);
        String endpoint = url.getText().trim();
        if (!old.baseUrl().equals(endpoint) && !AssistantSettings.sameOrigin(old.baseUrl(), endpoint)) {
            keys.remove(old.id());
            deleted.add(old.id());
            selectedId = UUID.randomUUID().toString();
            key.setText("");
        } else if (!key.getText().isBlank()) {
            keys.put(selectedId, key.getText().trim());
        }
        entries.set(index, new AssistantSettings.Entry(selectedId, old.provider(), old.name(), endpoint,
                model.getText().trim(), images.isOn()));
    }

    private void add(String type) {
        remember();
        AssistantSettings.Entry entry = AssistantSettings.Entry.from(AssistantSettings.preset(type, approvals));
        entries.add(entry);
        selectedId = entry.id();
        choices.setDisplay(false);
        fill();
    }

    private void refreshEntries() {
        entryList.clearAllChildren();
        for (AssistantSettings.Entry entry : List.copyOf(entries)) {
            Component name = entry.name() == null || entry.name().isBlank()
                    ? Component.translatable("folkways.assistant.provider." + entry.provider())
                            .append(" · " + entry.model()) : Component.literal(entry.name());
            Button select = new Button().setText(name).setOnClick(event -> {
                        remember();
                        selectedId = entry.id();
                        fill();
                    });
            Tokens.name(select, "folkways.assistant.entry." + entry.id());
            select.layout(layout -> layout.width(0).flexGrow(1).flexShrink(1).minWidth(0)
                    .justifyContent(AlignContent.FLEX_START).paddingHorizontal(4));
            select.textStyle(style -> style.textAlignHorizontal(Horizontal.LEFT).textWrap(TextWrap.HIDE));
            if (entry.id().equals(selectedId)) {
                select.addClass("folkways_chosen");
            }
            Button delete = AssistantPanel.iconButton("folkways.assistant.delete_entry", "delete", () -> {
                if (entries.size() > 1) {
                    entries.removeIf(candidate -> candidate.id().equals(entry.id()));
                    keys.remove(entry.id());
                    deleted.add(entry.id());
                    if (entry.id().equals(selectedId)) {
                        selectedId = entries.getFirst().id();
                    }
                    fill();
                }
            });
            Tokens.name(delete, "folkways.assistant.delete." + entry.id());
            delete.style(style -> style.tooltips(Component.translatable("folkways.assistant.delete_entry")));
            entryList.addChildren(Rows.row().addChildren(select, delete));
        }
    }

    private void fill() {
        AssistantSettings settings = selected().settings(approvals);
        provider = settings.provider();
        providerLabel.setText(Component.translatable("folkways.assistant.provider." + provider));
        refreshEntries();
        url.setText(settings.baseUrl());
        model.setText(settings.model());
        images.setOn(settings.imageInput(), false);
        key.setText(keys.getOrDefault(selectedId, ""));
        url.setDisplay(provider.equals("openai-compatible"));
        endpoint.setDisplay(!provider.equals("openai-compatible"));
        endpoint.setText(Component.literal(settings.baseUrl()));
        String selected = selectedId;
        CompletableFuture.supplyAsync(() -> {
            try {
                return JpiMini.credentials().get(AssistantModels.credentialKey(selected)).isPresent();
            } catch (IOException failure) {
                return false;
            }
        }).thenAccept(stored -> Minecraft.getInstance().execute(() -> {
            if (selectedId.equals(selected)) {
                key.textFieldStyle(style -> style.placeholder(Component.translatable(
                        stored ? "folkways.assistant.key_saved" : "folkways.assistant.api_key")));
            }
        }));
    }

    private void save() {
        if (session.running()) {
            return;
        }
        remember();
        AssistantSettings.Config config = new AssistantSettings.Config(entries, selectedId, approvals);
        Map<String, String> entered = Map.copyOf(keys);
        Set<String> removed = Set.copyOf(deleted);
        CompletableFuture.runAsync(() -> {
            try {
                for (var item : entered.entrySet()) {
                    JpiMini.credentials().put(AssistantModels.credentialKey(item.getKey()),
                            new Credential.ApiKey(item.getValue()));
                }
                config.save();
                AssistantModels.removeCredentials(removed);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save assistant settings", failure);
            }
        }).whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
            if (AssistantSession.current() != session) {
                return;
            }
            if (error == null) {
                keys.clear();
                deleted.removeAll(removed);
                fill();
                if (!session.running()) {
                    session.configure();
                }
                session.note(Component.translatable("folkways.assistant.saved"));
            } else {
                session.note(Component.translatable("folkways.assistant.config_error"));
            }
        }));
    }
}
