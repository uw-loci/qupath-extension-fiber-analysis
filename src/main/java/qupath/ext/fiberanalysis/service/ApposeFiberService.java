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
    public static final String REQUIRED_FIBERLIB_VERSION = "0.2.4";

    private static ApposeFiberService instance;

    private Environment environment;
    private Service pythonService;
    private boolean initialized;
    private String installedFiberlibVersion;
    private String initError;
    private Thread shutdownHook;

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

    public boolean isAvailable() {
        return initialized && initError == null && pythonService != null;
    }

    public String getInitError() {
        return initError;
    }

    /**
     * Builds the pixi environment, unpacks fiberlib resources, and starts the
     * Python service. Idempotent.
     */
    public synchronized void initialize(Consumer<String> statusCallback) throws IOException {
        if (initialized) {
            report(statusCallback, "Already initialized");
            return;
        }

        try {
            report(statusCallback, "Loading environment configuration...");
            logger.info("Initializing Fiber Analysis Appose environment...");

            String pixiToml = loadResource(PIXI_TOML_RESOURCE);

            // ALL Appose operations require the extension classloader as TCCL.
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ApposeFiberService.class.getClassLoader());

            try {
                // Sync pixi.toml: detect bundled content changes vs on-disk
                syncPixiToml(pixiToml);

                report(statusCallback, "Building pixi environment (this may take several minutes)...");

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
                Path fiberlibDir = unpackFiberLib();

                report(statusCallback, "Starting Python service...");

                pythonService = environment.python();
                pythonService.debug(msg -> {
                    logger.info("[Fiber Python] {}", msg);
                    qupath.ext.fiberanalysis.ui.PythonConsoleWindow.appendMessage(msg);
                });

                String initScript = "import numpy\n"
                        + "fiberlib_dir = r'" + fiberlibDir.toString().replace("'", "\\'") + "'\n"
                        + loadScript("init_fiber.py");
                pythonService.init(initScript);

                // Verify with a small task.
                report(statusCallback, "Verifying fiberlib...");
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

                if (pythonInitError != null && !pythonInitError.isEmpty()) {
                    throw new IOException("Python init failed: " + pythonInitError);
                }

                installedFiberlibVersion = fiberlibVersion;
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
            initError = e.getMessage();
            initialized = false;
            logger.error("Failed to initialize Fiber Analysis Appose: {}", e.getMessage(), e);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
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
            // Appose "thread death" race: a previous task's Python worker
            // thread can emit its cleanup/death event AFTER a new task is
            // submitted, and Appose's UUID routing misattributes it to the
            // new task. The new task's actual LAUNCH then arrives but is
            // dropped because the FAILURE already removed the routing entry.
            // Symptom: an immediate FAILURE("thread death") while the Python
            // side is still running and producing output. Mirror the DL
            // pixel classifier's retry pattern -- pause briefly to let the
            // stale event drain, then resubmit with the same (already-built)
            // script + inputs. Max two attempts.
            int maxAttempts = 2;
            TaskException last = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                Task task = pythonService.task(script, inputs);
                final int attemptCapture = attempt;
                task.listen(event -> {
                    if (event.responseType == ResponseType.CRASH) {
                        logger.error("Fiber task '{}' CRASH (attempt {}): {}", scriptName, attemptCapture, task.error);
                    } else if (event.responseType == ResponseType.FAILURE) {
                        logger.error(
                                "Fiber task '{}' FAILURE (attempt {}): {}",
                                scriptName,
                                attemptCapture,
                                task.error);
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
                    if (!isThreadDeath || attempt == maxAttempts) {
                        throw new IOException("Fiber task '" + scriptName + "' failed: " + msg, te);
                    }
                    logger.warn(
                            "Fiber task '{}' hit stale 'thread death' (attempt {}/{}); retrying after 250ms",
                            scriptName,
                            attempt,
                            maxAttempts);
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Fiber task '" + scriptName + "' interrupted during retry backoff", ie);
                    }
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

        logger.info("Running pixi install to resolve dependencies...");
        report(statusCallback, "Installing Python dependencies (this may take several minutes on first run)...");
        runPixiCommand(pixi, envBase, manifestPath, "install");
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

        for (String resourcePath : entries) {
            String relative = resourcePath.substring(FIBERLIB_RESOURCE_BASE.length());
            if (relative.isEmpty() || relative.endsWith("/")) {
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
            }
        }

        Files.writeString(marker, REQUIRED_FIBERLIB_VERSION, StandardCharsets.UTF_8);
        logger.info("Unpacked {} fiberlib files to {}", entries.size(), libDir);
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
            "io.py");

    /**
     * Overwrite on-disk pixi.toml if the bundled content has changed, and
     * delete pixi.lock + .pixi/ so the next build re-resolves dependencies.
     */
    private void syncPixiToml(String expectedContent) {
        try {
            Path envDir = getEnvironmentPath();
            Path pixiTomlFile = envDir.resolve("pixi.toml");
            if (!Files.exists(pixiTomlFile)) {
                return; // First-time install
            }
            String existing = Files.readString(pixiTomlFile, StandardCharsets.UTF_8);
            String normalizedExisting = existing.replace("\r\n", "\n").strip();
            String normalizedExpected = expectedContent.replace("\r\n", "\n").strip();
            if (normalizedExisting.equals(normalizedExpected)) {
                return;
            }
            logger.info("pixi.toml content changed - updating and forcing rebuild");
            Files.writeString(pixiTomlFile, expectedContent, StandardCharsets.UTF_8);
            Files.deleteIfExists(envDir.resolve("pixi.lock"));
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
            logger.info("Environment sync complete - next build will re-resolve");
        } catch (IOException e) {
            logger.warn("Failed to sync pixi.toml (will attempt build anyway): {}", e.getMessage());
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

    private void ensureInitialized() throws IOException {
        if (!isAvailable()) {
            throw new IOException(
                    "Fiber Analysis Appose service is not available" + (initError != null ? ": " + initError : ""));
        }
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

    private static void report(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    // Suppress unused-import warning for HashSet/Set if compilers complain.
    @SuppressWarnings("unused")
    private static final Set<String> _RESERVED = new HashSet<>();
}
