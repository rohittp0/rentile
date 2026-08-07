#!/usr/bin/env python3
"""Generate Rentile's credential-free, pinned compatibility manifest.

The input directory may contain credential-bearing style documents. This tool never
copies a style document or a resource URL into its output. The output is limited to
redacted content digests, layer identities, classifications, and aggregate counts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


PROFILE_ID = "rentile-v1"
MANIFEST_SCHEMA_VERSION = 1
CAPTURED_DATE = "2026-08-06"

SUPPORTED_NON_SYMBOL_TYPES = {
    "background",
    "fill",
    "line",
    "raster",
    "hillshade",
}
SENSITIVE_NAMES = {
    "access_token",
    "apikey",
    "api_key",
    "key",
    "mtsid",
    "session",
    "session_id",
    "token",
}
SECRET_PATTERNS = (
    re.compile(r"(?i)(?:access_token|api_?key|key|mtsid|session(?:_id)?|token)=([^&\s]+)"),
    re.compile(r"\bpk\.[A-Za-z0-9._-]{16,}\b"),
    re.compile(r"\bsk\.[A-Za-z0-9._-]{16,}\b"),
)

EXPECTED_COUNTS = {
    "declaredLayers": 4787,
    "declaredNonSymbolLayers": 3497,
    "visibleNonSymbolLayers": 3479,
    "declaredSymbolLayers": 1290,
    "declaredIconImageProperties": 681,
    "emptyIconImageDeclarations": 2,
    "meaningfulIconLayers": 679,
    "hiddenMeaningfulIconLayers": 13,
    "visibleMeaningfulIconLayers": 666,
    "visiblePureIconLayers": 61,
    "visibleMixedIconTextLayers": 605,
    "retainedIndependentIconLayers": 198,
    "retainedPureIconLayers": 61,
    "retainedMixedIconLayers": 137,
    "excludedTextCoupledIconLayers": 468,
    "excludedTextOnlyLayers": 611,
    "selectedLayerDefinitions": 3695,
    "visibleSelectedLayers": 3677,
    "flattenedExtrusionLayers": 6,
}


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()


def sha256_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def redact_url(value: str) -> str:
    try:
        parsed = urlsplit(value)
    except ValueError:
        return value
    if not parsed.scheme or not parsed.netloc:
        return value
    query = [
        (name, "<redacted>" if name.casefold() in SENSITIVE_NAMES else item_value)
        for name, item_value in parse_qsl(parsed.query, keep_blank_values=True)
    ]
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), parsed.fragment))


def redact(value: Any, parent_key: str | None = None) -> Any:
    if parent_key is not None and parent_key.casefold() in SENSITIVE_NAMES:
        return "<redacted>"
    if isinstance(value, dict):
        return {key: redact(item, key) for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, str):
        redacted = redact_url(value)
        for pattern in SECRET_PATTERNS:
            redacted = pattern.sub(lambda match: match.group(0).split("=", 1)[0] + "=<redacted>" if "=" in match.group(0) else "<redacted>", redacted)
        return redacted
    return value


def is_hidden(layer: dict[str, Any]) -> bool:
    return layer.get("layout", {}).get("visibility") == "none"


def is_empty_icon_image(value: Any) -> bool:
    return isinstance(value, str) and value == ""


def has_text_component(layout: dict[str, Any]) -> bool:
    if "text-field" not in layout:
        return False
    value = layout["text-field"]
    return not (isinstance(value, str) and value == "")


def classify_layer(layer: dict[str, Any]) -> tuple[str, str | None, bool]:
    layer_type = layer.get("type")
    hidden = is_hidden(layer)

    if layer_type in SUPPORTED_NON_SYMBOL_TYPES:
        if hidden:
            return "HIDDEN", "HIDDEN_LAYER_NO_DRAW", True
        return "RETAINED", None, True

    if layer_type == "fill-extrusion":
        if hidden:
            return "HIDDEN", "HIDDEN_LAYER_NO_DRAW", True
        return "TRANSFORMED", "EXTRUSION_FLATTENED", True

    if layer_type != "symbol":
        return "UNSUPPORTED", "UNSUPPORTED_LAYER_TYPE", False

    layout = layer.get("layout", {})
    icon_declared = "icon-image" in layout
    icon_meaningful = icon_declared and not is_empty_icon_image(layout.get("icon-image"))
    text_present = has_text_component(layout)

    if not icon_meaningful:
        diagnostic = "EMPTY_ICON_IMAGE_NO_DRAW" if icon_declared else "TEXT_ONLY_LAYER_EXCLUDED"
        return "EXCLUDED", diagnostic, False

    if hidden:
        return "HIDDEN", "HIDDEN_LAYER_NO_DRAW", False

    if not text_present:
        return "RETAINED", None, True

    text_optional = layout.get("text-optional") is True
    icon_text_fit = layout.get("icon-text-fit")
    fit_allows_independence = icon_text_fit is None or icon_text_fit == "none"
    if text_optional and fit_allows_independence:
        return "TRANSFORMED", "TEXT_COMPONENT_REMOVED_ICON_RETAINED", True

    return "EXCLUDED", "TEXT_COUPLED_ICON_LAYER_EXCLUDED", False


def build_manifest(style_paths: list[Path]) -> dict[str, Any]:
    layers: list[dict[str, Any]] = []
    style_records: list[dict[str, Any]] = []
    counts: Counter[str] = Counter()
    source_declarations: set[tuple[str, str]] = set()
    source_types: Counter[str] = Counter()

    for path in style_paths:
        document = json.loads(path.read_text(encoding="utf-8"))
        redacted_document = redact(document)
        style_ref = path.stem
        style_digest = sha256_hex(canonical_bytes(redacted_document))
        style_records.append(
            {
                "styleRef": style_ref,
                "sanitizedSha256": style_digest,
                "declaredLayerCount": len(document.get("layers", [])),
            }
        )

        sources = document.get("sources", {})
        for index, layer in enumerate(document.get("layers", [])):
            layer_type = layer.get("type", "<missing>")
            outcome, diagnostic, selected = classify_layer(layer)
            hidden = is_hidden(layer)
            layout = layer.get("layout", {})
            icon_declared = layer_type == "symbol" and "icon-image" in layout
            icon_meaningful = icon_declared and not is_empty_icon_image(layout.get("icon-image"))
            text_present = layer_type == "symbol" and has_text_component(layout)

            counts["declaredLayers"] += 1
            if layer_type == "symbol":
                counts["declaredSymbolLayers"] += 1
                if icon_declared:
                    counts["declaredIconImageProperties"] += 1
                if icon_declared and not icon_meaningful:
                    counts["emptyIconImageDeclarations"] += 1
                if icon_meaningful:
                    counts["meaningfulIconLayers"] += 1
                    if hidden:
                        counts["hiddenMeaningfulIconLayers"] += 1
                    else:
                        counts["visibleMeaningfulIconLayers"] += 1
                        if text_present:
                            counts["visibleMixedIconTextLayers"] += 1
                        else:
                            counts["visiblePureIconLayers"] += 1
                if outcome == "EXCLUDED" and diagnostic in {"TEXT_ONLY_LAYER_EXCLUDED", "EMPTY_ICON_IMAGE_NO_DRAW"}:
                    counts["excludedTextOnlyLayers"] += 1
                if diagnostic == "TEXT_COUPLED_ICON_LAYER_EXCLUDED":
                    counts["excludedTextCoupledIconLayers"] += 1
                if selected and not hidden:
                    counts["retainedIndependentIconLayers"] += 1
                    if text_present:
                        counts["retainedMixedIconLayers"] += 1
                    else:
                        counts["retainedPureIconLayers"] += 1
            else:
                counts["declaredNonSymbolLayers"] += 1
                if not hidden:
                    counts["visibleNonSymbolLayers"] += 1
                if layer_type == "fill-extrusion":
                    counts["flattenedExtrusionLayers"] += 1

            if selected:
                counts["selectedLayerDefinitions"] += 1
                if not hidden:
                    counts["visibleSelectedLayers"] += 1
                source_id = layer.get("source")
                if isinstance(source_id, str):
                    source_declarations.add((style_ref, source_id))

            record = {
                "styleRef": style_ref,
                "styleLayerIndex": index,
                "layerId": str(layer.get("id", "<missing>")),
                "layerType": str(layer_type),
                "outcome": outcome,
            }
            if diagnostic is not None:
                record["diagnostic"] = diagnostic
            layers.append(record)

        for source_ref in sorted(source_declarations):
            if source_ref[0] != style_ref:
                continue
            source = sources.get(source_ref[1])
            if isinstance(source, dict):
                source_types[str(source.get("type", "<missing>"))] += 1

    actual_counts = {key: counts[key] for key in EXPECTED_COUNTS}
    mismatches = {
        key: {"expected": expected, "actual": actual_counts[key]}
        for key, expected in EXPECTED_COUNTS.items()
        if actual_counts[key] != expected
    }
    if mismatches:
        raise ValueError(f"Pinned corpus counts drifted: {json.dumps(mismatches, sort_keys=True)}")

    styles_sorted = sorted(style_records, key=lambda item: item["styleRef"])
    layers_sorted = sorted(layers, key=lambda item: (item["styleRef"], item["styleLayerIndex"]))
    corpus_digest = sha256_hex(canonical_bytes(styles_sorted))
    return {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "profileId": PROFILE_ID,
        "capturedDate": CAPTURED_DATE,
        "corpus": {
            "styleCount": len(styles_sorted),
            "sanitizedSha256": corpus_digest,
            "styles": styles_sorted,
        },
        "counts": actual_counts,
        "selectedSourceDeclarations": {
            "total": len(source_declarations),
            "byType": dict(sorted(source_types.items())),
        },
        "layers": layers_sorted,
    }


def assert_credential_free(encoded: bytes) -> None:
    text = encoded.decode("utf-8")
    if "://" in text:
        raise ValueError("Manifest contains a URL")
    for pattern in SECRET_PATTERNS:
        if pattern.search(text):
            raise ValueError(f"Manifest contains credential-like material matching {pattern.pattern}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("styles", type=Path, help="Directory containing the 34 source style JSON files")
    parser.add_argument("output", type=Path, help="Credential-free manifest path")
    args = parser.parse_args()

    style_paths = sorted(args.styles.glob("*.json"), key=lambda item: item.name)
    if len(style_paths) != 34:
        raise SystemExit(f"Expected 34 style documents, found {len(style_paths)}")

    manifest = build_manifest(style_paths)
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True).encode() + b"\n"
    assert_credential_free(encoded)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)


if __name__ == "__main__":
    main()
