import { buildMod, checkSpecTypes } from "../e2e/global-setup";
import { ensureOut } from "../out-paths";

export default function promoSetup() {
  ensureOut("promo");
  checkSpecTypes("tests/tsconfig.promo.json");
  buildMod();
}
