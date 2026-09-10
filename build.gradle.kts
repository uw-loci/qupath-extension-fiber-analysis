plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
    // Auto-formatting (palantirJavaFormat) -- gates the build via `check`
    id("com.diffplug.spotless") version "7.0.2"
    // Static bug detection
    id("com.github.spotbugs") version "6.5.0"
}

// Configure the extension
qupathExtension {
    name = "qupath-extension-fiber-analysis"
    group = "io.github.uw-loci"
    version = "0.2.0-SNAPSHOT"
    description = "Testbed QuPath extension for fiber straightness, TWOMBLI-derived morphometrics, and GLCM texture on segmented collagen fibers."
    automaticModule = "io.github.uw.loci.extension.fiberanalysis"
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "SciJava"
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
        maven {
            name = "OME-Artifacts"
            url = uri("https://artifacts.openmicroscopy.org/artifactory/maven/")
        }
    }
}

val javafxVersion = "17.0.2"

dependencies {
    // Main dependencies for QuPath extensions (provided by QuPath at runtime).
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.gson)

    // Appose for embedded Java-Python IPC with shared memory.
    // NOT shadowed -- Appose is on QuPath's classpath at runtime (DL classifier precedent).
    implementation("org.apposed:appose:0.12.0")

    // Bio-Formats for the density-map sidecar writer (DensityTiffWriter).
    // compileOnly because QuPath ships Bio-Formats at runtime via its own
    // qupath-extension-bioformats; bundling it here would balloon the jar
    // (~50 MB) for no functional gain. Standard pattern for QuPath extensions
    // that target the bioformats stack -- see qupath-extension-tiles-to-pyramid.
    compileOnly("ome:formats-gpl:7.1.0")

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation("io.github.qupath:qupath-app:0.7.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation(libs.bundles.logging)
    testImplementation(libs.qupath.fxtras)
    testImplementation("org.openjfx:javafx-base:$javafxVersion")
    testImplementation("org.openjfx:javafx-graphics:$javafxVersion")
    testImplementation("org.openjfx:javafx-controls:$javafxVersion")
}

// Merge META-INF/services so ServiceLoader discovers Appose implementations
// (NDArray ShmFactory, Groovy FastStringService, etc.).
tasks.shadowJar {
    mergeServiceFiles()
}

// Keep locally-compiled Python bytecode out of the JAR. src/main/resources holds
// the fiberlib sources, and running the pytest suite (or importing fiberlib in a
// dev shell) leaves __pycache__/*.pyc next to them. Those are gitignored but
// processResources copies from the working tree, so without this they ship --
// and ApposeFiberService.listResourcesUnder() enumerates EVERY jar entry under
// fiberlib/, so they get unpacked into the user's Appose env as well.
tasks.processResources {
    exclude("**/__pycache__/**")
}

tasks.withType<JavaCompile> {
    options.release.set(21) // QuPath 0.7 runs on Java 21; pin bytecode target so any build JDK emits loadable classes
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
    // Move JavaFX JARs from classpath to module path so --add-modules can find them.
    // Temurin JDK does not bundle JavaFX, so the modules are only available
    // as dependency JARs which Gradle places on the classpath by default.
    doFirst {
        val cp = classpath.files
        val fxJars = cp.filter { it.name.startsWith("javafx-") }
        if (fxJars.isNotEmpty()) {
            classpath = files(cp - fxJars)
            jvmArgs(
                "--module-path", fxJars.joinToString(File.pathSeparator),
                "--add-modules", "javafx.base,javafx.graphics,javafx.controls",
                "--add-opens", "javafx.graphics/javafx.stage=ALL-UNNAMED"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Python tests -- fiberlib ships in the jar and has no Java coverage, so the
// pytest suite is the only thing testing it. `check` runs it, but skips loudly
// rather than blocking when the interpreter on PATH lacks the scientific stack
// (same posture the pre-push hook takes towards a missing JDK).
// ---------------------------------------------------------------------------
val fiberlibPythonTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the fiberlib pytest suite (src/test/python)."
    workingDir = projectDir
    commandLine("python3", "-m", "pytest", "src/test/python", "-q")
    inputs.dir("src/test/python")
    inputs.dir("src/main/resources/qupath/ext/fiberanalysis")
    outputs.upToDateWhen { false }
    onlyIf {
        val available = try {
            ProcessBuilder("python3", "-c", "import pytest, numpy, skimage")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            false
        }
        if (!available) {
            logger.lifecycle(
                "fiberlibPythonTest SKIPPED -- no python3 on PATH with pytest + numpy + scikit-image. " +
                    "Install them (pip install --user pytest numpy scikit-image) to test fiberlib."
            )
        }
        available
    }
}

tasks.named("check") {
    dependsOn(fiberlibPythonTest)
}

// ---------------------------------------------------------------------------
// Spotless -- auto-formatting (gates the build via `check`)
// ---------------------------------------------------------------------------
spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.90.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// ASCII-only enforcement (CLAUDE.md policy: no chars > 0x7F in Java sources).
// Prevents Windows cp1252 encoding failures.
// ---------------------------------------------------------------------------
tasks.register("checkAsciiOnly") {
    description = "Fails if any Java source file contains non-ASCII characters (> 0x7F)"
    group = "verification"
    val srcDirs = fileTree("src") { include("**/*.java") }
    inputs.files(srcDirs)
    doLast {
        val violations = mutableListOf<String>()
        srcDirs.forEach { file ->
            file.readText().lines().forEachIndexed { idx, line ->
                line.forEachIndexed { col, ch ->
                    if (ch.code > 0x7F) {
                        violations.add(
                            "${file.relativeTo(projectDir)}:${idx + 1}:${col + 1}  " +
                                    "'$ch' (U+${"04X".format(ch.code)})"
                        )
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Non-ASCII characters found (will break on Windows cp1252):\n" +
                        violations.joinToString("\n")
            )
        }
        logger.lifecycle("checkAsciiOnly: all Java sources are ASCII-clean")
    }
}
tasks.named("check") { dependsOn("checkAsciiOnly") }

// ---------------------------------------------------------------------------
// SpotBugs -- static bug detection (gates the build)
// ---------------------------------------------------------------------------
spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}
// QuPath 0.7.0's maven artifacts are published as requiring JVM 25 (org.gradle.jvm.version=25),
// even though the QuPath app runs on Java 21. options.release=21 makes Gradle resolve a
// JVM-21-compatible classpath, which then rejects those JVM-25 artifacts on a clean build. Force
// the resolvable classpaths to request JVM 25 so the deps resolve; bytecode target (21) is
// unaffected, so the jar still loads on Java 21. (Upstream QuPath metadata bug; remove if fixed.)
configurations.configureEach {
    if (isCanBeResolved) {
        attributes {
            attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}
