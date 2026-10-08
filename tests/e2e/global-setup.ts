import { execSync } from "node:child_process";
import { REPO_ROOT, ensureOut } from "../out-paths";

export function checkSpecTypes(project = "tests/tsconfig.e2e.json") {
  try {
    execSync(`npx --no-install tsc -p ${project}`, { cwd: REPO_ROOT, stdio: "pipe" });
  } catch (error: any) {
    const output = String(error?.stdout ?? "") + String(error?.stderr ?? "");
    throw new Error(
      `\n[spec typecheck] ${project} fails typecheck; refusing to run (so a type error does not burn a Minecraft launch).\n` +
        output.split("\n").slice(0, 20).join("\n") +
        `\n  -> fix and rerun; to check on its own:  npx tsc -p ${project}\n`,
    );
  }
}

export function buildMod() {
  try {
    execSync("gradle build -x test", { cwd: REPO_ROOT, stdio: "inherit" });
  } catch {
    throw new Error("\n[mod build] gradle build -x test failed; refusing to run (the specs run against the mod built here).\n");
  }
}

export default function globalSetup() {
  ensureOut("e2e");
  checkSpecTypes();
  buildMod();
}
