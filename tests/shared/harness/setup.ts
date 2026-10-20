import { execSync } from "node:child_process";
import { REPO_ROOT } from "./out-paths";

/** Typechecks a suite (its directory under tests/, holding a tsconfig.json) before anything launches. */
export function checkSpecTypes(suite: string) {
  const project = `tests/${suite}`;
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
    const wrapper = process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew";
    execSync(`${wrapper} build -x test`, { cwd: REPO_ROOT, stdio: "inherit" });
  } catch {
    throw new Error("\n[mod build] Gradle wrapper build -x test failed; refusing to run (the specs run against the mod built here).\n");
  }
}
