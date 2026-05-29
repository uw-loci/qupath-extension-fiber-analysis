"""
Sidecar writers: windows.json, results.json, window_metrics.npz.

windows.json schema (consumed by the Java side and by pandas users):

    {
      "meta": {
        "versions":   {"fiberlib": ..., "extension": ..., ...},
        "run":        {"timestamp_utc": ..., "run_id": ...},
        "parameters": {... full input echo ...}
      },
      "window_px": int,
      "stride_px": int,
      "window_um": float,
      "grid_shape": [Hw, Ww],
      "windows": [
        {"x": int, "y": int, "w": int, "h": int,
         "n_fiber_px": int,                  # was `n_pixels` in v0.1.0
         "n_fibers": int | null,             # contributing-segment count (NEW)
         "hdm": float | null,                # per-window high-density fraction
         "mean_angle_deg": float | null,
         "order_parameter": float | null,
         "tortuosity_median": float | null,  # was `tortuosity` in v0.1.0
         "texture": {                        # optional, per GLCM property
            "contrast": float, ...
         }
        },
        ...
      ]
    }

Phase-5 schema reconcile (Scientist-tester M2): keys renamed to match the
user-guide section 9 schema so a pandas user `pd.json_normalize` finds the
columns the docs promise. The Java side (`FiberAnalysisWorkflow`) accepts
both old (`n_pixels`, `tortuosity`) and new (`n_fiber_px`,
`tortuosity_median`) keys for back-compat with v0.1.0 sidecars.
"""
import json
import math

import numpy as np


def _scalar(x):
    """Convert numpy / NaN-ish values to JSON-safe primitives."""
    if x is None:
        return None
    if isinstance(x, np.ndarray):
        return None
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (np.floating,)):
        f = float(x)
        return None if (math.isnan(f) or math.isinf(f)) else f
    if isinstance(x, float):
        return None if (math.isnan(x) or math.isinf(x)) else x
    return x


def save_windows_json(window_grid, path, meta=None, zone_area_px=None, pixel_size_um=None):
    """Serialise a window_grid dict (from windows.compute_windows + family
    augmentations) to windows.json.

    Args:
        window_grid:    dict produced by windows.compute_windows + per-family
                        augmentations (tortuosity, texture).
        path:           output JSON path.
        meta:           optional provenance dict (versions / run / parameters)
                        written to the top-level "meta" key. See PI-tester M2.
        zone_area_px:   optional dilated-zone area (px) used as denominator for
                        the per-window HDM (n_fiber_px / window_area_px).
        pixel_size_um:  optional pixel size; reserved for future
                        physical-unit conversions in the windows array.
    """
    # `pixel_size_um` is accepted to keep the contract stable for future
    # extensions; not used today.
    del pixel_size_um  # silence linters

    grid_shape = window_grid["grid_shape"]
    window_px = int(window_grid["window_px"])
    stride_px = int(window_grid["stride_px"])
    mean_angle = window_grid.get("mean_angle_deg")
    order = window_grid.get("order_parameter")
    n_pix = window_grid.get("n_pixels")
    tort = window_grid.get("tortuosity")
    n_fibers_grid = window_grid.get("n_fibers")
    tex = window_grid.get("texture", {}) if isinstance(window_grid.get("texture"), dict) else {}
    # Per-window morphometric arrays. Flattened directly into each window
    # entry so `pd.json_normalize` finds them as flat columns next to the
    # existing fields (n_fiber_px, tortuosity_median, etc.).
    morph_pw = (
        window_grid.get("morphometrics", {})
        if isinstance(window_grid.get("morphometrics"), dict)
        else {}
    )

    # Per-window HDM denominator. If the caller did not supply the zone area
    # (legacy path), fall back to the window's own pixel area -- in that
    # degenerate case `hdm` becomes "fraction of the window that is fiber".
    # Either way the field is present in every record (docs section 9 promise).
    window_area_px = window_px * window_px

    invalid_mask = window_grid.get("_invalid_below_coverage")

    windows = []
    Hw, Ww = grid_shape
    for iy in range(Hw):
        y0 = iy * stride_px
        for ix in range(Ww):
            x0 = ix * stride_px

            # Coverage gate: when the parent script marked this cell below
            # the user's min_window_coverage_percent, write a stub entry
            # with included=false and no metrics. Downstream consumers
            # (Java createWindowDetections, pandas filters) drop these.
            below_threshold = (
                invalid_mask is not None
                and 0 <= iy < invalid_mask.shape[0]
                and 0 <= ix < invalid_mask.shape[1]
                and bool(invalid_mask[iy, ix])
            )
            if below_threshold:
                windows.append({
                    "x": int(x0), "y": int(y0),
                    "w": int(window_px), "h": int(window_px),
                    "included": False,
                    "n_fiber_px": _scalar(n_pix[iy, ix]) if n_pix is not None else 0,
                    "fiber_coverage_percent": (
                        100.0 * float(_scalar(n_pix[iy, ix])) / float(window_area_px)
                        if (n_pix is not None and window_area_px > 0) else None
                    ),
                })
                continue

            # Per-window pixel count (was `n_pixels`; renamed to `n_fiber_px`).
            n_fiber_v = _scalar(n_pix[iy, ix]) if n_pix is not None else 0

            # Per-window HDM (NEW field per docs). Denominator: prefer
            # zone-area-of-this-cell when supplied, else window pixel area.
            denom = zone_area_px if zone_area_px and zone_area_px > 0 else window_area_px
            hdm_v = None
            if n_fiber_v is not None and denom and denom > 0:
                try:
                    hdm_v = float(n_fiber_v) / float(window_area_px)
                except Exception:
                    hdm_v = None

            # User-comparable percentage: fraction of THIS window that is
            # fiber, on a 0-100 scale. hdm above is the same fraction scaled
            # 0-1 (and named for the fiber-literature term); we expose the
            # percentage separately because that's what scientists actually
            # plot in density-map workflows.
            coverage_pct = None
            if n_fiber_v is not None and window_area_px > 0:
                try:
                    coverage_pct = 100.0 * float(n_fiber_v) / float(window_area_px)
                except Exception:
                    coverage_pct = None

            entry = {
                "x": int(x0), "y": int(y0),
                "w": int(window_px), "h": int(window_px),
                "included": True,
                "n_fiber_px": n_fiber_v,
                "n_fibers": _scalar(n_fibers_grid[iy, ix]) if n_fibers_grid is not None else None,
                "hdm": hdm_v,
                "fiber_coverage_percent": coverage_pct,
                "mean_angle_deg": _scalar(mean_angle[iy, ix]) if mean_angle is not None else None,
                "order_parameter": _scalar(order[iy, ix]) if order is not None else None,
            }
            if tort is not None:
                entry["tortuosity_median"] = _scalar(tort[iy, ix])
            if tex:
                entry["texture"] = {k: _scalar(v[iy, ix]) for k, v in tex.items() if v is not None}
            for mk, mv in morph_pw.items():
                if isinstance(mv, np.ndarray) and mv.shape == (Hw, Ww):
                    entry[mk] = _scalar(mv[iy, ix])
            windows.append(entry)

    payload = {
        "window_px": window_px,
        "stride_px": stride_px,
        "window_um": _scalar(window_grid.get("window_um")),
        "grid_shape": [int(Hw), int(Ww)],
        "windows": windows,
    }
    if meta is not None:
        # `meta` is the top-level provenance dict, written *first* so a casual
        # `head` of the file shows versions/run before the grid geometry.
        payload = {"meta": _clean_for_json(meta), **payload}

    with open(path, "w", encoding="ascii") as fh:
        json.dump(payload, fh)


def save_results_json(result_dict, path):
    """Write the per-annotation result dict as JSON. Strips np.ndarray entries."""
    with open(path, "w", encoding="ascii") as fh:
        json.dump(_clean_for_json(result_dict), fh)


def _clean_for_json(obj):
    """Recursively replace np.ndarray with None and coerce numpy scalars."""
    if isinstance(obj, dict):
        return {k: _clean_for_json(v) for k, v in obj.items() if not isinstance(v, np.ndarray)}
    if isinstance(obj, list):
        return [_clean_for_json(v) for v in obj]
    if isinstance(obj, tuple):
        return [_clean_for_json(v) for v in obj]
    if isinstance(obj, np.ndarray):
        return None
    return _scalar(obj)


def save_window_metrics_npz(arrays, path):
    """Write a dict of {name: np.ndarray} as a compressed .npz."""
    np.savez_compressed(path, **arrays)
