package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LaborIncidentGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(LaborIncidentGameTests.class); }

    @GameTest(template = "empty")
    public static void pickupDiagnosticsIdentifyTheActualDropAndKeepUnknownCausesExplicit(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        var at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.mob().setPos(at.getX(), at.getY(), at.getZ());
        body.mob().setCustomName(Component.literal("Pickup witness"));
        ItemStack stack = new ItemStack(Items.WHEAT, 7);
        ItemEntity drop = new ItemEntity(level, at.getX(), at.getY(), at.getZ(), stack);
        level.addFreshEntity(drop);
        var node = new PickUp(drop.getUUID(), WorldPos.of(level, at), Goods.specOf(stack), 7, Stances.WHEREVER);
        UUID colony = UUID.randomUUID();
        List<LaborIncident> events = new ArrayList<>();
        Consumer<LaborIncident> listener = event -> { if (event.colony().equals(colony)) events.add(event); };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            LaborIncident.work(colony, level, body, node, "urge-preempts/flee", LaborIncident.surroundings(body));
            String json = Component.Serializer.toJson(events.getFirst().message(), level.registryAccess());
            helper.assertTrue(json.contains("Pickup witness") && json.contains("item.minecraft.wheat")
                    && json.contains(drop.getUUID().toString().substring(0, 8)) && json.contains("unknown"),
                "the message retains the real item, drop ID and explicit unknown context as client components");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            drop.discard();
            body.mob().discard();
        }
        helper.succeed();
    }
}
