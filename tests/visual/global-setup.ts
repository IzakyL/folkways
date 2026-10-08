import { rmSync } from "node:fs";
import { outAbs, ensureOut } from "../out-paths";
import { buildMod, checkSpecTypes } from "../e2e/global-setup";

export default function visualSetup() {
  rmSync(outAbs("visual", "takes"), { recursive: true, force: true });
  ensureOut("visual");
  checkSpecTypes("tests/tsconfig.visual.json");
  buildMod();
}
