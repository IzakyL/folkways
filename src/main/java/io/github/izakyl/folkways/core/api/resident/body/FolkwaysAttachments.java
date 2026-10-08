package io.github.izakyl.folkways.core.api.resident.body;

import com.mojang.serialization.Codec;
import io.github.izakyl.folkways.FolkwaysMod;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class FolkwaysAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
        NeoForgeRegistries.ATTACHMENT_TYPES,
        FolkwaysMod.MOD_ID
    );

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Licences>> LICENCES = ATTACHMENTS.register(
        "resident_capabilities",
        () -> AttachmentType.serializable(FolkwaysAttachments::licencesFor).build()
    );

    static final DeferredHolder<AttachmentType<?>, AttachmentType<BodyState>> BODY_STATE =
        ATTACHMENTS.register("body_state", () -> AttachmentType.serializable(BodyState::new).build());

    private static final ResourceLocation NOT_A_BODY =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "not_a_body");

    private static Licences licencesFor(IAttachmentHolder holder) {
        return new Licences(holder instanceof Entity entity
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

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
