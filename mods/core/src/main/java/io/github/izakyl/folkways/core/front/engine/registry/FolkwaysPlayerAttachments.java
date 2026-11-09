package io.github.izakyl.folkways.core.front.engine.registry;

import com.mojang.serialization.Codec;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.upgrade.RegistryAliases;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class FolkwaysPlayerAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
        NeoForgeRegistries.ATTACHMENT_TYPES,
        FolkwaysCoreMod.MOD_ID
    );

    private static final Codec<Optional<UUID>> MAYBE_UUID = UUIDUtil.CODEC.listOf(1, 1)
        .xmap(ids -> Optional.of(ids.getFirst()), id -> id.stream().toList());

    // The colony the player acts for; see CurrentColony.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Optional<UUID>>> CURRENT_COLONY =
        ATTACHMENTS.register("current_colony",
            () -> AttachmentType.<Optional<UUID>>builder(Optional::empty)
                .serialize(MAYBE_UUID, Optional::isPresent)
                .copyOnDeath()
                .build());

    private FolkwaysPlayerAttachments() {
    }

    public static void register(IEventBus modBus) {
        RegistryAliases.register(ATTACHMENTS, modBus);
    }
}
