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

Citations carried inline in each module docstring. See the shipped
documentation for the full references table.
"""
from ._version import __version__  # noqa: F401
