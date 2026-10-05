"""
Border-zone dilation around a user annotation.

Lifted verbatim (with light edits to remove ppm_library cross-imports) from
ppm_library/ppm_library/analysis/surface_analysis.py lines 221-266 in the
QPSC_Project monorepo. ppm_library is MIT-licensed (same lab):

    https://github.com/uw-loci/ppm_library

The MIT license header above and inline attribution here satisfy the
attribution requirement.
"""
import numpy as np
from scipy import ndimage


def compute_border_zone_mask(boundary_mask, dilation_px, mode="outside", fill_holes=True):
    """Create the analysis zone around the annotation boundary.

    Args:
        boundary_mask: (H, W) bool, True inside the annotation.
        dilation_px:   dilation distance in pixels (rounded to int).
        mode:          'whole', 'outside' (default), 'inside', or 'both'.
                       'whole' is the entire annotation interior and ignores
                       dilation_px; the other three are a BAND of width
                       dilation_px measured from the boundary.
        fill_holes:    fill holes in boundary mask before computing zone.

    Returns:
        dict with:
            'zone_mask':         (H, W) bool, the border zone.
            'distance_map':      (H, W) float, signed distance from boundary
                                 (positive = outside, negative = inside).
            'dist_from_boundary':(H, W) float, unsigned distance.
    """
    mask = boundary_mask.copy()
    if fill_holes:
        mask = ndimage.binary_fill_holes(mask)

    dilation_px = max(1, int(round(dilation_px)))

    dist_outside = ndimage.distance_transform_edt(~mask)
    dist_inside = ndimage.distance_transform_edt(mask)

    signed_distance = dist_outside - dist_inside

    if mode in ("whole", "annotation"):
        # The whole annotation interior, not a band. The band modes all
        # measure from the boundary, so when the annotation IS the tissue
        # (rather than a tumour outline with stroma around it) every one of
        # them misses the thing the user meant to measure -- 'outside' on an
        # acquired-area annotation lands in unacquired background and reported
        # 0.56% coverage where the region itself was 43.3% fiber.
        zone = mask.copy()
    elif mode == "outside":
        zone = (dist_outside > 0) & (dist_outside <= dilation_px)
    elif mode == "inside":
        zone = (dist_inside > 0) & (dist_inside <= dilation_px)
    elif mode == "both":
        zone = ((dist_outside > 0) & (dist_outside <= dilation_px)) | (
            (dist_inside > 0) & (dist_inside <= dilation_px)
        )
    else:
        raise ValueError(
            f"Invalid mode: {mode}. Use 'whole', 'outside', 'inside', or 'both'."
        )

    return {
        "zone_mask": zone,
        "distance_map": signed_distance,
        "dist_from_boundary": np.minimum(dist_outside, dist_inside),
    }
