package io.github.izakyl.folkways.front.engine.registry;

import com.mojang.serialization.Codec;
import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class FolkwaysPlayerAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
        NeoForgeRegistries.ATTACHMENT_TYPES,
        FolkwaysMod.MOD_ID
    );

    private static final Codec<Optional<UUID>> MAYBE_UUID = UUIDUtil.CODEC.listOf()
        .xmap(ids -> ids.stream().findFirst(), id -> id.stream().toList());

    // The colony the player acts for; see CurrentColony.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Optional<UUID>>> CURRENT_COLONY =
        ATTACHMENTS.register("current_colony",
            () -> AttachmentType.<Optional<UUID>>builder(Optional::empty)
                .serialize(MAYBE_UUID, Optional::isPresent)
                .copyOnDeath()
                .build());

    // Bound books a player died holding; handed back on respawn.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<ItemStack>>> KEPT_BOOKS =
        ATTACHMENTS.register("kept_books",
            () -> AttachmentType.<List<ItemStack>>builder(() -> List.<ItemStack>of())
                .serialize(ItemStack.CODEC.listOf(), books -> !books.isEmpty())
                .copyOnDeath()
                .build());

    private FolkwaysPlayerAttachments() {
    }

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
