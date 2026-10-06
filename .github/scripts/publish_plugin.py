"""Publish an already verified DEX plugin while preserving repository history.

The default prepares a local commit. Only --push changes the remote.
Failed/unfinished candidates remain in their own worktree for inspection.
"""

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile


PACKAGE = "org.skepsun.kototoro.parsers"


def plugin_entry(index):
    if not isinstance(index, list) or not all(isinstance(entry, dict) for entry in index):
        raise ValueError("Expected an index array of plugin objects")
    matches = [entry for entry in index if entry.get("pkg") == PACKAGE]
    if len(matches) != 1 or matches[0].get("apk") != "plugin.jar":
        raise ValueError("Expected exactly one existing parser entry using plugin.jar")
    code = matches[0].get("code")
    if type(code) is not int or not 0 < code <= 2147483647:
        raise ValueError("Remote plugin code must be a positive 32-bit integer")
    return matches[0]


def next_index(index):
    result = copy.deepcopy(index)
    entry = plugin_entry(result)
    if entry["code"] == 2147483647:
        raise ValueError("Plugin version code exhausted")
    entry["code"] += 1
    entry["version"] = f"1.0.{entry['code']}"
    return result


def git(root, *args):
    completed = subprocess.run(
        ["git", "-C", str(root), *args], check=True, capture_output=True, encoding="utf-8",
    )
    return completed.stdout.strip()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def artifact_bytes(path, expected):
    data = Path(path).read_bytes()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected) or sha256(data) != expected.lower():
        raise ValueError("Artifact SHA-256 differs from the verified build")
    # Check the same bytes that will be published, not a file that could change after checking.
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.testzip() is not None or not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError("Expected an intact plugin JAR containing classes.dex")
    return data


def prepare(root, artifact, expected, source_ref="HEAD", remote="origin"):
    root = Path(root).resolve()
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", remote):
        raise ValueError("Invalid remote name")
    data = artifact_bytes(artifact, expected)
    source = git(root, "rev-parse", "--verify", f"{source_ref}^{{commit}}")
    if source != git(root, "rev-parse", "HEAD") or git(root, "status", "--porcelain"):
        raise ValueError("Publish from the clean checkout of the verified source commit")
    git(root, "remote", "get-url", remote)
    remote_ref = f"refs/remotes/{remote}/repo"
    git(root, "fetch", "--no-tags", remote, f"refs/heads/repo:{remote_ref}")
    base = git(root, "rev-parse", remote_ref)
    index = json.loads(git(root, "show", f"{base}:index.min.json"))
    current = plugin_entry(index)
    previous = subprocess.run(
        ["git", "-C", str(root), "show", f"{base}:apk/plugin.jar"],
        check=True, capture_output=True,
    ).stdout
    result = {"root": str(root), "remote": remote, "parent": base, "source": source,
              "sha256": sha256(data), "code": current["code"], "version": current.get("version")}
    if previous == data:
        return dict(result, status="unchanged", commit=base)

    updated = next_index(index)
    entry = plugin_entry(updated)
    work_root = root / "build" / "plugin-releases"
    work_root.mkdir(parents=True, exist_ok=True)
    worktree = Path(tempfile.mkdtemp(prefix="candidate-", dir=work_root))
    git(root, "worktree", "add", "--detach", str(worktree), base)
    (worktree / "index.min.json").write_text(
        json.dumps(updated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n",
    )
    (worktree / "apk" / "plugin.jar").write_bytes(data)
    (worktree / "release.json").write_text(json.dumps({
        "source_commit": source, "sha256": sha256(data), "code": entry["code"], "version": entry["version"],
    }, indent=2) + "\n", encoding="utf-8", newline="\n")
    git(worktree, "add", "--", "index.min.json", "apk/plugin.jar", "release.json")
    git(worktree, "diff", "--cached", "--check")
    git(worktree, "commit", "-m", f"build(repo): 发布插件 {entry['version']}",
        "-m", f"源码提交 {source}\n产物 SHA-256: {sha256(data)}\n保留 repo 历史，版本由远端索引递增。")
    return dict(result, status="prepared", worktree=str(worktree),
                commit=git(worktree, "rev-parse", "HEAD"), code=entry["code"], version=entry["version"])


def push_prepared(result):
    if result["status"] == "unchanged":
        return result
    # Normal push deliberately rejects a racing publisher. Never force or silently rebase a release.
    git(result["root"], "push", result["remote"], f"{result['commit']}:refs/heads/repo")
    actual = git(result["root"], "ls-remote", result["remote"], "refs/heads/repo").split()[0]
    if actual != result["commit"]:
        raise RuntimeError("Push succeeded but repo advanced again; check the remote before continuing")
    return dict(result, status="published")


def verify_download(base_url, result):
    base_url = base_url.rstrip("/") + "/"
    with urllib.request.urlopen(base_url + "index.min.json", timeout=60) as response:
        index = json.load(response)
    entry = plugin_entry(index)
    if entry["code"] != result["code"] or entry.get("version") != result["version"]:
        raise ValueError("Remote HTTP index does not yet match the release; publication is not rolled back")
    with urllib.request.urlopen(base_url + "apk/plugin.jar", timeout=60) as response:
        downloaded = response.read()
    if sha256(downloaded) != result["sha256"]:
        raise ValueError("Downloaded plugin differs from the release; publication is not rolled back")
    return dict(result, http_verified=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--source-ref", default="HEAD")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--push", action="store_true")
    parser.add_argument("--verify-base-url", help="Verify the existing HTTP repository URL after --push")
    args = parser.parse_args()
    if args.verify_base_url and not args.push:
        parser.error("--verify-base-url requires --push")
    result = prepare(args.root, args.artifact, args.expected_sha256, args.source_ref, args.remote)
    print(json.dumps(result, ensure_ascii=False), flush=True)
    if args.push:
        result = push_prepared(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if args.verify_base_url:
            result = verify_download(args.verify_base_url, result)
            print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, RuntimeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Publication stopped: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr, file=sys.stderr)
        sys.exit(1)
