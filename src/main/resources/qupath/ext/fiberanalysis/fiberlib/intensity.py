"""
Threshold-free signal planes: how much fiber signal a region holds, and how
aligned its texture is, measured on the image rather than on a fiber mask.

Every mask-based measurement inherits the segmentation's errors; on the bio-*
phantoms the mask over-reads coverage by 20-30 points. These two channels never
see a threshold:

  signal density       window mean of the preprocessed intensity, as a fraction
                       of the channel's full scale (bit depth). NOT rescaled
                       per image, so two slides acquired the same way compare.
  intensity coherence  (l1 - l2) / (l1 + l2) of the structure tensor summed
                       over the window: 0 for isotropic texture, 1 for a
                       perfectly oriented one. Summing the tensor first (the
                       window's coherency, as OrientationJ reports for a region)
                       rather than averaging per-pixel coherence is what makes
                       it poolable: the tensor components are plain sums.

The intensity is the same one segmentation thresholds: channel pick, optional
inversion, optional rolling-ball background (``segmentation.flatten_background``).
With no rolling ball the signal includes the background offset.

Acknowledgement: these channels were prompted by PyTextureAnalysis (A. Kulkarni,
doi:10.5281/zenodo.7562833, GPL-3.0; no code taken), whose local density is a
box mean of intensity following Qian et al. 2022, Opt. Express 30(14):25718,
doi:10.1364/OE.452767. The structure-tensor coherency is the measure of Bigun
and Granlund (1987) as used for collagen by Rezakhaniha et al. 2011, Biomech.
Model. Mechanobiol. 11:461-473, doi:10.1007/s10237-011-0325-z.
"""

import numpy as np
from scipy import ndimage
from skimage import filters as skfilters

from . import segmentation as seg

#: Gaussian smoothing before differentiating, in pixels.
DEFAULT_GRAD_SIGMA = 1.0

#: Mean squared gradient below which a region is treated as flat and its
#: coherence left undefined. A [0, 1] image quantised to 8 bits has steps of
#: 1/255, so real texture sits orders of magnitude above this.
FLAT_TRACE = 1e-12


def signal_scalar(image, channel, invert_intensity=False, rolling_ball_radius=0):
    """The preprocessed intensity on the channel's absolute [0, 1] scale.

    Args:
        image:               source tile as loaded (2-D or RGB).
        channel:             channel name as for ``segmentation.pick_channel``.
        invert_intensity:    flip bright and dark (DAB-style images).
        rolling_ball_radius: background radius in pixels; 0 disables.

    Returns:
        (H, W) float64.
    """
    scalar = seg.pick_channel(image, channel)
    out = seg.flatten_background(
        scalar, invert_intensity, rolling_ball_radius
    )
    return np.asarray(out, dtype=np.float64)


def tensor_planes(scalar, grad_sigma=DEFAULT_GRAD_SIGMA):
    """Per-pixel gradient products, to be summed over a window.

    Args:
        scalar:     (H, W) intensity.
        grad_sigma: Gaussian smoothing before differentiating, pixels.

    Returns:
        dict with ``jxx``, ``jyy``, ``jxy`` float64 planes.
    """
    smooth = ndimage.gaussian_filter(
        np.asarray(scalar, dtype=np.float64), sigma=float(grad_sigma)
    )
    gy = skfilters.scharr_h(smooth)
    gx = skfilters.scharr_v(smooth)
    return {"jxx": gx * gx, "jyy": gy * gy, "jxy": gx * gy}


def coherence(sxx, syy, sxy, n=1.0):
    """Coherency of a summed tensor; NaN where the region is flat.

    Args:
        sxx, syy, sxy: tensor components summed (or averaged) over a region.
        n:             pixels in the region, so the flatness test is per pixel.

    Returns:
        array in [0, 1], NaN where flat.
    """
    sxx = np.asarray(sxx, dtype=np.float64)
    syy = np.asarray(syy, dtype=np.float64)
    sxy = np.asarray(sxy, dtype=np.float64)
    trace = sxx + syy
    ok = trace > FLAT_TRACE * np.asarray(n, dtype=np.float64)
    out = np.full(trace.shape, np.nan, dtype=np.float64)
    np.divide(np.hypot(sxx - syy, 2.0 * sxy), trace, out=out, where=ok)
    return np.clip(out, 0.0, 1.0, out=out, where=ok)


def halo_px(grad_sigma=DEFAULT_GRAD_SIGMA, rolling_ball_radius=0):
    """Pixels a tile must read past its edge for these planes to be exact.

    The Gaussian is truncated at 4 sigma by scipy, Scharr adds one pixel, and
    a white top-hat (erosion then dilation) reaches twice its radius.
    """
    return int(np.ceil(4.0 * float(grad_sigma))) + 2 + 2 * int(rolling_ball_radius or 0)
