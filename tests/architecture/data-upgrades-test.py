#!/usr/bin/env python3
"""Exercise migration boundaries and release freezing in isolated disposable git repositories."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io.github.izakyl.folkways.core"
BASE = Path("mods/core/src/main/java/io/github/izakyl/folkways/core")


class UpgradeBoundaryTests(unittest.TestCase):
    def setUp(self):
        parent = ROOT / "build/data-upgrades"
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="boundary-", dir=parent)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "tests/architecture").mkdir(parents=True)
        for name in ("data-upgrades.py", "core-packages.py"):
            shutil.copyfile(ROOT / "tests/architecture" / name, self.root / "tests/architecture" / name)
        self.git("init", "-q")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Architecture fixture")
        self.source("base/persist/upgrade/DataVersion.java", "public final class DataVersion { public static final int BASELINE = 1; public static final int CURRENT = 3; }")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True)

    def source(self, relative, body):
        path = self.root / BASE / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        package = PACKAGE + "." + str(Path(relative).parent).replace("/", ".")
        path.write_text(f"package {package};\n{body}\n")
        return path

    def check(self, succeeds=True):
        result = subprocess.run(["python3", "tests/architecture/data-upgrades.py"], cwd=self.root,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)

    def step(self):
        return self.source("upgrade/V3_Rename.java", """
import net.minecraft.nbt.CompoundTag;
import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
public final class V3_Rename implements Step {
    public int target() { return 3; }
}
""")

    def release(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        self.git("tag", "v0.2.0")

    def test_boundaries_and_entrypoint_exception(self):
        self.step()
        self.check()
        bad = self.source("upgrade/Bad.java", "import io.github.izakyl.folkways.core.colony.api.Colony; class Bad {}")
        self.check(False)
        bad.unlink()
        bad = self.source("upgrade/Bad.java", "class Bad { io.github.izakyl.folkways.core.colony.api.Colony colony; }")
        self.check(False)
        bad.unlink()
        user = self.source("business/Use.java", "import io.github.izakyl.folkways.core.upgrade.V3_Rename; class Use {}")
        self.check(False)
        user.unlink()
        self.source("entry/Main.java", "import io.github.izakyl.folkways.core.upgrade.V3_Rename; @Mod(ID) class Main {}")
        self.check()

    def test_component_codec_adapter_has_narrow_boundaries(self):
        self.source("upgrade/ComponentCodecs.java", """
import com.mojang.serialization.Codec;
public final class ComponentCodecs {}
""")
        self.source("front/engine/registry/FolkwaysDataComponents.java", """
import io.github.izakyl.folkways.core.upgrade.ComponentCodecs;
public final class FolkwaysDataComponents {}
""")
        self.check()
        bad = self.source("business/Use.java", """
import io.github.izakyl.folkways.core.upgrade.ComponentCodecs;
class Use {}
""")
        self.check(False)
        bad.unlink()
        bad = self.source("upgrade/Other.java", "import com.mojang.serialization.Codec; class Other {}")
        self.check(False)
        bad.unlink()
        self.source("upgrade/ComponentCodecs.java", """
import io.github.izakyl.folkways.core.front.engine.item.BookGestures;
public final class ComponentCodecs {}
""")
        self.check(False)

    def test_target_must_match_filename(self):
        path = self.step()
        path.write_text(path.read_text().replace("return 3", "return 4"))
        self.check(False)

    def test_released_steps_cannot_change_or_disappear(self):
        path = self.step()
        self.release()
        self.check()
        original = path.read_text()
        path.write_text(original + "// changed\n")
        self.check(False)
        path.unlink()
        self.check(False)
        path.write_text(original)
        self.check()

    def test_released_steps_survive_package_moves_only_unchanged(self):
        old = self.root / "mods/core/src/main/java/io/github/izakyl/folkways"
        shutil.rmtree(self.root / BASE)
        (old / "base/persist/upgrade").mkdir(parents=True)
        (old / "upgrade").mkdir(parents=True)
        (old / "base/persist/upgrade/DataVersion.java").write_text(
            "package io.github.izakyl.folkways.base.persist.upgrade;\n"
            "public final class DataVersion { public static final int BASELINE = 1; public static final int CURRENT = 3; }\n")
        (old / "upgrade/V3_Rename.java").write_text("""package io.github.izakyl.folkways.upgrade;
import io.github.izakyl.folkways.base.persist.upgrade.Step;
public final class V3_Rename implements Step {
    public int target() { return 3; }
}
""")
        self.release()
        (old / "core").mkdir()
        self.git("mv", str(old / "base"), str(old / "core/base"))
        self.git("mv", str(old / "upgrade"), str(old / "core/upgrade"))
        for path in (old / "core").rglob("*.java"):
            path.write_text(path.read_text().replace("folkways.upgrade", "folkways.core.upgrade")
                            .replace("folkways.base", "folkways.core.base"))
        self.check()
        moved = old / "core/upgrade/V3_Rename.java"
        moved.write_text(moved.read_text() + "// changed\n")
        self.check(False)

    def test_retroactive_steps_are_rejected(self):
        self.release()
        self.step()
        self.check(False)


if __name__ == "__main__":
    unittest.main()
