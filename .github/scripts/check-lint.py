#!/usr/bin/env python3
"""Fail when Android lint reported any issue, at any severity.

``lint { warningsAsErrors = true }`` already fails the Gradle build for
error/warning findings, but lint also has an ``information`` severity that does
not fail a build. CI wants a clean report, so this parses the XML reports and
fails on every issue, including informational ones.
"""

import glob
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    reports = sorted(glob.glob("app/build/reports/lint-results-*.xml"))
    if not reports:
        print(
            "error: no lint XML reports found under app/build/reports; "
            "run ./gradlew :app:lintDebug :app:lintRelease first",
            file=sys.stderr,
        )
        return 1

    issues = []
    for report in reports:
        root = ET.parse(report).getroot()
        for issue in root.findall("issue"):
            location = issue.find("location")
            where = ""
            if location is not None:
                path = location.get("file")
                line = location.get("line")
                if path:
                    where = f" ({path}{':' + line if line else ''})"
            issues.append(
                f"{issue.get('severity')}: {issue.get('id')}: "
                f"{issue.get('message')}{where}"
            )

    if issues:
        print(f"{len(issues)} lint issue(s) found:", file=sys.stderr)
        for issue in issues:
            print(f"  {issue}", file=sys.stderr)
        return 1

    print(f"No lint issues found in {len(reports)} report(s): {', '.join(reports)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
