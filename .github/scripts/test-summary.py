"""Write a Markdown summary of JUnit XML results to stdout."""

import glob
import sys
import xml.etree.ElementTree as ET


def main(directory):
    reports = sorted(glob.glob(f"{directory}/TEST-*.xml"))
    if not reports:
        print("### Test results\n\nNo test results were produced.")
        return

    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    duration = 0.0
    failed = []

    for path in reports:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
        duration += float(suite.get("time", 0))
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                message = (problem.get("message") or "").strip().splitlines()
                failed.append((f"{case.get('classname')}.{case.get('name')}",
                               message[0][:200] if message else ""))

    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    print("### Test results\n")
    print("| Tests | Passed | Failed | Errors | Skipped | Duration |")
    print("|------:|-------:|-------:|-------:|--------:|---------:|")
    print(f"| {totals['tests']} | {passed} | {totals['failures']} | "
          f"{totals['errors']} | {totals['skipped']} | {duration:.1f} s |")

    if failed:
        print("\n#### Failures\n")
        for name, message in failed:
            print(f"- `{name}`" + (f": {message}" if message else ""))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/test-results/test")
