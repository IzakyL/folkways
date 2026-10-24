import { ensureOut } from "../shared/harness/out-paths";
import { buildMod, checkSpecTypes } from "../shared/harness/setup";
import { writeTakesIndex } from "../shared/capture/takes-index";

export default function globalSetup() {
  ensureOut("visual");
  checkSpecTypes("visual");
  buildMod();
  return () => writeTakesIndex("visual");
}
