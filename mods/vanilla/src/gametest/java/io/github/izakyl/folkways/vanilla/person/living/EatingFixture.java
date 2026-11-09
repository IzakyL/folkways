package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class EatingFixture implements AutoCloseable {
    final GameTestHelper helper;
    final Colony colony;
    final Body body;
    final Container pack;
    final LivingPresence living;
    final Worker worker;

    EatingFixture(GameTestHelper helper, ItemStack food, int fullness) {
        this(helper, fullness, food);
    }

    EatingFixture(GameTestHelper helper, int fullness, ItemStack... food) {
        this.helper = helper;
        colony = Colonies.mint(helper.getLevel().getServer());
        body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.mob().setPos(helper.absolutePos(new BlockPos(2, 1, 2)).getCenter());
        pack = body.pack();
        for (int slot = 0; slot < food.length; slot++) {
            // Preserve the deliberately oversized stacks accepted by the original SimpleContainer fixture.
            int count = food[slot].getCount();
            pack.setItem(slot, food[slot]);
            pack.getItem(slot).setCount(count);
        }
        menu(Arrays.stream(food).map(ItemStack::getItem).toArray(Item[]::new));
        colony.keep(LivingContent.ID, Writer.of(new LivingPresence(colony).save())
            .blob("pets", Writer.of().longValue("next_attempt", -1L).uuids("pets", List.of()).tag())
            .children("hunger", List.of(body.id()),
            id -> Writer.of().uuid("resident", id).integer("FoodLevel", fullness)
                .decimal("Saturation", 0).decimal("Exhaustion", 0).tag()).tag());
        living = new LivingPresence(colony);
        worker = TestWorker.of(body).pack(pack);
        InventoryObservations.publish(colony, helper.getLevel(), java.util.Map.of(body.id(), body));
    }

    EatAction action() {
        return new EatAction(helper.getLevel(), living, body.id(), body.mob().blockPosition());
    }

    void menu(Item... foods) {
        rules(Arrays.stream(foods).map(BuiltInRegistries.ITEM::getKey).map(ItemFilter::item).toArray(ItemFilter[]::new));
    }

    void rules(ItemFilter... rules) {
        var wanted = new ColonySettings.Value.Items(List.of(rules));
        ((ColonyFront) Fronts.of(colony)).setSetting(LivingContent.ID, LivingContent.FOOD.key(), wanted);
        helper.assertTrue(((ColonySettings) Fronts.of(colony).settings(LivingContent.ID))
            .valueOf(LivingContent.FOOD.key()).orElseThrow().equals(wanted), "the colony holds the food rules");
    }

    void blacklist(boolean blacklist) {
        var wanted = blacklist ? LivingContent.BLACKLIST : LivingContent.WHITELIST;
        ((ColonyFront) Fronts.of(colony)).setSetting(LivingContent.ID, LivingContent.FOOD_MODE.key(),
            new ColonySettings.Value.Choice(wanted));
        helper.assertTrue(Fronts.of(colony).settings(LivingContent.ID).choice(LivingContent.FOOD_MODE.key()).equals(wanted),
            "the colony holds the food list mode");
    }

    void rejectUnsafe(boolean reject) {
        ((ColonyFront) Fronts.of(colony)).setSetting(LivingContent.ID, LivingContent.FOOD_SAFETY.key(),
            new ColonySettings.Value.Flag(reject));
    }

    void settle() {
        helper.getLevel().addFreshEntity(body.mob());
        colony.hold(PersonContent.ID, Claimed.Entity.of(body.mob())).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
    }

    Outcome.Done eat() {
        Outcome outcome = action().commit(helper.getLevel(), worker);
        helper.assertTrue(outcome instanceof Outcome.Done, "the resident finishes its meal");
        return (Outcome.Done) outcome;
    }

    @Override
    public void close() {
        body.mob().discard();
        colony.raze();
    }
}
