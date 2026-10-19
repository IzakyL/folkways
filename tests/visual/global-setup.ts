import { rmSync } from "node:fs";
import { outAbs, ensureOut } from "../shared/harness/out-paths";
import { buildMod, checkSpecTypes } from "../shared/harness/setup";
import { writeTakesIndex } from "../shared/capture/takes-index";

export default function globalSetup() {
  rmSync(outAbs("visual", "takes"), { recursive: true, force: true });
  ensureOut("visual");
  checkSpecTypes("visual");
  buildMod();
  return () => writeTakesIndex("visual");
}
