import { buildMod, checkSpecTypes } from "../shared/harness/setup";
import { ensureOut } from "../shared/harness/out-paths";

export default function promoSetup() {
  ensureOut("promo");
  checkSpecTypes("promo");
  buildMod();
}
