import { buildMod, checkSpecTypes } from "../e2e/global-setup";
import { ensureOut } from "../out-paths";

export default function benchSetup() {
  ensureOut("bench");
  checkSpecTypes("tests/tsconfig.bench.json");
  buildMod();
}
