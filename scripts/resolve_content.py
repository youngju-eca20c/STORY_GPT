#!/usr/bin/env python3
"""Resolve a branch/requested SHA once without interpolating input into shell."""
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request


def resolve():
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("저장소 이름이 올바르지 않습니다")
    branch = os.environ.get("CONTENT_BRANCH", "").strip() or "editor-drafts"
    ref = os.environ.get("CONTENT_REF", "").strip()
    if len(branch) > 200 or branch.startswith("-") or re.search(r"[\x00-\x20~^:?*\[\\]", branch) or ".." in branch:
        raise ValueError("저장용 branch 이름이 올바르지 않습니다")
    if ref and not re.fullmatch(r"[0-9a-fA-F]{40}", ref):
        raise ValueError("content_ref에는 앱이 저장한 정확한 40자리 Commit SHA를 입력하세요")
    requested = ref or branch
    url = f"https://api.github.com/repos/{repository}/commits/{urllib.parse.quote(requested, safe='')}"
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "STORY-GPT-publication"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            sha = json.load(response)["sha"]
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise ValueError(f"저장용 branch/Commit을 찾을 수 없습니다: {requested}. 앱에서 저장용 branch를 먼저 만드세요. main으로 자동 대체하지 않습니다.") from None
        raise ValueError(f"콘텐츠 Commit 확인 실패: HTTP {error.code}") from None
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or (ref and sha != ref.lower()):
        raise ValueError("요청한 Commit과 GitHub 응답이 일치하지 않습니다")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("content_sha=" + sha + "\n")
    print("공개할 콘텐츠 Commit: " + sha)


if __name__ == "__main__":
    try:
        resolve()
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit("공개 준비 실패: " + str(error))
