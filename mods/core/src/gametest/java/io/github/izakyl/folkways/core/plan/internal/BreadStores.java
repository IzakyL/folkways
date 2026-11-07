package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// Three stashes of four bread in a row from the fixture's source, and its target to bring eight of them to.
final class BreadStores {
    static final ItemSpec BREAD = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));

    final PlanningFixture fixture;
    final List<Stash> stashes;
    final Stands stands;

    BreadStores(GameTestHelper helper) {
        fixture = new PlanningFixture(helper);
        List<Stash> stashes = new ArrayList<>();
        Stands.Builder stands = Stands.building();
        for (int at = 0; at < 3; at++) {
            WorldPos pos = fixture.source.at(fixture.source.cell().offset(0, 0, 2 * at));
            stashes.add(new Stash(pos));
            stands.container(new Stash(pos), Set.of(new Stand(pos)));
        }
        stands.container(new Stash(fixture.target), Set.of(new Stand(fixture.target)));
        this.stashes = List.copyOf(stashes);
        this.stands = stands.done();
    }

    // Every stash but the emptied still holds its bread; the target is empty.
    Situation stocked(Set<Stash> emptied) {
        List<Stock.Holding> holdings = new ArrayList<>();
        Map<Stash, Integer> room = new LinkedHashMap<>();
        for (Stash stash : stashes) {
            if (!emptied.contains(stash)) {
                holdings.add(new Stock.Holding(stash, new ItemStack(Items.BREAD, 4)));
            }
            room.put(stash, 26);
        }
        room.put(new Stash(fixture.target), 27);
        return new Situation(fixture.ways, new Stock(holdings, room), List.of(), stands);
    }

    Known known(boolean stocked) {
        return new Known(stocked(stocked ? Set.of() : Set.copyOf(stashes)), fixture.crew.hands());
    }

    // The work planned besides `goal`, by the stash each takes from first.
    Map<Stash, UUID> fetches(Planned solved, UUID goal) {
        Map<Stash, UUID> found = new LinkedHashMap<>();
        solved.weave().vertices().forEach((id, vertex) -> {
            if (!id.equals(goal)) {
                vertex.plan().firstFrom().ifPresent(stash -> found.put(stash, id));
            }
        });
        return found;
    }
}
