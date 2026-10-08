package io.github.izakyl.folkways.front.ui.screen;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class PageKinds {

    public static final PageKind CITIZENS =
        core("residents", "player_head", "residents");
    public static final PageKind METRICS = core("metrics", "chest", "colony_panel");
    public static final PageKind LESSONS = core("lessons", "knowledge_book", "basics");
    public static final PageKind SETTINGS = core("settings", "comparator", "colony_panel");

    public static final List<PageKind> CORE =
        List.of(CITIZENS, METRICS, LESSONS, SETTINGS);

    // Listed last, under the preferences heading, below every plugin's page.
    public static final List<PageKind> TRAILING = List.of(LESSONS, SETTINGS);

    private PageKinds() {
    }

    public static List<PageKind> all() {
        List<PageKind> pages = new ArrayList<>(CORE);
        for (Enrollment enrolled : Enrollments.all().values()) {
            List<PageKind> declared = enrolled.pages();
            if (!declared.isEmpty()) {
                pages.add(declared.get(0));
            }
        }
        return List.copyOf(pages);
    }

    public static List<PageKind> every() {
        List<PageKind> pages = new ArrayList<>(CORE);
        for (Enrollment enrolled : Enrollments.all().values()) {
            pages.addAll(enrolled.pages());
        }
        return List.copyOf(pages);
    }

    public static void verify() {
        List<ResourceLocation> seen = new ArrayList<>();
        for (PageKind kind : every()) {
            if (seen.contains(kind.id())) {
                throw new IllegalStateException("page " + kind.id() + " is declared more than once");
            }
            seen.add(kind.id());
        }
    }

    public static void registerUi(RegisteringUi event) {
        event.page(CITIZENS.id(), ResidentsPage::new);
        event.page(METRICS.id(), MetricsPage::create);
        event.page(LESSONS.id(), LessonsPage::create);
        event.page(SETTINGS.id(), SettingsPage::of);
    }

    private static PageKind core(String path, String icon, String lesson) {
        return PageKind.boarded(id(path), "folkways.tab." + path,
            ResourceLocation.withDefaultNamespace(icon), Optional.of(id(lesson)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path);
    }
}
