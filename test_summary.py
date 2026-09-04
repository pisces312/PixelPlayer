#!/usr/bin/env python3
"""Print a compact summary of PixelPlayer JVM unit-test results.

Reads the JUnit XML that Gradle writes to
``app/build/test-results/testDebugUnitTest/*.xml`` and prints totals plus a
failure list, splitting failures into:

* **known baseline** failures that already exist on ``master`` (see
  ``BASELINE_FAILING_CLASSES``), and
* **new** failures that need attention.

That split is the whole point of this script: the branch has a handful of
pre-existing red tests, and without the split a new regression is impossible
to spot by eye.

Usage (from the repository root)::

    python test_summary.py          # summary + failure list
    python test_summary.py -v       # also list every test class
    python test_summary.py --dir X  # read a different results directory
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_RESULTS_DIR = os.path.join(
    "app", "build", "test-results", "testDebugUnitTest"
)

# A full run currently writes ~76 result files. Gradle replaces the whole
# directory on a filtered run, so a handful of classes means "subset ran",
# not "the suite shrank" - and the stale-baseline check must stay quiet.
MIN_CLASSES_FOR_STALE_CHECK = 20

# Test classes that were already failing on `master` before this branch.
# Keep in sync with reality: the script warns when an entry stops failing
# (fixed, renamed or deleted) so the baseline never silently rots.
BASELINE_FAILING_CLASSES = {
    "com.theveloper.pixelplay.data.backup.model.BackupSectionTest",
    "com.theveloper.pixelplay.data.service.player.LoadControlBufferProfileTest",
    "com.theveloper.pixelplay.presentation.screens."
    "LibraryScreenFolderNavigationAnimationTest",
    "com.theveloper.pixelplay.presentation.viewmodel.LyricsStateHolderTest",
    "com.theveloper.pixelplay.utils.AudioMetaUtilsTest",
    "com.theveloper.pixelplay.utils.LocalArtworkUriTest",
}

BULLET = "  - "


def _first_line(text: str | None) -> str:
    if not text:
        return ""
    for line in text.strip().splitlines():
        line = line.strip()
        if line:
            return line
    return ""


def collect(results_dir: str):
    """Return (total, failed, skipped, failures, classes)."""
    total = failed = skipped = 0
    failures: list[tuple[str, str, str]] = []
    classes: list[tuple[str, int, int]] = []

    pattern = os.path.join(results_dir, "*.xml")
    for path in sorted(glob.glob(pattern)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"WARNING: cannot parse {path}: {exc}", file=sys.stderr)
            continue

        num_tests = int(root.get("tests", 0))
        num_failed = int(root.get("failures", 0)) + int(root.get("errors", 0))
        num_skipped = int(root.get("skipped", 0))
        class_name = root.get("name") or os.path.basename(path)

        total += num_tests
        failed += num_failed
        skipped += num_skipped
        classes.append((class_name, num_tests, num_failed))

        for case in root.iter("testcase"):
            for kind in ("failure", "error"):
                for node in case.iter(kind):
                    failures.append(
                        (class_name, case.get("name", "?"), _first_line(node.get("message")))
                    )
                    break  # one entry per test case is enough

    return total, failed, skipped, failures, classes


def short(name: str) -> str:
    return name.rsplit(".", 1)[-1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-v", "--verbose", action="store_true", help="list every test class"
    )
    parser.add_argument(
        "--dir", default=DEFAULT_RESULTS_DIR, help="test-results directory"
    )
    args = parser.parse_args()

    # Do NOT force UTF-8 here: on a Chinese Windows console the active code
    # page is GBK, and emitting UTF-8 bytes there turns any non-ASCII test
    # message into mojibake. Use the console's own encoding and only make
    # unencodable characters survivable instead of fatal.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    if not os.path.isdir(args.dir):
        print(f"No results directory: {args.dir}")
        print("Run the tests first:  run-tests.bat")
        return 1

    total, failed, skipped, failures, classes = collect(args.dir)

    if total == 0:
        print(f"No test results found in {args.dir}")
        return 1

    passed = total - failed - skipped
    print(f"Classes : {len(classes)}")
    print(f"Total   : {total}")
    print(f"Passed  : {passed}")
    print(f"Failed  : {failed}")
    print(f"Skipped : {skipped}")

    baseline = [f for f in failures if f[0] in BASELINE_FAILING_CLASSES]
    new = [f for f in failures if f[0] not in BASELINE_FAILING_CLASSES]

    print()
    print(f"Known baseline failures (pre-existing on master): {len(baseline)}")
    for class_name, test, message in baseline:
        print(f"{BULLET}{short(class_name)}.{test}")
        if message:
            print(f"      {message[:180]}")

    print()
    print(f"NEW failures (need attention): {len(new)}")
    if new:
        for class_name, test, message in new:
            print(f"{BULLET}{short(class_name)}.{test}")
            if message:
                print(f"      {message[:180]}")
    else:
        print(f"{BULLET}(none)")

    # Guard against a stale baseline silently hiding information.
    # Only meaningful for a full run: Gradle replaces the results directory
    # on a filtered run, so the baseline classes are merely absent there,
    # which is not evidence that they got fixed.
    if len(classes) >= MIN_CLASSES_FOR_STALE_CHECK:
        still_failing = {f[0] for f in failures}
        stale = sorted(BASELINE_FAILING_CLASSES - still_failing)
        if stale:
            print()
            print("NOTE: these baseline entries no longer fail - update "
                  "BASELINE_FAILING_CLASSES in test_summary.py:")
            for class_name in stale:
                print(f"{BULLET}{class_name}")
    elif args.verbose:
        print()
        print(f"(stale-baseline check skipped: only {len(classes)} classes "
              f"in results, not a full run)")

    if args.verbose:
        print()
        print(f"All {len(classes)} test classes:")
        for class_name, num_tests, num_failed in classes:
            flag = "" if num_failed == 0 else f"  <-- {num_failed} failed"
            print(f"{BULLET}{class_name} ({num_tests} tests){flag}")

    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
