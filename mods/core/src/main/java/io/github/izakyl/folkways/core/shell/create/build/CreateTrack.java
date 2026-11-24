package io.github.izakyl.folkways.core.shell.create.build;

import io.github.izakyl.folkways.core.work.api.Joints;

public final class CreateTrack {

    private CreateTrack() {
    }

    public static void install() {
        Joints.install(new CreateJoiner());
    }
}
