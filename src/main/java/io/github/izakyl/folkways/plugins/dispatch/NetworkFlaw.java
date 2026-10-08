package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.front.api.notice.NoticeKind;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** What keeps a package network from the one shape the colony orders through: every packager feeding one chain net. */
enum NetworkFlaw implements NoticeKind {

    NO_PACKAGER("packager"),

    ELSEWHERE(null),

    NO_FROGPORT("package_frogport"),

    OFF_CHAIN("chain_conveyor"),

    SPLIT(null),

    UNLOADED(null),

    STILL(null);

    private final String lacking;

    NetworkFlaw(String lacking) {
        this.lacking = lacking;
    }

    /** The Create block the network lacks for this flaw, where it is one. */
    Optional<ResourceLocation> lacking() {
        return Optional.ofNullable(lacking).map(path -> ResourceLocation.fromNamespaceAndPath("create", path));
    }

    @Override
    public String translationKey() {
        return "folkways.dispatch.flaw." + name().toLowerCase(Locale.ROOT);
    }
}
