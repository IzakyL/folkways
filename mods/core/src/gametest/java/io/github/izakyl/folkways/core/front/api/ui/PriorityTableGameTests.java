package io.github.izakyl.folkways.core.front.api.ui;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.resident.api.Licences;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.resident.internal.ResidentLicences;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationGroup;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PriorityTableGameTests {
    private static final VocationGroup BULK = new VocationGroup(ResourceLocation.parse("folkways_test_bulk:bulk"), 950);
    private static final ResourceLocation TAKEN = ResourceLocation.parse("folkways_test_bulk:taken");
    private static final ResourceLocation ALSO_TAKEN = ResourceLocation.parse("folkways_test_bulk:also_taken");
    private static final ResourceLocation NOT_TAKEN = ResourceLocation.parse("folkways_test_bulk:not_taken");

    @SubscribeEvent
    public static void declare(Declaring event) {
        for (ResourceLocation id : List.of(TAKEN, ALSO_TAKEN, NOT_TAKEN)) {
            event.vocation(VocationSpec.of(id).grouped(BULK));
            Participations.register(PersonBody.ID, Stake.vocation(id),
                id.equals(NOT_TAKEN) ? Participation.NONE : Participation.OPTIONAL);
        }
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PriorityTableGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void eachGroupIsOneRunAndUngroupedVocationsKeepTheirColumns(GameTestHelper helper) {
        List<PriorityTable.Run> runs = PriorityTable.runs();
        int next = 0;
        for (PriorityTable.Run run : runs) {
            helper.assertValueEqual(run.start(), next, "runs cover the columns in order without gaps");
            next += run.members().size();
            helper.assertTrue(run.members().stream().allMatch(vocation -> vocation.group().equals(run.group())),
                "a run holds only its own group's vocations");
        }
        helper.assertValueEqual(next, Vocations.ids().size(), "every vocation column sits in one run");
        helper.assertValueEqual(runs.stream().map(PriorityTable.Run::group).distinct().count(), (long) runs.size(),
            "no group is split across two runs");
        PriorityTable.Run bulk = runs.stream().filter(run -> run.group().equals(BULK)).findFirst().orElseThrow();
        helper.assertValueEqual(bulk.members().stream().map(Vocation::id).toList(), List.of(ALSO_TAKEN, NOT_TAKEN, TAKEN),
            "a group cell stands over its vocations in column order");
        helper.assertTrue(bulk.grouped(), "a named group folds into one column");
        helper.assertFalse(new PriorityTable.Run(VocationGroup.OTHER, 0, List.of()).grouped(),
            "vocations that name no group keep a column each, with no group cell");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void groupCellShowsTheSharedStandingOrMixed(GameTestHelper helper) {
        helper.assertValueEqual(PriorityTable.summary(new int[]{3, 3, 0}), 3, "untaken vocations do not count");
        helper.assertValueEqual(PriorityTable.summary(new int[]{-2, -2}), -2, "all forbidden alike shows forbidden");
        helper.assertValueEqual(PriorityTable.summary(new int[]{3, -3}), PriorityTable.MIXED, "allowed and forbidden mix");
        helper.assertValueEqual(PriorityTable.summary(new int[]{2, 3}), PriorityTable.MIXED, "differing priorities mix");
        helper.assertValueEqual(PriorityTable.summary(new int[]{0, 0}), 0, "a group nobody takes up stands empty");
        PriorityTable.Run bulk = PriorityTable.runs().stream().filter(run -> run.group().equals(BULK)).findFirst().orElseThrow();
        int[] line = new int[Vocations.ids().size()];
        line[bulk.start()] = 4;
        line[bulk.start() + 2] = 1;
        helper.assertValueEqual(java.util.Arrays.stream(PriorityTable.of(bulk, line)).boxed().toList(),
            List.of(4, 0, 1), "a group's standings are picked from its own columns");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void groupEditsSetEveryTakenVocationAlike(GameTestHelper helper) {
        List<Vocation> members = List.of(TAKEN, ALSO_TAKEN, NOT_TAKEN).stream()
            .map(id -> Vocations.of(id).orElseThrow()).toList();
        Licences licences = new ResidentLicences(PersonBody.ID);
        Licences.Trade taken = licences.trade(members.get(0)).orElseThrow();
        Licences.Trade also = licences.trade(members.get(1)).orElseThrow();
        helper.assertTrue(licences.trade(members.get(2)).isEmpty(), "the resident does not take up the third vocation");

        licences.allow(taken, false);
        PriorityTable.toggle(licences, members);
        helper.assertTrue(licences.allows(taken) && licences.allows(also), "a mixed group click allows all of them");
        PriorityTable.toggle(licences, members);
        helper.assertTrue(!licences.allows(taken) && !licences.allows(also), "an all-allowed group click forbids all");
        helper.assertTrue(licences.trade(members.get(2)).isEmpty(), "a group edit grants no licence it lacked");

        licences.setKeenness(taken, Keenness.SECOND);
        licences.setKeenness(also, Keenness.FOURTH);
        PriorityTable.rank(licences, members, 1);
        helper.assertTrue(licences.keennessOf(taken) == Keenness.THIRD && licences.keennessOf(also) == Keenness.THIRD,
            "the wheel lines mixed priorities up one step from the keener of a tie");
        PriorityTable.rank(licences, members, -1);
        helper.assertTrue(licences.keennessOf(taken) == Keenness.SECOND && licences.keennessOf(also) == Keenness.SECOND,
            "then steps them together");
        helper.assertTrue(!licences.allows(taken) && !licences.allows(also), "ranking leaves allowing alone");
        helper.assertValueEqual(PriorityTable.rankAll(List.of(1, 1, 4), -1), 1, "priorities stop at first");
        helper.assertValueEqual(PriorityTable.rankAll(List.of(4, 4, 1), 1), 4, "and at fourth");
        helper.assertValueEqual(PriorityTable.rankAll(List.of(1, 4, 4), 0), 4, "the commonest priority leads");
        helper.succeed();
    }
}
