/*
 * Copyright 2026 Mike Nelson and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package qupath.ext.fiberanalysis.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.apposed.appose.Appose;
import org.apposed.appose.Environment;
import org.apposed.appose.Service;
import org.apposed.appose.Service.ResponseType;
import org.apposed.appose.Service.Task;
import org.apposed.appose.TaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;

/**
 * Singleton managing the Appose Environment and Python Service lifecycle for
 * the bundled {@code fiberlib} Python analysis module.
 *
 * <p>Structurally a direct port of
 * {@code qupath.ext.ppm.service.ApposePPMService} with two key differences:
 * <ol>
 *     <li>No pip-installed library. The {@code fiberlib} package is bundled in
 *     the extension JAR resources and unpacked to the env directory on first
 *     init / when its version marker changes.</li>
 *     <li>Library install method ({@link #installFiberLibrary}) only runs
 *     {@code pixi install} -- it never pip-installs anything.</li>
 * </ol>
 *
 * <p>All Appose calls go through {@link #withExtensionClassLoader} to satisfy
 * the TCCL requirement (Appose ServiceLoader / Groovy JSON / SharedMemory all
 * need the extension classloader).
 */
public class ApposeFiberService {

    private static final Logger logger = LoggerFactory.getLogger(ApposeFiberService.class);

    private static final String RESOURCE_BASE = "qupath/ext/fiberanalysis/";
    private static final String PIXI_TOML_RESOURCE = RESOURCE_BASE + "pixi.toml";
    // Bundled lockfile pinning the full transitive dependency tree. Installed
    // with --frozen so updates install the exact tested versions instead of
    // re-resolving against current conda-forge/PyPI. Regenerate via
    // tools/regen-pixi-lock.sh when pixi.toml changes.
    private static final String PIXI_LOCK_RESOURCE = RESOURCE_BASE + "pixi.lock";
    private static final String SCRIPTS_BASE = RESOURCE_BASE + "scripts/";
    private static final String FIBERLIB_RESOURCE_BASE = RESOURCE_BASE + "fiberlib/";
    private static final String ENV_NAME = "qupath-fiber-analysis";

    /**
     * Required version of the bundled fiberlib module.
     *
     * <p>Bump this whenever a JAR-bundled fiberlib script changes in a way the
     * Java side relies on. The on-disk {@code fiberlib/_version.py} is compared
     * to this string on init; mismatch triggers a re-extraction of the bundled
     * package over the on-disk copy.
     */
    public static final String REQUIRED_FIBERLIB_VERSION = "0.2.5";

    private static ApposeFiberService instance;

    private Environment environment;
    private Service pythonService;
    private boolean initialized;
    private String installedFiberlibVersion;
    private String initError;
    private Thread shutdownHook;

    /**
     * On-disk directory CONTAINING the unpacked {@code fiberlib} package.
     * Held as a field (not just a local in {@link #initialize}) because
     * {@link #restartWorker()} has to rebuild the same init script for the
     * fresh worker.
     */
    private Path fiberlibDir;

    private ApposeFiberService() {}

    public static synchronized ApposeFiberService getInstance() {
        if (instance == null) {
            instance = new ApposeFiberService();
        }
        return instance;
    }

    /**
     * Fast filesystem check -- does the env directory contain a built .pixi?
     */
    public static boolean isEnvironmentBuilt() {
        ApposeFiberService svc = instance;
        if (svc != null && svc.environment != null) {
            Path envDir = Path.of(svc.environment.base());
            return Files.isDirectory(envDir.resolve(".pixi"));
        }
        Path envDir = getEnvironmentPath();
        return Files.isDirectory(envDir.resolve(".pixi"));
    }

    public static Path getEnvironmentPath() {
        ApposeFiberService svc = instance;
        if (svc != null && svc.environment != null) {
            return Path.of(svc.environment.base());
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "appose", ENV_NAME);
    }

    public String getInstalledFiberlibVersion() {
        return installedFiberlibVersion;
    }

    /**
     * True when the service is initialized AND its Python worker subprocess is
     * still alive.
     *
     * <p>The {@code isAlive()} half matters: a worker that has died (OOM-killed,
     * crashed on a native import, killed by the user) leaves {@code initialized}
     * true and {@code pythonService} non-null, so without this check every later
     * call in the session fails inside Appose with no path to recovery. Callers
     * that see {@code false} here re-run {@link #initialize} (the env is already
     * built, so that is fast) or hit {@link #restartWorker()} via
     * {@link #runTask}'s recovery path.
     *
     * <p>Note {@code Service.isAlive()} is false both for a worker that died and
     * for one whose subprocess has not been spawned yet. Both
     * {@link #initialize} and {@link #restartWorker()} spawn the subprocess
     * before returning (via a verify / ping task), so a live service always
     * reports true here.
     */
    public boolean isAvailable() {
        Service svc = pythonService;
        return initialized && initError == null && svc != null && svc.isAlive();
    }

    public String getInitError() {
        return initError;
    }

    /**
     * Builds the pixi environment, unpacks fiberlib resources, and starts the
     * Python service. Idempotent.
     *
     * <p>If a previous initialize succeeded but the worker subprocess has since
     * died, this recreates just the worker rather than reporting "already
     * initialized" and leaving the session permanently broken.
     */
    public synchronized void initialize(Consumer<String> statusCallback) throws IOException {
        if (initialized) {
            if (isAvailable()) {
                report(statusCallback, "Already initialized");
                return;
            }
            // Initialized, but the worker subprocess is gone. The pixi env and
            // the unpacked fiberlib are still on disk, so a worker restart is
            // all that is needed -- and it is seconds, not minutes.
            logger.warn("Fiber Analysis Python worker is not alive; recreating it");
            report(statusCallback, "Python worker died; restarting it...");
            restartWorker();
            return;
        }

        try {
            report(statusCallback, "Loading environment configuration...");
            logger.info("Initializing Fiber Analysis Appose environment...");

            String pixiToml = loadResource(PIXI_TOML_RESOURCE);
            String pixiLock = loadResource(PIXI_LOCK_RESOURCE);

            // ALL Appose operations require the extension classloader as TCCL.
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeFiberService.class.getClassLoader());

            try {
                // Sync manifest + lock; the staged lock lets the build install
                // --frozen (exact pinned versions, no re-resolution).
                syncManifest(pixiToml, pixiLock);

                report(statusCallback, "Building pixi environment (this may take several minutes)...");

                // Note: do NOT pass .flags(List.of("--frozen")) here. Appose
                // appends those flags before the pixi subcommand
                // (`pixi --frozen ...`), but pixi only accepts `--frozen`
                // AFTER the subcommand (`pixi install --frozen`), so the
                // builder-time invocation fails with "unexpected argument
                // '--frozen' found". The explicit `pixi install --frozen`
                // call in installFiberLibrary() below already enforces
                // strict lockfile-pinning at install time, which is what
                // we actually wanted.
                environment = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(ENV_NAME)
                        .logDebug()
                        .build();

                logger.info("Appose environment configured at: {}", environment.base());

                // Resolve conda deps via explicit pixi install.
                installFiberLibrary(statusCallback);

                // Unpack the bundled fiberlib package to a stable location
                // on the env directory so the init script can sys.path it.
                report(statusCallback, "Unpacking fiberlib analysis module...");
                fiberlibDir = unpackFiberLib();

                report(statusCallback, "Starting Python service...");
                spawnWorker();

                // Verify with a small task. This is also what spawns the
                // subprocess (Appose starts it lazily on the first task), so
                // isAvailable() reports true from here on.
                report(statusCallback, "Verifying fiberlib...");
                String fiberlibVersion = verifyWorker();
                logger.info("Fiber Analysis environment verified: fiberlib {}", fiberlibVersion);

                String extVersion = GeneralTools.getPackageVersion(ApposeFiberService.class);
                logger.info("=== Fiber Analysis Environment ===");
                logger.info("  Extension version: {}", extVersion != null ? extVersion : "dev");
                logger.info("  fiberlib version: {}", fiberlibVersion);
                logger.info("  Required fiberlib: {}", REQUIRED_FIBERLIB_VERSION);
                logger.info("  Environment path: {}", getEnvironmentPath());
                logger.info("  fiberlib path: {}", fiberlibDir);
                logger.info("==================================");

                initialized = true;
                registerShutdownHook();
                report(statusCallback, "Setup complete (fiberlib " + fiberlibVersion + ")");
                logger.info("Fiber Analysis Appose Python service initialized");

            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }

        } catch (Exception e) {
            // Match failure signatures against the full cause chain -- pixi's
            // BuildException only says "pixi build failed" at the top; the
            // actionable Windows file-lock text lives in a nested cause.
            String fullMsg = collectCauseMessages(e);
            initError = e.getMessage();
            initialized = false;
            logger.error("Failed to initialize Fiber Analysis Appose: {}", e.getMessage(), e);
            if (looksLikeWindowsFileLock(fullMsg)) {
                logger.warn(
                        "Pixi env install hit a Windows file lock; manual recovery required\n{}",
                        windowsFileLockAdvice(ENV_NAME));
                report(statusCallback, "Pixi env install failed: Windows file lock. See recovery steps.");
                try {
                    qupath.fx.dialogs.Dialogs.showWarningNotification(
                            "Fiber Analysis",
                            "Pixi env install failed: a file was locked by another process. Close QuPath,"
                                    + " delete the .pixi folder, and relaunch. See the log for full recovery steps.");
                } catch (Exception fxEx) {
                    // FX not running; log advice already emitted.
                }
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Creates the Python worker Service object and queues its init script.
     * Does NOT spawn the subprocess -- Appose does that lazily on the first
     * task ({@link #verifyWorker()} is what actually starts it).
     *
     * <p>Caller must hold the monitor and have the extension classloader set as
     * TCCL. {@code import numpy} MUST stay the literal first line of the init
     * string: on Windows an init script whose first statement is anything else
     * has deadlocked the worker's stdin pump. Note {@code Service.init} REPLACES
     * the init script rather than appending, and Appose rejects it outright once
     * the subprocess is running -- so it is called exactly once per Service.
     */
    private void spawnWorker() throws IOException {
        if (fiberlibDir == null) {
            throw new IOException("Cannot start Python worker: fiberlib has not been unpacked");
        }
        pythonService = environment.python();
        pythonService.debug(msg -> {
            logger.info("[Fiber Python] {}", msg);
            qupath.ext.fiberanalysis.ui.PythonConsoleWindow.appendMessage(msg);
        });
        String initScript = "import numpy\n"
                + "fiberlib_dir = r'" + fiberlibDir.toString().replace("'", "\\'") + "'\n"
                + loadScript("init_fiber.py");
        pythonService.init(initScript);
    }

    /**
     * Runs the small verification task against a freshly spawned worker. This
     * is the call that actually starts the subprocess, so on return
     * {@link #isAvailable()} sees a live process.
     *
     * <p>Caller must hold the monitor and have the extension classloader set as
     * TCCL.
     *
     * @return the fiberlib version reported by the worker
     * @throws IOException if the worker's Python-side init recorded an error
     */
    private String verifyWorker() throws IOException, InterruptedException, TaskException {
        String verifyScript = "task.outputs['fiberlib_version'] = "
                + "fiberlib_version if 'fiberlib_version' in globals() else 'unknown'\n"
                + "task.outputs['init_error'] = str(init_error) if init_error else ''\n";

        Task verifyTask = pythonService.task(verifyScript);
        verifyTask.listen(event -> {
            if (event.responseType == ResponseType.FAILURE || event.responseType == ResponseType.CRASH) {
                logger.error("Verification task failed: {}", verifyTask.error);
            }
        });
        verifyTask.waitFor();

        String fiberlibVersion = String.valueOf(verifyTask.outputs.get("fiberlib_version"));
        String pythonInitError = String.valueOf(verifyTask.outputs.get("init_error"));
        if (pythonInitError != null && !pythonInitError.isEmpty() && !"null".equals(pythonInitError)) {
            throw new IOException("Python init failed: " + pythonInitError);
        }
        installedFiberlibVersion = fiberlibVersion;
        return fiberlibVersion;
    }

    /**
     * Recreates ONLY the Python worker subprocess -- not the pixi environment,
     * not the unpacked fiberlib.
     *
     * <p>This is the recovery for an Appose "thread death". A worker that has
     * gone stale emits a FAILURE on its next task <em>before any Python runs</em>;
     * Appose's {@code Service} then drops that task from its routing map and
     * relaunches it as an unobservable zombie inside the same worker (every
     * later event for it hits "No such task"). Retrying against that same worker
     * therefore risks a second copy of the analysis running concurrently against
     * the same output directory. Killing and recreating the worker terminates
     * the zombie, so the retry runs alone.
     *
     * <p>The pixi env is already built and fiberlib is already on disk, so this
     * is a subprocess respawn plus a re-run of {@code init_fiber.py} -- seconds,
     * not the minutes a full {@link #initialize} would cost. The verification
     * task at the end both confirms the fresh worker imports fiberlib and spawns
     * the subprocess, keeping the {@link #isAvailable()} contract intact.
     *
     * @throws IOException if the environment is not built or the worker cannot
     *                     be recreated
     */
    public synchronized void restartWorker() throws IOException {
        restartWorker(null);
    }

    /**
     * {@link #restartWorker()}, but a no-op when another thread already
     * replaced the worker that {@code staleWorker} refers to.
     *
     * <p>Two tasks running concurrently can both hit the same stale worker and
     * both ask for a restart. Without this guard the second restart would tear
     * down the fresh worker the first one just built -- and the first thread's
     * retry would then be submitting to a dead Service. Passing the exact
     * Service instance the failing task ran on makes the second call recognise
     * that the recovery already happened.
     *
     * @param staleWorker the Service the failed task was submitted to, or
     *                    {@code null} to restart unconditionally
     */
    public synchronized void restartWorker(Service staleWorker) throws IOException {
        if (environment == null) {
            throw new IOException("Cannot restart worker: Appose environment not built");
        }
        if (staleWorker != null && pythonService != staleWorker) {
            logger.info("Python worker was already replaced by another thread; skipping redundant restart");
            return;
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeFiberService.class.getClassLoader());
        try {
            // Tear down the stale worker: close stdin for a clean exit, then kill.
            Service stale = pythonService;
            pythonService = null;
            if (stale != null) {
                try {
                    stale.close();
                    long deadline = System.currentTimeMillis() + 3000;
                    while (stale.isAlive() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                    }
                    if (stale.isAlive()) {
                        stale.kill();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    try {
                        stale.kill();
                    } catch (Exception ignored) {
                        // best effort
                    }
                    throw new IOException("Interrupted while tearing down the stale Python worker", ie);
                } catch (Exception e) {
                    logger.warn("Error tearing down stale worker: {}", e.getMessage());
                    try {
                        stale.kill();
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }

            spawnWorker();
            String version = verifyWorker();
            logger.info("Fiber Analysis Appose worker restarted (fresh Python subprocess, fiberlib {})", version);
            // A restart after a hard worker death leaves initialized==false;
            // the env and fiberlib are intact, so mark the service usable again.
            initialized = true;
            initError = null;
            registerShutdownHook();
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while restarting the Fiber Analysis Python worker", ie);
        } catch (Exception e) {
            throw new IOException("Failed to restart Fiber Analysis Appose worker: " + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Runs a named task script with the given inputs.
     *
     * @param scriptName script name without {@code .py} extension
     * @param inputs     map of input values passed to the script
     * @return the completed {@link Task} with outputs populated
     */
    public Task runTask(String scriptName, Map<String, Object> inputs) throws IOException {
        ensureInitialized();

        String script;
        try {
            script = loadScript(scriptName + ".py");
        } catch (IOException e) {
            throw new IOException("Failed to load task script: " + scriptName, e);
        }

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeFiberService.class.getClassLoader());
        try {
            // Appose "thread death" recovery. A worker that has gone stale
            // emits FAILURE("thread death") on its next task BEFORE any Python
            // runs; Appose then drops that task from its routing map and
            // relaunches it inside the same worker as an unobservable zombie
            // (every later event for it hits "No such task").
            //
            // Three things make the recovery correct, and all three are
            // required:
            //   1. RESTART THE WORKER FIRST. Resubmitting to the same worker
            //      leaves the zombie running, so two copies of the analysis
            //      would race on the same output directory. restartWorker()
            //      kills the worker and takes the zombie with it.
            //   2. RETRY ONLY WHEN NO PYTHON EVER RAN. The stale-worker race is
            //      identifiable precisely because the FAILURE arrives before
            //      the LAUNCH -- so if we saw a LAUNCH or an UPDATE for this
            //      task, Python did start, the "thread death" is a real
            //      mid-run death, and re-running would repeat side effects
            //      (PNG/NPZ writes) rather than recover.
            //   3. LOG THREAD-DEATH AT WARN, EVERYTHING ELSE AT ERROR. A
            //      recovered thread-death is not an error; logging it as one
            //      sends users chasing a non-problem.
            int maxAttempts = 2;
            TaskException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                // Capture the exact Service this attempt runs on, so a
                // concurrent thread's restart cannot be double-applied. Read
                // once: restartWorker() nulls the field mid-swap, and this
                // method deliberately does not hold the monitor for the whole
                // (potentially minutes-long) task.
                Service submittedOn = pythonService;
                if (submittedOn == null) {
                    throw new IOException("Fiber task '" + scriptName
                            + "' cannot start: the Python worker is being replaced. Retry the operation.");
                }
                Task task = submittedOn.task(script, inputs);
                final int attemptCapture = attempt;
                // Set from the event pump thread, read from this one.
                final java.util.concurrent.atomic.AtomicBoolean pythonStarted =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                task.listen(event -> {
                    if (event.responseType == ResponseType.LAUNCH || event.responseType == ResponseType.UPDATE) {
                        pythonStarted.set(true);
                    } else if (event.responseType == ResponseType.CRASH) {
                        logger.error("Fiber task '{}' CRASH (attempt {}): {}", scriptName, attemptCapture, task.error);
                    } else if (event.responseType == ResponseType.FAILURE) {
                        String err = task.error == null ? "" : task.error;
                        if (err.toLowerCase().contains("thread death") && !pythonStarted.get()) {
                            logger.warn(
                                    "Fiber task '{}' FAILURE (attempt {}): stale-worker 'thread death' before Python"
                                            + " started: {}",
                                    scriptName,
                                    attemptCapture,
                                    err);
                        } else {
                            logger.error("Fiber task '{}' FAILURE (attempt {}): {}", scriptName, attemptCapture, err);
                        }
                    }
                });
                try {
                    task.waitFor();
                    if (attempt > 1) {
                        logger.info("Fiber task '{}' succeeded on attempt {}", scriptName, attempt);
                    }
                    return task;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Fiber task '" + scriptName + "' interrupted", e);
                } catch (TaskException te) {
                    last = te;
                    String msg = te.getMessage() == null ? "" : te.getMessage();
                    boolean isThreadDeath = msg.toLowerCase().contains("thread death");
                    boolean retryable = isThreadDeath && !pythonStarted.get() && attempt < maxAttempts;
                    if (!retryable) {
                        if (isThreadDeath && pythonStarted.get()) {
                            logger.error(
                                    "Fiber task '{}' died mid-run ('thread death' AFTER Python started) -- not retried,"
                                            + " because a rerun would repeat whatever the script had already written.",
                                    scriptName);
                        }
                        throw new IOException("Fiber task '" + scriptName + "' failed: " + msg, te);
                    }
                    logger.warn(
                            "Fiber task '{}' hit stale-worker 'thread death' before Python started (attempt {}/{});"
                                    + " restarting the Python worker to kill the zombie relaunch, then retrying once.",
                            scriptName,
                            attempt,
                            maxAttempts);
                    try {
                        restartWorker(submittedOn);
                    } catch (IOException re) {
                        logger.error("Worker restart after thread-death failed: {}", re.getMessage());
                        throw new IOException(
                                "Fiber task '" + scriptName + "' failed: " + msg + " (worker restart also failed: "
                                        + re.getMessage() + ")",
                                te);
                    }
                    // restartWorker() swaps the TCCL back to whatever it was on
                    // entry; re-assert ours for the retry's JSON serialization.
                    Thread.currentThread().setContextClassLoader(ApposeFiberService.class.getClassLoader());
                }
            }
            // Unreachable: the loop either returns or throws.
            throw new IOException("Fiber task '" + scriptName + "' failed after retries: "
                    + (last == null ? "unknown" : last.getMessage()));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Gracefully shuts down the Python service.
     */
    public synchronized void shutdown() {
        if (pythonService != null) {
            try {
                logger.info("Shutting down Fiber Analysis Python service...");
                pythonService.close();
                if (pythonService.isAlive()) {
                    long deadline = System.currentTimeMillis() + 5000;
                    while (pythonService.isAlive() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (pythonService.isAlive()) {
                    logger.warn("Fiber Python service did not exit gracefully, force-killing");
                    pythonService.kill();
                }
            } catch (Exception e) {
                try {
                    pythonService.kill();
                } catch (Exception ignored) {
                    // nothing more we can do
                }
                logger.warn("Error during Fiber Python shutdown: {}", e.getMessage());
            }
            pythonService = null;
        }
        initialized = false;
        removeShutdownHook();
        logger.info("Fiber Analysis Appose service shut down");
    }

    /**
     * Deletes the Appose pixi environment from disk. The service must be shut down first.
     */
    public synchronized void deleteEnvironment() throws IOException {
        if (pythonService != null) {
            throw new IOException("Cannot delete environment while Python service is running. Call shutdown() first.");
        }
        if (environment != null) {
            try {
                logger.info("Deleting Fiber Analysis environment via API: {}", environment.base());
                environment.delete();
                environment = null;
                return;
            } catch (Exception e) {
                logger.warn("environment.delete() failed; manual deletion: {}", e.getMessage());
                environment = null;
            }
        }
        Path envPath = getEnvironmentPath();
        if (Files.exists(envPath)) {
            logger.info("Deleting environment directory: {}", envPath);
            deleteDirectoryRecursively(envPath);
        }
    }

    /**
     * Executes a callable with the TCCL set to the extension's classloader.
     */
    public static <T> T withExtensionClassLoader(java.util.concurrent.Callable<T> callable) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(ApposeFiberService.class.getClassLoader());
        try {
            return callable.call();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    // ==================== Internal helpers ====================

    /**
     * "Installs" the fiber library. In this extension the Python analysis module
     * is bundled as resources, so this only runs {@code pixi install} to
     * materialize the conda dependencies. Mirrors the shape (not the body) of
     * PPM's {@code installPPMLibrary}.
     */
    private void installFiberLibrary(Consumer<String> statusCallback) throws IOException {
        Path envBase = Path.of(environment.base());
        Path manifestPath = envBase.resolve("pixi.toml");

        Path pixi = findPixiBinary();
        if (pixi == null) {
            throw new IOException("Cannot find pixi binary. The Appose environment may not have been set up correctly. "
                    + "Try Extensions > Fiber Analysis > Setup environment...");
        }

        // Install strictly from the bundled lockfile: --frozen installs the
        // exact pinned versions and never re-resolves.
        logger.info("Running pixi install --frozen from the bundled lock...");
        report(statusCallback, "Installing Python dependencies (this may take several minutes on first run)...");
        runPixiCommand(pixi, envBase, manifestPath, "install", "--frozen");
    }

    private void runPixiCommand(Path pixi, Path workDir, Path manifestPath, String... args) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(pixi.toString());
        for (String arg : args) {
            command.add(arg);
        }
        command.add("--manifest-path");
        command.add(manifestPath.toString());

        logger.info("Running: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                logger.info("[pixi] {}", line);
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("pixi command interrupted", e);
        }

        if (exitCode != 0) {
            throw new IOException("pixi " + args[0] + " failed (exit code " + exitCode + "):\n" + output);
        }
    }

    private Path findPixiBinary() {
        Path apposeDir = Path.of(System.getProperty("user.home"), ".local", "share", "appose");
        String pixiName = GeneralTools.isWindows() ? "pixi.exe" : "pixi";
        Path pixi = apposeDir.resolve(".pixi").resolve("bin").resolve(pixiName);
        if (Files.isRegularFile(pixi)) {
            return pixi;
        }
        try {
            Process p = new ProcessBuilder(pixiName, "--version")
                    .redirectErrorStream(true)
                    .start();
            if (p.waitFor() == 0) {
                return Path.of(pixiName);
            }
        } catch (IOException | InterruptedException ignored) {
            // Not on PATH
        }
        return null;
    }

    /**
     * Unpacks the bundled {@code fiberlib/} resource directory to a stable
     * location next to the pixi env. Re-extracts if the on-disk version marker
     * does not match {@link #REQUIRED_FIBERLIB_VERSION}.
     *
     * @return path to the on-disk {@code fiberlib} directory (parent of the package)
     */
    private Path unpackFiberLib() throws IOException {
        Path envBase = Path.of(environment.base());
        Path libParent = envBase.resolve("fiberlib_pkg");
        Path libDir = libParent.resolve("fiberlib");
        Path marker = libDir.resolve("_installed_version.txt");

        if (Files.isRegularFile(marker)) {
            String onDisk = Files.readString(marker, StandardCharsets.UTF_8).strip();
            if (REQUIRED_FIBERLIB_VERSION.equals(onDisk)) {
                logger.info("Bundled fiberlib up to date at {} (version {})", libDir, onDisk);
                return libParent;
            }
            logger.info("Bundled fiberlib changed ({} -> {}); re-extracting", onDisk, REQUIRED_FIBERLIB_VERSION);
            deleteDirectoryRecursively(libDir);
        }

        Files.createDirectories(libDir);

        // Enumerate all resources under FIBERLIB_RESOURCE_BASE and copy them.
        Set<String> entries = listResourcesUnder(FIBERLIB_RESOURCE_BASE);
        if (entries.isEmpty()) {
            throw new IOException("No bundled fiberlib resources found at " + FIBERLIB_RESOURCE_BASE
                    + ". The extension JAR may be malformed.");
        }

        int copied = 0;
        for (String resourcePath : entries) {
            String relative = resourcePath.substring(FIBERLIB_RESOURCE_BASE.length());
            if (relative.isEmpty() || relative.endsWith("/")) {
                continue;
            }
            // Never unpack compiled bytecode. The build excludes __pycache__
            // from the JAR, but an exploded dev classpath points straight at
            // src/main/resources where pytest leaves .pyc files behind. Those
            // were compiled by the developer's interpreter, not the env's, and
            // have no business in a user's environment.
            if (relative.contains("__pycache__/") || relative.endsWith(".pyc") || relative.endsWith(".pyo")) {
                continue;
            }
            Path out = libDir.resolve(relative);
            Files.createDirectories(out.getParent());
            try (InputStream is = ApposeFiberService.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    logger.warn("Resource disappeared during enumeration: {}", resourcePath);
                    continue;
                }
                Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }

        Files.writeString(marker, REQUIRED_FIBERLIB_VERSION, StandardCharsets.UTF_8);
        logger.info("Unpacked {} fiberlib files to {}", copied, libDir);
        return libParent;
    }

    /**
     * Enumerates all resource paths under the given JAR prefix. Works for both
     * a packaged JAR (walks the JarFile) and the IDE/exploded layout (walks the
     * filesystem).
     */
    private Set<String> listResourcesUnder(String resourcePrefix) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        ClassLoader cl = ApposeFiberService.class.getClassLoader();
        // Always include the explicit known files first so an exploded build
        // still works even when the directory listing fails.
        for (String known : KNOWN_FIBERLIB_FILES) {
            if (cl.getResource(resourcePrefix + known) != null) {
                out.add(resourcePrefix + known);
            }
        }

        // Now try to enumerate via JarFile / filesystem.
        for (URL url : Collections.list(cl.getResources(resourcePrefix))) {
            String protocol = url.getProtocol();
            if ("jar".equals(protocol)) {
                String spec = url.toString(); // jar:file:/...!/qupath/ext/fiberanalysis/fiberlib/
                int bang = spec.indexOf("!/");
                if (bang < 0) {
                    continue;
                }
                String jarPath = spec.substring("jar:file:".length(), bang);
                try (JarFile jf = new JarFile(jarPath)) {
                    java.util.Enumeration<JarEntry> en = jf.entries();
                    while (en.hasMoreElements()) {
                        JarEntry je = en.nextElement();
                        if (!je.isDirectory() && je.getName().startsWith(resourcePrefix)) {
                            out.add(je.getName());
                        }
                    }
                }
            } else if ("file".equals(protocol)) {
                Path dir;
                try {
                    dir = Path.of(url.toURI());
                } catch (Exception e) {
                    continue;
                }
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (var stream = Files.walk(dir)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        String rel = dir.relativize(p).toString().replace('\\', '/');
                        out.add(resourcePrefix + rel);
                    });
                }
            }
        }
        return out;
    }

    /**
     * Belt-and-braces fallback list of fiberlib files. Used in addition to the
     * directory enumeration above so an exploded-classpath dev build still
     * unpacks the right set even if listResourcesUnder under-enumerates.
     */
    private static final List<String> KNOWN_FIBERLIB_FILES = List.of(
            "__init__.py",
            "_version.py",
            "segmentation.py",
            "dilation.py",
            "windows.py",
            "straightness.py",
            "morphometrics.py",
            "texture.py",
            "render.py",
            "pipeline.py",
            "io.py");

    /**
     * Sync the on-disk pixi.toml AND pixi.lock with the JAR-bundled versions.
     * The lock pins the full dependency tree and the env installs with
     * --frozen, so the lock is staged into the env dir before the build: first
     * run stages the lock (Appose writes the manifest); a change to either file
     * rewrites both and wipes .pixi/ for a clean reinstall; otherwise the lock
     * is re-staged if a prior wipe removed it.
     */
    private void syncManifest(String expectedToml, String expectedLock) {
        try {
            Path envDir = getEnvironmentPath();
            Path pixiTomlFile = envDir.resolve("pixi.toml");
            Path lockFile = envDir.resolve("pixi.lock");

            if (!Files.exists(pixiTomlFile)) {
                Files.createDirectories(envDir);
                Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                return;
            }

            String existing = Files.readString(pixiTomlFile, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .strip();
            String normalizedExpected = expectedToml.replace("\r\n", "\n").strip();
            String onLock = Files.exists(lockFile)
                    ? Files.readString(lockFile, StandardCharsets.UTF_8)
                            .replace("\r\n", "\n")
                            .strip()
                    : "";
            String exLock = expectedLock.replace("\r\n", "\n").strip();

            if (existing.equals(normalizedExpected) && onLock.equals(exLock)) {
                if (!Files.exists(lockFile)) {
                    Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
                }
                return;
            }

            logger.info("pixi manifest/lock changed - updating and forcing rebuild");
            Files.writeString(pixiTomlFile, expectedToml, StandardCharsets.UTF_8);
            Files.writeString(lockFile, expectedLock, StandardCharsets.UTF_8);
            Path pixiDir = envDir.resolve(".pixi");
            if (Files.isDirectory(pixiDir)) {
                try {
                    deleteDirectoryRecursively(pixiDir);
                } catch (IOException e) {
                    Path renamed = envDir.resolve(".pixi_old_" + System.currentTimeMillis());
                    try {
                        Files.move(pixiDir, renamed);
                        logger.info("Could not delete .pixi/ (locked); renamed to {}", renamed.getFileName());
                    } catch (IOException e2) {
                        logger.warn(
                                "Could not delete or rename .pixi/: {}. " + "Use Setup environment to force a rebuild.",
                                e2.getMessage());
                    }
                }
            }
            logger.info("Environment sync complete - next build will install from the bundled lock");
        } catch (IOException e) {
            logger.warn("Failed to sync pixi manifest/lock (will attempt build anyway): {}", e.getMessage());
        }
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        java.nio.file.FileVisitor<Path> visitor = new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(directory, visitor);
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = new Thread(
                () -> {
                    logger.info("JVM shutdown hook: cleaning up Fiber Analysis Python subprocess");
                    Service svc = pythonService;
                    if (svc != null) {
                        try {
                            svc.close();
                            if (svc.isAlive()) {
                                Thread.sleep(2000);
                            }
                            if (svc.isAlive()) {
                                svc.kill();
                            }
                        } catch (Exception e) {
                            try {
                                svc.kill();
                            } catch (Exception ignored) {
                                // best-effort
                            }
                        }
                    }
                },
                "FiberAnalysis-ShutdownHook");
        shutdownHook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM already shutting down
            }
            shutdownHook = null;
        }
    }

    /**
     * Guards every task submission. A worker that has died (OOM kill, native
     * crash, user killed the process) would otherwise poison the rest of the
     * session: {@code initialized} stays true, so nothing would ever rebuild
     * it. When the env is still built we respawn just the worker instead of
     * failing.
     */
    private void ensureInitialized() throws IOException {
        if (isAvailable()) {
            return;
        }
        if (initialized && initError == null && environment != null && fiberlibDir != null) {
            logger.warn("Fiber Analysis Python worker is not alive; recreating it before running the task");
            restartWorker();
            if (isAvailable()) {
                return;
            }
        }
        throw new IOException(
                "Fiber Analysis Appose service is not available" + (initError != null ? ": " + initError : ""));
    }

    String loadScript(String scriptFileName) throws IOException {
        return loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private static String loadResource(String resourcePath) throws IOException {
        try (InputStream is = ApposeFiberService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    /** Concatenate the message of a throwable and its entire cause chain.
     *  pixi's BuildException only says "pixi build failed" at the top; the
     *  actionable "failed to link ... os error 32" text lives in a nested
     *  cause, so failure-signature detection must see the whole chain. */
    private static String collectCauseMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        for (Throwable c = t; c != null && guard < 20; c = c.getCause(), guard++) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    /** True when the Pixi build failed because Windows held an exclusive lock
     *  on a file the conda link step needed to replace (canonical signature:
     *  "failed to link" + "os error 32" / "being used by another process").
     *  Do NOT auto-wipe -- the blocking process may still be writing. */
    private static boolean looksLikeWindowsFileLock(String message) {
        if (message == null) return false;
        return message.contains("failed to link")
                && (message.contains("os error 32") || message.contains("being used by another process"));
    }

    /** Recovery instructions for the Windows file-lock failure mode during the
     *  Pixi env install. Surfaced to the log; a short notification points here. */
    private static String windowsFileLockAdvice(String envName) {
        return "The Python environment could not finish building because another"
                + " process is holding a file open inside the env directory.\n\n"
                + "RECOVERY STEPS (Windows):\n"
                + "  1. Close QuPath completely (File -> Quit).\n"
                + "  2. Open Task Manager -- end any leftover java.exe or python.exe"
                + " running under your user.\n"
                + "  3. In PowerShell:\n"
                + "       Remove-Item -Recurse -Force \"$env:USERPROFILE\\.local\\share\\appose\\"
                + envName + "\\.pixi\"\n"
                + "  4. (If step 3 fails: reboot Windows -- guaranteed to release every file handle.)\n"
                + "  5. (Optional) Add an antivirus exclusion for"
                + " %USERPROFILE%\\.local\\share\\appose\\ to prevent repeat occurrences.\n"
                + "  6. Relaunch QuPath. The env will rebuild from the bundled lock and the"
                + " link step will succeed.";
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    // Suppress unused-import warning for HashSet/Set if compilers complain.
    @SuppressWarnings("unused")
    private static final Set<String> _RESERVED = new HashSet<>();
}
