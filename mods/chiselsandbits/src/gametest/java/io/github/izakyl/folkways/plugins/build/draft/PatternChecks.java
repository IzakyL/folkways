package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
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

    // Every material knob set to its declared defaults.
    public static CompoundTag defaults(Pattern pattern) {
        CompoundTag settings = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                settings.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        return settings;
    }
}
