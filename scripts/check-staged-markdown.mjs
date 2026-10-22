import { execFileSync } from "node:child_process";
import * as prettier from "prettier";

// Check the index too: formatting the working tree need not update a staged copy.
const paths = execFileSync("git", ["ls-files", "--cached", "-z", "--", "*.md"], {
  encoding: "utf8",
}).split("\0").filter(Boolean);
const failures = [];

for (const path of new Set(paths)) {
  const { ignored } = await prettier.getFileInfo(path, {
    ignorePath: [".gitignore", ".prettierignore"],
  });
  if (ignored) continue;

  const source = execFileSync("git", ["show", `:${path}`], { encoding: "utf8" });
  const options = await prettier.resolveConfig(path);
  if (!await prettier.check(source, { ...options, filepath: path })) {
    failures.push(path);
  }
}

if (failures.length) {
  console.error("Staged Markdown needs formatting:");
  failures.forEach(path => console.error(`  ${path}`));
  console.error("Run npm run format:md, review the changes, and stage the formatted files.");
  process.exit(1);
}

console.log("Staged Markdown formatting passed.");
