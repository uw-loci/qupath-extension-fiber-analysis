"""
Smoke test for the bundled fiberlib package.

Run by `./gradlew check` via the fiberlibPythonTest task, which skips
loudly when the interpreter on PATH cannot import pytest / numpy /
scikit-image. Also runnable by hand with `pytest src/test/python` against
any env that has the scientific stack (the qupath-fiber-analysis pixi env
works). fiberlib has no Java coverage, so this file is the only thing
testing it.

Builds a synthetic image with horizontal stripes in the top half and
vertical stripes in the bottom half, then checks the windowed orientation
statistics and a few other surface invariants.
"""

import os
import sys

import numpy as np
import pytest

# Make the bundled fiberlib importable when running this file directly.
THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(
        THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"
    )
)
sys.path.insert(0, PKG_ROOT)

from fiberlib import dilation, windows  # noqa: E402
from fiberlib import segmentation, straightness, morphometrics, texture  # noqa: E402

# 2 px stripes on an 8 px pitch. The gap width is the load-bearing number:
# segment_internal finishes with binary_closing(disk(1)), which bridges any gap
# of 2 px or less. The original fixture used a 4 px pitch, so closing welded
# every stripe to its neighbour and coverage came out at 0.985 against an
# assert of 0.2 < coverage < 0.6 -- the suite had been failing on its own
# fixture, and nothing ran it to notice.
_STRIPE_WIDTH = 2
_STRIPE_PITCH = 8


def synthetic_stripes(h=200, w=200, background=0.18, foreground=0.82, noise=0.04):
    """Top half: horizontal stripes; bottom half: vertical stripes.

    Grey levels rather than a pure {0, 1} image, with a little seeded noise, so
    Otsu is picking a threshold from a real bimodal histogram instead of
    landing on it because the background happens to be exactly zero.
    """
    rng = np.random.default_rng(12345)
    img = np.full((h, w), background, dtype=np.float32)
    for r in range(0, h // 2, _STRIPE_PITCH):
        img[r : r + _STRIPE_WIDTH, :] = foreground
    for c in range(0, w, _STRIPE_PITCH):
        img[h // 2 :, c : c + _STRIPE_WIDTH] = foreground
    img += rng.normal(0.0, noise, img.shape).astype(np.float32)
    return np.clip(img, 0.0, 1.0)


def test_segment_internal_finds_mostly_stripes():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    assert mask.dtype == np.bool_
    # 2 px of every 8 is stripe, so a correct segmentation lands near 0.25.
    coverage = mask.mean()
    assert 0.2 < coverage < 0.6


def test_dilation_outside_zone_grows_by_width():
    h, w = 100, 100
    boundary = np.zeros((h, w), dtype=bool)
    boundary[40:60, 40:60] = True
    info = dilation.compute_border_zone_mask(boundary, dilation_px=5, mode="outside")
    zone = info["zone_mask"]
    # The outside zone should not overlap the boundary itself.
    assert not (zone & boundary).any()
    # And it should have nonzero area.
    assert zone.sum() > 0


def test_morphometrics_smoke():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    skel = morphometrics.build_skeleton(mask)
    bp, ep = morphometrics.branch_endpoint_counts(skel)
    assert bp >= 0 and ep >= 0
    fd = morphometrics.fractal_dimension(skel, [2, 4, 8, 16, 32])
    assert 0.5 < fd < 2.5
    lac = morphometrics.lacunarity(mask, [4, 8, 16])
    assert lac and all(v >= 1.0 for v in lac.values() if v == v)
    hdm_value = morphometrics.hdm(mask, np.ones_like(mask))
    assert 0.0 <= hdm_value <= 1.0


def test_texture_smoke():
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    skel, angles = straightness.skeletonize_and_tangents(mask)
    grid = windows.compute_windows(angles, mask, window_px=20, stride_px=20)
    out = texture.compute_glcm_window(
        img,
        mask,
        grid,
        quant_levels=16,
        distances_px=[1],
        props=["contrast", "energy"],
    )
    assert "per_window" in out
    assert set(out["per_window"].keys()) == {"contrast", "energy"}


if __name__ == "__main__":
    test_segment_internal_finds_mostly_stripes()
    test_windows_axial_stats()
    test_dilation_outside_zone_grows_by_width()
    test_morphometrics_smoke()
    test_texture_smoke()
    print("OK -- fiberlib smoke tests passed.")


def test_manual_threshold_is_an_absolute_gray_level():
    """A manual cut must land on the same raw value whatever the region range.

    Before fiberlib 0.2.6 segment_internal min-max stretched every region
    before thresholding, so manual=4500 cut at a different raw value in each
    one -- it was a percentile of the region's own range, not a gray level.
    """
    for lo, hi in [(0, 65535), (200, 12000), (3000, 9000)]:
        img = np.linspace(lo, hi, 128 * 128).astype(np.uint16).reshape(128, 128)
        mask = segmentation.segment_internal(
            image=img,
            channel="raw",
            threshold_method="manual",
            manual_threshold=4500,
            ridge_filter="none",
            sigma_min=1.0,
            sigma_max=1.0,
            sigma_step=1.0,
            min_fiber_area_px=0,
        )
        assert (
            img[mask].min() == 4500
        ), f"range [{lo},{hi}] cut at {img[mask].min()}, not 4500"


def test_manual_threshold_matches_bit_depth():
    """The same fraction of full scale must mean the same thing at 8 and 16 bit."""
    img8 = np.linspace(0, 255, 128 * 128).astype(np.uint8).reshape(128, 128)
    img16 = np.linspace(0, 65535, 128 * 128).astype(np.uint16).reshape(128, 128)
    common = dict(
        channel="raw",
        threshold_method="manual",
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=1.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    m8 = segmentation.segment_internal(image=img8, manual_threshold=128, **common)
    m16 = segmentation.segment_internal(image=img16, manual_threshold=32768, **common)
    assert abs(m8.mean() - m16.mean()) < 0.01


def test_value_channel_scales_by_bit_depth():
    """rgb_to_value must not assume 8-bit; the texture path clips at 1.0."""
    from fiberlib.pipeline import rgb_to_value

    assert rgb_to_value(np.full((4, 4, 3), 12000, np.uint16))[0, 0] == pytest.approx(
        12000 / 65535, abs=1e-4
    )
    assert rgb_to_value(np.full((4, 4, 3), 128, np.uint8))[0, 0] == pytest.approx(
        128 / 255, abs=1e-4
    )


def test_glcm_quantise_trusts_its_unit_contract():
    """quantise re-scaling by 255 is what forced the compensating bug upstream."""
    assert texture.quantise(np.full((4, 4), 0.25, np.float32), 16)[0, 0] == 4
    assert texture.quantise(np.full((4, 4), 1.0, np.float32), 16)[0, 0] == 15


def test_whole_zone_is_the_entire_annotation_interior():
    """'whole' must cover the annotation, not a band at its edge.

    The band modes all measure from the boundary. When the annotation IS the
    tissue rather than an outline with stroma around it, 'outside' lands in
    background: on real acquired-area annotations it reported 0.56% fiber
    coverage where the region itself was 43.3% fiber.
    """
    import numpy as np
    from fiberlib import dilation as dil

    mask = np.zeros((100, 100), dtype=bool)
    mask[20:80, 20:80] = True  # 60x60 annotation = 3600 px

    whole = dil.compute_border_zone_mask(mask, dilation_px=5, mode="whole")["zone_mask"]
    assert whole.sum() == 3600
    assert np.array_equal(whole, mask)

    # A band mode covers far less, and 'outside' covers none of the annotation.
    inside = dil.compute_border_zone_mask(mask, dilation_px=5, mode="inside")["zone_mask"]
    outside = dil.compute_border_zone_mask(mask, dilation_px=5, mode="outside")["zone_mask"]
    assert inside.sum() < whole.sum()
    assert (outside & mask).sum() == 0


def test_whole_zone_ignores_the_border_width():
    """dilation_px is meaningless for 'whole'; the result must not move."""
    import numpy as np
    from fiberlib import dilation as dil

    mask = np.zeros((60, 60), dtype=bool)
    mask[10:50, 10:50] = True
    a = dil.compute_border_zone_mask(mask, dilation_px=1, mode="whole")["zone_mask"]
    b = dil.compute_border_zone_mask(mask, dilation_px=999, mode="whole")["zone_mask"]
    assert np.array_equal(a, b)


def test_unknown_zone_mode_still_raises():
    import numpy as np
    import pytest
    from fiberlib import dilation as dil

    with pytest.raises(ValueError, match="whole"):
        dil.compute_border_zone_mask(np.ones((10, 10), dtype=bool), 3, mode="sideways")


# ---- Orientation regressions ----------------------------------------------
#
# Every test below targets a defect that shipped through v0.3.2 and that the
# original `test_windows_axial_stats` could not catch, because it asserted
# only that the order parameter was HIGH. When the angle estimator collapsed
# to a constant, every window agreed perfectly on the wrong answer, so the
# order parameter read ~1.0 and the assertion passed. Assert the angle VALUE,
# not just its concentration.


def _axial_mean_deg(angles_deg):
    """Circular mean of a 180-periodic angle field, ignoring NaN."""
    a = np.asarray(angles_deg, dtype=np.float64)
    a = a[np.isfinite(a)]
    if a.size == 0:
        return float("nan")
    two = np.deg2rad(a) * 2.0
    return float(np.rad2deg(0.5 * np.arctan2(np.sin(two).mean(), np.cos(two).mean())) % 180.0)


def _axial_err_deg(measured, expected):
    d = abs(float(measured) - float(expected)) % 180.0
    return min(d, 180.0 - d)


def _bars(angle_deg, n=192, pitch=12, width=3):
    """A field of parallel bars at `angle_deg` (0 = horizontal, 90 = vertical)."""
    yy, xx = np.mgrid[:n, :n]
    # Offset along the bar NORMAL; the bars themselves run at angle_deg.
    rad = np.deg2rad(angle_deg)
    normal = (xx - n / 2.0) * -np.sin(rad) + (yy - n / 2.0) * np.cos(rad)
    return (np.mod(normal, float(pitch)) < float(width))


@pytest.mark.parametrize("true_deg", [0.0, 30.0, 45.0, 60.0, 90.0, 135.0])
def test_tangents_recover_known_fiber_angles(true_deg):
    # Through v0.3.2 the tangent was read from the gradient AT the skeleton
    # pixel, which sits on the ridge crest of the smoothed mask where the
    # gradient vanishes: |grad| measured 0.001 on-skeleton against 0.145 one
    # pixel out on the flank, so arctan2(gx, -gy) degenerated to
    # arctan2(0, 0) == 0 and every fiber read as horizontal. Only the 0 deg
    # case passed, by accident of the degenerate value being 0.
    mask = _bars(true_deg)
    _, angles = straightness.skeletonize_and_tangents(mask)
    err = _axial_err_deg(_axial_mean_deg(angles), true_deg)
    assert err < 3.0, f"fiber angle {true_deg} deg read as {_axial_mean_deg(angles)}"


def test_angle_field_is_dense_over_the_fiber_mask():
    # windows.compute_windows gates a window on min_pixels, defaulting to 10%
    # of the window AREA. A skeleton is one pixel wide and can never reach
    # that, so a skeleton-only angle field silently emptied the axial stats:
    # 7,891 of 7,935 scored windows on the MH_Colon run had a null
    # mean_angle_deg and order_parameter.
    mask = _bars(30.0)
    skel, angles = straightness.skeletonize_and_tangents(mask)
    finite = np.isfinite(angles)
    assert not finite[~mask].any(), "angles must be NaN outside the fiber mask"
    assert finite[mask].all(), "angles must be defined on every fiber pixel"
    # The point of the test: far denser than the skeleton it used to follow.
    # 3 px bars skeletonise to 1 px, so the mask carries roughly three times
    # the samples -- and the ratio grows with fiber width.
    assert int(finite.sum()) > 2 * int(skel.sum())


def test_windows_report_the_actual_stripe_orientations():
    # The fixture is horizontal stripes on top, vertical on the bottom. The
    # old assertion (mean order parameter > 0.4) held even when every angle
    # collapsed to 0, because agreeing on one wrong value is still agreement.
    img = synthetic_stripes()
    rgb = np.stack([img, img, img], axis=-1)
    mask = segmentation.segment_internal(
        image=rgb,
        channel="raw",
        threshold_method="otsu",
        manual_threshold=128,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=3.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
    )
    _, angles = straightness.skeletonize_and_tangents(mask)
    h = angles.shape[0]
    # Stay clear of the seam where the two stripe fields meet.
    top = _axial_mean_deg(angles[: h // 2 - 20])
    bottom = _axial_mean_deg(angles[h // 2 + 20 :])
    assert _axial_err_deg(top, 0.0) < 10.0, f"top half should read horizontal, got {top}"
    assert _axial_err_deg(bottom, 90.0) < 10.0, f"bottom half should read vertical, got {bottom}"


def test_radon_recovers_a_known_dominant_angle():
    # theta_star_deg was the argmax of a profile that is constant by
    # construction, so it reported numerical noise: errors of 63 to 89 deg on
    # the known-angle phantoms, and the same ~148 deg for true 30, 45 and 60.
    mask = _bars(60.0)
    scalar = mask.astype(np.float32)
    out = straightness.compute_radon_scalar(scalar, mask)
    assert _axial_err_deg(out["theta_star_deg"], 60.0) < 5.0


def test_radon_alignment_separates_aligned_from_isotropic():
    # The Radon transform conserves mass, so every column of the sinogram
    # sums to the same total and sino.sum(axis=0) is flat in theta. Using
    # that sum as the angular profile pinned ai at 0.1018 (the isotropic
    # floor is exactly 0.1), entropy at 7.4918 against a 7.4919 ceiling, and
    # fwhm_theta_deg at 180.0 -- for every image ever analysed.
    aligned = _bars(30.0)
    rng = np.random.default_rng(7)
    iso = np.zeros((192, 192), dtype=bool)
    for ang in rng.uniform(0.0, 180.0, 40):
        iso |= _bars(float(ang), pitch=96, width=3)

    a = straightness.compute_radon_scalar(aligned.astype(np.float32), aligned)
    i = straightness.compute_radon_scalar(iso.astype(np.float32), iso)

    assert a["ai"] > 2.0 * i["ai"], f"aligned ai {a['ai']} vs isotropic {i['ai']}"
    assert a["entropy"] < i["entropy"] - 0.5
    assert a["fwhm_theta_deg"] < i["fwhm_theta_deg"]
    assert a["pmr"] > i["pmr"]
    # The isotropic case must sit near, not at, the analytic floor of 0.1.
    assert i["ai"] < 0.3


# ---- Skeleton topology regressions -----------------------------------------


def test_staircase_corner_is_not_a_branch_point():
    # A rasterised diagonal runs as a staircase, and a staircase corner has
    # THREE neighbours while being topologically ordinary. Counting
    # neighbours called every corner a branch: 65% to 97% of the branch
    # points on the waviness phantoms were corners, and wav-00 -- straight
    # horizontal fibers with 69 real junctions -- reported 2,245.
    t = np.zeros((5, 6), dtype=bool)
    t[1, 1] = t[1, 2] = t[2, 2] = t[2, 3] = True
    assert straightness.neighbour_counts(t)[1, 2] == 3
    assert straightness.crossing_numbers(t)[1, 2] == 2
    assert not straightness.junction_mask(t)[1, 2]
    branches, _ = morphometrics.branch_endpoint_counts(t)
    assert branches == 0


def test_true_junction_is_still_found():
    t = np.zeros((7, 7), dtype=bool)
    t[3, 1:6] = True
    t[4:7, 3] = True
    assert straightness.crossing_numbers(t)[3, 3] == 3
    assert straightness.junction_mask(t)[3, 3]
    branches, endpoints = morphometrics.branch_endpoint_counts(t)
    assert branches == 1
    assert endpoints == 3


def test_tracer_links_a_fiber_through_a_crossing():
    # Two fibers crossing at right angles. Cutting at the junction gives four
    # short arms; following direction through it gives back two fibers.
    n = 81
    m = np.zeros((n, n), dtype=bool)
    m[40, 5:76] = True   # horizontal
    m[5:76, 40] = True   # vertical
    fibers = straightness.trace_fibers(m)
    long_ones = [f for f in fibers if len(f) >= 50]
    assert len(long_ones) == 2, f"expected 2 linked fibers, got {[len(f) for f in fibers]}"
    for f in long_ones:
        assert straightness.segment_tortuosity(f) > 0.95


def test_tracer_refuses_a_sharp_join():
    # An L: the two arms meet at 90 deg, which is past max_turn_deg, so they
    # must NOT be welded into one fiber.
    n = 61
    m = np.zeros((n, n), dtype=bool)
    m[30, 10:51] = True
    m[30:51, 30] = True
    fibers = straightness.trace_fibers(m, max_turn_deg=45.0)
    assert all(len(f) < 60 for f in fibers), "a 90 deg corner was linked as one fiber"


def test_tracer_covers_the_skeleton_without_duplicating_it():
    rng = np.random.default_rng(3)
    n = 160
    m = np.zeros((n, n), dtype=bool)
    for _ in range(12):
        y = int(rng.integers(10, n - 10))
        x0 = int(rng.integers(0, 40))
        m[y, x0:x0 + 100] = True
    for _ in range(12):
        x = int(rng.integers(10, n - 10))
        y0 = int(rng.integers(0, 40))
        m[y0:y0 + 100, x] = True
    sk = straightness.skeletonize_and_tangents(m)[0]
    fibers = straightness.trace_fibers(sk)
    covered = set()
    for f in fibers:
        covered.update(f)
    # Junction pixels are shared by the links that meet there, so coverage is
    # near-total rather than exact; nothing should be missed wholesale.
    assert len(covered) >= 0.95 * int(sk.sum())


def test_straightness_summary_reports_the_fiber_length_it_measured():
    # median_fiber_len_px is the number that says whether the other
    # statistics describe fibers or fragments, so it must be present.
    mask = _bars(0.0, n=192, pitch=16, width=3)
    out = straightness.compute_skeleton_tortuosity(mask, min_branch_px=20)
    for key in (
        "mean_tortuosity",
        "mean_straightness_len",
        "straightness_p10",
        "straightness_median",
        "straightness_sd",
        "wavy_fraction",
        "median_fiber_len_px",
        "n_segments",
    ):
        assert key in out, f"missing {key}"
    # Straight bars: chord/arc near 1, nothing wavy, fibers span the image.
    assert out["mean_tortuosity"] > 0.97
    assert out["wavy_fraction"] == 0.0
    assert out["median_fiber_len_px"] > 100


def test_per_window_and_per_annotation_branch_counts_agree():
    # Through v0.3.4 these were computed by different rules: the
    # per-annotation path used the Rutovitz crossing number, the per-window
    # path a raw neighbour count. On a tiled region the per-window counts
    # must sum to the per-annotation total, or one of them is wrong -- and
    # it was the per-window one, by 2.9x on the wav-25 phantom.
    mask = _bars(30.0, n=256, pitch=10, width=3) | _bars(120.0, n=256, pitch=10, width=3)
    skel, _ = straightness.skeletonize_and_tangents(mask)

    w = 64
    hw, ww = skel.shape[0] // w, skel.shape[1] // w
    sub = skel[: hw * w, : ww * w]

    ann_branches, ann_endpoints = morphometrics.branch_endpoint_counts(sub)
    out = morphometrics.per_window_morphometrics(
        sub, mask[: hw * w, : ww * w], w, w, (hw, ww),
        lac_boxes=(), fd_boxes=(), flags={"branch": True, "endpoints": True},
    )
    assert int(out["branch_points"].sum()) == ann_branches
    assert int(out["endpoints"].sum()) == ann_endpoints

    # And the branch count must be the crossing-number one, not the
    # neighbour-count one, which is larger on any rasterised diagonal.
    naive = int(((straightness.neighbour_counts(sub) >= 3) & sub).sum())
    assert ann_branches < naive, "fixture has no staircase corners; it cannot detect the defect"
