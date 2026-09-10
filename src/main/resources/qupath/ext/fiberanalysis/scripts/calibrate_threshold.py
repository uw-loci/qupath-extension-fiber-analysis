"""
Appose task: compute a project-wide threshold by streaming N region PNGs and
accumulating a single 256-bin intensity histogram, then running Otsu on the
accumulated histogram.

Why this exists
---------------
Running Otsu *per region* gives every annotation its own threshold, so sparse
regions threshold background noise as "fiber" and dense regions clip the
dimmer ends of real fibers. Coverage / ridge count / HDM become not
comparable across the project. Running Otsu *once* on a histogram pooled
across many regions gives a single threshold that's adaptive to the project
as a whole.

Inputs (injected by Appose as Python variables)
-----------------------------------------------
  region_paths        : list[str]  -- absolute paths to region PNGs to sample
  seg_channel         : str        -- 'Raw intensity' | 'Value (HSV)' | ...
  ridge_filter        : str        -- 'none' | 'frangi' | 'sato' | 'meijering'
  sigma_min, sigma_max, sigma_step : float (used when ridge_filter != 'none')
  invert_intensity    : bool       -- True for DAB / dark-fiber-on-bright-bg
  rolling_ball_radius : int        -- background-subtract radius in px (0=off)

Outputs
-------
  task.outputs['result_json'] -- JSON string:
    {
      "threshold_normalised": float,  # in [0,1] -- this is what the run uses
      "n_regions_used": int,
      "n_pixels_total": int,
      "histogram_bins": 256,
      "histogram_counts": [int, ...],  # length 256, JSON-friendly
      "error": "..."  (only when something failed)
    }
"""
import json
import logging
import os
import sys
import traceback

import numpy as np
from PIL import Image

logger = logging.getLogger("fiber.appose.calibrate")


def _opt(name, default):
    return globals().get(name, default)


try:
    # Imports from the bundled fiberlib package -- same path the run script uses.
    import fiberlib  # noqa: F401  (used for version stamp in callers)
    from fiberlib import segmentation as seg

    paths = list(region_paths)
    chan = str(seg_channel)
    rf = str(ridge_filter).lower()
    smin = float(_opt("sigma_min", 1.0))
    smax = float(_opt("sigma_max", 4.0))
    sstep = float(_opt("sigma_step", 1.0))
    invert = bool(_opt("invert_intensity", False))
    rb_radius = int(_opt("rolling_ball_radius", 0))

    # Accumulated 256-bin histogram across all sampled regions. uint64 to
    # tolerate >4 billion pixels (50 MP * 1000 regions easily).
    hist = np.zeros(256, dtype=np.uint64)
    n_pix_total = 0
    n_used = 0

    for p in paths:
        if not os.path.exists(p):
            logger.warning("Region PNG missing: %s", p)
            continue
        try:
            pil = Image.open(p)
            arr = np.asarray(pil)
            # Coerce to (H, W, 3) RGB or (H, W) grayscale -- same shape contract
            # the run script uses.
            if arr.ndim == 2:
                rgb = np.stack([arr, arr, arr], axis=-1)
            elif arr.ndim == 3 and arr.shape[2] >= 3:
                rgb = arr[..., :3]
            else:
                logger.warning("Skipping %s: unexpected shape %s", p, arr.shape)
                continue

            scalar = seg.pick_channel(rgb, chan)
            response = rf in seg._RIDGE_FILTERS
            if response:
                # Match the run-path polarity choice: invert -> black ridges.
                scalar = seg.apply_ridge_filter(scalar, rf, smin, smax, sstep, black_ridges=invert)
                # A vesselness response has no image units, so the only stable
                # 256-bin range is the region's own. Matches segment_internal.
                mn = float(scalar.min())
                mx = float(scalar.max())
                if mx - mn < 1e-12:
                    logger.info("Skipping %s: flat response (min==max==%f)", p, mn)
                    continue
                normed = (scalar - mn) / (mx - mn + 1e-12)
            else:
                # pick_channel already put the scalar on an ABSOLUTE [0,1] by bit
                # depth. Stretching each region here would give every region its
                # own scale -- the per-region adaptivity this calibration exists
                # to remove -- and would not transfer to a run path that no
                # longer stretches.
                normed = scalar

            if invert:
                normed = 1.0 - normed

            if rb_radius > 0:
                # Rolling-ball background subtraction. skimage's white_tophat is
                # the morphological equivalent and is faster than the literal
                # rolling-ball implementation.
                try:
                    from skimage.morphology import white_tophat, disk
                    normed = white_tophat(normed, footprint=disk(rb_radius))
                    if response:
                        m = float(normed.max())
                        if m > 1e-12:
                            normed = normed / m
                except Exception as exc:
                    logger.warning("Rolling-ball failed on %s: %s -- using raw scalar", p, exc)

            # Histogram the normalised scalar into 256 bins matching the
            # [0, 1] range. uint64 increments keep us safe for very large
            # accumulations.
            counts, _edges = np.histogram(normed, bins=256, range=(0.0, 1.0))
            hist += counts.astype(np.uint64)
            n_pix_total += int(scalar.size)
            n_used += 1
        except Exception as inner_exc:
            logger.warning("Region %s skipped: %s", p, inner_exc)
            continue

    if n_used == 0 or n_pix_total == 0:
        raise RuntimeError(
            "Calibration accumulated zero usable pixels. Check that region "
            "PNGs exist and that the source channel produces non-flat data."
        )

    # Run Otsu on the accumulated histogram. skimage.filters.threshold_otsu
    # accepts a hist=(counts, bin_centers) tuple -- avoids re-binning.
    from skimage.filters import threshold_otsu

    bin_centers = (np.arange(256, dtype=np.float64) + 0.5) / 256.0
    threshold_norm = float(threshold_otsu(hist=(hist.astype(np.float64), bin_centers)))

    result = {
        "threshold_normalised": threshold_norm,
        "n_regions_used": int(n_used),
        "n_pixels_total": int(n_pix_total),
        "histogram_bins": 256,
        "histogram_counts": [int(c) for c in hist.tolist()],
    }
    task.outputs["result_json"] = json.dumps(result)
    logger.info(
        "Calibration done: threshold=%.4f (normalised), regions=%d, pixels=%d",
        threshold_norm,
        n_used,
        n_pix_total,
    )

except Exception as e:
    logger.error("Calibration failed: %s\n%s", e, traceback.format_exc())
    task.outputs["result_json"] = json.dumps({"error": str(e)})
