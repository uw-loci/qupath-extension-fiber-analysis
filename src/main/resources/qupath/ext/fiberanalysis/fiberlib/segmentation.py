"""
Fiber segmentation -- internal segmenter and existing-mask reader.

Two entry points:

    segment_internal(image, channel, threshold_method, manual_threshold,
                     ridge_filter, sigma_min, sigma_max, sigma_step,
                     min_fiber_area_px)
        -> (H, W) bool fiber mask.

    load_existing_mask(path, image_shape)
        -> (H, W) bool fiber mask read from a .tif or .npy file.

The internal pipeline is intentionally minimal -- channel pick -> optional
scikit-image vesselness (Frangi / Sato / Meijering, with a sigma sweep) ->
threshold (Otsu / triangle / manual) -> binary_closing -> remove_small_objects.

References used in this module:
    Frangi, A. F. et al. (1998). Multiscale vessel enhancement filtering.
        MICCAI.
    Sato, Y. et al. (1998). Three-dimensional multi-scale line filter.
        Med Image Anal 2(2).
    Meijering, E. et al. (2004). Design and validation of a tool for neurite
        tracing and analysis. Cytometry Part A 58(2).
"""

import logging
import os

import numpy as np
from skimage import filters as skfilters
from skimage import morphology as skmorph
from skimage.color import rgb2hsv

logger = logging.getLogger("fiber.segmentation")

# Ridge filters whose output is a vesselness RESPONSE rather than image
# intensity. segment_internal keys its scaling contract off this set.
_RIDGE_FILTERS = ("frangi", "sato", "meijering")


# ---- channel pick -----------------------------------------------------------

_CHANNEL_KEYS = {
    "raw intensity": "value",
    "raw": "value",
    "value (hsv)": "value",
    "value": "value",
    "hue (hsv)": "hue",
    "hue": "hue",
    "saturation (hsv)": "saturation",
    "saturation": "saturation",
}


def pick_channel(image, channel):
    """Return a HxW float32 array for the requested channel.

    Output is scaled into [0, 1]. The previous implementation divided by 255
    unconditionally, which silently misnormalised 16-bit grayscale images
    (a value of 65535 became 257.0 instead of ~1.0). We now detect bit-depth
    by dtype: uint16 divides by 65535, uint8 by 255, float passes through
    after a min-max if out of range.
    """
    if image.ndim == 2:
        return scale_to_unit(image)

    key = _CHANNEL_KEYS.get(str(channel).strip().lower(), "value")
    rgb_scaled = scale_to_unit(image[..., :3])
    hsv = rgb2hsv(rgb_scaled)
    if key == "hue":
        return hsv[..., 0].astype(np.float32)
    if key == "saturation":
        return hsv[..., 1].astype(np.float32)
    # default 'value' (HSV V or max-of-channels for a single-channel image)
    return hsv[..., 2].astype(np.float32)


def scale_to_unit(arr):
    """Coerce an array into a float32 scalar in [0, 1] regardless of bit depth.

    uint8  -> divide by 255
    uint16 -> divide by 65535
    float  -> pass through if already in [0,1]; min-max otherwise
    other  -> min-max into [0,1]
    """
    a = np.asarray(arr)
    if a.dtype == np.uint8:
        return a.astype(np.float32) / 255.0
    if a.dtype == np.uint16:
        return a.astype(np.float32) / 65535.0
    af = a.astype(np.float32)
    mn = float(af.min())
    mx = float(af.max())
    if 0.0 <= mn and mx <= 1.0:
        return af
    if mx > mn:
        return (af - mn) / (mx - mn)
    return af


def full_scale(image):
    """Return the divisor that puts ``image``'s dtype onto [0, 1].

    Mirrors :func:`scale_to_unit`, and is what a manual threshold entered in
    the image's own gray levels must be divided by. Note PIL delivers a 16-bit
    RGB PNG as uint8, so a colour source is 255 here even when the slide is
    16-bit; the mask is built from what actually arrived, not from the
    server's advertised bit depth.

    Args:
        image: the source array as loaded.

    Returns:
        255.0 for uint8, 65535.0 for uint16, 1.0 otherwise (those are min-max
        scaled into [0, 1] by :func:`scale_to_unit`).
    """
    a = np.asarray(image)
    if a.dtype == np.uint8:
        return 255.0
    if a.dtype == np.uint16:
        return 65535.0
    logger.info(
        "Source dtype %s is min-max scaled into [0,1]; manual threshold is a "
        "fraction of that range, not a gray level",
        a.dtype,
    )
    return 1.0


# ---- ridge / vesselness filter ---------------------------------------------


def apply_ridge_filter(
    image_scalar, name, sigma_min, sigma_max, sigma_step, black_ridges=False
):
    """Run a scikit-image vesselness filter with a linear sigma sweep.

    ``black_ridges`` is the critical polarity flag. scikit-image's frangi /
    sato / meijering default it to True ("detect dark ridges on bright
    background"), which for our typical bright-fiber-on-dark imaging
    (fluorescence, SHG, PPM birefringence) responds strongest in the dark
    GAPS between fibers, not on the fibers themselves. We default to False
    so the filter does what users intuit; pass True only for DAB /
    chromogenic brightfield where collagen is darker than background.

    Returns the filter response (same shape as ``image_scalar``).
    Pass name='none' to skip; the caller falls through to the bare scalar.
    """
    name = (name or "none").lower()
    if name not in _RIDGE_FILTERS:
        if name != "none":
            logger.warning("Unknown ridge filter %r -- skipping", name)
        return image_scalar

    sigmas = []
    s = float(sigma_min)
    smax = float(sigma_max)
    step = max(float(sigma_step), 1e-6)
    while s <= smax + 1e-9:
        sigmas.append(s)
        s += step
    if not sigmas:
        sigmas = [float(sigma_min)]

    if name == "frangi":
        return skfilters.frangi(
            image_scalar, sigmas=sigmas, black_ridges=bool(black_ridges)
        )
    if name == "sato":
        return skfilters.sato(
            image_scalar, sigmas=sigmas, black_ridges=bool(black_ridges)
        )
    return skfilters.meijering(
        image_scalar, sigmas=sigmas, black_ridges=bool(black_ridges)
    )


# ---- thresholding ----------------------------------------------------------


def threshold_scalar(
    arr, method, manual_threshold, project_threshold_norm=None, manual_full_scale=255.0
):
    """Pick a threshold and return a boolean foreground mask.

    Parameters:
        arr:                 scalar image on [0, 1].
        method:              'otsu' (per-region) | 'triangle' | 'manual' |
                             'project_otsu' (use the pre-computed project value).
        manual_threshold:    cut-off in the source image's own gray levels,
                             divided by ``manual_full_scale`` to land on [0,1].
        project_threshold_norm:
                             the calibrated [0,1] threshold from a prior
                             project-calibration pass. Required when method ==
                             'project_otsu'; ignored otherwise.
        manual_full_scale:   divisor for ``manual_threshold``; see
                             :func:`full_scale`.
    """
    method = (method or "otsu").lower()
    if method == "otsu":
        t = skfilters.threshold_otsu(arr)
    elif method == "triangle":
        t = skfilters.threshold_triangle(arr)
    elif method == "manual":
        t = float(manual_threshold) / float(manual_full_scale)
    elif method == "project_otsu":
        if project_threshold_norm is None:
            logger.warning(
                "project_otsu selected but no calibrated threshold supplied -- falling back to per-region Otsu"
            )
            t = skfilters.threshold_otsu(arr)
        else:
            t = float(project_threshold_norm)
    else:
        logger.warning("Unknown threshold method %r -- falling back to Otsu", method)
        t = skfilters.threshold_otsu(arr)
    # Otsu / triangle / project_otsu resolve at runtime, so this is the only
    # record of what a given run actually cut at.
    logger.info(
        "Threshold %s resolved to %.6f on [0,1] (= %.1f of full scale %.0f)",
        method,
        t,
        t * float(manual_full_scale),
        float(manual_full_scale),
    )
    return arr >= t


# ---- entry points ----------------------------------------------------------


def segment_internal(
    image,
    channel,
    threshold_method,
    manual_threshold,
    ridge_filter,
    sigma_min,
    sigma_max,
    sigma_step,
    min_fiber_area_px,
    invert_intensity=False,
    rolling_ball_radius=0,
    project_threshold_norm=None,
):
    """Internal fiber segmenter. See module docstring for parameters.

    ``invert_intensity`` flips bright<->dark before thresholding -- needed for
    DAB / chromogenic brightfield where collagen appears DARKER than
    background (the calibration dialog warns about this).

    ``rolling_ball_radius`` runs white_tophat (morphological rolling-ball
    equivalent) to subtract local background before thresholding. 0 disables.

    ``project_threshold_norm`` is the calibrated [0,1] threshold from a prior
    project-calibration pass; required when threshold_method == 'project_otsu'.

    Scale contract: with no ridge filter the scalar keeps the source image's
    absolute scale, so ``manual_threshold`` is a gray level in the image's own
    units and a calibrated threshold is comparable across the project. A ridge
    filter's response has no image units, so that branch min-max stretches the
    region and both thresholds become a fraction of the response range. Otsu
    and triangle are unaffected either way -- a min-max stretch is affine, so
    it maps their threshold without moving the partition.
    """
    scalar = pick_channel(image, channel)
    # Ridge polarity: when invert_intensity is True we're segmenting a DAB-
    # style image (dark fibers on bright background), which is exactly when
    # the vesselness filters should hunt for black ridges.
    enhanced = apply_ridge_filter(
        scalar,
        ridge_filter,
        sigma_min,
        sigma_max,
        sigma_step,
        black_ridges=bool(invert_intensity),
    )
    response = str(ridge_filter or "none").lower() in _RIDGE_FILTERS
    # One arithmetic rule either way: manual_threshold / manual_full_scale is
    # the cut on [0,1]. Without a ridge filter that is an absolute gray level;
    # with one it is that same fraction of the response's own range.
    manual_full_scale = full_scale(image)

    if response and enhanced.max() > enhanced.min():
        normed = (enhanced - enhanced.min()) / (enhanced.max() - enhanced.min() + 1e-12)
    else:
        normed = enhanced

    if invert_intensity:
        normed = 1.0 - normed

    if rolling_ball_radius and rolling_ball_radius > 0:
        try:
            from skimage.morphology import white_tophat, disk

            normed = white_tophat(normed, footprint=disk(int(rolling_ball_radius)))
            # Only a response is safe to rescale -- doing it on the absolute
            # scalar would put the gray levels back on a per-region scale.
            if response:
                m = float(normed.max())
                if m > 1e-12:
                    normed = normed / m
        except Exception as exc:
            logger.warning("Rolling-ball failed: %s -- using raw scalar", exc)

    mask = threshold_scalar(
        normed,
        threshold_method,
        manual_threshold,
        project_threshold_norm,
        manual_full_scale,
    )
    mask = skmorph.binary_closing(mask, footprint=skmorph.disk(1))
    if min_fiber_area_px > 0:
        mask = skmorph.remove_small_objects(mask, min_size=int(min_fiber_area_px))
    return mask.astype(bool)


def load_existing_mask(path, image_shape):
    """Read a binary fiber mask from a .tif or .npy file.

    Validates that the mask's shape matches ``image_shape`` (H, W).
    Anything non-zero is treated as fiber.
    """
    ext = os.path.splitext(path)[1].lower()
    if ext in (".npy", ".npz"):
        arr = np.load(path)
        if ext == ".npz":
            # take the first array stored inside
            arr = arr[arr.files[0]]
    elif ext in (".tif", ".tiff"):
        # PIL is already a dependency; avoid pulling tifffile.
        from PIL import Image

        arr = np.asarray(Image.open(path))
    else:
        raise ValueError(f"Unsupported mask file extension {ext!r}")

    if arr.ndim == 3:
        arr = arr[..., 0]
    if arr.shape != tuple(image_shape):
        raise ValueError(
            f"Mask shape {arr.shape} does not match image shape {tuple(image_shape)}"
        )
    return arr.astype(bool)
