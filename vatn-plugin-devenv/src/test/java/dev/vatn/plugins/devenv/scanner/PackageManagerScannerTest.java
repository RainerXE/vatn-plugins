package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.PackageEntry;
import dev.vatn.plugins.devenv.model.ServiceEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageManagerScannerTest {

    @Test
    void parsesBrewVersions() {
        List<PackageEntry> p = PackageManagerScanner.parseBrewVersions(
                "git 2.44.0\nopenssl@3 3.2.1 3.2.0\njq 1.7.1\n", "homebrew");
        assertEquals(3, p.size());
        assertEquals("git", p.get(0).name());
        assertEquals("2.44.0", p.get(0).version());
        // multiple versions → newest-listed (last) token
        assertEquals("openssl@3", p.get(1).name());
        assertEquals("3.2.0", p.get(1).version());
        assertEquals("homebrew", p.get(2).source());
    }

    @Test
    void parsesBrewServicesSkippingHeader() {
        List<ServiceEntry> s = PackageManagerScanner.parseBrewServices(
                "Name          Status  User  File\npostgresql@16 started rainer /path\nredis         stopped\n");
        assertEquals(2, s.size());
        assertEquals("postgresql@16", s.get(0).name());
        assertEquals("started", s.get(0).status());
        assertEquals("rainer", s.get(0).user());
        assertEquals("stopped", s.get(1).status());
        assertEquals("", s.get(1).user());
    }

    @Test
    void parsesNpmGlobalsTree() {
        List<PackageEntry> p = PackageManagerScanner.parseNpmGlobals(
                "/usr/local/lib\n+-- npm@10.2.3\n`-- @angular/cli@17.0.0\n");
        assertEquals(2, p.size());
        assertEquals("npm", p.get(0).name());
        assertEquals("10.2.3", p.get(0).version());
        assertEquals("@angular/cli", p.get(1).name());
        assertEquals("17.0.0", p.get(1).version());
    }

    @Test
    void parsesPipFreeze() {
        List<PackageEntry> p = PackageManagerScanner.parsePipFreeze(
                "requests==2.31.0\nnumpy==1.26.4\n# a comment\nlocalpkg @ file:///tmp/x\n");
        assertEquals(3, p.size());
        assertEquals("requests", p.get(0).name());
        assertEquals("2.31.0", p.get(0).version());
        assertEquals("localpkg", p.get(2).name());
        assertEquals("", p.get(2).version());
    }

    @Test
    void emptyOutputsYieldEmptyLists() {
        assertTrue(PackageManagerScanner.parseBrewVersions("", "homebrew").isEmpty());
        assertTrue(PackageManagerScanner.parseNpmGlobals("/usr/local/lib\n").isEmpty());
        assertTrue(PackageManagerScanner.parsePipFreeze("").isEmpty());
    }
}
