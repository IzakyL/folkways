import { rmSync } from "node:fs";
import { outAbs, ensureOut } from "../shared/harness/out-paths";
import { buildMod, checkSpecTypes } from "../shared/harness/setup";

export default function visualSetup() {
  rmSync(outAbs("visual", "takes"), { recursive: true, force: true });
  ensureOut("visual");
  checkSpecTypes("visual");
  buildMod();
}
