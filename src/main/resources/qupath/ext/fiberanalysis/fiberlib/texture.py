"""
GLCM / Haralick texture features per moving window.

References:
    Haralick, R. M. et al. (1973). Textural features for image classification.
        IEEE Trans. Syst. Man Cybern.
    Aerts, H. J. W. L. et al. (2014). Decoding tumour phenotype by
        noninvasive imaging using a quantitative radiomics approach.
        Nature Communications 5:4006.
    Depeursinge, A. et al. (2014). Three-dimensional solid texture analysis
        in biomedical imaging. Med Image Anal 18(1).

We use scikit-image's ``graycomatrix`` + ``graycoprops``. The image is
quantised to ``quant_levels`` (default 16). Co-occurrence is computed at
each requested distance and averaged over four angles (0, 45, 90, 135 deg)
to be rotation-invariant, then ``graycoprops`` extracts the requested
properties.

A native ``entropy`` is added since scikit-image's ``graycoprops`` does
not include it.
"""

import logging
from collections import OrderedDict

import numpy as np
from skimage.feature import graycomatrix, graycoprops

logger = logging.getLogger("fiber.texture")

_ANGLES = [0.0, np.pi / 4.0, np.pi / 2.0, 3.0 * np.pi / 4.0]


def quantise(image_scalar, levels):
    """Quantise a [0,1]-ish scalar image into 0..levels-1 uint8."""
    # Callers pass a [0,1] scalar (see pipeline.rgb_to_value). Re-scaling here
    # by an assumed 8-bit range is what made 16-bit sources depend on a
    # compensating bug upstream; clip only, as a guard.
    arr = np.clip(image_scalar.astype(np.float32), 0.0, 1.0)
    q = np.floor(arr * (levels - 1) + 0.5).astype(np.uint8)
    return q


def glcm_props_for_patch(patch_q, distances_px, levels, props):
    """Compute a dict of {prop_name: scalar} for a single window patch."""
    if patch_q.size == 0:
        return {p: float("nan") for p in props}
    glcm = graycomatrix(
        patch_q,
        distances=list(distances_px),
        angles=_ANGLES,
        levels=int(levels),
        symmetric=True,
        normed=True,
    )
    out = {}
    for p in props:
        if p == "entropy":
            # Native: sum(- P * log2(P)) averaged over distances/angles.
            P = glcm
            valid = P > 0
            with np.errstate(divide="ignore", invalid="ignore"):
                ent = -np.where(valid, P * np.log2(np.where(valid, P, 1)), 0.0)
            ent = ent.sum(axis=(0, 1))  # (Nd, Na)
            out[p] = float(np.mean(ent))
        else:
            try:
                v = graycoprops(glcm, p)  # (Nd, Na)
                out[p] = float(np.mean(v))
            except Exception as e:
                logger.warning("graycoprops(%s) failed: %s", p, e)
                out[p] = float("nan")
    return out


def compute_glcm_window(
    image_scalar, fiber_mask, window_grid, quant_levels, distances_px, props
):
    """Per-window GLCM features.

    If ``window_grid`` is None, returns a single dict with one scalar per
    requested prop computed over the entire fiber-masked image. Otherwise
    returns a dict with ``per_window``: a dict {prop_name: (Hw, Ww) array}.
    """
    q = quantise(image_scalar, int(quant_levels))
    q_masked = np.where(fiber_mask, q, 0).astype(np.uint8)

    if window_grid is None:
        # graycomatrix needs a 2D array, so the masked image goes in whole --
        # background reads as level 0 rather than being dropped.
        single = glcm_props_for_patch(q_masked, distances_px, quant_levels, props)
        return {
            "per_window": {
                p: np.array([[v]], dtype=np.float32) for p, v in single.items()
            }
        }

    grid_shape = window_grid["grid_shape"]
    window_px = int(window_grid["window_px"])
    stride_px = int(window_grid["stride_px"])
    per_window = OrderedDict()
    for p in props:
        per_window[p] = np.full(grid_shape, np.nan, dtype=np.float32)

    for iy in range(grid_shape[0]):
        y0 = iy * stride_px
        y1 = y0 + window_px
        for ix in range(grid_shape[1]):
            x0 = ix * stride_px
            x1 = x0 + window_px
            patch = q_masked[y0:y1, x0:x1]
            if patch.size == 0 or not fiber_mask[y0:y1, x0:x1].any():
                continue
            vals = glcm_props_for_patch(patch, distances_px, quant_levels, props)
            for p, v in vals.items():
                per_window[p][iy, ix] = v
    return {"per_window": per_window}
