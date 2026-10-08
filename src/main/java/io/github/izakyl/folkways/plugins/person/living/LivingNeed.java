package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.front.api.notice.NoticeKind;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;

public enum LivingNeed implements NoticeKind {

    BED,
    FOOD;

    @Override
    public String translationKey() {
        return "folkways.refusal.settler." + name().toLowerCase(Locale.ROOT);
    }

    public String nameKey() {
        return "folkways.settler.need." + name().toLowerCase(Locale.ROOT);
    }

    public ResourceLocation icon() {
        return ResourceLocation.withDefaultNamespace(this == BED ? "red_bed" : "bread");
    }
}
