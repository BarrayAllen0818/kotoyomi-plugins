"""Remind about upstream commits missing from the published source history.

Default CLI is read-only. Only the dedicated Actions workflow can post reminders.
"""

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

TARGET = "BarrayAllen0818/kotoyomi-plugins"
SOURCE = "skepsun/kototoro-parsers"
BRANCH = "master"
INITIAL_BASE = "ca313756e395b5ddbd201e01cc01ece01078d15c"
ISSUE_NUMBER = 1
WORKFLOW = "check-upstream.yml"
SHANGHAI = timezone(timedelta(hours=8))
RESULT_PREFIX = "UPSTREAM_CHECK_RESULT "
MARKER = "<!-- upstream-reminder-v1 "
SHA = re.compile(r"[0-9a-f]{40}")


class CheckError(RuntimeError):
    pass


def command(args, *, cwd=None, input=None, timeout=120, allowed=(0,), env=None):
    try:
        result = subprocess.run(args, cwd=cwd, input=input, text=True, encoding="utf-8",
                                errors="replace", capture_output=True, timeout=timeout, env=env)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise CheckError(f"{args[0]} unavailable or timed out") from exc
    if result.returncode not in allowed:
        # Never include raw stderr: it may contain remote credentials or untrusted text.
        raise CheckError(f"{args[0]} failed (exit {result.returncode})")
    return result


def valid_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise CheckError("Invalid Git SHA")
    return value


class GitSnapshot:
    def __init__(self, target_url=f"https://github.com/{TARGET}.git",
                 source_url=f"https://github.com/{SOURCE}.git", initial_base=INITIAL_BASE,
                 *, target_branch=BRANCH, source_branch=BRANCH, temp_root=None):
        self.urls = (target_url, source_url)
        self.branches = (target_branch, source_branch)
        self.initial_base = valid_sha(initial_base)
        self.temp_root = temp_root
        self.tmp = None

    def __enter__(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="upstream-check-", dir=self.temp_root)
        self.path = Path(self.tmp.name)
        try:
            self.git("init", "--bare", ".")
        except BaseException:
            self.tmp.cleanup()
            raise
        return self

    def __exit__(self, *args):
        self.tmp.cleanup()

    def git(self, *args, allowed=(0,)):
        env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
        return command(["git", *args], cwd=self.path, allowed=allowed, env=env)

    def ancestor(self, base, tip):
        return self.git("merge-base", "--is-ancestor", base, tip, allowed=(0, 1)).returncode == 0

    def refresh(self):
        for role, url, branch in zip(("target", "source"), self.urls, self.branches):
            self.git("fetch", "--no-tags", url, f"+refs/heads/{branch}:refs/check/{role}")
        origin = valid_sha(self.git("rev-parse", "refs/check/target").stdout.strip())
        upstream = valid_sha(self.git("rev-parse", "refs/check/source").stdout.strip())
        if self.git("rev-parse", "--is-shallow-repository").stdout.strip() != "false":
            raise CheckError("Incomplete shallow Git history")
        self.git("fsck", "--connectivity-only", "--no-dangling", origin, upstream)
        self.git("merge-base", origin, upstream)  # Exit 1 means unrelated histories.
        count = int(self.git("rev-list", "--count", upstream, "--not", origin).stdout.strip())
        commits = []
        lines = self.git("log", "--max-count=20", "--format=%H%x09%s", upstream, "--not", origin).stdout
        for line in lines.splitlines():
            sha, title = line.split("\t", 1)
            commits.append({"sha": valid_sha(sha), "title": title})
        base_exists = self.git("cat-file", "-e", f"{self.initial_base}^{{commit}}",
                               allowed=(0, 1, 128)).returncode == 0
        baseline_contained = base_exists and all(self.ancestor(self.initial_base, tip)
                                                for tip in (origin, upstream))
        return {"schema": 1, "status": "pending" if count else "synced",
                "origin_sha": origin, "upstream_sha": upstream, "pending_count": count,
                "commits": commits, "baseline_contained": baseline_contained}

    def matches(self, result):
        for url, branch, expected in zip(self.urls, self.branches,
                                         (result["origin_sha"], result["upstream_sha"])):
            lines = self.git("ls-remote", "--exit-code", url, f"refs/heads/{branch}").stdout.splitlines()
            if len(lines) != 1 or valid_sha(lines[0].split()[0]) != expected:
                return False
        return True


def now_utc():
    return datetime.now(timezone.utc)


def issue_url():
    return f"https://github.com/{TARGET}/issues/{ISSUE_NUMBER}"


def parse_time(value):
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None:
            raise ValueError("timezone missing")
        return result
    except (ValueError, TypeError, AttributeError) as exc:
        raise CheckError("Invalid timestamp") from exc


def reminder_for_day(comments, day):
    found = None
    for comment in comments:
        if comment.get("user", {}).get("login") != "github-actions[bot]":
            continue
        body = comment.get("body") or ""
        if MARKER not in body:
            continue
        matches = re.findall(re.escape(MARKER) + r"(.*?) -->", body, re.DOTALL)
        try:
            if len(matches) != 1:
                raise ValueError("ambiguous marker")
            record = json.loads(matches[0])
            if (record["schema"] != 1 or record["source"] != SOURCE or record["target"] != TARGET
                    or record["branch"] != BRANCH or type(record["pending_count"]) is not int
                    or record["pending_count"] < 1):
                raise ValueError("unexpected record")
            valid_sha(record["origin_sha"])
            valid_sha(record["upstream_sha"])
            expected_day = parse_time(record["checked_at"]).astimezone(SHANGHAI).date().isoformat()
            if record["day"] != expected_day:
                raise ValueError("date mismatch")
            if not re.fullmatch(r"https://github\.com/" + re.escape(TARGET) + r"/actions/runs/[0-9]+",
                                record["run_url"]):
                raise ValueError("invalid run URL")
            if record["day"] == day:
                found = comment
        except (KeyError, TypeError, ValueError, CheckError) as exc:
            raise CheckError("Malformed upstream reminder from Actions bot") from exc
    return found


def safe_title(title):
    value = " ".join(title.split())[:200].replace("@", "＠")
    return re.sub(r"([\\\`*_{}\[\]<>#!|])", r"\\\1", value)


def reminder_body(result, run_url):
    record = {key: result[key] for key in
              ("schema", "day", "origin_sha", "upstream_sha", "pending_count", "checked_at")}
    record.update(source=SOURCE, target=TARGET, branch=BRANCH, run_url=run_url)
    lines = [f"仍有 **{result['pending_count']}** 个上游提交尚未合并并推送到远端 master。",
             "", f"检查时间：{result['checked_at']}（北京时间 {result['day']}）",
             f"- 个人远端：[master @ {result['origin_sha'][:8]}](https://github.com/{TARGET}/commit/{result['origin_sha']})",
             f"- 上游：[master @ {result['upstream_sha'][:8]}](https://github.com/{SOURCE}/commit/{result['upstream_sha']})",
             f"- [检查记录]({run_url})", "", "尚未合入的提交：", ""]
    for commit in result["commits"]:
        lines.append(f"- [{commit['sha'][:8]}](https://github.com/{SOURCE}/commit/{commit['sha']}) "
                     + safe_title(commit["title"]))
    if result["pending_count"] > len(result["commits"]):
        lines.append(f"仅展示前 {len(result['commits'])} 条，共 {result['pending_count']} 条。")
    if not result["baseline_contained"]:
        lines.extend(["", "初始已同步基线不再被两端完整包含，请留意历史回退或改写。"])
    lines.extend(["", "合并并推送到个人远端 master 后，下一次成功检查停止提醒。", "",
                  MARKER + json.dumps(record, ensure_ascii=True, sort_keys=True) + " -->"])
    return "\n".join(lines)


def check(api, snapshot, clock=now_utc, run_url=""):
    issue = api.issue()
    if issue.get("pull_request") is not None or issue.get("locked"):
        raise CheckError("Reminder Issue is unavailable, locked or a pull request")
    comments = api.list_comments()
    for attempt in range(2):
        result = snapshot.refresh()
        if snapshot.matches(result):
            break
    else:
        raise CheckError("snapshot_changed: remote branches kept moving")
    # Take the date after network/Git work, so a midnight crossing uses the new day.
    checked = clock().astimezone(SHANGHAI)
    result.update(checked_at=checked.isoformat(), day=checked.date().isoformat(),
                  issue_url=issue["html_url"], notification="none", run_url=run_url)
    prior = reminder_for_day(comments, result["day"])
    if result["status"] == "synced":
        return result
    if prior:
        result.update(notification="daily_reminder_exists", comment_url=prior["html_url"])
        return result
    body = reminder_body(result, run_url)
    try:
        saved = api.post_comment(body)
        result.update(notification="posted", comment_url=saved["html_url"])
    except (CheckError, KeyError, TypeError) as exc:
        saved = reminder_for_day(api.list_comments(), result["day"])
        if not saved:
            raise CheckError("Reminder POST failed or is uncertain; no blind retry") from exc
        result.update(notification="recovered", comment_url=saved["html_url"])
    return result


def get_token():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        token = command(["gh", "auth", "token", "--hostname", "github.com"]).stdout.strip()
    if not token:
        raise CheckError("GitHub authentication is required")
    return token


class GitHub:
    def __init__(self, token, issue_number=ISSUE_NUMBER):
        self.token = token
        self.issue_number = issue_number

    def request(self, path, method="GET", body=None):
        if not path.startswith(f"/repos/{TARGET}/"):
            raise CheckError("Unexpected API target")
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "kotoyomi-upstream-check"}
        data = None if body is None else json.dumps(body).encode("utf-8")
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request("https://api.github.com" + path, data=data,
                                         headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            raise CheckError(f"GitHub API HTTP {exc.code} ({method})") from exc
        except (OSError, ValueError) as exc:
            raise CheckError(f"GitHub API response unavailable or invalid ({method})") from exc

    def issue(self):
        if self.issue_number < 1:
            raise CheckError("Reminder Issue has not been initialized")
        result = self.request(f"/repos/{TARGET}/issues/{self.issue_number}")
        expected = f"https://github.com/{TARGET}/issues/{self.issue_number}"
        if (not isinstance(result, dict) or result.get("number") != self.issue_number
                or result.get("html_url") != expected):
            raise CheckError("Unexpected reminder Issue")
        return result

    def list_comments(self):
        comments = []
        page = 1
        while True:
            batch = self.request(f"/repos/{TARGET}/issues/{self.issue_number}/comments?per_page=100&page={page}")
            if not isinstance(batch, list) or not all(isinstance(item, dict) for item in batch):
                raise CheckError("Invalid Issue comments page")
            comments.extend(batch)
            if len(batch) < 100:
                return comments
            page += 1

    def post_comment(self, body):
        return self.request(f"/repos/{TARGET}/issues/{self.issue_number}/comments",
                            "POST", {"body": body})

    def runs(self, conclusion=None):
        path = f"/repos/{TARGET}/actions/workflows/{WORKFLOW}/runs?branch={BRANCH}&per_page=1"
        if conclusion:
            path += "&status=" + conclusion
        result = self.request(path)
        if not isinstance(result, dict) or not isinstance(result.get("workflow_runs"), list):
            raise CheckError("Invalid workflow runs response")
        return result["workflow_runs"]

    def run_logs(self, run_id):
        if type(run_id) is not int or run_id < 1:
            raise CheckError("Invalid run id")
        return command(["gh", "run", "view", str(run_id), "--repo", TARGET, "--log"],
                       env=dict(os.environ, GH_TOKEN=self.token, GH_HOST="github.com")).stdout


def result_from_logs(logs, run_url):
    results = []
    for line in logs.splitlines():
        if RESULT_PREFIX not in line:
            continue
        raw = line.split(RESULT_PREFIX, 1)[1]
        try:
            record = json.loads(raw)
            count = record["pending_count"]
            if (record["schema"] != 1 or record["run_url"] != run_url
                    or record["source"] != SOURCE or record["target"] != TARGET
                    or type(count) is not int or count < 0
                    or record["status"] != ("synced" if count == 0 else "pending")):
                raise ValueError("mismatched result")
            valid_sha(record["origin_sha"])
            valid_sha(record["upstream_sha"])
            parse_time(record["checked_at"])
            results.append(record)
        except (ValueError, KeyError, TypeError, CheckError) as exc:
            raise CheckError("Invalid structured check result in logs") from exc
    if len(results) != 1:
        raise CheckError("Expected exactly one structured check result in logs")
    return results[0]


def status_report(api, clock=now_utc):
    issue = api.issue()
    report = {"mode": "read-only", "issue_url": issue["html_url"],
              "scope": "Result as of the last check, not a live comparison",
              "latest_run": None, "last_successful_check": None, "stale": None}
    try:
        runs = api.runs()
    except CheckError as exc:
        report["detail_error"] = "Workflow unavailable: " + str(exc)
        return report
    if not runs:
        report["detail_error"] = "Workflow has never run"
        return report
    latest = runs[0]
    report["latest_run"] = {key: latest.get(key) for key in
                            ("id", "status", "conclusion", "html_url", "created_at", "updated_at")}
    try:
        successes = [latest] if latest.get("conclusion") == "success" else api.runs("success")
        if not successes:
            report["detail_error"] = "No successful check"
            return report
        success = successes[0]
        report["last_successful_run"] = {key: success.get(key) for key in
                                       ("id", "html_url", "created_at", "updated_at")}
        result = result_from_logs(api.run_logs(success["id"]), success["html_url"])
        report["last_successful_check"] = result
        age = clock() - parse_time(result["checked_at"])
        report["stale"] = age > timedelta(hours=30)
        if age < timedelta(minutes=-5):
            raise CheckError("Check timestamp is unexpectedly in the future")
    except (CheckError, KeyError, TypeError) as exc:
        report["last_successful_check"] = None
        report["stale"] = None
        report["detail_error"] = "Check log details unavailable: " + str(exc)
    return report


def emit_result(result):
    print(RESULT_PREFIX + json.dumps(result, ensure_ascii=True, sort_keys=True))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write("## 上游提交检查\n\n")
            stream.write("```json\n" + json.dumps(result, ensure_ascii=True, indent=2) + "\n```\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--status", action="store_true", help="Read the last remote check (default)")
    modes.add_argument("--notify", action="store_true", help="Dedicated Actions workflow only")
    args = parser.parse_args(argv)
    try:
        if not args.notify:
            print(json.dumps(status_report(GitHub(get_token())), ensure_ascii=True, indent=2))
            return 0
        expected_ref = f"{TARGET}/.github/workflows/{WORKFLOW}@refs/heads/{BRANCH}"
        if (os.environ.get("GITHUB_ACTIONS") != "true"
                or os.environ.get("GITHUB_REPOSITORY") != TARGET
                or os.environ.get("GITHUB_EVENT_NAME") not in ("schedule", "workflow_dispatch")
                or os.environ.get("GITHUB_WORKFLOW_REF") != expected_ref):
            raise CheckError("Notification writes require the dedicated default-branch Actions workflow")
        run_id = os.environ.get("GITHUB_RUN_ID", "")
        if not run_id.isdigit():
            raise CheckError("Invalid Actions run id")
        api = GitHub(get_token())
        run_url = f"https://github.com/{TARGET}/actions/runs/{run_id}"
        with GitSnapshot(temp_root=os.environ.get("RUNNER_TEMP")) as snapshot:
            result = check(api, snapshot, run_url=run_url)
        result.update(source=SOURCE, target=TARGET)
        emit_result(result)
        return 0
    except (CheckError, OSError, ValueError, KeyError, TypeError) as exc:
        result = {"schema": 1, "status": "error", "checked_at": now_utc().isoformat(),
                  "error": str(exc), "issue_url": issue_url()}
        if args.notify:
            emit_result(result)
        else:
            print(json.dumps(result, ensure_ascii=True))
        return 1


if __name__ == "__main__":
    sys.exit(main())
