package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.FolkwaysClientConfig;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;

/** The local launch account's camera preference, shared by the book and the rebindable shortcut. */
public final class PersonalCameraControls {
    private PersonalCameraControls() { }

    @Nullable
    public static UUID currentUserUUID() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.getUser() == null ? null : minecraft.getUser().getProfileId();
    }

    public static boolean globalTacticalControls() {
        return FolkwaysClientConfig.globalTacticalControls(currentUserUUID());
    }

    public static void globalTacticalControls(boolean on) {
        FolkwaysClientConfig.globalTacticalControls(currentUserUUID(), on);
    }

    public static void toggle() {
        globalTacticalControls(!globalTacticalControls());
    }
}
