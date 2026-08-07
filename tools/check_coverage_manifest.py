#!/usr/bin/env python3
"""Validate Rentile's credential-free rendering Coverage Manifest."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


PROFILE_ID = "rentile-v1"
SCHEMA_VERSION = 1
MINIMUM_ZOOM = 0
MAXIMUM_ZOOM = 22
OVERZOOM_ZOOMS = {16, 18, 20, 22}
STYLE_REF = re.compile(r"[A-Za-z0-9._-]+")
SECRET_PATTERNS = (
    re.compile(r"(?i)(?:access_token|api_?key|key|mtsid|session(?:_id)?|token)="),
    re.compile(r"\b(?:pk|sk)\.[A-Za-z0-9._-]{16,}\b"),
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_tile(tile: Any, case_id: str) -> tuple[int, int, int]:
    require(isinstance(tile, dict), f"Coverage case {case_id} has a non-object tile")
    require(set(tile) == {"z", "x", "y"}, f"Coverage case {case_id} has an invalid tile shape")
    z, x, y = tile["z"], tile["x"], tile["y"]
    require(all(isinstance(value, int) and not isinstance(value, bool) for value in (z, x, y)), f"Coverage case {case_id} has a non-integer tile")
    require(MINIMUM_ZOOM <= z <= MAXIMUM_ZOOM, f"Coverage case {case_id} has an unsupported zoom")
    dimension = 1 << z
    require(0 <= x < dimension and 0 <= y < dimension, f"Coverage case {case_id} has an invalid XYZ tile")
    return z, x, y


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()

    encoded = args.manifest.read_bytes()
    text = encoded.decode("utf-8")
    require("://" not in text, "Coverage Manifest contains a URL")
    require(not any(pattern.search(text) for pattern in SECRET_PATTERNS), "Coverage Manifest contains credential-like material")
    manifest = json.loads(text)

    require(manifest.get("schemaVersion") == SCHEMA_VERSION, "Unexpected Coverage Manifest schema version")
    require(manifest.get("profileId") == PROFILE_ID, "Unexpected compatibility profile")
    require(
        manifest.get("outputZoomRange") == {"minimum": MINIMUM_ZOOM, "maximum": MAXIMUM_ZOOM},
        "RentileV1 must cover z0 through z22",
    )

    style_refs = manifest.get("styleRefs")
    require(isinstance(style_refs, list) and style_refs, "Coverage Manifest has no style references")
    require(all(isinstance(item, str) and STYLE_REF.fullmatch(item) for item in style_refs), "Invalid style reference")
    require(style_refs == sorted(style_refs, key=lambda item: (int(item) if item.isdigit() else 2**31, item)), "Style references are not stable-sorted")
    require(len(style_refs) == len(set(style_refs)), "Duplicate style reference")

    capabilities = manifest.get("requiredCapabilities")
    require(isinstance(capabilities, list) and capabilities, "Coverage Manifest has no capability requirements")
    require(capabilities == sorted(set(capabilities)), "Capability requirements must be unique and sorted")

    cases = manifest.get("cases")
    require(isinstance(cases, list) and cases, "Coverage Manifest has no cases")
    case_ids: set[str] = set()
    covered_zooms: set[int] = set()
    overzoom_zooms: set[int] = set()
    seam_zooms: set[int] = set()
    for case in cases:
        require(isinstance(case, dict), "Coverage case must be an object")
        require(set(case) == {"id", "tags", "tiles"}, "Coverage case has unknown fields")
        case_id = case["id"]
        require(isinstance(case_id, str) and STYLE_REF.fullmatch(case_id), "Coverage case id is invalid")
        require(case_id not in case_ids, "Duplicate Coverage case id")
        case_ids.add(case_id)
        tags = case["tags"]
        require(isinstance(tags, list) and tags == sorted(set(tags)), f"Coverage case {case_id} tags must be unique and sorted")
        tiles = [validate_tile(tile, case_id) for tile in case["tiles"]]
        require(tiles and len(tiles) == len(set(tiles)), f"Coverage case {case_id} has no tiles or duplicate tiles")
        covered_zooms.update(tile[0] for tile in tiles)
        if "vector-overzoom" in tags:
            overzoom_zooms.update(tile[0] for tile in tiles)
        if "mosaic-3x3" in tags:
            require(len(tiles) == 9, f"Coverage case {case_id} is not a 3x3 mosaic")
            zooms = {tile[0] for tile in tiles}
            xs = {tile[1] for tile in tiles}
            ys = {tile[2] for tile in tiles}
            require(len(zooms) == 1 and len(xs) == 3 and len(ys) == 3, f"Coverage case {case_id} is not a rectangular 3x3 mosaic")
            require(max(xs) - min(xs) == 2 and max(ys) - min(ys) == 2, f"Coverage case {case_id} is not contiguous")
            require(set(tiles) == {(next(iter(zooms)), x, y) for x in xs for y in ys}, f"Coverage case {case_id} is incomplete")
            seam_zooms.update(zooms)

    require(covered_zooms == set(range(MINIMUM_ZOOM, MAXIMUM_ZOOM + 1)), "Every integer zoom from z0 through z22 must be covered")
    require((0, 0, 0) in {validate_tile(tile, "whole-mercator-z0") for case in cases for tile in case["tiles"]}, "z0/0/0 is missing")
    require(OVERZOOM_ZOOMS <= overzoom_zooms, "Required vector-overzoom zooms are missing")
    require({6, 12, 16, 22} <= seam_zooms, "Required seam mosaics are missing")

    canonical = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True).encode() + b"\n"
    require(encoded == canonical, "Coverage Manifest JSON is not canonical")


if __name__ == "__main__":
    main()
