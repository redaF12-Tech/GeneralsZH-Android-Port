#!/usr/bin/env python3
"""GeneralsX @bugfix Android mysorez-merge-strings 06/10/2026

Union-merge helper for the PR #21 (MYSOREZ-Up-Steam) conflict in the launcher
string tables. For every <string> element it keeps BOTH branches' additions:
names only in ours, names only in theirs, and shared names (one copy; a value
difference is reported for a manual decision instead of being resolved
silently by whichever side git's line merge happened to keep).

Without --write it only reports. With --write it resolves the working-tree
file in place: our branch's full content, then their branch's exclusive
elements appended before </resources>.
"""
import re
import subprocess
import sys

OURS = "19b324d598a6c82084e9e34f649925165b02194e"
THEIRS = "81456be78fadcdc7a25cf1545e7cf136ee20bccb"

ELEMENT_RE = re.compile(r'^( +)<string name="([a-z0-9_]+)"(.*)$')


def git_show(rev: str, path: str) -> str:
    out = subprocess.run(
        ["git", "show", f"{rev}:{path}"], capture_output=True, text=True, check=True
    )
    return out.stdout


def collect(content: str):
    """Return {name: full element text} preserving each element verbatim."""
    items = {}
    lines = content.splitlines()
    i = 0
    while i < len(lines):
        m = ELEMENT_RE.match(lines[i])
        if not m:
            i += 1
            continue
        start = i
        name = m.group(2)
        # A <string> element spans until the line that closes the tag.
        while "</string>" not in lines[i] and i + 1 < len(lines):
            i += 1
        items[name] = "\n".join(lines[start : i + 1])
        i += 1
    return items


def main() -> int:
    argv = sys.argv[1:]
    write_mode = "--write" in argv
    paths = [a for a in argv if a != "--write"]
    had_diff = 0
    failures = 0
    for path in paths:
        ours_text = git_show(OURS, path)
        theirs_text = git_show(THEIRS, path)
        ours = collect(ours_text)
        theirs = collect(theirs_text)
        shared = set(ours) & set(theirs)
        only_ours = set(ours) - set(theirs)
        only_theirs = set(theirs) - set(ours)
        diff = [n for n in sorted(shared) if ours[n] != theirs[n]]
        print(f"== {path}: ours={len(ours)} theirs={len(theirs)} shared={len(shared)} "
              f"ours-only={len(only_ours)} theirs-only={len(only_theirs)} value-diffs={len(diff)}")
        for n in diff:
            had_diff += 1
            print(f"   DIFF {n}:")
            print(f"     OURS  : {ours[n]}")
            print(f"     THEIRS: {theirs[n]}")
        if not write_mode:
            continue
        if diff:
            print(f"   SKIP {path}: value differences need a manual decision first")
            failures += 1
            continue
        # Their exclusive elements, in their own file order, verbatim.
        theirs_only_ordered = [n for n in theirs if n in only_theirs]
        block = "\n".join(theirs[n] for n in theirs_only_ordered)
        closing = "</resources>"
        if not ours_text.rstrip().endswith(closing):
            print(f"   FAIL {path}: ours does not end with </resources>")
            failures += 1
            continue
        body = ours_text.rstrip()[: -len(closing)].rstrip("\n")
        resolved = body + "\n\n" + block + "\n" + closing + "\n"
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(resolved)
        print(f"   WROTE {path}: union={len(ours) + len(only_theirs)} names "
              f"(+{len(theirs_only_ordered)} from theirs)")
    return 1 if (had_diff or failures) else 0


if __name__ == "__main__":
    sys.exit(main())
