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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
 * <p>The generic plumbing (manifest staging, pixi, worker shutdown, failure
 * classification, the thread-death retry) lives in the shared copies
 * {@link ApposeSupport} and {@link ApposeTaskRunner}; this class holds only
 * what is specific to the fiber environment.
 *
 * <p>All Appose calls go through {@link #withExtensionClassLoader} to satisfy
 * the TCCL requirement (Appose ServiceLoader / Groovy JSON / SharedMemory all
 * need the extension classloader).
 */
public class ApposeFiberService {

    private static final Logger logger = LoggerFactory.getLogger(ApposeFiberService.class);

    private static final String TITLE = "Fiber Analysis";
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
    public static final String REQUIRED_FIBERLIB_VERSION = "0.4.4";

    private static ApposeFiberService instance;

    private Environment environment;
    // Read outside the synchronized methods by runTask()/isAvailable(); a
    // restart swaps the reference, so readers must not see a stale one.
    private volatile Service pythonService;
    private volatile boolean initialized;
    private String installedFiberlibVersion;
    private volatile String initError;
    private Thread shutdownHook;

    /**
     * On-disk directory CONTAINING the unpacked {@code fiberlib} package.
     * Held as a field (not just a local in {@link #initialize}) because
     * {@link #restartWorker()} has to rebuild the same init script for the
     * fresh worker.
     */
    private Path fiberlibDir;

    /** How {@link ApposeTaskRunner} reaches the worker and replaces a stale one. */
    private final ApposeTaskRunner.WorkerSource workerSource = new ApposeTaskRunner.WorkerSource() {
        @Override
        public Service worker() throws IOException {
            ensureInitialized();
            Service service = pythonService;
            if (service == null) {
                throw new IOException(
                        TITLE + " task cannot start: the Python worker is being replaced. " + "Retry the operation.");
            }
            return service;
        }

        @Override
        public void replaceWorker(Service stale) throws IOException {
            restartWorker(stale);
        }
    };

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
        return ApposeEnvLocation.isBuilt(getEnvironmentPath());
    }

    public static Path getEnvironmentPath() {
        ApposeFiberService svc = instance;
        if (svc != null && svc.environment != null) {
            return Path.of(svc.environment.base());
        }
        return ApposeEnvLocation.resolve(
                qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences.getEnvBaseDir(), ENV_NAME);
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

        long setupStart = System.nanoTime();
        try {
            report(statusCallback, "Loading environment configuration...");
            logger.info("Initializing Fiber Analysis Appose environment...");

            String pixiToml = ApposeSupport.loadResource(PIXI_TOML_RESOURCE);
            String pixiLock = ApposeSupport.loadResource(PIXI_LOCK_RESOURCE);

            // ALL Appose operations require the extension classloader as TCCL.
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());

            try {
                // Sync manifest + lock; the staged lock lets the build install
                // --frozen (exact pinned versions, no re-resolution).
                ApposeSupport.syncManifest(getEnvironmentPath(), pixiToml, pixiLock, logger);

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
                var envBuilder = Appose.pixi()
                        .content(pixiToml)
                        .scheme("pixi.toml")
                        .name(ENV_NAME)
                        .logDebug();

                // Builder.base() overrides name(), so pass the FULL
                // <base>/ENV_NAME directory -- the same path
                // getEnvironmentPath() reports and the manifest was staged into.
                Path configuredDir = configuredEnvDir();
                if (configuredDir != null) {
                    Files.createDirectories(configuredDir);
                    envBuilder.base(configuredDir.toFile());
                    logger.info("Building the environment at the configured location: {}", configuredDir);
                }
                long buildStart = System.nanoTime();
                environment = envBuilder.build();
                logger.info(
                        "Appose environment configured at {} ({} ms)",
                        environment.base(),
                        ApposeSupport.elapsedMs(buildStart));

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
                long workerStart = System.nanoTime();
                String fiberlibVersion = verifyWorker();
                logger.info(
                        "Fiber Analysis environment verified: fiberlib {}; the Python worker started in {} ms",
                        fiberlibVersion,
                        ApposeSupport.elapsedMs(workerStart));

                String extVersion = GeneralTools.getPackageVersion(ApposeFiberService.class);
                logger.info("=== Fiber Analysis Environment ===");
                logger.info("  Extension version: {}", extVersion != null ? extVersion : "dev");
                logger.info("  fiberlib version: {}", fiberlibVersion);
                logger.info("  Required fiberlib: {}", REQUIRED_FIBERLIB_VERSION);
                logger.info("  Environment path: {}", getEnvironmentPath());
                logger.info("  fiberlib path: {}", fiberlibDir);
                logger.info("  Setup time: {} ms", ApposeSupport.elapsedMs(setupStart));
                logger.info("==================================");

                initialized = true;
                // Built AND verified: the only point at which offering to
                // remove the environment this replaced is safe.
                offerPreviousEnvCleanup();
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
            String fullMsg = ApposeSupport.collectCauseMessages(e);
            initError = e.getMessage();
            initialized = false;
            logger.error("Failed to initialize Fiber Analysis Appose: {}", e.getMessage(), e);
            if (ApposeSupport.looksLikeWindowsFileLock(fullMsg)) {
                logger.warn(
                        "Pixi env install hit a Windows file lock; manual recovery required\n{}",
                        ApposeSupport.windowsFileLockAdvice(getEnvironmentPath()));
                report(statusCallback, "Pixi env install failed: Windows file lock. See recovery steps.");
                try {
                    qupath.fx.dialogs.Dialogs.showWarningNotification(
                            TITLE,
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
        pythonService = ApposeGpu.forWorker(environment).python();
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

        Object version = verifyTask.outputs.get("fiberlib_version");
        String fiberlibVersion = version == null ? "unknown" : String.valueOf(version);
        Object initErr = verifyTask.outputs.get("init_error");
        String pythonInitError = initErr == null ? "" : String.valueOf(initErr);
        if (!pythonInitError.isEmpty()) {
            throw new IOException("Python init failed: " + pythonInitError);
        }
        installedFiberlibVersion = fiberlibVersion;
        return fiberlibVersion;
    }

    /**
     * Recreates ONLY the Python worker subprocess -- not the pixi environment,
     * not the unpacked fiberlib.
     *
     * <p>This is the recovery for an Appose "thread death" (see
     * {@link ApposeTaskRunner} for why the worker must be replaced rather than
     * reused). The pixi env is already built and fiberlib is already on disk,
     * so this is a subprocess respawn plus a re-run of {@code init_fiber.py} --
     * seconds, not the minutes a full {@link #initialize} would cost. The
     * verification task at the end both confirms the fresh worker imports
     * fiberlib and spawns the subprocess, keeping the {@link #isAvailable()}
     * contract intact.
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
        Thread.currentThread().setContextClassLoader(ApposeSupport.extensionClassLoader());
        try {
            long start = System.nanoTime();
            Service stale = pythonService;
            pythonService = null;
            ApposeSupport.stopWorker(stale, 3000, logger);

            spawnWorker();
            String version = verifyWorker();
            logger.info(
                    "Fiber Analysis Appose worker restarted in {} ms (fresh Python subprocess, fiberlib {})",
                    ApposeSupport.elapsedMs(start),
                    version);
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
     * <p>A stale worker is replaced and the task retried once, only when no
     * Python ran; see {@link ApposeTaskRunner}.
     *
     * @param scriptName script name without {@code .py} extension
     * @param inputs     map of input values passed to the script
     * @return the completed {@link Task} with outputs populated
     */
    public Task runTask(String scriptName, Map<String, Object> inputs) throws IOException {
        String script;
        try {
            script = loadScript(scriptName + ".py");
        } catch (IOException e) {
            throw new IOException("Failed to load task script: " + scriptName, e);
        }
        return ApposeTaskRunner.run(
                TITLE, workerSource, scriptName, script, inputs, new ApposeTaskRunner.Options().maxAttempts(2));
    }

    /**
     * Gracefully shuts down the Python service.
     */
    public synchronized void shutdown() {
        Service service = pythonService;
        if (service != null) {
            logger.info("Shutting down Fiber Analysis Python service...");
            ApposeSupport.stopWorker(service, 5000, logger);
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
            ApposeSupport.deleteDirectoryRecursively(envPath);
        }
    }

    /**
     * Executes a callable with the TCCL set to the extension's classloader.
     */
    public static <T> T withExtensionClassLoader(java.util.concurrent.Callable<T> callable) throws Exception {
        return ApposeSupport.withExtensionClassLoader(callable);
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

        Path pixi = ApposeSupport.findPixiBinary();
        if (pixi == null) {
            throw new IOException("Cannot find pixi binary. The Appose environment may not have been set up correctly. "
                    + "Try Extensions > Fiber Analysis > Setup environment...");
        }

        // Install strictly from the bundled lockfile: --frozen installs the
        // exact pinned versions and never re-resolves.
        logger.info("Running pixi install --frozen from the bundled lock...");
        report(statusCallback, "Installing Python dependencies (this may take several minutes on first run)...");
        long start = System.nanoTime();
        ApposeSupport.runPixiCommand(
                pixi, envBase, manifestPath, line -> logger.info("[pixi] {}", line), "install", "--frozen");
        logger.info("pixi install --frozen finished in {} ms", ApposeSupport.elapsedMs(start));
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
            ApposeSupport.deleteDirectoryRecursively(libDir);
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
            "io.py",
            "wfeatures.py",
            "wsplits.py",
            "wvalidate.py",
            "wmodel.py",
            "wcontext.py",
            "intensity.py");

    private void registerShutdownHook() {
        if (shutdownHook != null) return;
        shutdownHook = ApposeSupport.registerShutdownHook("FiberAnalysis-ShutdownHook", () -> pythonService, logger);
    }

    private void removeShutdownHook() {
        ApposeSupport.removeShutdownHook(shutdownHook);
        shutdownHook = null;
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
        return ApposeSupport.loadResource(SCRIPTS_BASE + scriptFileName);
    }

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    /**
     * The configured environment directory, or null to use the Appose default.
     * Kept beside the path-reporting method so the reported location and the
     * built location cannot drift apart: setting only one moves where we SAY
     * the environment is while Appose keeps building elsewhere, and the staged
     * manifest is never read.
     */
    private static Path configuredEnvDir() {
        String base = qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences.getEnvBaseDir();
        if (base == null || base.isBlank()) {
            return null;
        }
        return ApposeEnvLocation.resolve(base, ENV_NAME);
    }

    /**
     * After a VERIFIED build, offer to remove the environment this one replaced.
     *
     * <p>Off the initialization thread and never blocking it; degrades to
     * leaving the old directory alone when there is no UI. Records the current
     * location regardless of the answer -- the question is asked once, and
     * declining means keep, not ask again every launch.
     */
    private void offerPreviousEnvCleanup() {
        final Path current = getEnvironmentPath();
        final String previous = qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences.getEnvLastBuiltDir();
        qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences.setEnvLastBuiltDir(current.toString());
        if (previous == null || previous.isBlank() || previous.equals(current.toString())) {
            return;
        }
        try {
            javafx.application.Platform.runLater(() -> {
                try {
                    ApposeEnvLocation.promptCleanup(Path.of(previous), current);
                } catch (Exception e) {
                    logger.warn("Previous-environment cleanup prompt failed: {}", e.getMessage());
                }
            });
        } catch (IllegalStateException e) {
            logger.info("Previous environment at {} left in place (no UI available)", previous);
        }
    }
}
