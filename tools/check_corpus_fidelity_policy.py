#!/usr/bin/env python3
"""Validate the omission and capability policy in Rentile's Coverage Manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


ALLOWED_DISPOSITIONS = {
    "evaluated",
    "label-candidate",
    "rasterized",
    "resource-acquired",
    "sprite-decoded",
}
REQUIRED_OMISSION_CAPABILITIES = {
    "expression-greater-than",
    "expression-not-equal",
    "expression-slice",
    "expression-to-string",
    "line-round-limit",
    "symbol-functional-placement",
    "symbol-functional-text-anchor",
    "symbol-functional-text-padding",
    "symbol-functional-text-transform",
    "symbol-icon-text-fit",
    "symbol-line-placement",
    "symbol-text",
    "symbol-text-icon",
}
REQUIRED_PREPARATION_REJECTIONS = {
    "LINE_PLACEMENT_LABEL_EXCLUDED",
    "TEXT_COUPLED_ICON_LAYER_EXCLUDED",
    "UNSUPPORTED_TEXT_CONSTRUCT",
}
REQUIRED_LABEL_REJECTIONS = {
    "LINE_PLACEMENT_LABEL_EXCLUDED",
    "UNSUPPORTED_TEXT_CONSTRUCT",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def require_sorted_strings(value: Any, field: str) -> list[str]:
    require(isinstance(value, list), f"{field} must be an array")
    require(all(isinstance(item, str) and item for item in value), f"{field} must contain non-empty strings")
    require(value == sorted(set(value)), f"{field} must be unique and sorted")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    capabilities = require_sorted_strings(manifest.get("requiredCapabilities"), "requiredCapabilities")
    require(
        REQUIRED_OMISSION_CAPABILITIES <= set(capabilities),
        "Coverage Manifest is missing a current-corpus omission capability",
    )

    dispositions = manifest.get("capabilityDispositions")
    require(isinstance(dispositions, dict), "capabilityDispositions must be an object")
    require(set(dispositions) == set(capabilities), "Every required capability must have exactly one disposition")
    require(
        all(isinstance(value, str) and value in ALLOWED_DISPOSITIONS for value in dispositions.values()),
        "capabilityDispositions contains an unsupported disposition",
    )

    policy = manifest.get("fidelityPolicy")
    require(isinstance(policy, dict), "fidelityPolicy must be an object")
    require(
        set(policy)
        == {
            "forbiddenLabelDiagnosticCodes",
            "forbiddenPreparationDiagnosticCodes",
            "requireAllVisibleTextVectorSymbolsHaveDescriptors",
            "requireEveryDescriptorToContribute",
        },
        "fidelityPolicy has an invalid shape",
    )
    forbidden_preparation = set(
        require_sorted_strings(
            policy.get("forbiddenPreparationDiagnosticCodes"),
            "fidelityPolicy.forbiddenPreparationDiagnosticCodes",
        )
    )
    forbidden_label = set(
        require_sorted_strings(
            policy.get("forbiddenLabelDiagnosticCodes"),
            "fidelityPolicy.forbiddenLabelDiagnosticCodes",
        )
    )
    require(
        REQUIRED_PREPARATION_REJECTIONS <= forbidden_preparation,
        "fidelityPolicy must reject known layer-level preparation omissions",
    )
    require(
        REQUIRED_LABEL_REJECTIONS <= forbidden_label,
        "fidelityPolicy must reject known layer-level label omissions",
    )
    require(
        "TEXT_ONLY_LAYER_EXCLUDED" not in forbidden_preparation,
        "TEXT_ONLY_LAYER_EXCLUDED remains valid for the PNG path when the layer is emitted as labels",
    )
    require(
        policy.get("requireAllVisibleTextVectorSymbolsHaveDescriptors") is True,
        "Every visible text-bearing vector symbol layer must require a LabelLayerDescriptor",
    )
    require(
        isinstance(policy.get("requireEveryDescriptorToContribute"), bool),
        "requireEveryDescriptorToContribute must be a boolean",
    )


if __name__ == "__main__":
    main()
