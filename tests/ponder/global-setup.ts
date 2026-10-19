import { rmSync } from "node:fs";
import { outAbs, ensureOut } from "../shared/harness/out-paths";
import { buildMod, checkSpecTypes } from "../shared/harness/setup";
import { writeTakesIndex } from "../shared/capture/takes-index";

export default function globalSetup() {
  rmSync(outAbs("ponder", "takes"), { recursive: true, force: true });
  ensureOut("ponder");
  checkSpecTypes("ponder");
  buildMod();
  return () => writeTakesIndex("ponder");
}
