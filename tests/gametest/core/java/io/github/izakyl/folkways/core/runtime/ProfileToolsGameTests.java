package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.WorkListener;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.Kit;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.CORE_ID)
@PrefixGameTestTemplate(false)
public final class ProfileToolsGameTests {

    private static final ResourceLocation BUILDING = ResourceLocation.fromNamespaceAndPath(
        "folkways_vanilla", "building");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ProfileToolsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void profileTakesOverOnlyItsToolFamilies(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        var body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(helper.absolutePos(BlockPos.ZERO).getBottomCenter());
        level.addFreshEntity(body);
        colony.hold(PersonBody.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        var front = ColonyFront.of(colony);
        try {
            for (var vocation : Vocations.all()) {
                front.setSetting(vocation.id(), "tool", new ColonySettings.Value.Items(List.of()));
            }
            front.setSetting(BUILDING, "tool", new ColonySettings.Value.Items(List.of(
                ItemFilter.item(ResourceLocation.withDefaultNamespace("iron_pickaxe")),
                ItemFilter.item(ResourceLocation.withDefaultNamespace("iron_axe")))));
            var diamond = new ItemStack(Items.DIAMOND_PICKAXE);
            body.pack().setItem(0, diamond);
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Builder's tools");

            var survey = new Survey();
            var kits = new Kits();
            List<Message> messages = new ArrayList<>();
            Runnable read = () -> {
                survey.read(body.id());
                survey.answer(level, data, Map.of(body.id(), body),
                    (seen, supply) -> kits.read(colony, seen, supply, messages::add));
            };
            read.run();
            var automatic = deliveries(messages).stream()
                .filter(delivery -> Goods.matches(new ItemStack(Items.IRON_PICKAXE), delivery.goods().spec()))
                .findFirst().orElseThrow();
            var step = NodeSpec.of(UUID.randomUUID(), BUILDING, WorkSite.on(body), Stances.WHEREVER,
                Workload.Once.of(1)).vocation(Vocations.required(BUILDING))
                .tools(new ToolUse(new ToolNeed.Ability(ItemAbilities.PICKAXE_DIG), 1)).done();
            var before = survey.hands().getFirst();
            helper.assertFalse(Tools.canPrepare(body.kind().id(), step, before.tools(), before.toolRules()),
                "a diamond pickaxe outside the automatic kit starts as cargo");

            profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.DIAMOND_PICKAXE), 1, 1));
            profiles.assign(List.of(body.id()), Optional.of(profile.id()));
            messages.clear();
            read.run();
            helper.assertTrue(messages.stream().anyMatch(message -> message instanceof Message.Withdrawn withdrawn
                && withdrawn.node().equals(automatic.id())), "assigning the profile withdraws the iron pickaxe request");
            var hand = survey.hands().getFirst();
            helper.assertTrue(Tools.canPrepare(body.kind().id(), step, hand.tools(), hand.toolRules()),
                "the carried diamond pickaxe qualifies the builder for a pickaxe step");
            helper.assertFalse(Kit.of(colony, body).holds(new ItemStack(Items.IRON_PICKAXE)),
                "the automatic kit stops retaining the overridden family");

            body.pack().setItem(0, ItemStack.EMPTY);
            messages.clear();
            var freshKits = new Kits();
            survey.read(body.id());
            survey.answer(level, data, Map.of(body.id(), body),
                (seen, supply) -> freshKits.read(colony, seen, supply, messages::add));
            var automaticWithProfile = deliveries(messages);
            helper.assertValueEqual(automaticWithProfile.size(), 1, "the profile leaves one automatic tool request");
            helper.assertTrue(Goods.matches(new ItemStack(Items.IRON_AXE),
                automaticWithProfile.getFirst().goods().spec()), "the unmentioned axe family still uses the kit");

            List<Delivery> personal = new ArrayList<>();
            var listener = new WorkListener() {
                @Override
                public void submitted(ResourceLocation owner, Grown work) {
                    if (owner.equals(EquipmentProfiles.ID)) {
                        work.nodes().stream().filter(Delivery.class::isInstance).map(Delivery.class::cast)
                            .forEach(personal::add);
                    }
                }

                @Override
                public void withdrawn(ResourceLocation owner, UUID node) {
                }

                @Override
                public void offered() {
                }
            };
            data.works().listen(level.dimension(), listener);
            try {
                data.works().pulse(level.getServer());
            } finally {
                data.works().unlisten(level.dimension(), listener);
            }
            helper.assertValueEqual(personal.size(), 1, "only the profile supplies a missing pickaxe");
            helper.assertTrue(Goods.members(personal.getFirst().goods().spec()).equals(Goods.members(
                ItemSpec.of(Items.DIAMOND_PICKAXE))), "the personal delivery requests the profile pickaxe");

            profiles.assign(List.of(body.id()), Optional.empty());
            messages.clear();
            read.run();
            helper.assertTrue(deliveries(messages).stream().anyMatch(delivery ->
                Goods.matches(new ItemStack(Items.IRON_PICKAXE), delivery.goods().spec())),
                "unassigning restores the automatic pickaxe request");
        } finally {
            body.discard();
            colony.raze();
            ColonyFront.forget(colony.id());
        }
        helper.succeed();
    }

    private static List<Delivery> deliveries(List<Message> messages) {
        return messages.stream().filter(Message.Submitted.class::isInstance).map(Message.Submitted.class::cast)
            .flatMap(message -> message.work().nodes().stream()).filter(Delivery.class::isInstance)
            .map(Delivery.class::cast).toList();
    }
}
