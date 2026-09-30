import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("publication", REPO / "scripts/build_publication.py")
PUBLICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLICATION)
SHA = "a" * 40


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.reader, self.content, self.output = self.base / "reader", self.base / "content", self.base / "output"
        self.build_number = 0
        for file in PUBLICATION.READER_FILES:
            self.write(self.reader, file, "trusted reader " + file)
            self.write(self.content, file, "untrusted draft " + file)
        self.write(self.content, "android-editor/local.properties", "MUST NOT PUBLISH")
        self.write(self.content, "notes.txt", "MUST NOT PUBLISH")
        self.json("data/novels.json", {"novels": [
            {"id": "visible", "title": "공개", "cover": "cover.jpg"},
            {"id": "hidden", "title": "비공개", "visibility": "private"},
        ]})
        self.json("novels/visible/meta.json", {"title": "공개", "cover": "cover.jpg", "chapters": [
            {"id": "001", "title": "기존 공개", "file": "chapters/001.txt", "published": "2026-09-15"},
            {"id": "002", "title": "비공개 원고", "file": "chapters/002.txt", "visibility": "private"},
            {"id": "003", "title": "작성중", "file": "chapters/003.txt", "visibility": "draft"},
        ]})
        self.json("novels/hidden/meta.json", {"title": "비공개", "chapters": [
            {"id": "001", "title": "숨김 원고", "file": "chapters/001.txt"}]})
        self.write(self.content, "novels/visible/chapters/001.txt", "앞\n\n![그림](illust/public.webp)\n\n뒤")
        self.write(self.content, "novels/visible/chapters/002.txt", "PRIVATE BODY\n\n![그림](illust/private.png)")
        self.write(self.content, "novels/visible/chapters/003.txt", "DRAFT BODY")
        self.write(self.content, "novels/visible/chapters/deleted.txt", "DELETED BODY")
        self.write(self.content, "novels/hidden/chapters/001.txt", "HIDDEN NOVEL BODY")
        for image in ["novels/visible/cover.jpg", "illust/public.webp", "illust/private.png", "illust/orphan.png", "illust/portrait.jpg"]:
            self.write(self.content, image, "image")
        self.json("novels/visible/worldbuilding.json", {"characters": [{"name": "인물", "portrait": "illust/portrait.jpg"}]})

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, root, relative, body):
        file = root / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(body, encoding="utf-8")

    def json(self, relative, value):
        self.write(self.content, relative, json.dumps(value, ensure_ascii=False))

    def build(self):
        self.build_number += 1
        self.output = self.base / f"output-{self.build_number}"
        return PUBLICATION.build_publication(self.reader, self.content, self.output, SHA, "request-123", "b" * 40)

    def test_private_draft_deleted_files_and_unreachable_images_are_absent(self):
        marker = self.build()
        paths = {file.relative_to(self.output).as_posix() for file in self.output.rglob("*") if file.is_file()}
        self.assertEqual(paths, set(PUBLICATION.READER_FILES) | {
            "data/novels.json", "novels/visible/meta.json", "novels/visible/chapters/001.txt", "novels/visible/cover.jpg",
            "novels/visible/worldbuilding.json", "illust/public.webp", "illust/portrait.jpg", "publication.json", ".nojekyll"})
        self.assertEqual(marker["content_sha"], SHA)
        self.assertEqual(marker["request_id"], "request-123")
        self.assertEqual(marker["chapters"], 1)
        meta = json.loads((self.output / "novels/visible/meta.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["chapters"][0]["published"], "2026-09-15")
        self.assertEqual(len(meta["chapters"]), 1)
        self.assertTrue((self.output / "js/views.js").read_text().startswith("trusted reader"))

    def test_meta_visibility_also_hides_novel(self):
        meta = json.loads((self.content / "novels/visible/meta.json").read_text(encoding="utf-8"))
        meta["visibility"] = "draft"
        self.json("novels/visible/meta.json", meta)
        self.assertEqual(self.build()["novels"], 0)
        self.assertFalse((self.output / "novels").exists())
        self.assertFalse((self.output / "illust").exists())

    def test_unknown_visibility_and_duplicate_ids_fail(self):
        for change in ("bad_visibility", "duplicate"):
            with self.subTest(change=change):
                index = {"novels": [{"id": "visible", "title": "작품"}]}
                if change == "bad_visibility":
                    index["novels"][0]["visibility"] = "publci"
                else:
                    index["novels"].append({"id": "VISIBLE", "title": "중복"})
                self.json("data/novels.json", index)
                with self.assertRaises(ValueError):
                    self.build()

    def test_path_traversal_external_and_encoded_images_fail(self):
        for image in ["https://evil.test/a.png", "illust/../a.png", "illust/%2e%2e.png", "illust/%252e%252e.png", "illust/a.svg"]:
            with self.subTest(image=image):
                self.write(self.content, "novels/visible/chapters/001.txt", f"![그림]({image})")
                with self.assertRaises(ValueError):
                    self.build()

    def test_missing_referenced_image_and_malformed_schema_fail(self):
        (self.content / "illust/public.webp").unlink()
        with self.assertRaisesRegex(ValueError, "참조 파일"):
            self.build()
        self.json("novels/visible/meta.json", {"title": "작품", "chapters": "wrong"})
        with self.assertRaises(ValueError):
            self.build()

    def test_output_reuse_fails_so_stale_files_cannot_leak(self):
        self.build()
        with self.assertRaisesRegex(ValueError, "비어 있어야"):
            PUBLICATION.build_publication(self.reader, self.content, self.output, SHA)

    def test_real_current_manuscripts_are_preserved_in_publication(self):
        marker = PUBLICATION.build_publication(REPO, REPO, self.base / "real-output", SHA)
        index = json.loads((REPO / "data/novels.json").read_text(encoding="utf-8"))
        public_chapters = 0
        for novel in index["novels"]:
            novel_id = novel["id"]
            meta = json.loads((REPO / "novels" / novel_id / "meta.json").read_text(encoding="utf-8"))
            if not PUBLICATION.visibility(novel, novel_id) or not PUBLICATION.visibility(meta, novel_id):
                continue
            for chapter in meta["chapters"]:
                if not PUBLICATION.visibility(chapter, chapter["id"]):
                    continue
                relative = Path("novels") / novel_id / chapter["file"]
                self.assertEqual((REPO / relative).read_bytes(), (self.base / "real-output" / relative).read_bytes())
                public_chapters += 1
        self.assertEqual(marker["chapters"], public_chapters)


if __name__ == "__main__":
    unittest.main()
