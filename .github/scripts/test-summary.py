#!/usr/bin/env python3
"""Print a Markdown summary of Gradle's JUnit XML results.

Usage: test-summary.py <directory with TEST-*.xml>
Written to $GITHUB_STEP_SUMMARY so the totals (and any failing test) show on
the workflow run page without opening the logs. Standard library only.
"""
import glob
import sys
import xml.etree.ElementTree as ET

directory = sys.argv[1] if len(sys.argv) > 1 else "build/test-results/test"
files = sorted(glob.glob(f"{directory}/TEST-*.xml"))

if not files:
    print("## Test results\n\nNo test result files were produced "
          "(the build failed before the tests ran).")
    sys.exit(0)

tests = failures = errors = skipped = 0
seconds = 0.0
problems = []

for path in files:
    suite = ET.parse(path).getroot()
    tests += int(suite.get("tests", 0))
    failures += int(suite.get("failures", 0))
    errors += int(suite.get("errors", 0))
    skipped += int(suite.get("skipped", 0))
    seconds += float(suite.get("time", 0))
    for case in suite.iter("testcase"):
        bad = case.find("failure")
        if bad is None:
            bad = case.find("error")
        if bad is not None:
            message = (bad.get("message") or "").strip().splitlines()
            problems.append((case.get("classname", "?"), case.get("name", "?"),
                             (message[0][:200] if message else "")))

passed = tests - failures - errors - skipped
icon = "✅" if failures + errors == 0 else "❌"
print(f"## {icon} Test results\n")
print("| Tests | Passed | Failed | Errors | Skipped | Time |")
print("|---|---|---|---|---|---|")
print(f"| {tests} | {passed} | {failures} | {errors} | {skipped} | {seconds:.1f}s |")

if problems:
    print("\n### Failing tests\n")
    for classname, name, message in problems:
        print(f"- `{classname}.{name}` {message}".rstrip())
