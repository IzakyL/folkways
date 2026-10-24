package io.github.izakyl.folkways.front.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import io.github.izakyl.folkways.FolkwaysClientConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

/** A current-account boolean over the persisted UUID list, using native config saving and undo. */
public final class PersonalCameraConfigScreen extends ConfigurationSectionScreen {
    private static final String CAMERA = "globalTacticalControlUsers";
    private final UUID account = PersonalCameraControls.currentUserUUID();

    private PersonalCameraConfigScreen(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
        super(parent, type, config, title);
    }

    private PersonalCameraConfigScreen(Context context, Component title) {
        super(context, title);
    }

    public static Screen open(ModContainer mod, Screen parent) {
        return new ConfigurationScreen(mod, parent, (screen, type, config, title) ->
            config.getSpec() == FolkwaysClientConfig.SPEC
                ? new PersonalCameraConfigScreen(screen, type, config, title)
                : new ConfigurationSectionScreen(screen, type, config, title));
    }

    @Override
    protected Element createSection(String key, UnmodifiableConfig specs, UnmodifiableConfig values) {
        if (!key.equals("book")) return super.createSection(key, specs, values);
        Component title = Component.translatable("folkways.settings.personal");
        return new Element(title, Component.empty(), Button.builder(title, button ->
            minecraft.setScreen(sectionCache.computeIfAbsent(key, ignored -> new PersonalCameraConfigScreen(
                Context.section(context, this, values.entrySet(), specs.valueMap(), key), title))))
            .width(Button.DEFAULT_WIDTH).build(), false);
    }

    @Override
    protected <T> Element createList(String key, ModConfigSpec.ListValueSpec spec, ModConfigSpec.ConfigValue<List<T>> value) {
        if (!key.equals(CAMERA)) return super.createList(key, spec, value);
        return createBooleanValue(key, spec, () -> FolkwaysClientConfig.globalTacticalControls(account),
            on -> FolkwaysClientConfig.stageGlobalTacticalControls(account, on));
    }

    @Override
    protected boolean isAnyNondefault() {
        return super.isAnyNondefault() || context.valueSpecs().containsKey(CAMERA)
            && FolkwaysClientConfig.globalTacticalControls(account);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void createResetButton() {
        resetButton = Button.builder(ConfigurationScreen.RESET, button -> {
            List<ConfigurationScreen.UndoManager.Step<?>> steps = new ArrayList<>();
            for (var entry : context.entries()) {
                String key = entry.getKey();
                if (entry.getRawValue() instanceof ModConfigSpec.ConfigValue value
                        && !(getValueSpec(key) instanceof ModConfigSpec.ListValueSpec) && isNonDefault(value)) {
                    steps.add(undoManager.step(v -> { value.set(v); onChanged(key); }, getValueSpec(key).correct(null),
                        v -> { value.set(v); onChanged(key); }, value.getRaw()));
                }
            }
            if (context.valueSpecs().containsKey(CAMERA) && FolkwaysClientConfig.globalTacticalControls(account)) {
                steps.add(undoManager.step(on -> stageCamera(on), false, on -> stageCamera(on), true));
            }
            undoManager.add(steps);
            rebuild();
        }).tooltip(Tooltip.create(ConfigurationScreen.RESET_TOOLTIP)).width(Button.SMALL_WIDTH).build();
    }

    private void stageCamera(boolean on) {
        FolkwaysClientConfig.stageGlobalTacticalControls(account, on);
        onChanged(CAMERA);
    }
}
