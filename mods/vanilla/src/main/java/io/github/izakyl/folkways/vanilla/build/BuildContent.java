package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationGroup;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.effect.Modifier;
import io.github.izakyl.folkways.core.effect.Scope;
import io.github.izakyl.folkways.core.effect.Stats;
import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;

public final class BuildContent {

    public static final ResourceLocation BUILDING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building");

    public static final ResourceLocation CLEARING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "clearing");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "build");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building");

    public static final String QUICK_HANDS = "building_quick_hands";

    static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.build",
        ResourceLocation.withDefaultNamespace("bricks")).tools(VOCATION);

    static final PageKind BLUEPRINTS = PageKind.boarded(
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "blueprints"),
        "folkways.tab.blueprints", ResourceLocation.withDefaultNamespace("map"));

    private static final VocationSpec SPEC =
        new VocationSpec(VOCATION, List.of(
            new Perk(QUICK_HANDS, 3, Perk.Grant.haste(VOCATION, FolkwaysVanillaConfig::buildingHaste)),
            new Perk("building_prospecting", 3, rank -> List.of(
                Modifier.add(BuildingPerks.PROSPECTING, new double[]{0.04, 0.07, 0.11}[rank - 1]),
                Modifier.add(BuildingPerks.SECOND_FIND, rank == 3 ? 0.15 : 0))),
            new Perk("building_fortune", 3, Perk.Grant.perRank(BuildingPerks.FORTUNE, 1)),
            new Perk("building_quarrying", 3, rank -> List.of(
                Modifier.add(BuildingPerks.QUARRY_MOMENTUM, new double[]{0.15, 0.30, 0.50}[rank - 1]))),
            new Perk("building_tool_expert", 3, rank -> List.of(new Modifier.Multiply(
                Stats.TOOL_WEAR, 1.0 - rank * 0.15, Scope.trade(VOCATION))))),
            List.of(ItemSpec.of(ItemTags.PICKAXES), ItemSpec.of(ItemTags.AXES),
                ItemSpec.of(ItemTags.SHOVELS)));

    private static final Workload.Haste HASTE = Workload.Haste.of(VOCATION);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC.ordered(100).grouped(VocationGroup.CONSTRUCTION));
        declaring.colony(ID, scope -> {
            BuildPresence state = new BuildPresence(scope.colony(), scope.server());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, BuildPresence.class)
            .map(value -> (Facing) value));
        registering.enrollment(ID, new Enrollment(List.of(), Schema.none(), List.of(PAGE, BLUEPRINTS)));
    }

    public static Vocation trade() {
        return Vocations.required(VOCATION);
    }

    static Optional<BuildPresence> presenceIn(Optional<?> works) {
        return works.filter(BuildPresence.class::isInstance).map(BuildPresence.class::cast);
    }
}
