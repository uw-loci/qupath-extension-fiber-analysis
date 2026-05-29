"""
Appose task script: run fiber analysis for a single annotation.

Inputs (injected by Appose 0.10+ as Python variables; read with the
try/except NameError default idiom):

  Image region
    region_image_path   : str   PNG path written by Java
    region_w, region_h  : int   region pixel dimensions
    region_offset_x/_y  : int   region origin in source image (unused here)
    boundary_mask_path  : str   8-bit PNG (0/255) with the annotation polygon
                                rasterised in region-local coordinates. Optional
                                fallback to bbox when the file is missing.
    bbox_x, bbox_y      : int   annotation bbox top-left in region coords
                                (fallback if boundary_mask_path is unavailable)
    bbox_w, bbox_h      : int   annotation bbox size in pixels (fallback)

  Search area
    pixel_size_um           : float
    border_zone_width_um    : float
    zone_mode               : str  'inside' | 'outside' | 'both'

  Segmentation
    seg_source              : str  'internal' | 'existing'
    seg_channel             : str  'Value (HSV)' etc -- see fiberlib.segmentation
    threshold_method        : str  'otsu' | 'triangle' | 'manual'
    manual_threshold        : int  0-255
    ridge_filter            : str  'none' | 'frangi' | 'sato' | 'meijering'
    sigma_min / sigma_max / sigma_step  : float (pixels)
    min_fiber_area_px       : int
    existing_mask_path      : str  (only used when seg_source == 'existing')

  Window analysis
    window_enabled          : bool
    window_size_um          : float
    window_overlap_percent  : int 0-90
    window_create_objects   : bool (Java side reads windows.json regardless)

  Family toggles + tuning
    straightness_enabled, tortuosity_on, radon_on, min_branch_um
    morph_enabled, morph_branch, morph_endpoints, morph_length, morph_curvature
    morph_hdm, morph_lac, morph_fd, morph_gaps
    lac_box_sizes_px        : str comma-separated ints
    fractal_box_sizes_px    : str comma-separated ints
    texture_enabled, quant_levels, glcm_distances_px
    texture_contrast, texture_correlation, texture_energy,
    texture_homogeneity, texture_entropy, texture_dissimilarity

  Output
    output_dir              : str
    heatmap_property        : str  GLCM prop key to render as a PNG
    emit_fiber_mask_png, emit_straightness_png, emit_glcm_png,
    emit_morph_summary, emit_json_sidecar, emit_npz : bool

Outputs:
    task.outputs['result_json']  -- summary dict as a JSON string. Side files
                                    (overlays, windows.json, results.json,
                                    optional .npz) are written under output_dir.
"""
import datetime
import json
import logging
import os
import sys
import uuid
from collections import OrderedDict

import numpy as np
from PIL import Image

logger = logging.getLogger("fiber.appose.run")


# ---- small helpers (defined before the try block so they're in scope) -------

def parse_int_list(s):
    """Parse a comma-separated string of ints. Robust to whitespace."""
    if isinstance(s, list):
        return [int(x) for x in s]
    return [int(tok.strip()) for tok in str(s).split(",") if tok.strip()]


def rgb_to_value(rgb):
    """Cheap value-channel extraction (max of RGB), returned as float32 in [0,1]."""
    arr = rgb.astype(np.float32)
    if arr.max() > 1.0:
        arr = arr / 255.0
    return arr.max(axis=-1)


def opt_get(var_name, default):
    """Read an optional injected input; return default if unbound.

    Uses ``globals().get`` rather than ``eval`` so a typo like
    ``opt_get('eemit_npz', False)`` raises a clean lookup-miss instead of
    silently returning the default.
    """
    return globals().get(var_name, default)


try:
    import fiberlib
    from fiberlib import (
        segmentation as seg,
        dilation as dil,
        windows as win,
        straightness as straight,
        morphometrics as morph,
        texture as tex,
        render as rndr,
        io as fio,
    )

    # Image region
    region_path = region_image_path  # required
    rw = int(region_w)
    rh = int(region_h)
    bx = int(opt_get("bbox_x", 0))
    by = int(opt_get("bbox_y", 0))
    bw = int(opt_get("bbox_w", rw))
    bh = int(opt_get("bbox_h", rh))

    # Search area
    px_um = float(pixel_size_um)
    border_um = float(border_zone_width_um)
    zone = str(zone_mode)

    # Segmentation
    seg_src = str(seg_source)
    seg_chan = str(seg_channel)
    thr_method = str(threshold_method).lower()
    manual_thr = int(manual_threshold)
    rf = str(ridge_filter).lower()
    smin = float(sigma_min)
    smax = float(sigma_max)
    sstep = float(sigma_step)
    min_area = int(min_fiber_area_px)
    existing_mask = str(opt_get("existing_mask_path", "") or "")
    invert_intensity_v = bool(opt_get("invert_intensity", False))
    rolling_ball_radius_v = int(opt_get("rolling_ball_radius", 0))
    project_threshold_norm_v = opt_get("project_threshold_norm", None)
    if project_threshold_norm_v is not None:
        try:
            project_threshold_norm_v = float(project_threshold_norm_v)
        except (TypeError, ValueError):
            project_threshold_norm_v = None

    # Window analysis
    win_on = bool(window_enabled)
    win_um = float(window_size_um)
    win_overlap_pct = float(window_overlap_percent)
    min_window_cov_pct = float(opt_get("min_window_coverage_percent", 0.0))

    # Straightness
    st_on = bool(straightness_enabled)
    tort_on = bool(tortuosity_on)
    rad_on = bool(radon_on)
    min_branch_um_v = float(min_branch_um)

    # Morphometrics
    morph_on = bool(morph_enabled)
    morph_flags = {
        "branch": bool(morph_branch),
        "endpoints": bool(morph_endpoints),
        "length": bool(morph_length),
        "curvature": bool(morph_curvature),
        "hdm": bool(morph_hdm),
        "lac": bool(morph_lac),
        "fd": bool(morph_fd),
        "gaps": bool(morph_gaps),
    }
    lac_boxes = parse_int_list(lac_box_sizes_px) if "lac_box_sizes_px" in globals() else [4, 8, 16, 32, 64]
    fd_boxes = (
        parse_int_list(fractal_box_sizes_px)
        if "fractal_box_sizes_px" in globals()
        else [2, 4, 8, 16, 32, 64, 128]
    )

    # Texture
    tex_on = bool(texture_enabled)
    qlevels = int(quant_levels)
    glcm_dists = parse_int_list(glcm_distances_px) if "glcm_distances_px" in globals() else [1, 2, 3]
    tex_props = OrderedDict(
        contrast=bool(texture_contrast),
        correlation=bool(texture_correlation),
        energy=bool(texture_energy),
        homogeneity=bool(texture_homogeneity),
        entropy=bool(texture_entropy),
        dissimilarity=bool(texture_dissimilarity),
    )

    # Output
    out_dir = str(output_dir)
    os.makedirs(out_dir, exist_ok=True)
    heatmap_prop = str(opt_get("heatmap_property", "contrast"))
    emit_mask = bool(emit_fiber_mask_png)
    emit_str = bool(emit_straightness_png)
    emit_glcm = bool(emit_glcm_png)
    emit_morph_sum = bool(emit_morph_summary)
    emit_json = bool(emit_json_sidecar)
    emit_npz_v = bool(emit_npz)

    # ---- Provenance: parameter echo, versions, run-id, timestamp ------------
    # PI-tester M1 + Clinical-tester M1: results.json must round-trip the
    # full input parameter set so a reviewer with the JSON + image can
    # reproduce the run. Intentionally excludes any user-supplied identifier
    # field (annotation name, image name, mask file path) -- those would
    # carry PHI into the Appose IPC channel; see Clinical-tester M4.
    # Build the parameters echo conditionally so the user-facing JSON only
    # carries values that actually drove this run. Including manual_threshold
    # when threshold_method='otsu', or sigma_min/max/step when
    # ridge_filter='none', is misleading -- a reader of results.json would
    # think 128 is the actual cutoff Otsu used on a 16-bit image, which is
    # nonsense.
    pe = OrderedDict()
    pe["border_zone_width_um"] = border_um
    pe["zone_mode"] = zone
    pe["pixel_size_um"] = px_um
    pe["seg_source"] = seg_src
    if seg_src == "existing":
        # mask_source is supplied by the Java side (not currently bound into
        # this script's inputs); existing_mask_path carries the actual path
        # used. Echo the path only when it drove the run.
        if existing_mask:
            pe["existing_mask_path"] = existing_mask
    else:
        pe["seg_channel"] = seg_chan
        pe["threshold_method"] = thr_method
        if thr_method == "manual":
            pe["manual_threshold"] = manual_thr
        if thr_method == "project_otsu" and project_threshold_norm_v is not None:
            pe["project_threshold_norm"] = project_threshold_norm_v
        if invert_intensity_v:
            pe["invert_intensity"] = True
        if rolling_ball_radius_v > 0:
            pe["rolling_ball_radius"] = rolling_ball_radius_v
        pe["ridge_filter"] = rf
        if rf != "none":
            pe["sigma_min"] = smin
            pe["sigma_max"] = smax
            pe["sigma_step"] = sstep
        pe["min_fiber_area_px"] = min_area
    pe["window_enabled"] = win_on
    if win_on:
        pe["window_size_um"] = win_um
        pe["window_overlap_percent"] = win_overlap_pct
        if min_window_cov_pct > 0:
            pe["min_window_coverage_percent"] = min_window_cov_pct
    pe["straightness_enabled"] = st_on
    if st_on:
        pe["tortuosity_on"] = tort_on
        pe["radon_on"] = rad_on
        if tort_on:
            pe["min_branch_um"] = min_branch_um_v
    pe["morph_enabled"] = morph_on
    if morph_on:
        pe["morph_branch"] = morph_flags["branch"]
        pe["morph_endpoints"] = morph_flags["endpoints"]
        pe["morph_length"] = morph_flags["length"]
        pe["morph_curvature"] = morph_flags["curvature"]
        pe["morph_hdm"] = morph_flags["hdm"]
        pe["morph_lac"] = morph_flags["lac"]
        pe["morph_fd"] = morph_flags["fd"]
        pe["morph_gaps"] = morph_flags["gaps"]
        if morph_flags["lac"]:
            pe["lac_box_sizes_px"] = list(lac_boxes)
        if morph_flags["fd"]:
            pe["fractal_box_sizes_px"] = list(fd_boxes)
    pe["texture_enabled"] = tex_on
    if tex_on:
        pe["quant_levels"] = qlevels
        pe["glcm_distances_px"] = list(glcm_dists)
        pe["texture_props"] = [k for k, on in tex_props.items() if on]
        pe["heatmap_property"] = heatmap_prop
    pe["emit_fiber_mask_png"] = emit_mask
    pe["emit_straightness_png"] = emit_str
    pe["emit_glcm_png"] = emit_glcm
    pe["emit_morph_summary"] = emit_morph_sum
    pe["emit_json_sidecar"] = emit_json
    pe["emit_npz"] = emit_npz_v
    parameters_echo = pe
    # Intentionally EXCLUDED: region_image_path, boundary_mask_path,
    # output_dir, region_offset_x/y, bbox_*. These either carry user paths
    # (PHI risk) or are per-run derived state, not configuration the user
    # supplied.

    try:
        import scipy
        scipy_version = scipy.__version__
    except Exception:
        scipy_version = "unknown"
    try:
        import skimage
        skimage_version = skimage.__version__
    except Exception:
        skimage_version = "unknown"
    try:
        fiberlib_version = fiberlib.__version__
    except Exception:
        fiberlib_version = "unknown"

    versions_block = OrderedDict([
        ("fiberlib", fiberlib_version),
        ("extension", str(opt_get("extension_version", "unknown"))),
        ("python", sys.version.split()[0]),
        ("numpy", np.__version__),
        ("scipy", scipy_version),
        ("scikit_image", skimage_version),
    ])

    run_block = OrderedDict([
        ("timestamp_utc", datetime.datetime.now(datetime.timezone.utc).isoformat()),
        ("run_id", str(uuid.uuid4())),
    ])

    # ---- Load region as RGB or grayscale ----
    pil = Image.open(region_path)
    image = np.asarray(pil)
    if image.ndim == 2:
        image_rgb = np.stack([image, image, image], axis=-1)
    elif image.ndim == 3 and image.shape[2] >= 3:
        image_rgb = image[..., :3]
    else:
        raise ValueError(f"Unexpected image shape: {image.shape}")
    logger.info("Loaded region: shape=%s dtype=%s", image_rgb.shape, image_rgb.dtype)

    # ---- Boundary mask: prefer Java-side polygon raster, fall back to bbox ----
    boundary_mask_p = str(opt_get("boundary_mask_path", "") or "")
    boundary_mask = None
    if boundary_mask_p and os.path.exists(boundary_mask_p):
        try:
            mp = Image.open(boundary_mask_p)
            mask_arr = np.asarray(mp)
            # Accept grayscale (HxW) or RGBA; collapse to a single channel.
            if mask_arr.ndim == 3:
                mask_arr = mask_arr[..., 0]
            if mask_arr.shape != (rh, rw):
                logger.warning(
                    "boundary_mask shape %s != region (%d, %d); falling back to bbox",
                    mask_arr.shape, rh, rw,
                )
                boundary_mask = None
            else:
                boundary_mask = mask_arr > 127
                logger.info(
                    "Loaded polygon boundary mask: %d inside pixels (%.2f%% of region)",
                    int(boundary_mask.sum()),
                    100.0 * float(boundary_mask.sum()) / boundary_mask.size,
                )
        except Exception as exc:
            logger.warning("Could not load boundary_mask_path %s: %s", boundary_mask_p, exc)
            boundary_mask = None
    if boundary_mask is None:
        boundary_mask = np.zeros((rh, rw), dtype=bool)
        y0 = max(0, by)
        y1 = min(rh, by + bh)
        x0 = max(0, bx)
        x1 = min(rw, bx + bw)
        if y1 > y0 and x1 > x0:
            boundary_mask[y0:y1, x0:x1] = True
        logger.info("Using bbox boundary mask fallback")

    # ---- Segmentation ----
    if seg_src == "existing":
        if not existing_mask or not os.path.exists(existing_mask):
            raise FileNotFoundError(
                f"seg_source=existing but mask file not found: {existing_mask!r}"
            )
        fiber_mask = seg.load_existing_mask(existing_mask, image_rgb.shape[:2])
    else:
        fiber_mask = seg.segment_internal(
            image=image_rgb,
            channel=seg_chan,
            threshold_method=thr_method,
            manual_threshold=manual_thr,
            ridge_filter=rf,
            sigma_min=smin,
            sigma_max=smax,
            sigma_step=sstep,
            min_fiber_area_px=min_area,
            invert_intensity=invert_intensity_v,
            rolling_ball_radius=rolling_ball_radius_v,
            project_threshold_norm=project_threshold_norm_v,
        )
    logger.info(
        "Fiber mask: %d pixels (%.2f%% coverage)",
        int(fiber_mask.sum()),
        100.0 * float(fiber_mask.sum()) / fiber_mask.size,
    )

    # ---- Dilated zone ----
    dilation_px = max(1, int(round(border_um / px_um)))
    zone_info = dil.compute_border_zone_mask(boundary_mask, dilation_px, mode=zone, fill_holes=True)
    zone_mask = zone_info["zone_mask"]
    analysis_mask = fiber_mask & zone_mask
    logger.info(
        "Zone: %d px, fiber-in-zone: %d px", int(zone_mask.sum()), int(analysis_mask.sum())
    )

    # ---- Fiber-angle map (geometric: tangent of the skeleton) ----
    # straightness module exposes a helper to skeletonise + estimate per-pixel
    # tangents which the windows module aggregates.
    skeleton, fiber_angles_deg = straight.skeletonize_and_tangents(analysis_mask)

    # ---- Window grid (axial circular stats) ----
    window_grid = None
    if win_on:
        window_px = max(2, int(round(win_um / px_um)))
        overlap_frac = max(0.0, min(0.95, win_overlap_pct / 100.0))
        stride_px = max(1, int(round(window_px * (1.0 - overlap_frac))))
        window_grid = win.compute_windows(
            fiber_angles_deg, analysis_mask, window_px, stride_px=stride_px
        )
        window_grid["window_um"] = float(win_um)
        logger.info(
            "Windows: %s grid, window=%dpx stride=%dpx",
            window_grid["grid_shape"], window_px, stride_px,
        )

    # ---- Build result dict ----
    result = OrderedDict()
    # Provenance first so a `head -20 results.json` immediately tells the
    # reader which version and run this is.
    result["versions"] = versions_block
    result["run"] = run_block
    result["parameters"] = parameters_echo

    result["region"] = {
        "width": rw, "height": rh,
        "offset_x": int(opt_get("region_offset_x", 0)),
        "offset_y": int(opt_get("region_offset_y", 0)),
    }
    result["pixel_size_um"] = px_um
    result["border_zone_width_um"] = border_um
    result["zone_mode"] = zone
    fiber_px_v = int(fiber_mask.sum())
    zone_px_v = int(zone_mask.sum())
    fiber_in_zone_v = int(analysis_mask.sum())
    region_px_v = int(rh * rw)
    result["fiber_pixels"] = fiber_px_v
    result["zone_pixels"] = zone_px_v
    result["fiber_in_zone_pixels"] = fiber_in_zone_v
    # User-facing comparable values: raw pixel counts scale with annotation
    # size, percentages are sample-to-sample comparable. fiber_coverage_*
    # answers "what fraction of {zone, region} is fiber" -- the question
    # scientists actually want.
    result["fiber_coverage_zone_percent"] = (
        100.0 * fiber_in_zone_v / zone_px_v if zone_px_v > 0 else None
    )
    result["fiber_coverage_region_percent"] = (
        100.0 * fiber_px_v / region_px_v if region_px_v > 0 else None
    )
    result["zone_area_um2"] = float(zone_px_v) * (px_um * px_um)
    result["fiber_in_zone_area_um2"] = float(fiber_in_zone_v) * (px_um * px_um)

    # ---- Family: straightness ----
    if st_on:
        s_block = OrderedDict()
        if tort_on:
            tort = straight.compute_skeleton_tortuosity(
                analysis_mask,
                window_grid=window_grid,
                min_branch_px=max(1, int(round(min_branch_um_v / px_um))),
            )
            s_block["mean_tortuosity"] = float(tort.get("mean_tortuosity", float("nan")))
            s_block["n_segments"] = int(tort.get("n_segments", 0))
            if window_grid is not None and "per_window_tortuosity" in tort:
                window_grid["tortuosity"] = tort["per_window_tortuosity"]
            if window_grid is not None and "per_window_n_fibers" in tort:
                window_grid["n_fibers"] = tort["per_window_n_fibers"]
        if rad_on:
            scalar = rgb_to_value(image_rgb)
            radon_out = straight.compute_radon_scalar(scalar, analysis_mask)
            s_block["radon"] = {k: float(v) for k, v in radon_out.items()}
        result["straightness"] = s_block

    # ---- Family: morphometrics ----
    if morph_on:
        m_block = OrderedDict()
        if morph_flags["hdm"]:
            m_block["hdm_coverage"] = float(morph.hdm(analysis_mask, zone_mask))
        if morph_flags["length"]:
            m_block["total_length_px"] = int(morph.total_length_px(skeleton))
            m_block["total_length_um"] = float(m_block["total_length_px"] * px_um)
        if morph_flags["branch"] or morph_flags["endpoints"]:
            bp, ep = morph.branch_endpoint_counts(skeleton)
            if morph_flags["branch"]:
                m_block["branch_points"] = int(bp)
            if morph_flags["endpoints"]:
                m_block["endpoints"] = int(ep)
        if morph_flags["curvature"]:
            m_block["mean_curvature_per_um"] = float(
                morph.mean_curvature(skeleton, segment_len_px=max(2, int(round(2.0 / px_um))))
                / max(px_um, 1e-9)
            )
        if morph_flags["fd"]:
            m_block["fractal_dimension"] = float(morph.fractal_dimension(skeleton, fd_boxes))
        if morph_flags["lac"]:
            lac = morph.lacunarity(analysis_mask, lac_boxes)
            m_block["lacunarity_mean"] = float(np.nanmean(list(lac.values()))) if lac else float("nan")
            m_block["lacunarity_per_box"] = {int(k): float(v) for k, v in lac.items()}
        if morph_flags["gaps"]:
            gap = morph.gap_analysis(analysis_mask)
            m_block["gap_mean_um"] = float(gap.get("mean_gap_px", float("nan")) * px_um)
            m_block["gap_max_um"] = float(gap.get("max_gap_px", float("nan")) * px_um)
        result["morphometrics"] = m_block

    # ---- Family: texture (GLCM) ----
    if tex_on:
        scalar = rgb_to_value(image_rgb)
        tex_block = tex.compute_glcm_window(
            image_scalar=scalar,
            fiber_mask=analysis_mask,
            window_grid=window_grid,
            quant_levels=qlevels,
            distances_px=glcm_dists,
            props=[k for k, on in tex_props.items() if on],
        )
        result["texture"] = {
            "props": [k for k, on in tex_props.items() if on],
            "distances_px": list(glcm_dists),
            "quant_levels": int(qlevels),
            "mean_per_prop": {
                k: float(np.nanmean(v)) for k, v in tex_block.get("per_window", {}).items()
            },
        }
        # stash per-window arrays into the window grid for sidecars
        if window_grid is not None:
            window_grid["texture"] = tex_block.get("per_window", {})

    # ---- Per-window morphometric arrays ----
    # Annotation-level scalars go into result["morphometrics"]; the per-window
    # equivalents -- requested by the user since window-size + overlap are
    # configurable -- go into window_grid["morphometrics"]. Names match the
    # annotation-level keys where the underlying quantity is the same
    # (total_length_px, branch_points, endpoints, mean_curvature_per_px,
    # fractal_dimension, lacunarity_mean / per box, gap_mean_px / gap_max_px).
    if morph_on and window_grid is not None:
        pw_morph = morph.per_window_morphometrics(
            skeleton=skeleton,
            fiber_mask=analysis_mask,
            window_px=int(window_grid["window_px"]),
            stride_px=int(window_grid["stride_px"]),
            grid_shape=window_grid["grid_shape"],
            lac_boxes=lac_boxes,
            fd_boxes=fd_boxes,
            segment_len_px=max(2, int(round(2.0 / px_um))),
            flags=morph_flags,
        )
        # Augment with um-converted views so the QuPath measurement table can
        # surface the unit scientists actually use. Original *_px arrays
        # stay too, for downstream pandas users who prefer pixel domain.
        if "total_length_px" in pw_morph:
            pw_morph["total_length_um"] = pw_morph["total_length_px"] * px_um
        if "mean_curvature_per_px" in pw_morph:
            pw_morph["mean_curvature_per_um"] = pw_morph["mean_curvature_per_px"] / max(px_um, 1e-9)
        if "gap_mean_px" in pw_morph:
            pw_morph["gap_mean_um"] = pw_morph["gap_mean_px"] * px_um
        if "gap_max_px" in pw_morph:
            pw_morph["gap_max_um"] = pw_morph["gap_max_px"] * px_um
        window_grid["morphometrics"] = pw_morph

    # ---- Coverage gate ----
    # Mask out windows whose fiber coverage is below the user threshold. The
    # rectangular window grid extends past the actual analysis zone for
    # rounded annotations -- their corners would otherwise show 'near zero'
    # values that look like real data but are just absence of zone. We
    # compute the invalid mask once and apply NaN to every numeric metric
    # array; the heatmap renderer already paints NaN cells transparent, and
    # the io / Java sides filter out windows marked invalid.
    if window_grid is not None and min_window_cov_pct > 0:
        n_pix_grid = window_grid.get("n_pixels")
        win_px = int(window_grid.get("window_px", 0))
        window_area_px = float(win_px) * float(win_px) if win_px > 0 else 0.0
        if n_pix_grid is not None and window_area_px > 0:
            coverage_grid = 100.0 * n_pix_grid.astype(np.float64) / window_area_px
            invalid = coverage_grid < min_window_cov_pct
            window_grid["_invalid_below_coverage"] = invalid
            for key, arr in list(window_grid.items()):
                if key in ("_invalid_below_coverage", "window_px", "stride_px", "window_um", "grid_shape"):
                    continue
                if isinstance(arr, np.ndarray) and arr.shape == invalid.shape and arr.dtype.kind == "f":
                    arr[invalid] = np.nan
            tex_block = window_grid.get("texture")
            if isinstance(tex_block, dict):
                for k, v in tex_block.items():
                    if isinstance(v, np.ndarray) and v.shape == invalid.shape and v.dtype.kind == "f":
                        v[invalid] = np.nan
            morph_block_g = window_grid.get("morphometrics")
            if isinstance(morph_block_g, dict):
                for k, v in morph_block_g.items():
                    if isinstance(v, np.ndarray) and v.shape == invalid.shape and v.dtype.kind == "f":
                        v[invalid] = np.nan
            logger.info(
                "Coverage gate: %d/%d windows dropped at <%.1f%% coverage",
                int(invalid.sum()), int(invalid.size), min_window_cov_pct,
            )

    # ---- Sidecars ----
    if window_grid is not None:
        windows_json = os.path.join(out_dir, "windows.json")
        # Stamp the same provenance block into windows.json so a sidecar
        # loaded directly into pandas carries its versions/run-id/params
        # fingerprint (PI-tester M2).
        meta_for_windows = OrderedDict([
            ("versions", versions_block),
            ("run", run_block),
            ("parameters", parameters_echo),
        ])
        # Pass the dilated-zone area so per-window HDM can be normalised
        # against the same denominator as the per-annotation HDM scalar
        # (Scientist M2 schema reconcile).
        fio.save_windows_json(
            window_grid,
            windows_json,
            meta=meta_for_windows,
            zone_area_px=int(zone_mask.sum()),
            pixel_size_um=px_um,
        )
    if emit_npz_v and window_grid is not None:
        npz_path = os.path.join(out_dir, "window_metrics.npz")
        # Top-level ndarrays plus the flattened texture and morphometrics
        # sub-dicts. Without flattening, np.savez_compressed would skip the
        # nested arrays and a downstream NumPy loader would never see them.
        npz_arrays = {
            k: v for k, v in window_grid.items() if isinstance(v, np.ndarray)
        }
        tex_npz = window_grid.get("texture")
        if isinstance(tex_npz, dict):
            for k, v in tex_npz.items():
                if isinstance(v, np.ndarray):
                    npz_arrays[f"texture_{k}"] = v
        morph_npz = window_grid.get("morphometrics")
        if isinstance(morph_npz, dict):
            for k, v in morph_npz.items():
                if isinstance(v, np.ndarray):
                    npz_arrays[f"morph_{k}"] = v
        if npz_arrays:
            fio.save_window_metrics_npz(npz_arrays, npz_path)

    # ---- Overlays (PNGs) ----
    if emit_mask:
        rndr.render_fiber_mask(analysis_mask, os.path.join(out_dir, "fiber_mask_overlay.png"))
    if emit_str and st_on and window_grid is not None and "tortuosity" in window_grid:
        rndr.render_window_heatmap(
            window_grid["tortuosity"],
            window_grid,
            os.path.join(out_dir, "straightness_overlay.png"),
            rh, rw, cmap_name="viridis",
        )
    if emit_glcm and tex_on and window_grid is not None and "texture" in window_grid:
        # Render one overlay per enabled GLCM property rather than just the
        # single "heatmap_property" the user picked in Section 7. GLCM is a
        # collection of values, so "Show GLCM" with a single image was
        # ambiguous -- the panel now offers a dropdown of all rendered
        # properties. The heatmap_property is still emitted (for backwards
        # compat with consumers that hard-code that filename) and is the
        # initial selection on the panel.
        tex_per_window = window_grid["texture"]
        for prop_name, arr in tex_per_window.items():
            if arr is None:
                continue
            try:
                rndr.render_window_heatmap(
                    arr,
                    window_grid,
                    os.path.join(out_dir, f"texture_{prop_name}_overlay.png"),
                    rh, rw, cmap_name="magma",
                )
            except Exception as exc:
                logger.warning("Could not render texture overlay for %s: %s", prop_name, exc)

    if emit_morph_sum and "morphometrics" in result:
        summary_path = os.path.join(out_dir, "morphometrics_summary.txt")
        with open(summary_path, "w", encoding="ascii") as fh:
            for k, v in result["morphometrics"].items():
                if isinstance(v, dict):
                    fh.write(f"{k}:\n")
                    for kk, vv in v.items():
                        fh.write(f"  {kk}: {vv}\n")
                else:
                    fh.write(f"{k}: {v}\n")

    # Per-window morphometrics overlays. The "Show morphometrics" UI button
    # used to render one PNG (HDM) but the family has many measurements; we
    # now emit one heatmap per per-window morphometric quantity that
    # genuinely has a per-window value. Annotation-level scalars (branch
    # points, endpoints, total length, mean curvature, fractal dimension,
    # lacunarity per box, gap stats) have NO per-window equivalent and are
    # intentionally not rendered as heatmaps -- they are summary numbers
    # reported in results.json + the per-annotation card.
    if morph_on and window_grid is not None and emit_mask:
        zone_area_px_v = int(zone_mask.sum())
        # HDM heatmap (legacy single-file name kept for back-compat) ...
        rndr.render_morphometrics_overlay(
            window_grid,
            os.path.join(out_dir, "morphometrics_overlay.png"),
            rh,
            rw,
            zone_area_px=zone_area_px_v,
        )
        # ... plus property-specific morph overlays for the ComboBox picker.
        morph_per_window = OrderedDict()
        if "n_pixels" in window_grid and window_grid["n_pixels"] is not None:
            # Same data the HDM overlay uses, but rendered as the user-friendly
            # 0-100 fiber-coverage percentage (per window).
            n_pix_grid = window_grid["n_pixels"]
            window_area_px = float(window_grid["window_px"]) ** 2
            if window_area_px > 0:
                morph_per_window["fiber_coverage_percent"] = 100.0 * n_pix_grid.astype(float) / window_area_px
            morph_per_window["hdm"] = n_pix_grid.astype(float) / window_area_px if window_area_px > 0 else None
        if "n_fibers" in window_grid and window_grid["n_fibers"] is not None:
            morph_per_window["ridge_count"] = window_grid["n_fibers"]
        # Append every per-window array from the morphometrics sub-dict so
        # the panel's "Show morphometrics" ComboBox has a separate overlay
        # for each enabled metric. Per-box lacunarity arrays land here too
        # (key shape "lacunarity_box_<N>") -- the ComboBox label rendering
        # is fine with that key as-is.
        morph_extra = window_grid.get("morphometrics")
        if isinstance(morph_extra, dict):
            for k, arr in morph_extra.items():
                if isinstance(arr, np.ndarray):
                    morph_per_window[k] = arr
        for prop_name, arr in morph_per_window.items():
            if arr is None:
                continue
            try:
                rndr.render_window_heatmap(
                    arr,
                    window_grid,
                    os.path.join(out_dir, f"morph_{prop_name}_overlay.png"),
                    rh, rw, cmap_name="viridis",
                )
            except Exception as exc:
                logger.warning("Could not render morph overlay for %s: %s", prop_name, exc)

    # ---- JSON output ----
    if emit_json:
        fio.save_results_json(result, os.path.join(out_dir, "results.json"))

    # Strip np.ndarray entries from the result before JSON-encoding the
    # task.outputs payload (those go via sidecar files, not the IPC channel).
    def sanitize_json(obj):
        if isinstance(obj, dict):
            return {k: sanitize_json(v) for k, v in obj.items() if not isinstance(v, np.ndarray)}
        if isinstance(obj, list):
            return [sanitize_json(v) for v in obj]
        if isinstance(obj, np.ndarray):
            return None
        if isinstance(obj, (np.integer,)):
            return int(obj)
        if isinstance(obj, (np.floating,)):
            f = float(obj)
            return None if (np.isnan(f) or np.isinf(f)) else f
        if isinstance(obj, float):
            return None if (obj != obj or obj in (float("inf"), float("-inf"))) else obj
        return obj

    task.outputs["result_json"] = json.dumps(sanitize_json(result))
    logger.info("Fiber analysis complete")

except Exception as e:
    logger.error("Fiber analysis failed: %s", e, exc_info=True)
    task.outputs["result_json"] = json.dumps({"error": str(e)})
