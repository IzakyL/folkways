// Runs the Blockwright native CLI bundled for this platform (linux-x64 or win32-x64).
import { spawnSync } from "node:child_process";
import { resolveBinaryPath } from "@izakyl/blockwright-client/dist/binary.js";

const result = spawnSync(resolveBinaryPath(), process.argv.slice(2), { stdio: "inherit" });
if (result.error) {
  throw result.error;
}
process.exit(result.status ?? 1);
