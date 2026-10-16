"""
Appose task: compute a project-wide threshold by streaming N region PNGs and
accumulating a single intensity histogram (HIST_BINS bins on [0, 1]), then
running Otsu or Triangle on the accumulated histogram.

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
  mask_paths          : list[str]  -- optional, index-aligned with region_paths.
                                      Each is a binary PNG marking the pixels
                                      INSIDE the annotation. A region is a
                                      bounding box, so without this the
                                      histogram includes whatever surrounds the
                                      annotation. An empty string or a missing
                                      file means "use the whole region".
  seg_channel         : str        -- 'Raw intensity' | 'Value (HSV)' | ...
  ridge_filter        : str        -- 'none' | 'frangi' | 'sato' | 'meijering'
  sigma_min, sigma_max, sigma_step : float (used when ridge_filter != 'none')
  invert_intensity    : bool       -- True for DAB / dark-fiber-on-bright-bg
  rolling_ball_radius : int        -- background-subtract radius in px (0=off)
  method              : str        -- 'otsu' (default) | 'triangle'

Outputs
-------
  task.outputs['result_json'] -- JSON string:
    {
      "threshold_normalised": float,  # in [0,1] -- this is what the run uses
      "method": "otsu" | "triangle",
      "n_regions_used": int,
      "n_pixels_total": int,
      "histogram_bins": HIST_BINS,
      "histogram_counts": [int, ...],  # length HIST_BINS, JSON-friendly
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


# 256 bins on [0, 1] let the threshold land only on 1/256 steps of full scale:
# on 16-bit data sitting in the low range (one liver birefringence image spans
# 0.03-0.17) a half-bin is ~1.4% of the data range. 4096 bins resolve 16-bit
# data to 16 gray levels for the same pooling.
HIST_BINS = 4096


def triangle_on_histogram(hist, centers):
    """Zack's triangle threshold on a pre-binned histogram.

    The line from the histogram peak to the far end of the longer tail; the
    threshold is the bin farthest below that line. Works on the pooled
    histogram directly, which skimage's threshold_triangle cannot take.
    """
    h = np.asarray(hist, dtype=np.float64)
    nz = np.flatnonzero(h)
    if nz.size == 0:
        raise RuntimeError("empty histogram")
    lo, hi = int(nz[0]), int(nz[-1])
    peak = int(np.argmax(h))
    # Use the longer side of the peak: the tail the threshold sits in.
    flip = (peak - lo) > (hi - peak)
    if flip:
        h = h[::-1]
        lo, hi, peak = len(h) - 1 - hi, len(h) - 1 - lo, len(h) - 1 - peak
    if hi <= peak:
        idx = peak
    else:
        x = np.arange(peak, hi + 1, dtype=np.float64)
        y = h[peak : hi + 1]
        # Distance below the line from (peak, h[peak]) to (hi, h[hi]).
        nx = h[peak] - h[hi]
        ny = hi - peak
        d = nx * (x - peak) + ny * (y - h[peak])
        idx = peak + int(np.argmin(d))
    if flip:
        idx = len(h) - 1 - idx
    return float(centers[idx])


try:
    # Imports from the bundled fiberlib package -- same path the run script uses.
    import fiberlib  # noqa: F401  (used for version stamp in callers)
    from fiberlib import segmentation as seg

    paths = list(region_paths)
    masks = list(_opt("mask_paths", []) or [])
    chan = str(seg_channel)
    rf = str(ridge_filter).lower()
    smin = float(_opt("sigma_min", 1.0))
    smax = float(_opt("sigma_max", 4.0))
    sstep = float(_opt("sigma_step", 1.0))
    invert = bool(_opt("invert_intensity", False))
    rb_radius = int(_opt("rolling_ball_radius", 0))

    # Accumulated histogram across all sampled regions. uint64 to
    # tolerate >4 billion pixels (50 MP * 1000 regions easily).
    hist = np.zeros(HIST_BINS, dtype=np.uint64)
    n_pix_total = 0
    n_used = 0

    for idx, p in enumerate(paths):
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
                scalar = seg.apply_ridge_filter(
                    scalar, rf, smin, smax, sstep, black_ridges=invert
                )
                # A vesselness response has no image units, so the only stable
                # histogram range is the region's own. Matches segment_internal.
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

            # The same invert + rolling-ball step segmentation applies.
            normed = seg.flatten_background(normed, invert, rb_radius, rescale=response)

            # Histogram the normalised scalar into HIST_BINS bins on the
            # [0, 1] range. uint64 increments keep us safe for very large
            # accumulations.
            # Restrict to the annotation. The region handed over is its
            # BOUNDING BOX plus a pad, so for any shape that is not a filled
            # rectangle the surplus is not tissue the user selected. On
            # acquired-area annotations that surplus is unacquired black and
            # it dominated the pool: 55.8% of pixels in bin 0, which pushed
            # Otsu to split background-from-tissue rather than
            # collagen-from-tissue.
            sel = normed
            mpath = masks[idx] if idx < len(masks) else ""
            if mpath and os.path.exists(mpath):
                try:
                    marr = np.asarray(Image.open(mpath))
                    if marr.ndim == 3:
                        marr = marr[..., 0]
                    if marr.shape == normed.shape:
                        inside = marr > 127
                        if inside.any():
                            sel = normed[inside]
                        else:
                            logger.warning(
                                "Mask %s selects no pixels -- using the whole region",
                                mpath,
                            )
                    else:
                        logger.warning(
                            "Mask %s shape %s != region %s -- using the whole region",
                            mpath,
                            marr.shape,
                            normed.shape,
                        )
                except Exception as exc:
                    logger.warning(
                        "Could not read mask %s: %s -- using the whole region",
                        mpath,
                        exc,
                    )

            counts, _edges = np.histogram(sel, bins=HIST_BINS, range=(0.0, 1.0))
            hist += counts.astype(np.uint64)
            n_pix_total += int(sel.size)
            n_used += 1
        except Exception as inner_exc:
            logger.warning("Region %s skipped: %s", p, inner_exc)
            continue

    if n_used == 0 or n_pix_total == 0:
        raise RuntimeError(
            "Calibration accumulated zero usable pixels. Check that region "
            "PNGs exist and that the source channel produces non-flat data."
        )

    # Threshold the accumulated histogram directly -- no re-binning.
    from skimage.filters import threshold_otsu

    method = str(_opt("method", "otsu")).lower()
    bin_centers = (np.arange(HIST_BINS, dtype=np.float64) + 0.5) / HIST_BINS
    if method == "triangle":
        threshold_norm = triangle_on_histogram(hist, bin_centers)
    elif method == "otsu":
        threshold_norm = float(
            threshold_otsu(hist=(hist.astype(np.float64), bin_centers))
        )
    else:
        raise ValueError(f"method must be 'otsu' or 'triangle', got '{method}'")

    result = {
        "threshold_normalised": threshold_norm,
        "method": method,
        "n_regions_used": int(n_used),
        "n_pixels_total": int(n_pix_total),
        "histogram_bins": HIST_BINS,
        "histogram_counts": [int(c) for c in hist.tolist()],
    }
    task.outputs["result_json"] = json.dumps(result)
    logger.info(
        "Calibration done: %s threshold=%.5f (normalised), regions=%d, pixels=%d",
        method,
        threshold_norm,
        n_used,
        n_pix_total,
    )

except Exception as e:
    logger.error("Calibration failed: %s\n%s", e, traceback.format_exc())
    task.outputs["result_json"] = json.dumps({"error": str(e)})
