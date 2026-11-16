#!/usr/bin/env python3
"""Turns the interesting lines of a failed Gradle log into GitHub error annotations,
so the cause of a failed build is visible on the run page without opening the full log."""
import re
import sys

lines = open(sys.argv[1], errors="replace").read().splitlines()
keep = []
for i, line in enumerate(lines):
    if re.search(r"^e: |^w: .*error|error:|What went wrong|Could not |FAILED$|FAIL  |Unresolved|Exception", line):
        keep.extend(lines[i:i + (14 if "What went wrong" in line else 2)])
seen, out = set(), []
for line in keep:
    if line.strip() and line not in seen:
        seen.add(line)
        out.append(line)
if not out:
    out = lines[-40:]
text = "\n".join(out)[:24000]
for n in range(0, len(text), 3000):
    chunk = text[n:n + 3000].replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::error title=Build log {n // 3000 + 1}::{chunk}")
