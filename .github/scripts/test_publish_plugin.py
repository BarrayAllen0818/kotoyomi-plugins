import copy
import importlib.util
import json
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile


sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("publish_plugin", Path(__file__).with_name("publish_plugin.py"))
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class IndexTests(unittest.TestCase):
    def index(self):
        return [{"name": "Kototoro Parsers", "pkg": "org.skepsun.kototoro.parsers",
                 "version": "1.0.137", "code": 137, "apk": "plugin.jar", "lang": "all", "nsfw": 0}]

    def test_increments_remote_code_without_mutating_input_or_other_fields(self):
        original = self.index()
        original.append({"pkg": "other.plugin", "code": 900, "custom": True})
        before = copy.deepcopy(original)
        result = publisher.next_index(original)
        self.assertEqual(138, result[0]["code"])
        self.assertEqual("1.0.138", result[0]["version"])
        expected = copy.deepcopy(before)
        expected[0].update(code=138, version="1.0.138")
        self.assertEqual(expected, result)
        self.assertEqual(before, original)

    def test_version_uses_code_even_when_old_display_version_differs(self):
        index = self.index()
        index[0].update(code=205, version="upstream-9.0")
        self.assertEqual("1.0.206", publisher.next_index(index)[0]["version"])

    def test_rejects_invalid_or_overflowing_code(self):
        for code in (True, "137", None, -1, 0, 2147483647):
            with self.subTest(code=code):
                index = self.index()
                index[0]["code"] = code
                with self.assertRaises(ValueError):
                    publisher.next_index(index)

    def test_rejects_missing_duplicate_or_unexpected_plugin_entry(self):
        for index in ({}, [], self.index() * 2, [{"pkg": "other.plugin"}]):
            with self.subTest(index=index), self.assertRaises(ValueError):
                publisher.next_index(index)
        index = self.index()
        index[0]["apk"] = "../unexpected.jar"
        with self.assertRaises(ValueError):
            publisher.next_index(index)


class GitPublicationTests(unittest.TestCase):
    def setUp(self):
        # Keep all disposable repositories on the project's drive, including on Windows.
        test_root = Path(__file__).resolve().parents[2] / "build" / "release-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=test_root)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.remote = self.root / "remote.git"
        self.source = self.root / "source"
        subprocess.run(["git", "init", "--bare", str(self.remote)], check=True, capture_output=True)
        subprocess.run(["git", "init", "-b", "master", str(self.source)], check=True, capture_output=True)
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("config", "core.autocrlf", "false")
        (self.source / ".gitignore").write_text("build/\n", encoding="utf-8")
        (self.source / "source.txt").write_text("source\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-m", "source")
        self.source_commit = self.git("rev-parse", "HEAD")
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "origin", "master")
        seed = self.root / "seed"
        subprocess.run(["git", "init", "-b", "repo", str(seed)], check=True, capture_output=True)
        publisher.git(seed, "config", "user.name", "Release test")
        publisher.git(seed, "config", "user.email", "release-test@example.invalid")
        (seed / "apk").mkdir()
        self.jar(seed / "apk/plugin.jar", b"old")
        (seed / "index.min.json").write_text(json.dumps(IndexTests().index()), encoding="utf-8")
        (seed / ".nojekyll").write_text("", encoding="utf-8")
        (seed / "keep.txt").write_text("unrelated release data", encoding="utf-8")
        publisher.git(seed, "add", ".")
        publisher.git(seed, "commit", "-m", "existing release")
        self.base = publisher.git(seed, "rev-parse", "HEAD")
        publisher.git(seed, "push", str(self.remote), "repo")
        self.artifact = self.root / "new.jar"
        self.jar(self.artifact, b"new")
        self.hash = publisher.sha256(self.artifact.read_bytes())

    def git(self, *args):
        return publisher.git(self.source, *args)

    @staticmethod
    def jar(path, payload):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", b"dex\n035\x00" + payload)

    def prepare(self):
        return publisher.prepare(self.source, self.artifact, self.hash)

    def test_prepare_does_not_publish_and_push_preserves_history(self):
        candidate = self.prepare()
        self.assertEqual("prepared", candidate["status"])
        self.assertEqual(138, candidate["code"])
        self.assertEqual(self.base, self.git("ls-remote", "origin", "refs/heads/repo").split()[0])
        self.assertEqual(self.base, self.git("rev-parse", candidate["commit"] + "^"))
        self.assertEqual("unrelated release data", self.git("show", candidate["commit"] + ":keep.txt"))
        self.assertEqual("", self.git("show", candidate["commit"] + ":.nojekyll"))
        receipt = json.loads(self.git("show", candidate["commit"] + ":release.json"))
        self.assertEqual(self.source_commit, receipt["source_commit"])
        self.assertEqual(self.hash, receipt["sha256"])
        result = publisher.push_prepared(candidate)
        self.assertEqual("published", result["status"])
        self.assertEqual(candidate["commit"], self.git("ls-remote", "origin", "refs/heads/repo").split()[0])
        self.assertEqual(self.source_commit, self.git("rev-parse", "HEAD"))
        self.assertEqual("", self.git("status", "--porcelain"))

    def test_identical_artifact_retry_does_not_increment_or_commit(self):
        first = publisher.push_prepared(self.prepare())
        retry = self.prepare()
        self.assertEqual("unchanged", retry["status"])
        self.assertEqual(first["commit"], retry["commit"])
        self.assertEqual(138, retry["code"])
        self.assertEqual("unchanged", publisher.push_prepared(retry)["status"])

    def test_racing_candidate_is_rejected_and_preserved(self):
        first = self.prepare()
        self.jar(self.artifact, b"other release")
        self.hash = publisher.sha256(self.artifact.read_bytes())
        second = self.prepare()
        publisher.push_prepared(first)
        with self.assertRaises(subprocess.CalledProcessError):
            publisher.push_prepared(second)
        self.assertTrue(Path(second["worktree"]).is_dir())
        self.assertEqual(first["commit"], self.git("ls-remote", "origin", "refs/heads/repo").split()[0])
        fresh = self.prepare()
        self.assertEqual(139, fresh["code"])
        self.assertEqual(first["commit"], fresh["parent"])

    def test_wrong_hash_dirty_source_and_missing_dex_do_not_publish(self):
        with self.assertRaises(ValueError):
            publisher.prepare(self.source, self.artifact, "0" * 64)
        (self.source / "source.txt").write_text("uncommitted", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual(self.base, self.git("ls-remote", "origin", "refs/heads/repo").split()[0])
        with zipfile.ZipFile(self.artifact, "w") as archive:
            archive.writestr("not-dex", "invalid")
        with self.assertRaises(KeyError):
            publisher.artifact_bytes(self.artifact, publisher.sha256(self.artifact.read_bytes()))

    def test_http_verification_rejects_stale_index_and_wrong_download(self):
        candidate = self.prepare()
        worktree = Path(candidate["worktree"])

        class QuietHandler(SimpleHTTPRequestHandler):
            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(worktree)))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/"
            self.assertTrue(publisher.verify_download(url, candidate)["http_verified"])
            index_path = worktree / "index.min.json"
            index = json.loads(index_path.read_text(encoding="utf-8"))
            index[0]["code"] -= 1
            index_path.write_text(json.dumps(index), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "index"):
                publisher.verify_download(url, candidate)
            index[0]["code"] += 1
            index_path.write_text(json.dumps(index), encoding="utf-8")
            (worktree / "apk/plugin.jar").write_bytes(b"wrong download")
            with self.assertRaisesRegex(ValueError, "Downloaded"):
                publisher.verify_download(url, candidate)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
