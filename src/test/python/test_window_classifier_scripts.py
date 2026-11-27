"""
The train and apply Appose scripts, run end to end the way Appose runs them.

The fiberlib modules each have their own tests; these exist because the
defects were in how the two scripts fit together: training drops columns
the viability check excluded, and apply must read the same columns back.
"""

import json
import os
import sys
import types

import numpy as np
import pytest

THIS_DIR = os.path.abspath(os.path.dirname(__file__))
PKG_ROOT = os.path.abspath(
    os.path.join(
        THIS_DIR, "..", "..", "main", "resources", "qupath", "ext", "fiberanalysis"
    )
)
SCRIPTS = os.path.join(PKG_ROOT, "scripts")
sys.path.insert(0, PKG_ROOT)

pytest.importorskip("sklearn", reason="scikit-learn is only in the Appose env and CI")

GEOMETRY = {"window_um": 50.0, "pixel_size_um": 0.5, "window_px": 100, "stride_px": 50}


class _Nd:
    """Stands in for an Appose NDArray."""

    def __init__(self, arr):
        self._arr = arr

    def ndarray(self):
        return self._arr


def _run(script, **inputs):
    task = types.SimpleNamespace(outputs={})
    g = {"__name__": "__appose__", "task": task}
    for k, v in inputs.items():
        g[k] = _Nd(v) if isinstance(v, np.ndarray) else v
    path = os.path.join(SCRIPTS, script + ".py")
    with open(path, encoding="utf-8") as fh:
        exec(compile(fh.read(), path, "exec"), g)
    return task.outputs


def _training_set(n_slides=4, per_class=30, seed=0):
    """Four slides, two classes, and a column the viability check excludes.

    `tortuosity_median` is missing in most windows, as it was in 7,891 of
    7,935 windows of a real run, so training drops it.
    """
    rng = np.random.default_rng(seed)
    rows, labels, groups, anns = [], [], [], []
    ann = 0
    for s in range(n_slides):
        for cls in (0, 1):
            for _a in range(2):
                ann += 1
                k = per_class // 2
                cov = rng.uniform(0.1, 0.3, k) + 0.4 * cls
                order = rng.uniform(0, 1, k)
                width = rng.normal(2.0, 0.3, k)
                tort = np.where(rng.random(k) < 0.9, np.nan, rng.uniform(1.0, 1.5, k))
                rows.append(np.column_stack([tort, cov, order, width]))
                labels.append(np.full(k, cls))
                groups.append(np.full(k, s))
                anns.append(np.full(k, ann))
    names = [
        "tortuosity_median",
        "coverage_fraction",
        "order_parameter",
        "width_mean_um",
    ]
    return (
        np.vstack(rows).astype(np.float32),
        np.concatenate(labels).astype(np.int32),
        np.concatenate(groups).astype(np.int32),
        np.concatenate(anns).astype(np.int32),
        names,
    )


def _train(tmp_path, **extra):
    x, y, g, a, names = _training_set()
    model_dir = str(tmp_path / "model")
    inputs = dict(
        features=x,
        labels=y,
        groups=g,
        annotations=a,
        feature_names=",".join(names),
        class_names="Dense,Loose",
        model_dir=model_dir,
        geometry_json=json.dumps(GEOMETRY),
        n_perm=20,
        n_boot=200,
        imp_repeats=1,
    )
    inputs.update(extra)
    out = _run("train_window_classifier", **inputs)
    assert out.get("ok") == "1", out.get("error")
    return model_dir, out, x, names


@pytest.mark.parametrize("kind", ["hist_gradient_boosting", "random_forest"])
def test_a_model_trained_with_an_excluded_column_applies_to_the_full_feature_set(
    tmp_path, kind
):
    model_dir, train_out, x, names = _train(tmp_path, model_kind=kind)
    assert train_out["verdict"] != "BLOCK"
    with open(os.path.join(model_dir, "metadata.json"), encoding="ascii") as fh:
        meta = json.load(fh)
    # Train records what it was given and what it left out, not only what it kept.
    assert meta["input_feature_names"] == names
    assert "tortuosity_median" in meta["excluded_features"]

    npz = str(tmp_path / "applied.npz")
    out = _run(
        "apply_window_classifier",
        features=x,
        feature_names=",".join(names),
        model_dir=model_dir,
        geometry_json=json.dumps(GEOMETRY),
        allow_override=False,
        output_npz_path=npz,
    )
    assert out.get("ok") == "1", out.get("error")
    got = np.load(npz)
    assert got["proba"].shape == (x.shape[0], 2)
    # The novelty reference was saved from the kept columns; scoring it against
    # the full matrix made the channel NaN everywhere.
    assert np.isfinite(got["novelty_p"]).any()
    assert "novelty could not be computed" not in out["warnings"]


def test_apply_reads_columns_by_name_not_by_position(tmp_path):
    model_dir, _out, x, names = _train(tmp_path)
    npz_a = str(tmp_path / "a.npz")
    npz_b = str(tmp_path / "b.npz")
    common = dict(
        model_dir=model_dir, geometry_json=json.dumps(GEOMETRY), allow_override=False
    )
    out_a = _run(
        "apply_window_classifier",
        features=x,
        feature_names=",".join(names),
        output_npz_path=npz_a,
        **common,
    )
    order = [3, 1, 0, 2]
    out_b = _run(
        "apply_window_classifier",
        features=np.ascontiguousarray(x[:, order]),
        feature_names=",".join(names[i] for i in order),
        output_npz_path=npz_b,
        **common,
    )
    assert out_a["ok"] == "1" and out_b["ok"] == "1", (
        out_a.get("error"),
        out_b.get("error"),
    )
    np.testing.assert_allclose(np.load(npz_a)["proba"], np.load(npz_b)["proba"])


def test_apply_refuses_a_matrix_missing_a_trained_column(tmp_path):
    model_dir, _out, x, names = _train(tmp_path)
    out = _run(
        "apply_window_classifier",
        features=np.ascontiguousarray(x[:, [0, 1, 3]]),
        feature_names=",".join(names[i] for i in [0, 1, 3]),
        model_dir=model_dir,
        geometry_json=json.dumps(GEOMETRY),
        allow_override=True,
        output_npz_path=str(tmp_path / "x.npz"),
    )
    assert out["ok"] == "0"
    assert "order_parameter" in out["error"]


def test_zero_permutation_shuffles_reports_not_evaluated_instead_of_crashing(tmp_path):
    _dir, out, _x, _names = _train(tmp_path, n_perm=0)
    assert out["verdict"] in ("WARN", "REFUSE")
    assert "NOT EVALUATED" in out["summary"]


def test_zero_bootstrap_resamples_cannot_leave_the_verdict_ok(tmp_path):
    _dir, out, _x, _names = _train(tmp_path, n_boot=0)
    assert out["verdict"] != "OK"
    assert "NOT EVALUATED" in out["summary"]
    assert "bootstrap" in out["summary"].lower()


def test_window_features_uses_the_lattice_java_sends(tmp_path):
    # A 30 px window at 25 % overlap: Java's Math.round gives stride 23,
    # Python's half-to-even round gave 22, and Java placed the tiles on 23.
    from PIL import Image

    img = np.zeros((120, 160), dtype=np.uint8)
    img[:, 40:60] = 200
    img[70:90, :] = 200
    png = tmp_path / "tile.png"
    Image.fromarray(img).save(png)
    npz = str(tmp_path / "tile.npz")
    out = _run(
        "window_features",
        region_image_path=str(png),
        region_w=160,
        region_h=120,
        pixel_size_um=0.5,
        window_px=30,
        stride_px=23,
        seg_channel="Raw intensity",
        threshold_method="manual",
        manual_threshold=100,
        ridge_filter="none",
        sigma_min=1.0,
        sigma_max=2.0,
        sigma_step=1.0,
        min_fiber_area_px=0,
        include_intensity=False,
        output_npz_path=npz,
    )
    assert out.get("ok") == "1", out.get("error")
    assert out["stride_px"] == "23" and out["window_px"] == "30"
    assert int(np.load(npz)["stride_px"]) == 23


def test_a_blocked_retrain_removes_the_previous_model_of_that_name(tmp_path):
    model_dir, _out, x, names = _train(tmp_path)
    assert os.path.isfile(os.path.join(model_dir, "model.joblib"))
    # Every label on one slide: grouped validation is impossible, so BLOCK.
    _x2, y, _g, a, _n = _training_set()
    out = _run(
        "train_window_classifier",
        features=x,
        labels=y,
        groups=np.zeros_like(y),
        annotations=a,
        feature_names=",".join(names),
        class_names="Dense,Loose",
        model_dir=model_dir,
        geometry_json=json.dumps(GEOMETRY),
        n_perm=20,
        n_boot=200,
    )
    assert out["verdict"] == "BLOCK"
    for stale in ("model.joblib", "metadata.json", "train_reference.npz"):
        assert not os.path.exists(os.path.join(model_dir, stale)), stale
    assert os.path.isfile(os.path.join(model_dir, "report.json"))
