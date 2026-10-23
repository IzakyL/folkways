package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbilities;

final class ShearNode extends AnimalNode {

    private static final ToolUse SHEARS =
        new ToolUse(new ToolNeed.Ability(ItemAbilities.SHEARS_HARVEST), 1);

    // A sheep gives up to three of its wool.
    private static final List<Amount> FLEECE = List.of(new Amount(ItemSpec.of(ItemTags.WOOL), 6));

    ShearNode(Chore chore) {
        super(PenNode.declare(chore, Herd.Job.SHEAR).tools(SHEARS).leaves(FLEECE).doing(PastureContent.SHEARING)
            .done(), chore);
    }

    @Override
    Outcome work(ServerLevel level, Worker who, Animal beast) {
        if (!(beast instanceof Shearable shearable) || !shearable.readyForShearing()) {
            return refuse(PastureRefusal.NOTHING_TO_TAKE);
        }
        ItemStack shears = who.held(SHEARS.need());
        List<ItemStack> cut = shearable.onSheared(null, shears, level, beast.blockPosition());
        for (ItemStack stack : cut) {
            if (stack.is(ItemTags.WOOL)) {
                double extra = who.effects().value(PastureContent.EXTRA_WOOL);
                stack.grow((int) extra + (level.random.nextDouble() < extra % 1 ? 1 : 0));
                break;
            }
        }
        return new Outcome.Done(List.of(), List.copyOf(cut),
            Optional.of(new Xp(PastureContent.trade(), 1)));
    }
}
