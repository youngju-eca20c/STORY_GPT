#!/usr/bin/env python3
"""Build only public STORY_GPT content, using trusted reader files separately.

No source files are edited. Output must be empty to prevent stale deleted/private
files from surviving a later publication.
"""
import argparse
import json
import re
import shutil
from pathlib import Path

READER_FILES = ("index.html", "css/style.css", "js/storage.js", "js/views.js", "js/app.js")
SEGMENT = re.compile(r"^[^/\\%?#:\x00-\x1f]+$")
IMAGE = re.compile(r"^illust/[^/\\%?#:\x00-\x1f()]+\.(?:jpe?g|png|webp)$", re.I)
INLINE = re.compile(r"^!\[([^\]\r\n]*)\]\(([^\r\n]+)\)$")
CHAPTER = re.compile(r"^chapters/[^/\\%?#:\x00-\x1f]+\.txt$", re.I)
EXTENSION = re.compile(r"\.(?:jpe?g|png|webp)$", re.I)


def fail(message):
    raise ValueError(message)


def segment(value, label):
    if not isinstance(value, str) or not SEGMENT.fullmatch(value) or value in (".", ".."):
        fail(f"{label}: 안전하지 않은 이름")
    return value


def source_file(root, relative):
    root = Path(root).resolve()
    path = root.joinpath(*relative.split("/"))
    if not path.resolve().is_relative_to(root):
        fail(f"저장소 밖 경로: {relative}")
    cursor = path
    while cursor != root:
        if cursor.is_symlink():
            fail(f"심볼릭 링크는 공개할 수 없습니다: {relative}")
        cursor = cursor.parent
    if not path.is_file():
        fail(f"참조 파일이 없습니다: {relative}")
    return path


def read_json(root, relative):
    value = json.loads(source_file(root, relative).read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        fail(f"{relative}: JSON 객체가 필요합니다")
    return value


def visibility(item, label):
    value = item.get("visibility", "public")
    if value not in ("public", "private", "draft"):
        fail(f"{label}: visibility는 public/private/draft여야 합니다")
    return value == "public"


def metadata(item, label):
    if not isinstance(item, dict):
        fail(f"{label}: 객체가 필요합니다")
    if not isinstance(item.get("title"), str) or not item["title"].strip():
        fail(f"{label}: 제목이 없습니다")
    for key in ("author", "description", "status", "published", "updated"):
        if key in item and not isinstance(item[key], str):
            fail(f"{label}: {key}는 문자열이어야 합니다")
    if "tags" in item and (not isinstance(item["tags"], list) or not all(isinstance(v, str) for v in item["tags"])):
        fail(f"{label}: tags는 문자열 배열이어야 합니다")
    return visibility(item, label)


def illustration(path, label):
    if not isinstance(path, str) or not IMAGE.fullmatch(path):
        fail(f"{label}: illust/ 아래 JPEG/PNG/WebP 파일만 사용할 수 있습니다")
    segment(path.split("/")[1], label)
    return path


def inline_images(body, label):
    images = set()
    for paragraph in re.split(r"\n\s*\n", body.replace("\r\n", "\n")):
        paragraph = paragraph.strip()
        match = INLINE.fullmatch(paragraph)
        if match:
            images.add(illustration(match[2], label))
        elif re.match(r"^!\[[^\]\r\n]*\]\(", paragraph):
            fail(f"{label}: 일러스트는 빈 줄로 구분한 독립 문단이어야 합니다")
    return images


def lore_images(value, label):
    images = set()
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "portrait" and child:
                images.add(illustration(child, label))
            else:
                images.update(lore_images(child, label))
    elif isinstance(value, list):
        for child in value:
            images.update(lore_images(child, label))
    return images


def build_publication(reader, content, output, content_sha, request_id="", reader_sha=""):
    reader, content, output = Path(reader).resolve(), Path(content).resolve(), Path(output).resolve()
    if output == reader or output == content or reader.is_relative_to(output) or content.is_relative_to(output):
        fail("출력 경로가 소스 저장소를 포함할 수 없습니다")
    if output.exists() and any(output.iterdir()):
        fail("공개 출력 폴더는 비어 있어야 합니다 (이전 비공개/삭제 파일 잔존 방지)")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", content_sha):
        fail("정확한 40자리 content SHA가 필요합니다")
    if reader_sha and not re.fullmatch(r"[0-9a-fA-F]{40}", reader_sha):
        fail("reader SHA가 올바르지 않습니다")
    if not isinstance(request_id, str) or len(request_id) > 160 or re.search(r"[\x00-\x1f]", request_id):
        fail("공개 요청 ID가 올바르지 않습니다")
    output.mkdir(parents=True, exist_ok=True)
    copied = set()

    def copy(root, relative):
        key = relative.casefold()
        if key in copied:
            return
        target = output.joinpath(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_file(root, relative), target)
        copied.add(key)

    def write_json(relative, value):
        target = output.joinpath(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    index = read_json(content, "data/novels.json")
    novels = index.get("novels")
    if not isinstance(novels, list):
        fail("data/novels.json: novels 배열이 필요합니다")
    public_novels, novel_ids, images, total_chapters = [], set(), set(), 0
    for ref in novels:
        index_public = metadata(ref, "작품 목록")
        novel_id = segment(ref.get("id"), "작품 ID")
        if novel_id.casefold() in novel_ids:
            fail(f"중복 작품 ID: {novel_id}")
        novel_ids.add(novel_id.casefold())
        prefix = f"novels/{novel_id}/"
        meta = read_json(content, prefix + "meta.json")
        meta_public = metadata(meta, prefix + "meta.json")
        chapters = meta.get("chapters")
        if not isinstance(chapters, list):
            fail(f"{prefix}meta.json: chapters 배열이 필요합니다")
        chapter_ids, chapter_files, public_chapters = set(), set(), []
        for chapter in chapters:
            chapter_public = metadata(chapter, prefix + "회차")
            chapter_id = segment(chapter.get("id"), "회차 ID")
            chapter_file = chapter.get("file")
            if not isinstance(chapter_file, str) or not CHAPTER.fullmatch(chapter_file):
                fail(f"{novel_id}/{chapter_id}: chapters/*.txt 파일 경로가 필요합니다")
            segment(chapter_file.split("/")[1], "회차 파일")
            if chapter_id.casefold() in chapter_ids or chapter_file.casefold() in chapter_files:
                fail(f"{novel_id}: 중복 회차 ID 또는 파일")
            chapter_ids.add(chapter_id.casefold())
            chapter_files.add(chapter_file.casefold())
            if index_public and meta_public and chapter_public:
                body = source_file(content, prefix + chapter_file).read_text(encoding="utf-8-sig")
                images.update(inline_images(body, f"{novel_id}/{chapter_id}"))
                copy(content, prefix + chapter_file)
                public_chapters.append(chapter)
        if not (index_public and meta_public):
            continue
        effective = {**ref, **meta}
        for key in ("cover", "representative"):
            image = effective.get(key)
            if image:
                segment(image, f"{novel_id}/{key}")
                if not EXTENSION.search(image):
                    fail(f"{novel_id}/{key}: JPEG/PNG/WebP 파일이 필요합니다")
                images.add(prefix + image)
        lore_path = content / prefix / "worldbuilding.json"
        if lore_path.exists():
            lore = read_json(content, prefix + "worldbuilding.json")
            for key in ("world", "characters", "future"):
                if key in lore and not isinstance(lore[key], list):
                    fail(f"{novel_id}/worldbuilding.json: {key}는 배열이어야 합니다")
            images.update(lore_images(lore, novel_id + "/worldbuilding.json"))
            copy(content, prefix + "worldbuilding.json")
        write_json(prefix + "meta.json", {**meta, "chapters": public_chapters})
        # The home screen uses the index, while the detail screen merges meta.
        # Keep their visible values consistent even after a manual metadata edit.
        public_novels.append({**ref, **{key: effective[key] for key in
            ("title", "author", "description", "status", "tags", "cover", "representative", "visibility") if key in effective}})
        total_chapters += len(public_chapters)
    for image in sorted(images):
        copy(content, image)
    for relative in READER_FILES:
        copy(reader, relative)
    write_json("data/novels.json", {**index, "novels": public_novels})
    marker = {"content_sha": content_sha.lower(), "reader_sha": reader_sha.lower(), "request_id": request_id,
              "novels": len(public_novels), "chapters": total_chapters}
    write_json("publication.json", marker)
    (output / ".nojekyll").write_text("", encoding="utf-8")
    return marker


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reader", required=True)
    parser.add_argument("--content", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--content-sha", required=True)
    parser.add_argument("--reader-sha", default="")
    parser.add_argument("--request-id", default="")
    args = parser.parse_args()
    try:
        marker = build_publication(args.reader, args.content, args.output, args.content_sha, args.request_id, args.reader_sha)
    except (ValueError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, f"공개 자료 검증 실패: {error}\n")
    print(json.dumps(marker, ensure_ascii=False))


if __name__ == "__main__":
    main()
