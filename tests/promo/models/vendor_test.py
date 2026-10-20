"""Offline checks for pinned downloads and safe model preparation."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("promo_vendor", Path(__file__).with_name("vendor.py"))
vendor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vendor)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class CachedSourcesTests(unittest.TestCase):
    def test_verified_cache_is_reused_without_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            relative = vendor.BUILTIN + "/model/models/main.json"
            target = cache / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(b"cached model")
            manifest = {"revision": "pinned", "sha256": {relative: digest(b"cached model")}}
            with patch.object(vendor.urllib.request, "urlopen", side_effect=AssertionError("offline")) as request:
                self.assertEqual(vendor.cached_sources(manifest, cache), cache / vendor.BUILTIN)
                request.assert_not_called()

    def test_checksum_mismatch_preserves_existing_cache(self):
        for existing in (None, b"old corrupt model"):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as temporary:
                cache = Path(temporary)
                target = cache / "model.json"
                if existing is not None:
                    target.write_bytes(existing)
                manifest = {"revision": "pinned", "sha256": {"model.json": digest(b"expected")}}
                with patch.object(vendor.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong")):
                    with self.assertRaisesRegex(ValueError, "SHA-256 mismatch: model.json"):
                        vendor.cached_sources(manifest, cache)
                self.assertEqual(target.read_bytes() if target.exists() else None, existing)
                self.assertEqual(sorted(p.name for p in cache.iterdir()), [] if existing is None else ["model.json"])

    def test_verified_download_replaces_corrupt_cache(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            target = cache / "nested/model.json"
            target.parent.mkdir()
            target.write_bytes(b"corrupt")
            manifest = {"revision": "pinned", "sha256": {"nested/model.json": digest(b"verified")}}
            with patch.object(vendor.urllib.request, "urlopen", return_value=io.BytesIO(b"verified")) as request:
                vendor.cached_sources(manifest, cache)
                request.assert_called_once_with(
                    "https://raw.githubusercontent.com/OpenYSM/OpenYSM/pinned/nested/model.json", timeout=60)
            self.assertEqual(target.read_bytes(), b"verified")
            self.assertEqual(list(target.parent.iterdir()), [target])


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.here = self.root / "models"
        self.here.mkdir()
        self.state = self.here / ".prepared.json"
        self.manifest = self.here / "sources.json"
        self.manifest.write_text(json.dumps({"revision": "pinned", "sha256": {}}))
        self.patches = patch.multiple(vendor, HERE=self.here, STATE=self.state,
                                      MANIFEST=self.manifest, REPO=self.root)
        self.patches.start()
        self.addCleanup(self.patches.stop)

    def test_ready_checks_fingerprint_and_every_file(self):
        first = self.here / "main.json"
        second = self.here / "texture.png"
        first.write_bytes(b"geometry")
        second.write_bytes(b"texture")
        self.state.write_text(json.dumps({"fingerprint": "current", "sha256": {
            "main.json": digest(b"geometry"), "texture.png": digest(b"texture")}}))
        self.assertTrue(vendor.ready("current"))
        self.assertFalse(vendor.ready("different version"))
        second.write_bytes(b"corrupt")
        self.assertFalse(vendor.ready("current"))
        second.unlink()
        self.assertFalse(vendor.ready("current"))

    def test_ready_rejects_missing_malformed_and_empty_state(self):
        self.assertFalse(vendor.ready("current"))
        for state in ("invalid json", "{}", '{"fingerprint":"current","sha256":{}}'):
            with self.subTest(state=state):
                self.state.write_text(state)
                self.assertFalse(vendor.ready("current"))

    def test_failed_transform_preserves_models_and_state(self):
        model = self.here / "wine_fox_wedding"
        model.mkdir()
        (model / "main.json").write_bytes(b"old geometry")
        self.state.write_bytes(b"old state")

        def fail_after_partial_render(source, staging):
            partial = staging / "wine_fox_wedding"
            partial.mkdir()
            (partial / "main.json").write_bytes(b"partial replacement")
            raise ValueError("invalid upstream geometry")

        with patch.object(vendor, "render", side_effect=fail_after_partial_render):
            with self.assertRaisesRegex(ValueError, "invalid upstream geometry"):
                vendor.prepare(self.root / "upstream")
        self.assertEqual((model / "main.json").read_bytes(), b"old geometry")
        self.assertEqual(self.state.read_bytes(), b"old state")
        self.assertEqual(list(self.here.glob(".prepare-*")), [])


if __name__ == "__main__":
    unittest.main()
