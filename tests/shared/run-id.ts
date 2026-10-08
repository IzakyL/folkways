import { randomBytes } from "node:crypto";

// Instance names are built from this, so two launches in the same millisecond must still differ:
// blockwright fills an instance dir by hardlinking the shared store, and two fills racing on one dir corrupt it.
export function newRunId(env = "FOLKWAYS_E2E_RUN_ID"): string {
  const base = (process.env[env] ?? new Date().toISOString()).replace(/[^0-9A-Za-z_-]+/g, "-").slice(0, 26);
  return `${base}-${randomBytes(3).toString("hex")}`;
}
