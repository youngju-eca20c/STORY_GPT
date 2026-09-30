import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

SPEC = importlib.util.spec_from_file_location("resolve_content", Path(__file__).resolve().parents[1] / "scripts/resolve_content.py")
RESOLVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESOLVER)
SHA = "d" * 40


class ResolveTests(unittest.TestCase):
    def test_branch_resolves_to_an_exact_snapshot_without_token_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            environment = {"GITHUB_REPOSITORY": "owner/STORY_GPT", "GH_TOKEN": "test-token-never-print",
                           "GITHUB_OUTPUT": str(output), "CONTENT_BRANCH": "editor/drafts", "CONTENT_REF": ""}
            response = io.BytesIO(json.dumps({"sha": SHA}).encode())
            logs = io.StringIO()
            with patch.dict(os.environ, environment), patch.object(RESOLVER.urllib.request, "urlopen", return_value=response) as call, patch("sys.stdout", logs):
                RESOLVER.resolve()
            self.assertTrue(call.call_args.args[0].full_url.endswith("/commits/editor%2Fdrafts"))
            self.assertEqual(output.read_text(), f"content_sha={SHA}\n")
            self.assertNotIn(environment["GH_TOKEN"], logs.getvalue())

    def test_missing_branch_fails_without_falling_back_to_main(self):
        environment = {"GITHUB_REPOSITORY": "owner/STORY_GPT", "GH_TOKEN": "test-token", "CONTENT_BRANCH": "editor-drafts", "CONTENT_REF": ""}
        with patch.dict(os.environ, environment), patch.object(RESOLVER.urllib.request, "urlopen", side_effect=HTTPError("url", 404, "missing", {}, None)) as call:
            with self.assertRaisesRegex(ValueError, "main으로 자동 대체하지 않습니다"):
                RESOLVER.resolve()
            self.assertEqual(call.call_count, 1)

    def test_malformed_inputs_do_not_reach_the_network(self):
        for values in [{"CONTENT_REF": "$(echo unsafe)"}, {"CONTENT_BRANCH": "draft\nmain"}, {"CONTENT_BRANCH": "../../main"}]:
            environment = {"GITHUB_REPOSITORY": "owner/STORY_GPT", "GH_TOKEN": "test-token", "CONTENT_BRANCH": "editor-drafts", "CONTENT_REF": "", **values}
            with self.subTest(values=values), patch.dict(os.environ, environment), patch.object(RESOLVER.urllib.request, "urlopen") as call:
                with self.assertRaises(ValueError):
                    RESOLVER.resolve()
                call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
