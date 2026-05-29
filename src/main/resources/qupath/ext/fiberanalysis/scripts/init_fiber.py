"""
Appose worker initialization for Fiber Analysis.

Called once via pythonService.init() when the Python subprocess starts. Sets
up persistent globals that remain available across all task() calls.

CRITICAL: All output goes to sys.stderr, NOT sys.stdout. Appose uses stdout
for its JSON-based IPC protocol.

Inputs from the Java side (prepended to this script body):
    fiberlib_dir  : str, absolute path to the directory CONTAINING the
                    `fiberlib` package on disk (so sys.path append finds
                    `fiberlib`). Provided by ApposeFiberService.initialize().
"""
import sys
import os
import logging
import threading
import time

logging.basicConfig(
    level=logging.INFO,
    stream=sys.stderr,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("fiber.appose")

try:
    # Non-interactive matplotlib backend before any pyplot import.
    import matplotlib
    matplotlib.use("Agg")

    # Make the unpacked fiberlib package importable.
    if "fiberlib_dir" in globals() and fiberlib_dir not in sys.path:
        sys.path.insert(0, fiberlib_dir)
        logger.info("Added fiberlib_dir to sys.path: %s", fiberlib_dir)

    import numpy
    import scipy
    import skimage
    import fiberlib
    from fiberlib import _version as _fl_version

    fiberlib_version = getattr(_fl_version, "__version__", "unknown")
    logger.info("Fiber Analysis packages loaded successfully")
    logger.info("  fiberlib:     %s", fiberlib_version)
    logger.info("  numpy:        %s", numpy.__version__)
    logger.info("  scipy:        %s", scipy.__version__)
    logger.info("  scikit-image: %s", skimage.__version__)

    init_error = None

except Exception as e:
    logger.error("Failed to initialize fiberlib: %s", e, exc_info=True)
    fiberlib_version = "unknown"
    init_error = str(e)


# --- Parent process watcher (cleans up if QuPath dies) -------------------

def parent_alive(pid):
    """Check if a process with the given PID is still running.

    On Windows uses OpenProcess (os.kill(pid, 0) sends CTRL_C_EVENT and
    can crash the process -- documented project gotcha).
    """
    if sys.platform == "win32":
        import ctypes
        kernel32 = ctypes.windll.kernel32
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if handle:
            kernel32.CloseHandle(handle)
            return True
        return False
    try:
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True
    except OSError:
        return False


def watch_parent():
    """Exit if parent process (Java/QuPath) dies."""
    ppid = os.getppid()
    if ppid <= 1:
        return
    logger.info("Parent process watcher started (parent PID: %d)", ppid)
    while True:
        time.sleep(3)
        try:
            current_ppid = os.getppid()
            if current_ppid != ppid:
                logger.warning(
                    "Parent process changed (%d -> %d), exiting", ppid, current_ppid
                )
                os._exit(1)
            if not parent_alive(ppid):
                logger.warning("Parent process %d no longer exists, exiting", ppid)
                os._exit(1)
        except Exception as e:
            logger.debug("Parent watcher check error: %s", e)


# Use a non-underscore-prefixed name only where it doesn't need to be exported.
# Appose init exports STRIP underscore-prefixed globals, so `parent_alive`
# above (used by the daemon thread) is left without an underscore to be safe.
parent_watcher = threading.Thread(target=watch_parent, daemon=True)
parent_watcher.start()
