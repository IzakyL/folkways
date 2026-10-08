package io.github.izakyl.folkways.front.engine.registry;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
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
