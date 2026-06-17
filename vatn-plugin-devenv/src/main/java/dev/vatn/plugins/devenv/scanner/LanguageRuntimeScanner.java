package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.RuntimeInstall;
import dev.vatn.plugins.devenv.model.RuntimeSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enumerates <em>all</em> installed Python interpreters (pyenv, conda base+envs, python.org
 * framework, Homebrew, system/CLT) and Swift toolchains (Xcode/CLT + swift.org), across sources
 * with the active one marked — the {@link JvmScanner} pattern generalized to non-JVM languages.
 * Filesystem-first; a cheap {@code --version} probe resolves exact versions.
 */
public final class LanguageRuntimeScanner {

    private final ScannerUtil util;

    public LanguageRuntimeScanner(ScannerUtil util) {
        this.util = util;
    }

    public List<RuntimeInstall> scan() {
        var out = new java.util.ArrayList<RuntimeInstall>();
        out.addAll(scanPython());
        out.addAll(scanSwift());
        return List.copyOf(out);
    }

    // -- Python ---------------------------------------------------------------------------

    List<RuntimeInstall> scanPython() {
        Map<String, RuntimeInstall> byBin = new LinkedHashMap<>();
        String activeBin = util.which("python3").map(Path::of).map(LanguageRuntimeScanner::canonical).orElse(null);

        // python.org framework
        for (Path v : ScannerUtil.subdirs(Path.of("/Library/Frameworks/Python.framework/Versions"))) {
            if (!v.getFileName().toString().equals("Current")) addPython(byBin, v, RuntimeSource.PYTHON_ORG, "CPython", activeBin);
        }
        // Homebrew kegs: Cellar/python@X/<version>
        for (String prefix : List.of("/opt/homebrew", "/usr/local")) {
            for (Path keg : matching(Path.of(prefix, "Cellar"), "python@")) {
                for (Path ver : ScannerUtil.subdirs(keg)) addPython(byBin, ver, RuntimeSource.HOMEBREW, "CPython", activeBin);
            }
        }
        // pyenv
        for (Path v : ScannerUtil.subdirs(ScannerUtil.homeDir(".pyenv/versions"))) {
            addPython(byBin, v, RuntimeSource.PYENV, derivePythonDistribution(v.getFileName().toString()), activeBin);
        }
        // conda bases + their envs
        for (Path base : condaBases()) {
            String dist = derivePythonDistribution(base.getFileName().toString());
            addPython(byBin, base, RuntimeSource.CONDA, dist, activeBin);
            for (Path env : ScannerUtil.subdirs(base.resolve("envs"))) {
                addPython(byBin, env, RuntimeSource.CONDA, dist, activeBin);
            }
        }
        // system / CLT
        for (String bin : List.of("/usr/bin/python3",
                "/Library/Developer/CommandLineTools/usr/bin/python3", "/usr/local/bin/python3")) {
            addPythonBin(byBin, Path.of(bin), RuntimeSource.SYSTEM, "CPython", activeBin);
        }
        return List.copyOf(byBin.values());
    }

    /** Add a python install given its prefix dir (resolves the bin inside). */
    private void addPython(Map<String, RuntimeInstall> acc, Path prefix, RuntimeSource src,
                           String dist, String activeBin) {
        Path bin = pythonBin(prefix);
        if (bin != null) addPythonBin(acc, bin, src, dist, activeBin);
    }

    private void addPythonBin(Map<String, RuntimeInstall> acc, Path bin, RuntimeSource src,
                              String dist, String activeBin) {
        if (!ScannerUtil.isFile(bin)) return;
        String key = canonical(bin);
        if (acc.containsKey(key)) return;
        String version = util.exec(bin.toString(), "--version")
                .map(ScannerUtil::extractVersion).filter(s -> !s.isBlank())
                .orElseGet(() -> pathVersion(bin.toString()));
        boolean active = activeBin != null && activeBin.equals(key);
        acc.put(key, new RuntimeInstall("python", version, dist, src, bin.toString(), active));
    }

    /** First {@code python3}/{@code python}/{@code python3.x} executable in {@code <prefix>/bin}. */
    static Path pythonBin(Path prefix) {
        Path binDir = prefix.resolve("bin");
        for (String n : List.of("python3", "python")) {
            if (ScannerUtil.isFile(binDir.resolve(n))) return binDir.resolve(n);
        }
        try (Stream<Path> s = Files.list(binDir)) {
            return s.filter(p -> p.getFileName().toString().matches("python3\\.\\d+"))
                    .sorted().findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static java.util.List<Path> condaBases() {
        var out = new java.util.ArrayList<Path>();
        for (String b : List.of("miniconda3", "anaconda3", "miniforge3")) {
            Path p = ScannerUtil.homeDir(b);
            if (ScannerUtil.isDir(p)) out.add(p);
        }
        for (String b : List.of("/opt/anaconda3", "/opt/miniconda3")) {
            if (ScannerUtil.isDir(Path.of(b))) out.add(Path.of(b));
        }
        // Homebrew cask miniconda: /opt/homebrew/Caskroom/miniconda/<ver>/base
        for (Path v : ScannerUtil.subdirs(Path.of("/opt/homebrew/Caskroom/miniconda"))) {
            Path base = v.resolve("base");
            if (ScannerUtil.isDir(base)) out.add(base);
        }
        return out;
    }

    /** Infer Python distribution from a path/dir hint. */
    static String derivePythonDistribution(String hint) {
        String h = hint.toLowerCase();
        if (h.contains("pypy")) return "PyPy";
        if (h.contains("graalpy") || h.contains("graalpython")) return "GraalPy";
        if (h.contains("anaconda")) return "Anaconda";
        if (h.contains("miniforge")) return "Miniforge";
        if (h.contains("miniconda") || h.contains("conda")) return "Miniconda";
        return "CPython";
    }

    // -- Swift ----------------------------------------------------------------------------

    private static final Pattern PATH_VER = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    /** Version-like token from a path (fallback when --version probe fails); "" if none. */
    static String pathVersion(String path) {
        Matcher m = PATH_VER.matcher(path);
        return m.find() ? m.group(1) : "";
    }

    private static final Pattern SWIFT_VER = Pattern.compile("Swift version (\\d+(?:\\.\\d+)*)");
    private static final Pattern TOOLCHAIN_VER = Pattern.compile("swift-(\\d+(?:\\.\\d+)*)");

    List<RuntimeInstall> scanSwift() {
        Map<String, RuntimeInstall> byPath = new LinkedHashMap<>();

        // swift.org toolchains
        for (Path tc : matching(Path.of("/Library/Developer/Toolchains"), "swift-")) {
            String version = swiftToolchainVersion(tc.getFileName().toString());
            byPath.put(canonical(tc), new RuntimeInstall("swift", version, "swift.org",
                    RuntimeSource.XCODE, tc.toString(), false));
        }
        // active swift (Xcode/CLT or a toolchain)
        util.which("swift").ifPresent(path -> {
            String version = util.exec("swift", "--version").map(LanguageRuntimeScanner::parseSwiftVersion).orElse("");
            boolean fromToolchain = canonical(Path.of(path)).contains("/Toolchains/");
            byPath.put("active:" + path, new RuntimeInstall("swift", version,
                    fromToolchain ? "swift.org" : "Apple", RuntimeSource.XCODE, path, true));
        });
        return List.copyOf(byPath.values());
    }

    static String parseSwiftVersion(String swiftVersionOutput) {
        Matcher m = SWIFT_VER.matcher(swiftVersionOutput == null ? "" : swiftVersionOutput);
        return m.find() ? m.group(1) : "";
    }

    static String swiftToolchainVersion(String dirName) {
        Matcher m = TOOLCHAIN_VER.matcher(dirName);
        return m.find() ? m.group(1) : "";
    }

    // -- shared ---------------------------------------------------------------------------

    /** Subdirectories of {@code parent} whose name starts with {@code prefix}. */
    private static List<Path> matching(Path parent, String prefix) {
        return ScannerUtil.subdirs(parent).stream()
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .toList();
    }

    private static String canonical(Path p) {
        try { return p.toRealPath().toString(); }
        catch (Exception e) { return p.toAbsolutePath().normalize().toString(); }
    }
}
