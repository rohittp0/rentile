#!/usr/bin/env python3
"""Keep a non-main manual backport on its declared immutable Maven coordinate."""
import argparse
import re


def check_dispatch_version(event_name: str, ref: str, declared: str, resolved: str) -> bool:
    if not event_name or not ref:
        return False
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?", declared):
        return False
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", resolved):
        return False
    return event_name != "workflow_dispatch" or ref == "refs/heads/main" or declared == resolved


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--declared", required=True)
    parser.add_argument("--resolved", required=True)
    args = parser.parse_args()
    if not check_dispatch_version(args.event, args.ref, args.declared, args.resolved):
        print("::error::Non-main manual release must resolve exactly VERSION_NAME; refusing publication (or release inputs are invalid).")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
