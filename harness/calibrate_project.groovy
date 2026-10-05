/*
 * calibrate_project.groovy -- project-wide threshold calibration, headless.
 *
 * Pools pixels from every matching annotation across the project and resolves
 * ONE Otsu threshold for all of them, written to
 * <project>/fiber-analysis/calibration_<name>.json.
 *
 * This is what makes a tiled run defensible. Per-region Otsu resolves a
 * separate cut inside every tile, so a tiled run disagrees with an untiled one
 * (measured: 33% on fiber_pixels) and tiles do not agree with each other. A
 * calibrated threshold is a fixed number, so every tile and every image cuts
 * at the same place.
 *
 * args: projectDir calibrationName [nameFilter] [sampleSize]
 */
import qupath.lib.projects.ProjectIO
import qupath.ext.fiberanalysis.analysis.FiberCalibrationRunner
import qupath.ext.fiberanalysis.preferences.FiberAnalysisPreferences
import java.awt.image.BufferedImage

if (args.size() < 2) { println "USAGE calibrate_project.groovy projectDir calibrationName [nameFilter] [sampleSize]"; return }
FiberAnalysisPreferences.installPreferences()

def projFile = new File(args[0], "project.qpproj")
def project = ProjectIO.loadProject(projFile, BufferedImage.class)

// Windows URIs are unreadable from WSL. Remap in memory only; never
// syncChanges, so the project on disk keeps its Windows paths.
int remapped = 0
for (entry in project.getImageList()) {
    def map = [:]
    for (u in entry.getURIs()) {
        def m = (u.toString() =~ /file:\/{2,}([A-Za-z]):\/(.*)/)
        if (m.find()) {
            def f = new File("/mnt/" + m.group(1).toLowerCase() + "/" +
                    java.net.URLDecoder.decode(m.group(2), "UTF-8"))
            if (f.exists()) map[u] = f.toURI()
        }
    }
    if (!map.isEmpty()) { entry.updateURIs(map); remapped++ }
}
println "REMAPPED ${remapped} entries (in memory only)"

// The Appose Python service is started by the extension's GUI install path,
// which never runs headless. runForAnnotations happens to initialise it; the
// calibration runner assumes it is already up and fails with "Appose service
// is not available" after doing all the region extraction. Start it first.
import qupath.ext.fiberanalysis.service.ApposeFiberService
def svc = ApposeFiberService.getInstance()
if (!svc.isAvailable()) {
    println "APPOSE|initialising..."
    svc.initialize({ m -> println "APPOSE|${m}" })
}
println "APPOSE|available=${svc.isAvailable()} fiberlib=${svc.getInstalledFiberlibVersion()}"

def cfg = new FiberCalibrationRunner.CalibrationConfig()
cfg.calibrationName = args[1]
cfg.nameFilter = args.size() > 2 && args[2] != "-" ? args[2] : ""
cfg.sampleSize = args.size() > 3 ? args[3] as int : 0   // 0 = all
cfg.classFilter = ["Acquired"] as Set
cfg.segChannel = "Raw intensity"
cfg.ridgeFilter = "None"
cfg.invertIntensity = false
cfg.borderZoneUm = 50.0
// A histogram does not need full resolution. At downsample 4 this reads 1/16
// the pixels; the resulting gray-level threshold is the same number.
cfg.readDownsample = args.size() > 4 ? args[4] as double : 4.0

def cb = new FiberCalibrationRunner.ProgressCallback() {
    void update(String message, int completed, int total) {
        if (total > 0 && (completed % 5 == 0 || completed == total)) {
            println "CAL_PROGRESS|${completed}/${total}|${message}"
        }
    }
    boolean isCancelled() { return false }
}

long t0 = System.currentTimeMillis()
def res = FiberCalibrationRunner.run(project, cfg, cb)
println "CAL_FILE|${res.calibrationFile}"
println "CAL_THRESHOLD_NORM|${res.thresholdNormalised}"
println "CAL_THRESHOLD_GRAY16|${Math.round(res.thresholdNormalised * 65535)}"
println "CAL_REGIONS|${res.regionsUsed}"
println "CAL_PIXELS|${res.pixelsUsed}"
println "CAL_DOWNSAMPLE|${cfg.readDownsample}"
println "CAL_SECONDS|${(System.currentTimeMillis() - t0) / 1000.0}"
println "DONE"
