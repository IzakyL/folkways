package io.github.izakyl.folkways.core.shell.neoforge;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BodyAttachmentsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BodyAttachmentsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void nativeBodyCreatesAndSavesBothAttachmentsBeforeUse(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var restored = PersonBody.RESIDENT.get().create(helper.getLevel());
        try {
            assertStores(helper, body);
            CompoundTag saved = body.saveWithoutId(new CompoundTag());
            helper.assertTrue(saved.getBoolean("folkways_core:body"), "the writer records the body lifecycle");
            var attachments = saved.getCompound("neoforge:attachments");
            helper.assertTrue(attachments.contains("folkways_core:body_state")
                && attachments.contains("folkways_core:resident_capabilities"), "an untouched body saves both stores");
            restored.load(saved);
            assertStores(helper, restored);
            for (String missing : List.of("neoforge:attachments", "folkways_core:body_state",
                    "folkways_core:resident_capabilities")) {
                CompoundTag broken = saved.copy();
                if (missing.equals("neoforge:attachments")) {
                    broken.remove(missing);
                } else {
                    broken.getCompound("neoforge:attachments").remove(missing);
                }
                assertReported(helper, restored, broken);
                broken.remove("folkways_core:body");
                assertReported(helper, restored, broken);
            }
        } finally {
            body.discard();
            restored.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void adaptedEntitiesStayLazyUntilUseAndRequireBothStoresAfterJoining(GameTestHelper helper) {
        var entity = EntityType.COW.create(helper.getLevel());
        var restored = EntityType.COW.create(helper.getLevel());
        try {
            CompoundTag untouched = entity.saveWithoutId(new CompoundTag());
            restored.load(untouched);
            helper.assertFalse(entity.hasData(FolkwaysAttachments.BODY_STATE)
                || restored.hasData(FolkwaysAttachments.LICENCES), "ordinary entities retain lazy defaults");
            Body body = new Body() {
                public Mob mob() {
                    return entity;
                }

                public ResidentKind kind() {
                    return PersonBody.person();
                }
            };
            UUID colony = UUID.randomUUID();
            body.joinColony(colony);
            assertStores(helper, entity);
            CompoundTag saved = entity.saveWithoutId(new CompoundTag());
            restored.load(saved);
            assertStores(helper, restored);
            helper.assertValueEqual(restored.getData(FolkwaysAttachments.BODY_STATE).colony().orElseThrow(), colony,
                "adapted colony membership round trips");
            for (String missing : List.of("folkways_core:body_state", "folkways_core:resident_capabilities")) {
                CompoundTag broken = saved.copy();
                broken.getCompound("neoforge:attachments").remove(missing);
                assertReported(helper, restored, broken);
            }
            saved.remove("neoforge:attachments");
            assertReported(helper, restored, saved);
        } finally {
            entity.discard();
            restored.discard();
        }
        helper.succeed();
    }

    private static void assertStores(GameTestHelper helper, Entity entity) {
        helper.assertTrue(entity.hasData(FolkwaysAttachments.BODY_STATE)
            && entity.hasData(FolkwaysAttachments.LICENCES), "both body stores exist deterministically");
    }

    private static void assertReported(GameTestHelper helper, Entity entity, CompoundTag raw) {
        helper.assertTrue(FolkwaysAttachments.missingStores(entity, raw), "missing body stores are reported");
        // Throwing would make Minecraft drop the entity, so it loads and gets fresh stores instead.
        entity.load(raw.copy());
        assertStores(helper, entity);
    }
}
