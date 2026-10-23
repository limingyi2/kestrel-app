"""Display branding handles attached language particles without touching contracts."""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/kestrel_brand.py"
SPEC = importlib.util.spec_from_file_location("kestrel_brand", SCRIPT)
assert SPEC and SPEC.loader
BRAND = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BRAND)


@pytest.mark.parametrize(
    ("source", "expected"),
    [
        ("nanoMuse is working", "Kestrel is working"),
        ("nanoMuse가 현재 도구를 표시", "Kestrel가 현재 도구를 표시"),
        ("让nanoMuse帮忙", "让Kestrel帮忙"),
        ("nanoMuses eigene Server", "Kestrels eigene Server"),
        ("nanoMuse Cloud의", "nanoMuse Cloud의"),
        ("nanoMuse Desktop", "nanoMuse Desktop"),
        ("nanoMuseSkills /var/minis X-Minis-Token", "nanoMuseSkills /var/minis X-Minis-Token"),
        ("io.github.nanomuse.app", "io.github.nanomuse.app"),
    ],
)
def test_display_brand_language_boundaries(source: str, expected: str) -> None:
    assert BRAND.PRODUCT.sub("Kestrel", source) == expected
