package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class DamageControlContent {
    public static final ResourceLocation ID = id("damage_control");
    public static final ResourceLocation EXTINGUISHING = id("extinguishing");
    public static final ResourceLocation REPAIRING = id("repairing");
    public static final ResourceLocation REFILLING = id("refilling");

    private DamageControlContent() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }

    public static Vocation trade() {
        return Vocations.required(ID);
    }

    public static void declare(Declaring event) {
        event.vocation(new VocationSpec(ID, List.of(), List.of()));
        event.colony(ID, scope -> {
            DamageControlPresence presence = new DamageControlPresence(scope.colony());
            scope.share(presence);
            scope.tick(presence::tick);
            scope.urges(presence::urges);
            scope.closed(presence::closed);
        });
    }

    public static void enroll(Registering event) {
        event.facing(ID, colony -> colony.service(ID, DamageControlPresence.class).map(Facing.class::cast));
        List<Schema.Setting> settings = List.of(flag("enabled", true), flag("extinguish", true),
            flag("repair", false), flag("refill", true));
        event.enrollment(ID, new Enrollment(List.of(new Delegation(ID,
            new Shape.Volume(FolkwaysVanillaConfig.maxDamageControlCells()), new Schema(settings),
            ResourceLocation.withDefaultNamespace("water_bucket"))), Schema.none(),
            List.of(PageKind.boarded(ID, "folkways.page.damage_control",
                ResourceLocation.withDefaultNamespace("water_bucket")))));
    }

    private static Schema.Setting flag(String key, boolean value) {
        return new Schema.Setting.Flag(key, "folkways.settings.damage_control." + key,
            ResourceLocation.withDefaultNamespace("water_bucket"), value);
    }
}
