package io.github.izakyl.folkways.epicfight;

/**
 * Test-only observation switch. Pairing captures, reload-guard events and render records exist for the
 * GameTest and real-client acceptance suites; ordinary servers and clients never record or log them.
 * Enable with {@code -Dfolkways.epicfight.evidence=true}.
 */
public final class Evidence {
    public static final boolean ENABLED = Boolean.getBoolean("folkways.epicfight.evidence");

    private Evidence() {
    }
}
