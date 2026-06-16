package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.PackageEntry;
import dev.vatn.plugins.devenv.model.PackageInventory;
import dev.vatn.plugins.devenv.model.ServiceEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventories globally-installed packages via Homebrew, npm, and pip. Output parsing is split
 * into static, unit-testable helpers; subprocess calls go through {@link ScannerUtil}.
 */
public final class PackageManagerScanner {

    private final ScannerUtil util;

    public PackageManagerScanner(ScannerUtil util) {
        this.util = util;
    }

    public PackageInventory scan() {
        boolean hasBrew = util.which("brew").isPresent();
        List<PackageEntry> formulae = hasBrew
                ? util.exec("brew", "list", "--formula", "--versions").map(o -> parseBrewVersions(o, "homebrew")).orElse(List.of())
                : List.of();
        List<PackageEntry> casks = hasBrew
                ? util.exec("brew", "list", "--cask", "--versions").map(o -> parseBrewVersions(o, "homebrew-cask")).orElse(List.of())
                : List.of();
        List<ServiceEntry> services = hasBrew
                ? util.exec("brew", "services", "list").map(PackageManagerScanner::parseBrewServices).orElse(List.of())
                : List.of();

        List<PackageEntry> npm = util.which("npm").isPresent()
                ? util.exec("npm", "ls", "-g", "--depth=0").map(PackageManagerScanner::parseNpmGlobals).orElse(List.of())
                : List.of();

        String pip = util.which("pip3").isPresent() ? "pip3" : util.which("pip").isPresent() ? "pip" : null;
        List<PackageEntry> pipPkgs = pip != null
                ? util.exec(pip, "list", "--format=freeze").map(PackageManagerScanner::parsePipFreeze).orElse(List.of())
                : List.of();

        return new PackageInventory(formulae, casks, services, npm, pipPkgs);
    }

    // -- parsers (static, testable) -------------------------------------------------------

    /** {@code brew list --versions} lines: "name ver1 ver2" → newest-listed version. */
    static List<PackageEntry> parseBrewVersions(String output, String source) {
        var out = new ArrayList<PackageEntry>();
        for (String line : output.lines().toList()) {
            String l = line.strip();
            if (l.isEmpty()) continue;
            String[] parts = l.split("\\s+");
            String version = parts.length > 1 ? parts[parts.length - 1] : "";
            out.add(new PackageEntry(parts[0], version, source));
        }
        return List.copyOf(out);
    }

    /** {@code brew services list}: header then "name status [user] [file]". */
    static List<ServiceEntry> parseBrewServices(String output) {
        var out = new ArrayList<ServiceEntry>();
        for (String line : output.lines().toList()) {
            String l = line.strip();
            if (l.isEmpty() || l.toLowerCase().startsWith("name ")) continue; // header
            String[] parts = l.split("\\s+");
            String status = parts.length > 1 ? parts[1] : "unknown";
            String user = parts.length > 2 && !parts[2].startsWith("/") ? parts[2] : "";
            out.add(new ServiceEntry(parts[0], status, user));
        }
        return List.copyOf(out);
    }

    private static final Pattern NPM_LINE = Pattern.compile("(@?[^@\\s]+(?:/[^@\\s]+)?)@(\\S+)");

    /** {@code npm ls -g --depth=0} tree lines: "+-- pkg@1.2.3" / "`-- @scope/pkg@1.0.0". */
    static List<PackageEntry> parseNpmGlobals(String output) {
        var out = new ArrayList<PackageEntry>();
        for (String line : output.lines().toList()) {
            if (line.contains("@") && (line.contains("--") || line.contains("─"))) {
                Matcher m = NPM_LINE.matcher(line);
                if (m.find()) out.add(new PackageEntry(m.group(1), m.group(2), "npm"));
            }
        }
        return List.copyOf(out);
    }

    /** {@code pip list --format=freeze}: "name==version" (or "name @ file://…"). */
    static List<PackageEntry> parsePipFreeze(String output) {
        var out = new ArrayList<PackageEntry>();
        for (String line : output.lines().toList()) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int eq = l.indexOf("==");
            if (eq > 0) {
                out.add(new PackageEntry(l.substring(0, eq), l.substring(eq + 2), "pip"));
            } else {
                int at = l.indexOf(" @ ");
                if (at > 0) out.add(new PackageEntry(l.substring(0, at).strip(), "", "pip"));
            }
        }
        return List.copyOf(out);
    }
}
