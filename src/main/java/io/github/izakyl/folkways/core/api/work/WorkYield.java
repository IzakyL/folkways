package io.github.izakyl.folkways.core.api.work;

import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** What a node's work actually gave when a body finished it: the items the work handed over, as they came, before
 * they were stowed. Reported once per finished node, for statistics and diagnostics; listeners change nothing. A node
 * that gives nothing (laying a block, tilling) is reported with nothing made.
 */
public final class WorkYield extends Event {
    private final LivingEntity body;
    private final Node node;
    private final List<ItemStack> made;

    private WorkYield(LivingEntity body, Node node, List<ItemStack> made) {
        this.body = body;
        this.node = node;
        this.made = made;
    }

    public static void gave(LivingEntity body, Node node, List<ItemStack> made) {
        if (!body.level().isClientSide) {
            NeoForge.EVENT_BUS.post(new WorkYield(body, node, made.stream().map(ItemStack::copy).toList()));
        }
    }

    public LivingEntity body() { return body; }
    public Node node() { return node; }
    public List<ItemStack> made() { return made; }
}
