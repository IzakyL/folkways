package io.github.izakyl.folkways.core.front.engine.operation;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.internal.FolkwaysAttachments;
import io.github.izakyl.folkways.core.resident.api.Licences;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;

public final class ResidentChanges {

    private ResidentChanges() {
    }

    public static void work(Body body, Vocation vocation, BiConsumer<Licences, Licences.Trade> change) {
        Licences licences = body.licences();
        licences.trade(vocation).ifPresent(known -> change.accept(licences, known));
        body.mob().setData(FolkwaysAttachments.LICENCES, licences);
    }

    public static void rename(Body body, String typed) {
        String name = typed.trim();
        body.mob().setCustomName(name.isEmpty() ? null : Component.literal(name));
    }
}
