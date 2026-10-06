"""Offline notification contracts; temporary Git histories stay under build/."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name("check_upstream.py")
ROOT = Path(__file__).resolve().parents[2]
TMP = ROOT / "build" / "upstream-tests"
TMP.mkdir(parents=True, exist_ok=True)
if SCRIPT.exists():
    spec = importlib.util.spec_from_file_location("check_upstream", SCRIPT)
    monitor = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = monitor
    spec.loader.exec_module(monitor)
else:
    monitor = None


class GitHistoryTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(monitor, "upstream monitor has not been implemented")
        self.tmp = tempfile.TemporaryDirectory(dir=TMP)
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.remote = self.path / "remote.git"
        self.git("init", "--bare", str(self.remote))
        self.tree = self.git("--git-dir", str(self.remote), "mktree", input="").strip()
        self.base = self.commit("base")
        self.up = self.commit("upstream one", self.base)
        self.personal = self.commit("personal", self.base)
        self.set_ref("master", self.personal)
        self.set_ref("upstream", self.up)

    def git(self, *args, input=None):
        env = dict(os.environ, GIT_AUTHOR_NAME="Test", GIT_AUTHOR_EMAIL="test@example.invalid",
                   GIT_COMMITTER_NAME="Test", GIT_COMMITTER_EMAIL="test@example.invalid")
        return subprocess.run(["git", *args], input=input, text=True, encoding="utf-8",
                              capture_output=True, check=True, env=env).stdout

    def commit(self, message, *parents):
        args = ["--git-dir", str(self.remote), "commit-tree", self.tree]
        for parent in parents:
            args += ["-p", parent]
        return self.git(*args, input=message).strip()

    def set_ref(self, branch, sha):
        self.git("--git-dir", str(self.remote), "update-ref", f"refs/heads/{branch}", sha)

    def snapshot(self):
        return monitor.GitSnapshot(str(self.remote), str(self.remote), self.base,
                                   target_branch="master", source_branch="upstream", temp_root=self.path)

    def test_only_remote_master_ancestry_counts(self):
        self.set_ref("local-merged-but-not-pushed", self.commit("merge", self.personal, self.up))
        with self.snapshot() as snapshot:
            result = snapshot.refresh()
            self.assertEqual(result["pending_count"], 1)
            self.assertEqual(result["commits"][0]["sha"], self.up)
            self.set_ref("master", self.commit("merged and pushed", self.personal, self.up))
            result = snapshot.refresh()
            self.assertEqual(result["pending_count"], 0)
            self.assertEqual(result["status"], "synced")

    def test_partial_merge_and_remote_rollback(self):
        second = self.commit("upstream two", self.up)
        self.set_ref("upstream", second)
        self.set_ref("master", self.commit("partial", self.personal, self.up))
        with self.snapshot() as snapshot:
            self.assertEqual(snapshot.refresh()["pending_count"], 1)
            self.set_ref("master", self.personal)
            self.assertEqual(snapshot.refresh()["pending_count"], 2)

    def test_equal_trees_do_not_hide_missing_original_commit(self):
        with self.snapshot() as snapshot:
            self.assertEqual(snapshot.refresh()["pending_count"], 1)

    def test_summary_limit_does_not_truncate_count(self):
        tip = self.up
        for i in range(24):
            tip = self.commit(f"change {i}", tip)
        self.set_ref("upstream", tip)
        with self.snapshot() as snapshot:
            result = snapshot.refresh()
            self.assertEqual(result["pending_count"], 25)
            self.assertEqual(len(result["commits"]), 20)

    def test_history_rewrite_without_common_ancestor_fails(self):
        self.set_ref("upstream", self.commit("unrelated"))
        with self.snapshot() as snapshot:
            with self.assertRaises(monitor.CheckError):
                snapshot.refresh()

    def test_remote_changed_and_fetch_failure_are_not_synced(self):
        with self.snapshot() as snapshot:
            result = snapshot.refresh()
            self.set_ref("master", self.commit("later", self.personal))
            self.assertFalse(snapshot.matches(result))
            self.git("--git-dir", str(self.remote), "update-ref", "-d", "refs/heads/upstream")
            with self.assertRaises(monitor.CheckError):
                snapshot.refresh()


class FakeAPI:
    def __init__(self):
        self.comments = []
        self.posts = 0
        self.lose_response = False
        self.fail_post = False
        self.locked = False

    def issue(self):
        return {"number": 1, "locked": self.locked, "html_url": "https://github.com/" + monitor.TARGET + "/issues/1"}

    def list_comments(self):
        return list(self.comments)

    def post_comment(self, body):
        self.posts += 1
        if self.fail_post:
            raise monitor.CheckError("HTTP unavailable")
        comment = {"id": self.posts, "body": body, "user": {"login": "github-actions[bot]"},
                   "html_url": "https://github.com/" + monitor.TARGET + f"/issues/1#issuecomment-{self.posts}"}
        self.comments.append(comment)
        if self.lose_response:
            raise monitor.CheckError("response lost")
        return comment


class FakeSnapshot:
    def __init__(self, counts=(1,), stable=(True,)):
        self.counts = iter(counts)
        self.stable = iter(stable)

    def refresh(self):
        count = next(self.counts)
        return {"schema": 1, "status": "pending" if count else "synced",
                "origin_sha": "a" * 40, "upstream_sha": "b" * 40, "pending_count": count,
                "commits": [{"sha": "b" * 40, "title": "@someone **title**"}] if count else [],
                "baseline_contained": True}

    def matches(self, result):
        return next(self.stable)


class NotificationTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(callable(getattr(monitor, "check", None)), "notification engine is missing")
        self.api = FakeAPI()
        self.now = datetime(2026, 10, 6, 22, tzinfo=timezone.utc)
        self.url = "https://github.com/" + monitor.TARGET + "/actions/runs/1"

    def run_check(self, snapshot=None, when=None):
        return monitor.check(self.api, snapshot or FakeSnapshot(), lambda: when or self.now, self.url)

    def test_repeat_next_day_but_not_same_day(self):
        self.assertEqual(self.run_check()["notification"], "posted")
        self.assertEqual(self.run_check()["notification"], "daily_reminder_exists")
        self.assertEqual(self.run_check(when=self.now + timedelta(days=1))["notification"], "posted")
        self.assertEqual(self.api.posts, 2)

    def test_new_commits_same_day_update_result_without_extra_reminder(self):
        self.run_check()
        result = self.run_check(FakeSnapshot((2,)))
        self.assertEqual(result["pending_count"], 2)
        self.assertEqual(result["notification"], "daily_reminder_exists")
        self.assertEqual(self.api.posts, 1)

    def test_midnight_crossing_during_fetch_uses_new_date(self):
        class CrossingSnapshot(FakeSnapshot):
            def refresh(inner):
                self.now = datetime(2026, 10, 6, 16, tzinfo=timezone.utc)
                return super().refresh()
        self.now = datetime(2026, 10, 6, 15, 59, tzinfo=timezone.utc)
        result = self.run_check(CrossingSnapshot())
        self.assertEqual(result["day"], "2026-10-07")

    def test_recovery_read_failure_preserves_uncertainty_without_retry(self):
        self.api.lose_response = True
        original = self.api.list_comments
        def comments():
            if self.api.posts:
                raise monitor.CheckError("read failed")
            return original()
        self.api.list_comments = comments
        with self.assertRaises(monitor.CheckError):
            self.run_check()
        self.assertEqual(self.api.posts, 1)

    def test_synced_does_not_post_even_with_old_reminder(self):
        self.run_check()
        result = self.run_check(FakeSnapshot((0,)))
        self.assertEqual(result["status"], "synced")
        self.assertEqual(self.api.posts, 1)

    def test_lost_post_response_is_recovered(self):
        self.api.lose_response = True
        result = self.run_check()
        self.assertEqual(result["notification"], "recovered")
        self.assertEqual(self.api.posts, 1)
        self.run_check()
        self.assertEqual(self.api.posts, 1)

    def test_failed_post_does_not_retry(self):
        self.api.fail_post = True
        with self.assertRaises(monitor.CheckError):
            self.run_check()
        self.assertEqual(self.api.posts, 1)

    def test_locked_issue_is_failure(self):
        self.api.locked = True
        with self.assertRaises(monitor.CheckError):
            self.run_check()
        self.assertEqual(self.api.posts, 0)

    def test_remote_merge_before_post_recalculates(self):
        result = self.run_check(FakeSnapshot((1, 0), (False, True)))
        self.assertEqual(result["status"], "synced")
        self.assertEqual(self.api.posts, 0)

    def test_continuously_changing_snapshot_fails(self):
        with self.assertRaises(monitor.CheckError):
            self.run_check(FakeSnapshot((1, 1), (False, False)))
        self.assertEqual(self.api.posts, 0)

    def test_user_marker_does_not_suppress_notification(self):
        self.run_check()
        self.api.comments[0]["user"]["login"] = "ordinary-user"
        self.run_check()
        self.assertEqual(self.api.posts, 2)

    def test_malformed_bot_record_fails(self):
        self.api.comments = [{"user": {"login": "github-actions[bot]"}, "body": monitor.MARKER + "{} -->"}]
        with self.assertRaises(monitor.CheckError):
            self.run_check()

    def test_local_date_and_midnight(self):
        self.run_check(when=datetime(2026, 10, 6, 15, 59, tzinfo=timezone.utc))
        self.run_check(when=datetime(2026, 10, 6, 16, 0, tzinfo=timezone.utc))
        self.assertEqual(self.api.posts, 2)
        self.assertIn("2026-10-07", self.api.comments[-1]["body"])

    def test_commit_title_cannot_mention_user(self):
        self.run_check()
        self.assertNotIn("@someone", self.api.comments[0]["body"])


class APIAndStatusTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(hasattr(monitor, "GitHub"), "GitHub adapter is missing")
        self.now = datetime(2026, 10, 7, 6, tzinfo=monitor.SHANGHAI)

    def test_comments_paginate(self):
        api = monitor.GitHub("fake", issue_number=1)
        calls = []
        def request(path, method="GET", body=None):
            calls.append(path)
            return [{}] * 100 if path.endswith("&page=1") else [{"id": 101}]
        api.request = request
        self.assertEqual(len(api.list_comments()), 101)
        self.assertEqual(len(calls), 2)

    def test_transport_error_is_not_empty_success(self):
        api = monitor.GitHub("fake", issue_number=1)
        with patch.object(monitor.urllib.request, "urlopen", side_effect=OSError("token=secret")):
            with self.assertRaises(monitor.CheckError) as caught:
                api.issue()
        self.assertNotIn("secret", str(caught.exception))

    def test_status_stale_and_latest_failed_stay_distinct(self):
        api = self.fake_status_api("failure", self.now - timedelta(hours=40))
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertEqual(result["latest_run"]["conclusion"], "failure")
        self.assertTrue(result["stale"])
        self.assertEqual(result["last_successful_check"]["pending_count"], 0)
        self.assertEqual(api.posts, 0)

    def test_old_issue_does_not_override_synced_log(self):
        api = self.fake_status_api("success", self.now)
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertEqual(result["last_successful_check"]["status"], "synced")
        self.assertFalse(result["stale"])
        self.assertEqual(api.posts, 0)

    def test_missing_logs_are_unknown(self):
        api = self.fake_status_api("success", self.now)
        api.run_logs = lambda run: (_ for _ in ()).throw(monitor.CheckError("expired"))
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertIsNone(result["last_successful_check"])
        self.assertIn("log", result["detail_error"])

    def test_error_or_duplicate_log_records_cannot_be_a_success(self):
        api = self.fake_status_api("success", self.now)
        text = api.run_logs(1)
        for bad_logs in (text + "\n" + text,
                         monitor.RESULT_PREFIX + json.dumps({"schema": 1, "status": "error"})):
            with self.subTest(logs=bad_logs):
                api.run_logs = lambda run: bad_logs
                self.assertIsNone(monitor.status_report(api, clock=lambda: self.now)["last_successful_check"])

    def test_future_check_timestamp_is_unknown(self):
        api = self.fake_status_api("success", self.now + timedelta(hours=2))
        self.assertIsNone(monitor.status_report(api, clock=lambda: self.now)["last_successful_check"])

    def test_no_workflow_runs_is_not_synced(self):
        api = self.fake_status_api("success", self.now)
        api.runs = lambda conclusion=None: []
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertIsNone(result["latest_run"])
        self.assertIsNone(result["last_successful_check"])

    def test_log_from_other_run_rejected(self):
        api = self.fake_status_api("success", self.now)
        text = api.run_logs(1).replace("/runs/1", "/runs/999")
        api.run_logs = lambda run: text
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertIsNone(result["last_successful_check"])

    def fake_status_api(self, conclusion, checked):
        api = FakeAPI()
        run = {"id": 1, "status": "completed", "conclusion": conclusion,
               "html_url": "https://github.com/" + monitor.TARGET + "/actions/runs/1",
               "created_at": self.now.isoformat(), "updated_at": self.now.isoformat()}
        success = dict(run, conclusion="success")
        api.runs = lambda conclusion=None: [success if conclusion else run]
        result = {"schema": 1, "status": "synced", "origin_sha": "a" * 40,
                  "upstream_sha": "b" * 40, "pending_count": 0, "checked_at": checked.isoformat(),
                  "run_url": run["html_url"], "source": monitor.SOURCE, "target": monitor.TARGET}
        api.run_logs = lambda run: "timestamp\t" + monitor.RESULT_PREFIX + json.dumps(result)
        return api

    def test_default_cli_never_enters_writer(self):
        with patch.object(monitor, "GitHub") as cls, patch.object(monitor, "get_token", return_value="fake"), \
                patch.object(monitor, "status_report", return_value={"state": "read-only"}) as status, \
                patch.object(monitor, "check") as writer, patch("builtins.print"):
            self.assertEqual(monitor.main([]), 0)
            status.assert_called_once()
            writer.assert_not_called()

    def test_write_cli_rejects_local_execution(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(monitor, "get_token") as token, patch("builtins.print"):
            self.assertEqual(monitor.main(["--notify"]), 1)
            token.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
