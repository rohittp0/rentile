#!/usr/bin/env python3
"""Validate the committed compatibility manifest without access to source styles."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

from generate_compatibility_manifest import (
    EXPECTED_COUNTS,
    MANIFEST_SCHEMA_VERSION,
    PROFILE_ID,
    assert_credential_free,
    canonical_bytes,
    sha256_hex,
)


EXPECTED_SOURCE_DECLARATIONS = {
    "total": 82,
    "byType": {
        "geojson": 3,
        "raster": 11,
        "raster-dem": 3,
        "vector": 65,
    },
}
EXPECTED_OUTCOMES = {
    "EXCLUDED": 1079,
    "HIDDEN": 31,
    "RETAINED": 3534,
    "TRANSFORMED": 143,
}
EXPECTED_DIAGNOSTICS = {
    "EMPTY_ICON_IMAGE_NO_DRAW": 2,
    "EXTRUSION_FLATTENED": 6,
    "HIDDEN_LAYER_NO_DRAW": 31,
    "TEXT_COMPONENT_REMOVED_ICON_RETAINED": 137,
    "TEXT_COUPLED_ICON_LAYER_EXCLUDED": 468,
    "TEXT_ONLY_LAYER_EXCLUDED": 609,
}
SHA256 = re.compile(r"[0-9a-f]{64}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()

    encoded = args.manifest.read_bytes()
    assert_credential_free(encoded)
    manifest = json.loads(encoded)

    require(manifest["schemaVersion"] == MANIFEST_SCHEMA_VERSION, "Unexpected schema version")
    require(manifest["profileId"] == PROFILE_ID, "Unexpected compatibility profile")
    require(manifest["counts"] == EXPECTED_COUNTS, "Pinned aggregate counts changed")
    require(
        manifest["selectedSourceDeclarations"] == EXPECTED_SOURCE_DECLARATIONS,
        "Pinned source declaration counts changed",
    )

    styles = manifest["corpus"]["styles"]
    layers = manifest["layers"]
    require(manifest["corpus"]["styleCount"] == 34 == len(styles), "Expected 34 styles")
    require(len(layers) == EXPECTED_COUNTS["declaredLayers"], "Layer manifest is incomplete")
    require(styles == sorted(styles, key=lambda item: item["styleRef"]), "Styles are not stable-sorted")
    require(
        layers == sorted(layers, key=lambda item: (item["styleRef"], item["styleLayerIndex"])),
        "Layers are not stable-sorted",
    )

    style_refs = [item["styleRef"] for item in styles]
    require(len(set(style_refs)) == len(style_refs), "Duplicate style identity")
    require(all(SHA256.fullmatch(item["sanitizedSha256"]) for item in styles), "Invalid style digest")
    require(
        manifest["corpus"]["sanitizedSha256"] == sha256_hex(canonical_bytes(styles)),
        "Corpus digest does not cover the style records",
    )

    layer_identity = [(item["styleRef"], item["styleLayerIndex"], item["layerId"]) for item in layers]
    require(len(set(layer_identity)) == len(layer_identity), "Duplicate layer identity")
    require(set(item["styleRef"] for item in layers) == set(style_refs), "Unknown or unused style identity")

    outcomes = Counter(item["outcome"] for item in layers)
    diagnostics = Counter(item["diagnostic"] for item in layers if "diagnostic" in item)
    require(dict(sorted(outcomes.items())) == EXPECTED_OUTCOMES, "Layer outcomes changed")
    require(dict(sorted(diagnostics.items())) == EXPECTED_DIAGNOSTICS, "Layer diagnostics changed")

    canonical_file = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True).encode() + b"\n"
    require(encoded == canonical_file, "Manifest JSON is not canonical")


if __name__ == "__main__":
    main()
