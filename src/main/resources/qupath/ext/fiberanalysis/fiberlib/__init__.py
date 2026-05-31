"""
fiberlib -- self-contained fiber-analysis package for the QuPath
qupath-extension-fiber-analysis testbed.

Operates on a binary fiber mask (either supplied or produced by the
internal segmenter) and produces per-window straightness, TWOMBLI-derived
morphometric, and GLCM texture metrics on the dilated zone around a
user-drawn annotation.

Public modules:
    segmentation   : internal segmenter + existing-mask reader
    dilation       : border-zone dilation around a boundary mask
    windows        : moving-window grid + axial circular statistics
    straightness   : skeleton tortuosity + Radon scalar
    morphometrics  : branch / endpoint / length / curvature / HDM /
                     lacunarity / fractal dimension / gap analysis
    texture        : GLCM / Haralick features per window
    render         : matplotlib RGBA PNG renderers
    io             : windows.json / results.json / .npz writers

    pipeline       : end-to-end orchestration as a callable library
                     (`fiberlib.analyze(image, ...)`)

Citations carried inline in each module docstring. See the shipped
documentation for the full references table.

Library entry point::

    import fiberlib
    out = fiberlib.analyze(image, pixel_size_um=0.5, ...)
    metrics = out["result"]          # JSON-friendly summary dict

The QuPath/Appose path (`scripts/run_fiber_analysis.py`) is a thin wrapper over
this same `analyze` function.
"""

from ._version import __version__  # noqa: F401
from .pipeline import analyze, sanitize_json  # noqa: F401

__all__ = ["__version__", "analyze", "sanitize_json"]
