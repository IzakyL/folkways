package io.github.izakyl.folkways.create.build;

import io.github.izakyl.folkways.vanilla.build.Joints;

final class CreateTrack {

    private CreateTrack() {
    }

    static void install() {
        Joints.install(new CreateJoiner());
    }
}
