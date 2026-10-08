import { fileURLToPath } from "node:url";
import { CLIENT, defineModule } from "@izakyl/blockwright-minecraft";

/** The helper's Java (`java/` beside these sources). */
export const LDLIB2_JAVA_ROOT = fileURLToPath(new URL("./java/", import.meta.url));

/**
 * The LDLib2 module: reading a panel reflectively, the selection and hit rules, and the in-game checks
 * built on them. Client-only; it reaches LDLib2 only through the paths the TS side sends, so it loads (and
 * gradle checks it) without LDLib2 on the classpath.
 */
export const LDLIB2 = defineModule("blockwright.ldlib2", { requires: [CLIENT], roles: ["client"], root: LDLIB2_JAVA_ROOT });
