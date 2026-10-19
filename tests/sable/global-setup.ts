import { ensureOut } from "../shared/harness/out-paths";
import { buildMod, checkSpecTypes } from "../shared/harness/setup";

export default function globalSetup() {
  ensureOut("sable");
  ensureOut("e2e");
  checkSpecTypes("sable");
  checkSpecTypes("e2e");
  buildMod();
}
