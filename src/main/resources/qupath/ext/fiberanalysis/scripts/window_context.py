"""
Appose task: add neighbourhood context to one image's assembled window grid.

**Per image, never per tile.** A neighbourhood computed inside a tile is
truncated at every seam, and because the truncation is a function of position
the model learns the tiling. The feature extractor goes to the trouble of
overlapping its tiles precisely so this step has an unbroken grid to work on;
running it per tile would undo that.

Input is the base matrix Java assembled, as a shared-memory NDArray. Output is
an npz, because the number of context columns is a property of the feature set
and the radii, and making Java predict it in order to pre-allocate the output
array would duplicate the rule in two languages.

Inputs (Appose injected as variables):

  features        : NDArray float32 (n_windows, n_base), C order
  feature_names   : str, comma-joined, column order of `features`
  grid_h, grid_w  : int, the window lattice; grid_h * grid_w == n_windows
  radii           : str, comma-joined neighbourhood radii IN WINDOWS
                    (default "1,3")
  output_npz_path : str

Outputs:
    task.outputs['ok'] = '1', plus 'n_features' and 'feature_names' for the
    COMBINED matrix (base columns first, in their original order, then the
    context columns). The npz holds:

      features float32 (n_windows, n_base + n_context)
"""

import json
import logging

import numpy as np

logger = logging.getLogger("fiber.window_context")


def opt_get(name, default):
    return globals().get(name, default)


try:
    from fiberlib import wcontext

    names = [n for n in str(feature_names).split(",") if n]
    gh = int(grid_h)
    gw = int(grid_w)
    out_npz = str(output_npz_path)
    radii_raw = str(opt_get("radii", "1,3"))
    radii = tuple(int(r) for r in radii_raw.split(",") if r.strip())
    if not radii:
        raise ValueError("radii must name at least one neighbourhood radius")

    base = np.asarray(features.ndarray(), dtype=np.float64)
    if base.ndim != 2 or base.shape[1] != len(names):
        raise ValueError(
            f"features shape {base.shape} does not match {len(names)} feature names"
        )
    if base.shape[0] != gh * gw:
        raise ValueError(f"features has {base.shape[0]} rows but the grid is {gh}x{gw}")

    grids = {name: base[:, j].reshape(gh, gw) for j, name in enumerate(names)}
    ctx = wcontext.context_features(grids, radii=radii)

    ctx_names = sorted(ctx)
    combined_names = list(names) + ctx_names
    out = np.empty((gh * gw, len(combined_names)), dtype=np.float32)
    out[:, : len(names)] = base.astype(np.float32)
    for j, name in enumerate(ctx_names):
        out[:, len(names) + j] = np.asarray(ctx[name], dtype=np.float64).reshape(-1)

    np.savez_compressed(
        out_npz,
        features=out,
        grid_shape=np.array([gh, gw], dtype=np.int32),
    )
    with open(out_npz + ".names.json", "w", encoding="ascii") as fh:
        json.dump({"feature_names": combined_names, "grid_shape": [gh, gw]}, fh)

    logger.info(
        "window_context wrote %s (%d base + %d context = %d features over %dx%d windows)",
        out_npz,
        len(names),
        len(ctx_names),
        len(combined_names),
        gh,
        gw,
    )

    task.outputs["ok"] = "1"
    task.outputs["n_features"] = str(len(combined_names))
    task.outputs["feature_names"] = ",".join(combined_names)

except Exception as e:
    logger.error("window_context failed: %s", e, exc_info=True)
    task.outputs["ok"] = "0"
    task.outputs["error"] = str(e)
