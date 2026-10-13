import { execSync } from "node:child_process";
import { checkSpecTypes } from "../shared/harness/setup";
import { REPO_ROOT, ensureOut } from "../shared/harness/out-paths";

// The fuzz profile runs build/fuzz's jar: the mod with the fuzzers' in-game classes (tests/fuzz/java) packed in.
export default function fuzzSetup() {
  ensureOut("fuzz");
  checkSpecTypes("fuzz");
  try {
    execSync("gradle build fuzzJar -x test", { cwd: REPO_ROOT, stdio: "inherit" });
  } catch {
    throw new Error("\n[mod build] gradle build fuzzJar -x test failed; refusing to run (the fuzzers run against the jar built here).\n");
  }
}
