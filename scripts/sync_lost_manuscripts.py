#!/usr/bin/env python3
"""Import the current LOST prologue and episodes without changing their prose."""
import argparse
import datetime
import json
import re
from pathlib import Path


def sync(source, site, date):
    source, site = Path(source), Path(site)
    novel = site / "novels" / "last-observatory"
    meta_path = novel / "meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8-sig"))
    chapters = meta["chapters"]
    prologue = next((c for c in chapters if c["title"].startswith("프롤로그")), None)
    prologue_id = prologue["id"] if prologue else "000"
    imported = []
    for filename, chapter_id in [("프롤로그.txt", prologue_id), ("1화.txt", "001"),
                                 ("2화.txt", "002"), ("3화.txt", "003")]:
        original = (source / filename).read_text(encoding="utf-8-sig")
        lines = original.splitlines(keepends=True)
        title = re.sub(r"^#\s*", "", lines[0].strip())
        if not title:
            raise ValueError(f"제목이 없습니다: {filename}")
        # The reader removes the first paragraph when it matches the metadata title.
        body = title + "\n" + "".join(lines[1:])
        target = novel / "chapters" / f"{chapter_id}.txt"
        target.write_text(body, encoding="utf-8", newline="\n")
        chapter = next((c for c in chapters if c["id"] == chapter_id), None)
        if chapter is None:
            chapter = {"id": chapter_id, "published": date}
            chapters.insert(0, chapter) if filename == "프롤로그.txt" else chapters.append(chapter)
        chapter.update(title=title, file=f"chapters/{chapter_id}.txt", updated=date)
        chapter.pop("visibility", None)
        imported.append({"source": filename, "id": chapter_id, "title": title})
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return imported


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--site", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--date", default=datetime.date.today().isoformat())
    args = parser.parse_args()
    print(json.dumps(sync(args.source, args.site, args.date), ensure_ascii=False, indent=2))
