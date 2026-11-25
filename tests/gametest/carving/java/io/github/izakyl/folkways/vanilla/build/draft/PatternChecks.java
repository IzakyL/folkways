package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;

public final class PatternChecks {
    private PatternChecks() {
    }

    // A built-in pattern names itself and every knob in all four shared locales.
    public static void localised(GameTestHelper helper, Pattern pattern) {
        for (String language : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            var words = pattern.face().words().get(language);
            helper.assertTrue(words != null && words.containsKey("title"), pattern.id() + " has a title in " + language);
            for (var setting : pattern.knobs().settings()) {
                helper.assertTrue(words.containsKey(setting.key()), "translated knob " + language + "/" + setting.key());
            }
        }
    }

    // A complete Chosen.load payload: every knob at its declared default, material lists as explicit specs.
    public static CompoundTag defaults(Pattern pattern) {
        CompoundTag settings = Chosen.of(pattern.knobs(), ColonySettings.byDefault(pattern.knobs())).save();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                settings.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        return settings;
    }
}
