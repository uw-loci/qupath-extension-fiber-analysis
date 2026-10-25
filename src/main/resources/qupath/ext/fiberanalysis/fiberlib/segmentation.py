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

Ridge responses are on one absolute scale: 1.0 is the response of a
full-contrast fiber (a unit-amplitude Gaussian ridge at the smallest sigma).
scikit-image's Frangi picks its structureness constant from each image and its
Meijering divides each image's response by its own maximum, which made every
region's response relative to that region. Here Frangi gets a fixed gamma from
the reference ridge, Meijering is computed without the per-image division (the
eigenvalue combination is scikit-image's, BSD-3-Clause), and all three are
divided by their response on the reference ridge.
"""

import logging
import os

import numpy as np
from skimage import filters as skfilters
from skimage import morphology as skmorph
from skimage.color import rgb2hsv
from skimage.feature import hessian_matrix, hessian_matrix_eigvals

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
    if key == "value":
        # HSV V is the channel maximum; the full conversion took 24 s on a
        # 7830 px tile for this one plane.
        return rgb_scaled.max(axis=-1).astype(np.float32)
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

    ref, c = _reference_ridge(sigmas[0], black_ridges)
    if name == "frangi":
        # gamma from the reference ridge, not from this image's strongest
        # structure (scikit-image's default).
        eig = hessian_matrix_eigvals(
            hessian_matrix(ref, sigmas[0], mode="reflect", use_gaussian_derivatives=True)
        )
        gamma = float(np.sqrt((eig**2).sum(0))[c, c]) / 2.0

        def run(img):
            return skfilters.frangi(img, sigmas=sigmas, black_ridges=bool(black_ridges), gamma=gamma)

    elif name == "sato":

        def run(img):
            return skfilters.sato(img, sigmas=sigmas, black_ridges=bool(black_ridges))

    else:

        def run(img):
            return _meijering_absolute(img, sigmas, bool(black_ridges))

    scale = float(run(ref)[c, c])
    if not scale > 0:
        logger.warning("Ridge filter %s gave no response on the reference ridge; response left unscaled", name)
        scale = 1.0
    return np.clip(run(image_scalar) / scale, 0.0, 1.0).astype(np.float32)


def _reference_ridge(sigma, black_ridges):
    """A unit-amplitude Gaussian ridge of width ``sigma`` (dark if ``black_ridges``), and its centre index."""
    half = int(np.ceil(6.0 * sigma)) + 2
    x = np.arange(-half, half + 1, dtype=np.float32)
    ridge = np.tile(np.exp(-(x**2) / (2.0 * sigma * sigma)), (2 * half + 1, 1))
    return (1.0 - ridge if black_ridges else ridge), half


def _meijering_absolute(image, sigmas, black_ridges):
    """Meijering neuriteness without scikit-image's per-image max normalisation."""
    img = np.asarray(image, dtype=np.float32)
    if not black_ridges:
        img = -img
    alpha = 1.0 / 3.0  # scikit-image's default for 2-D, 1 / (ndim + 1)
    mtx = np.array([[1.0, alpha], [alpha, 1.0]], dtype=np.float32)
    out = np.zeros_like(img)
    for sigma in sigmas:
        eig = hessian_matrix_eigvals(hessian_matrix(img, sigma, mode="reflect", use_gaussian_derivatives=True))
        vals = np.tensordot(mtx, eig, 1)
        vals = np.take_along_axis(vals, abs(vals).argmax(0)[None], 0).squeeze(0)
        out = np.maximum(out, np.maximum(vals, 0))
    return out


# ---- thresholding ----------------------------------------------------------


def threshold_scalar(
    arr,
    method,
    manual_threshold,
    project_threshold_norm=None,
    manual_full_scale=255.0,
    fit_mask=None,
):
    """Pick a threshold and return a boolean foreground mask.

    Same parameters as :func:`resolve_threshold`.
    """
    return arr >= resolve_threshold(
        arr, method, manual_threshold, project_threshold_norm, manual_full_scale, fit_mask
    )


def resolve_threshold(
    arr,
    method,
    manual_threshold,
    project_threshold_norm=None,
    manual_full_scale=255.0,
    fit_mask=None,
):
    """The cut a threshold method resolves to on ``arr``.

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
        fit_mask:            pixels Otsu / triangle are fitted on, normally the
                             analysis zone, so the margin read around an
                             annotation does not move its threshold. None, or
                             a mask with no pixels, fits on the whole array.
    """
    method = (method or "otsu").lower()
    fit = arr
    if fit_mask is not None and method in ("otsu", "triangle", "project_otsu"):
        fm = np.asarray(fit_mask, dtype=bool)
        if fm.shape == arr.shape and fm.any():
            fit = arr[fm]
    if method == "otsu":
        t = skfilters.threshold_otsu(fit)
    elif method == "triangle":
        t = skfilters.threshold_triangle(fit)
    elif method == "manual":
        t = float(manual_threshold) / float(manual_full_scale)
    elif method == "project_otsu":
        if project_threshold_norm is None:
            logger.warning(
                "project_otsu selected but no calibrated threshold supplied -- falling back to per-region Otsu"
            )
            t = skfilters.threshold_otsu(fit)
        else:
            t = float(project_threshold_norm)
    else:
        logger.warning("Unknown threshold method %r -- falling back to Otsu", method)
        t = skfilters.threshold_otsu(fit)
    # Otsu / triangle / project_otsu resolve at runtime, so this is the only
    # record of what a given run actually cut at.
    logger.info(
        "Threshold %s resolved to %.6f on [0,1] (= %.1f of full scale %.0f)",
        method,
        t,
        t * float(manual_full_scale),
        float(manual_full_scale),
    )
    return float(t)


# ---- entry points ----------------------------------------------------------


def flatten_background(scalar, invert_intensity=False, rolling_ball_radius=0):
    """Invert (optionally) then subtract local background with a rolling ball.

    The one preprocessing rule shared by segmentation and the threshold-free
    signal channels, so both see the same image. Nothing is rescaled, so the
    result stays on the input's absolute scale.

    Args:
        scalar:              (H, W) float scalar in [0, 1].
        invert_intensity:    flip bright and dark first (DAB-style images).
        rolling_ball_radius: white top-hat disk radius in pixels; 0 disables.

    Returns:
        (H, W) float array.
    """
    out = 1.0 - scalar if invert_intensity else scalar
    if rolling_ball_radius and rolling_ball_radius > 0:
        try:
            from skimage.morphology import white_tophat, disk

            out = white_tophat(out, footprint=disk(int(rolling_ball_radius)))
        except Exception as exc:
            logger.warning("Rolling-ball failed: %s -- using raw scalar", exc)
    return out


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
    fit_mask=None,
    manual_response_threshold=0.1,
    return_threshold=False,
):
    """Internal fiber segmenter. See module docstring for parameters.

    ``return_threshold`` returns ``(mask, cut, full_scale)`` instead of the
    mask: the resolved cut on the thresholded scalar's scale, and the divisor
    that turns a gray level into it (1.0 with a ridge filter).

    ``invert_intensity`` flips bright<->dark before thresholding -- needed for
    DAB / chromogenic brightfield where collagen appears DARKER than
    background (the calibration dialog warns about this).

    ``rolling_ball_radius`` runs white_tophat (morphological rolling-ball
    equivalent) to subtract local background before thresholding. 0 disables.

    ``project_threshold_norm`` is the calibrated [0,1] threshold from a prior
    project-calibration pass; required when threshold_method == 'project_otsu'.

    ``fit_mask`` limits the pixels Otsu / triangle are fitted on (see
    :func:`threshold_scalar`); the mask itself still covers the whole image.

    Scale contract: with no ridge filter the scalar keeps the source image's
    absolute scale, so ``manual_threshold`` is a gray level in the image's own
    units. With a ridge filter the scalar is the ridge response on its absolute
    scale (1.0 = a full-contrast fiber; see :func:`apply_ridge_filter`), and a
    Manual cut is ``manual_response_threshold`` on that scale. Either way no
    region is rescaled to its own range, so Manual, calibrated and pooled
    thresholds mean the same thing in every region and image.
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
    # A Manual cut is a gray level / full scale without a ridge filter, and a
    # response value with one.
    if response:
        manual_cut, manual_full_scale = float(manual_response_threshold), 1.0
    else:
        manual_cut, manual_full_scale = manual_threshold, full_scale(image)

    # A ridge filter already looks for dark ridges when inverting
    # (black_ridges), so its response is high on fibers either way; inverting
    # the response too would make the threshold pick the background.
    normed = flatten_background(enhanced, invert_intensity and not response, rolling_ball_radius)

    cut = resolve_threshold(
        normed,
        threshold_method,
        manual_cut,
        project_threshold_norm,
        manual_full_scale,
        fit_mask=fit_mask,
    )
    mask = skmorph.binary_closing(normed >= cut, footprint=skmorph.disk(1))
    if min_fiber_area_px > 0:
        mask = skmorph.remove_small_objects(mask, min_size=int(min_fiber_area_px))
    if return_threshold:
        return mask.astype(bool), cut, float(manual_full_scale)
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
