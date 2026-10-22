package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

final class EatingFixture implements AutoCloseable {
    final GameTestHelper helper;
    final Colony colony;
    final Body body;
    final SimpleContainer pack;
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
        pack = new SimpleContainer(food);
        colony.keep(LivingContent.ID, Writer.of().children("hunger", List.of(body.id()),
            id -> Writer.of().uuid("resident", id).integer("FoodLevel", fullness).tag()).tag());
        living = new LivingPresence(colony);
        worker = new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body.mob(); }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { throw new AssertionError("outputs are stowed by the core"); }
            public Effects effects() { return body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }

    EatAction action() {
        return new EatAction(helper.getLevel(), living, body.id(), body.mob().blockPosition());
    }

    void menu(Item... foods) {
        ColonyFront front = (ColonyFront) Fronts.of(colony);
        helper.assertTrue(front.setSetting(LivingContent.ID, LivingContent.FOOD.key(),
            new ColonySettings.Value.Items(Arrays.stream(foods).map(BuiltInRegistries.ITEM::getKey)
                .map(ItemFilter::item).toList())), "the colony accepts the test food selection");
    }

    void settle() {
        helper.getLevel().addFreshEntity(body.mob());
        colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
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
