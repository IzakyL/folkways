package io.github.izakyl.folkways.base.persist.upgrade;

/** Global save format shared by every Folkways module; independent of release and Minecraft versions. */
public final class DataVersion {
    public static final int BASELINE = 1;
    public static final int CURRENT = 2;

    private DataVersion() {
    }
}
