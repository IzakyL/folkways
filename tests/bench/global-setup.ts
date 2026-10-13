import { buildMod, checkSpecTypes } from "../shared/harness/setup";
import { ensureOut } from "../shared/harness/out-paths";

export default function benchSetup() {
  ensureOut("bench");
  checkSpecTypes("bench");
  buildMod();
}
