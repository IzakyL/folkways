package io.github.izakyl.folkways.core.shell.neoforge;

import com.mojang.serialization.Codec;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.upgrade.RawUpgrades;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.BodyStateAccess;
import io.github.izakyl.folkways.core.resident.api.Licences;
import io.github.izakyl.folkways.core.resident.internal.BodyState;
import io.github.izakyl.folkways.core.resident.internal.ResidentLicences;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID)
public final class FolkwaysAttachments {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");
    private static final String TAG_BODY = "folkways:body";
    private static final String TAG_STATE = "folkways:body_state";
    private static final String TAG_LICENCES = "folkways:resident_capabilities";

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
        NeoForgeRegistries.ATTACHMENT_TYPES,
        FolkwaysMod.MOD_ID
    );

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Licences>> LICENCES = ATTACHMENTS.register(
        "resident_capabilities",
        () -> AttachmentType.serializable(FolkwaysAttachments::licencesFor).build()
    );

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<BodyState>> BODY_STATE =
        ATTACHMENTS.register("body_state", () -> AttachmentType.serializable(BodyState::new).build());

    private static final ResourceLocation NOT_A_BODY =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "not_a_body");

    private static Licences licencesFor(IAttachmentHolder holder) {
        return new ResidentLicences(holder instanceof Entity entity
            ? BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
            : NOT_A_BODY);
    }

    private static final Codec<Optional<UUID>> OWNER_CODEC = UUIDUtil.CODEC.xmap(Optional::of, Optional::orElseThrow);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Optional<UUID>>> COLONY_MEMBER = ATTACHMENTS.register(
        "colony_member",
        () -> AttachmentType.<Optional<UUID>>builder(Optional::empty).serialize(OWNER_CODEC, Optional::isPresent).build()
    );

    private FolkwaysAttachments() {
    }

    @SubscribeEvent
    public static void constructing(EntityEvent.EntityConstructing event) {
        // Native bodies always own both stores, even before joining a level or colony. Do not call their
        // embodiment or subclass methods here: EntityConstructing runs inside Entity's constructor.
        if (event.getEntity() instanceof Body) {
            initialize(event.getEntity());
        }
    }

    private static void initialize(Entity entity) {
        entity.getData(BODY_STATE);
        entity.getData(LICENCES);
    }

    public static void saving(Entity entity, CompoundTag raw) {
        // Adapted entities can remain outside Folkways. Once either store is used (including joinColony),
        // both are saved and this marker makes even the loss of the entire attachment wrapper detectable.
        if (entity instanceof Body || entity.hasData(BODY_STATE) || entity.hasData(LICENCES)) {
            initialize(entity);
            raw.putBoolean(TAG_BODY, true);
        }
    }

    // Reports a saved body whose stores are missing. It does not throw: Minecraft drops an entity whose load
    // fails, which would turn a damaged store into a lost resident; NeoForge likewise logs and skips a store
    // it cannot read.
    public static void loading(Entity entity, CompoundTag raw) {
        if (missingStores(entity, raw)) {
            LOGGER.error("Saved Folkways body {} ({}) is missing its body state or licences; it loads with fresh ones",
                raw.hasUUID("UUID") ? raw.getUUID("UUID") : "unknown",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        }
    }

    static boolean missingStores(Entity entity, CompoundTag raw) {
        if (!raw.contains(TAG_BODY) && (!(entity instanceof Body)
                || !raw.getCompound("NeoForgeData").contains(RawUpgrades.VERSION))) {
            // Entity.load also accepts fresh summon/template NBT. Native body saves are stamped; adapted
            // entities need our explicit marker because their original mod can save them without Folkways.
            return false;
        }
        CompoundTag attachments = raw.getCompound("neoforge:attachments");
        return raw.contains(TAG_BODY) && !raw.getBoolean(TAG_BODY)
            || !attachments.contains(TAG_STATE, Tag.TAG_COMPOUND)
            || !attachments.contains(TAG_LICENCES, Tag.TAG_COMPOUND);
    }

    public static void register(IEventBus modBus) {
        Bodies.install(new Bodies.StateSource() {

            @Override
            public BodyStateAccess state(Body body) {
                initialize(body.mob());
                return body.mob().getData(BODY_STATE);
            }

            @Override
            public Licences licences(Body body) {
                initialize(body.mob());
                return body.mob().getData(LICENCES);
            }
        });
        ATTACHMENTS.register(modBus);
    }
}
