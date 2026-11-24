package io.github.izakyl.folkways.core.front.ui.screen;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.ui.RegisteringUi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class PageKinds {

    public static final BookPage OVERVIEW = core("overview", "filled_map", "colony_panel");
    public static final BookPage CITIZENS =
        core("residents", "player_head", "residents");
    public static final BookPage METRICS = core("metrics", "chest", "colony_panel");
    public static final BookPage FACILITIES = core("facilities", "lectern", "colony_panel");
    public static final BookPage MEMBERS = core("members", "bell", "colony_panel");
    public static final BookPage LESSONS = core("lessons", "knowledge_book", "basics");
    public static final BookPage SETTINGS = core("settings", "comparator", "colony_panel");

    /** The book's pages, in the order its navigation lists them. */
    public static final List<BookPage> CORE =
        List.of(OVERVIEW, CITIZENS, METRICS, FACILITIES, SETTINGS, MEMBERS, LESSONS);

    // Listed last, under the preferences heading.
    public static final List<BookPage> TRAILING = List.of(SETTINGS, MEMBERS, LESSONS);

    public record BookPage(ResourceLocation id, String nameKey, ResourceLocation icon,
                           Optional<ResourceLocation> lesson) {
    }

    private PageKinds() {
    }

    /** The pages the book's navigation lists: its own. A module's rows reach them through its colony panel. */
    public static List<BookPage> all() {
        return CORE;
    }

    public static void verify() {
        List<ResourceLocation> seen = new ArrayList<>();
        for (BookPage kind : all()) {
            if (seen.contains(kind.id())) {
                throw new IllegalStateException("page " + kind.id() + " is declared more than once");
            }
            seen.add(kind.id());
        }
    }

    public static void registerUi(RegisteringUi event) {
        event.page(OVERVIEW.id(), OverviewPage::new);
        event.page(CITIZENS.id(), () -> Sectioned.flowing(new ResidentsPage(), ColonyPanel.Page.RESIDENTS));
        event.page(METRICS.id(), () -> new Sectioned(MetricsPage.create(), ColonyPanel.Page.STOCK));
        event.page(FACILITIES.id(), FacilitiesPage::new);
        event.page(MEMBERS.id(), MembersPage::new);
        event.page(LESSONS.id(), LessonsPage::create);
        event.page(SETTINGS.id(), SettingsPage::of);
    }

    private static BookPage core(String path, String icon, String lesson) {
        return new BookPage(id(path), "folkways.tab." + path,
            ResourceLocation.withDefaultNamespace(icon), Optional.of(lesson.equals("residents")
                ? ResourceLocation.fromNamespaceAndPath("folkways_vanilla", lesson) : id(lesson)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, path);
    }
}
