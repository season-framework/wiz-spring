package com.wiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

class ServiceCommandBundleTest {

    @TempDir
    Path tempDir;

    @Test
    void installDryRunLaunchesManifestJarDirectly() throws Exception {
        Path bundle = createBundle("jar");
        CommandResult result = execute(
                "service", "install", "demo",
                "--bundle", bundle.toString(),
                "--port", "19090",
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd").toString(),
                "--bin-dir", tempDir.resolve("bin").toString());

        assertEquals(0, result.exitCode(), result.error());
        String script = result.output();
        assertTrue(script.contains("# wiz.service.bundle=" + bundle.toRealPath()));
        assertTrue(script.contains("# wiz.service.artifact=" + bundle.resolve("app/application.jar").toRealPath()));
        assertTrue(script.contains("# wiz.service.artifact-type=jar"));
        assertTrue(script.contains("# wiz.service.profiles=prod,bundle"));
        assertTrue(script.contains("# wiz.service.logs=journald"));
        assertTrue(script.contains(" -jar '" + bundle.resolve("app/application.jar").toRealPath() + "'"));
        assertTrue(script.contains("'--spring.profiles.active=prod,bundle'"));
        assertTrue(script.contains("'--server.port=19090'"));
        assertTrue(script.contains("set -euo pipefail"));
        assertFalse(script.contains(" 2>&1"));
        assertFalse(script.contains(" >> "));
        assertFalse(script.contains("wiz-spring run"));
        assertFalse(script.contains(".bashrc"));
    }

    @Test
    void installDryRunSupportsExecutableWarFromManifest() throws Exception {
        Path bundle = createBundle("war");
        CommandResult result = execute(
                "service", "install", "jsp-demo",
                "--bundle", bundle.toString(),
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-war").toString(),
                "--bin-dir", tempDir.resolve("bin-war").toString());

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("# wiz.service.artifact-type=war"));
        assertTrue(result.output().contains(" -jar '" + bundle.resolve("app/application.war").toRealPath() + "'"));
        assertFalse(result.output().contains("wiz-spring run"));
    }

    @Test
    void rejectsBundleWithoutRequiredManifest() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("fallback-bundle/app")).getParent();
        Files.writeString(bundle.resolve("app/application.jar"), "archive");
        writeChecksums(bundle);

        CommandResult result = execute(
                "service", "install", "fallback",
                "--bundle", bundle.toString(),
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-fallback").toString(),
                "--bin-dir", tempDir.resolve("bin-fallback").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Bundle manifest is required"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void installRequiresExplicitBundleOption() {
        CommandResult result = execute(
                "service", "install", "missing-bundle",
                "--allow-root",
                "--dry-run");

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("Missing required option: '--bundle=<bundle>'"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void rejectsBundleWithoutRequiredChecksums() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("no-checksums-bundle/app")).getParent();
        Files.writeString(bundle.resolve("app/application.jar"), "archive");
        Files.createDirectories(bundle.resolve("public"));
        Files.writeString(bundle.resolve("public/index.html"), "<!doctype html>\n");
        writeManifest(bundle, "jar");

        CommandResult result = execute(
                "service", "install", "no-checksums",
                "--bundle", bundle.toString(),
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-no-checksums").toString(),
                "--bin-dir", tempDir.resolve("bin-no-checksums").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Bundle checksum file is required"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void rejectsRetiredServiceAliasesAndPositionalPort() throws Exception {
        Path bundle = createBundle("jar");
        CommandResult positionalPort = execute(
                "service", "install", "positional-port", "19090",
                "--bundle", bundle.toString(),
                "--allow-root",
                "--dry-run");

        assertEquals(2, positionalPort.exitCode());
        assertTrue(positionalPort.error().contains("Unmatched argument"), positionalPort.error());

        for (String retired : List.of("register", "regist", "unregist", "remove")) {
            CommandResult alias = execute("service", retired, "demo");
            assertEquals(2, alias.exitCode(), retired);
            assertTrue(alias.error().contains("Unmatched argument"), retired + ": " + alias.error());
        }
    }

    @Test
    void canonicalUninstallStopsDisablesDeletesAndReloads() throws Exception {
        Path systemd = Files.createDirectories(tempDir.resolve("uninstall-systemd"));
        Path bin = Files.createDirectories(tempDir.resolve("uninstall-bin"));
        Path service = systemd.resolve("wiz.demo.service");
        Path launcher = bin.resolve("wiz.demo");
        Files.writeString(service, "[Service]\n");
        Files.writeString(launcher, "#!/bin/sh\n");

        Path systemctlCalls = tempDir.resolve("uninstall-systemctl.calls");
        Path systemctl = tempDir.resolve("uninstall-systemctl");
        Files.writeString(systemctl,
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + systemctlCalls + "'\n");
        systemctl.toFile().setExecutable(true, false);

        CommandResult result = execute(
                "service", "uninstall", "demo",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString(),
                "--systemctl", systemctl.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("stop wiz.demo\n"
                + "disable wiz.demo\n"
                + "daemon-reload\n", Files.readString(systemctlCalls));
        assertFalse(Files.exists(service));
        assertFalse(Files.exists(launcher));
        assertTrue(result.output().contains("Service uninstalled: wiz.demo"));
    }

    @Test
    void rejectsChecksumPathTraversal() throws Exception {
        Path bundle = createBundle("jar");
        Files.writeString(bundle.resolve("SHA256SUMS"),
                "0".repeat(64) + "  ../outside.jar\n");

        CommandResult result = installDryRun("checksum-traversal", bundle);

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("checksum path"), result.error());
        assertTrue(result.error().contains("beneath the bundle"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void rejectsDuplicateChecksumPaths() throws Exception {
        Path bundle = createBundle("jar");
        Path checksums = bundle.resolve("SHA256SUMS");
        List<String> lines = Files.readAllLines(checksums);
        String duplicate = lines.stream()
                .filter(line -> line.endsWith("  manifest.json"))
                .findFirst()
                .orElseThrow();
        Files.writeString(checksums, String.join("\n", lines) + "\n" + duplicate + "\n");

        CommandResult result = installDryRun("checksum-duplicate", bundle);

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Duplicate bundle checksum path"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void rejectsChecksumFileThatOmitsABundleFile() throws Exception {
        Path bundle = createBundle("jar");
        Path checksums = bundle.resolve("SHA256SUMS");
        List<String> retained = Files.readAllLines(checksums).stream()
                .filter(line -> !line.endsWith("  public/index.html"))
                .toList();
        Files.writeString(checksums, String.join("\n", retained) + "\n");

        CommandResult result = installDryRun("checksum-missing", bundle);

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("missing entries for"), result.error());
        assertTrue(result.error().contains("public/index.html"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void permitsTheComposeEnvironmentFileWithoutWeakeningOtherChecksumCoverage() throws Exception {
        Path bundle = createBundle("jar");
        Files.writeString(bundle.resolve(".env"), "APP_API_PREFIX=/api/v2\n");

        CommandResult allowed = installDryRun("compose-env", bundle);

        assertEquals(0, allowed.exitCode(), allowed.error());
        assertTrue(allowed.output().contains("# wiz.service.bundle=" + bundle.toRealPath()));

        Files.writeString(bundle.resolve("unsigned.txt"), "not covered\n");
        CommandResult rejected = installDryRun("unsigned-extra", bundle);
        assertEquals(1, rejected.exitCode());
        assertTrue(rejected.error().contains("missing entries for: unsigned.txt"), rejected.error());
    }

    @Test
    void rejectsChecksumMismatch() throws Exception {
        Path bundle = createBundle("jar");
        Path checksums = bundle.resolve("SHA256SUMS");
        List<String> changed = Files.readAllLines(checksums).stream()
                .map(line -> line.endsWith("  app/application.jar")
                        ? "0".repeat(64) + "  app/application.jar"
                        : line)
                .toList();
        Files.writeString(checksums, String.join("\n", changed) + "\n");

        CommandResult result = installDryRun("checksum-mismatch", bundle);

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("checksum mismatch for app/application.jar"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void rejectsChecksumFileThatIncludesItself() throws Exception {
        Path bundle = createBundle("jar");
        Path checksums = bundle.resolve("SHA256SUMS");
        Files.writeString(
                checksums,
                Files.readString(checksums) + "0".repeat(64) + "  SHA256SUMS\n");

        CommandResult result = installDryRun("checksum-self", bundle);

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("must not include itself"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void installedLauncherRunsWithoutWizSpringCliAndEnablesImmediately() throws Exception {
        Path bundle = createBundle("jar");
        Path systemd = Files.createDirectories(tempDir.resolve("installed-systemd"));
        Path bin = Files.createDirectories(tempDir.resolve("installed-bin"));
        Path java = tempDir.resolve("fake-java");
        Files.writeString(java, "#!/bin/sh\nprintf 'FAKE_JAVA:%s\\n' \"$*\"\n");
        java.toFile().setExecutable(true, false);

        Path systemctlCalls = tempDir.resolve("systemctl.calls");
        Path systemctl = tempDir.resolve("fake-systemctl");
        Files.writeString(systemctl,
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + systemctlCalls + "'\n");
        systemctl.toFile().setExecutable(true, false);

        CommandResult result = execute(
                "service", "install", "demo",
                "--bundle", bundle.toString(),
                "--port", "18080",
                "--java", java.toString(),
                "--profiles", "prod,blue-green",
                "--allow-root",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString(),
                "--systemctl", systemctl.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("daemon-reload\n"
                + "enable --now wiz.demo\n", Files.readString(systemctlCalls));

        Path launcher = bin.resolve("wiz.demo");
        String launcherText = Files.readString(launcher);
        assertFalse(launcherText.contains("wiz-spring"));
        assertTrue(launcherText.contains("exec '" + java + "' -jar"));
        assertFalse(launcherText.contains(" 2>&1"));
        String unit = Files.readString(systemd.resolve("wiz.demo.service"));
        assertTrue(unit.contains("ExecStart=" + launcher));
        assertTrue(unit.contains("StandardOutput=journal"));
        assertTrue(unit.contains("StandardError=journal"));
        assertTrue(unit.contains("SyslogIdentifier=wiz.demo"));

        Process process = new ProcessBuilder(launcher.toString()).start();
        String applicationOutput = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor());
        assertEquals("FAKE_JAVA:-jar " + bundle.resolve("app/application.jar").toRealPath()
                + " --spring.profiles.active=prod,blue-green --server.port=18080\n", applicationOutput);
    }

    @Test
    void refusesImplicitRootServiceUserUnlessExplicitlyApproved() throws Exception {
        Path bundle = createBundle("jar");
        String owner = Files.getOwner(bundle).getName();
        if (!"root".equalsIgnoreCase(owner) && !"0".equals(owner)) {
            return;
        }

        CommandResult result = execute(
                "service", "install", "root-owned",
                "--bundle", bundle.toString(),
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-root").toString(),
                "--bin-dir", tempDir.resolve("bin-root").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Refusing to run service as root"), result.error());
        assertTrue(result.error().contains("--allow-root"), result.error());
    }

    @Test
    void rejectsUnsafeSpringProfileValues() throws Exception {
        Path bundle = createBundle("jar");

        CommandResult result = execute(
                "service", "install", "unsafe-profile",
                "--bundle", bundle.toString(),
                "--profiles", "prod,$(touch-pwned)",
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-profile").toString(),
                "--bin-dir", tempDir.resolve("bin-profile").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("comma-separated list of safe profile names"), result.error());
        assertFalse(result.output().contains("touch-pwned"));
    }

    @Test
    void rejectsUnknownServiceUserBeforeWritingFiles() throws Exception {
        Path bundle = createBundle("jar");
        Path systemd = tempDir.resolve("systemd-missing-user");
        Path bin = tempDir.resolve("bin-missing-user");

        CommandResult result = execute(
                "service", "install", "missing-user",
                "--bundle", bundle.toString(),
                "--user", "wiz_spring_user_that_must_not_exist_987654321",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Service user does not exist"), result.error());
        assertFalse(Files.exists(systemd));
        assertFalse(Files.exists(bin));
    }

    @Test
    void rejectsRootOwnedPrivateBundleForNonRootServiceUser() throws Exception {
        assumeRootPosixFixtureWithNobody();
        Path bundle = createBundle("jar");
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(bundle, PosixFilePermissions.fromString("rwx------"));

        CommandResult result = execute(
                "service", "install", "private-bundle",
                "--bundle", bundle.toString(),
                "--user", "nobody",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-private").toString(),
                "--bin-dir", tempDir.resolve("bin-private").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Service user 'nobody' cannot traverse directory"), result.error());
        assertTrue(result.error().contains(bundle.toString()), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void acceptsBundleReadableByConfiguredNonRootServiceUser() throws Exception {
        assumeRootPosixFixtureWithNobody();
        Path bundle = createBundle("jar");
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(bundle, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(bundle.resolve("app"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(
                bundle.resolve("app/application.jar"), PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(bundle.resolve("manifest.json"), PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(bundle.resolve("public"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(
                bundle.resolve("public/index.html"), PosixFilePermissions.fromString("rw-r--r--"));

        CommandResult result = execute(
                "service", "install", "readable-bundle",
                "--bundle", bundle.toString(),
                "--user", "nobody",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-readable").toString(),
                "--bin-dir", tempDir.resolve("bin-readable").toString());

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("User=nobody"));
        assertTrue(result.output().contains(bundle.resolve("app/application.jar").toRealPath().toString()));
    }

    @Test
    void rejectsFrontendThatTheConfiguredServiceUserCannotRead() throws Exception {
        assumeRootPosixFixtureWithNobody();
        Path bundle = createBundle("jar");
        makeBundleArtifactReadable(bundle, "jar");
        Files.setPosixFilePermissions(bundle.resolve("public"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(
                bundle.resolve("public/index.html"), PosixFilePermissions.fromString("rw-------"));

        CommandResult result = execute(
                "service", "install", "private-frontend",
                "--bundle", bundle.toString(),
                "--user", "nobody",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-private-frontend").toString(),
                "--bin-dir", tempDir.resolve("bin-private-frontend").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("cannot read bundle frontend file"), result.error());
        assertTrue(result.error().contains("public/index.html"), result.error());
    }

    @Test
    void rejectsConfigurationThatTheConfiguredServiceUserCannotTraverse() throws Exception {
        assumeRootPosixFixtureWithNobody();
        Path bundle = createBundle("jar");
        makeBundleArtifactReadable(bundle, "jar");
        Files.setPosixFilePermissions(bundle.resolve("public"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(
                bundle.resolve("public/index.html"), PosixFilePermissions.fromString("rw-r--r--"));
        Path config = Files.createDirectories(bundle.resolve("config"));
        Files.writeString(config.resolve("application-bundle.yml"), "spring: {}\n");
        writeChecksums(bundle);
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwx------"));

        CommandResult result = execute(
                "service", "install", "private-config",
                "--bundle", bundle.toString(),
                "--user", "nobody",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-private-config").toString(),
                "--bin-dir", tempDir.resolve("bin-private-config").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("cannot traverse bundle configuration directory"), result.error());
        assertTrue(result.error().contains("/config"), result.error());
    }

    @Test
    void rejectsJavaRuntimeThatTheConfiguredServiceUserCannotExecute() throws Exception {
        assumeRootPosixFixtureWithNobody();
        Path bundle = createBundle("jar");
        makeBundleArtifactReadable(bundle, "jar");
        Files.setPosixFilePermissions(bundle.resolve("public"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(
                bundle.resolve("public/index.html"), PosixFilePermissions.fromString("rw-r--r--"));
        Path privateJava = tempDir.resolve("private-java");
        Files.writeString(privateJava, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(privateJava, PosixFilePermissions.fromString("rwx------"));

        CommandResult result = execute(
                "service", "install", "private-java",
                "--bundle", bundle.toString(),
                "--java", privateJava.toString(),
                "--user", "nobody",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-private-java").toString(),
                "--bin-dir", tempDir.resolve("bin-private-java").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("cannot execute Java runtime"), result.error());
        assertTrue(result.error().contains(privateJava.toString()), result.error());
    }

    @Test
    void rejectsManifestArtifactThatEscapesBundle() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("escape-bundle"));
        Path outside = tempDir.resolve("outside.jar");
        Files.writeString(outside, "archive");
        Files.writeString(bundle.resolve("manifest.json"), """
                {
                  "schemaVersion": 1,
                  "artifact": {
                    "path": "../outside.jar",
                    "type": "jar"
                  },
                  "frontend": {
                    "path": "public"
                  }
                }
                """);

        CommandResult result = execute(
                "service", "install", "escape",
                "--bundle", bundle.toString(),
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-escape").toString(),
                "--bin-dir", tempDir.resolve("bin-escape").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("must stay beneath the bundle directory"), result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void artifactOverrideDoesNotBypassEscapingManifestFrontendPath() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("frontend-escape-bundle/app")).getParent();
        Files.writeString(bundle.resolve("app/application.jar"), "archive");
        Files.createDirectories(tempDir.resolve("outside-frontend"));
        Files.writeString(bundle.resolve("manifest.json"), """
                {
                  "schemaVersion": 1,
                  "artifact": {
                    "path": "app/application.jar",
                    "type": "jar"
                  },
                  "frontend": {
                    "path": "../outside-frontend"
                  }
                }
                """);

        CommandResult result = execute(
                "service", "install", "frontend-escape",
                "--bundle", bundle.toString(),
                "--artifact", "app/application.jar",
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-frontend-escape").toString(),
                "--bin-dir", tempDir.resolve("bin-frontend-escape").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Bundle frontend path must stay beneath the bundle directory"),
                result.error());
        assertFalse(result.output().contains("# wiz.service."));
    }

    @Test
    void rejectsNullBundleManifestInsteadOfFailingWithANullPointer() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("null-manifest-bundle"));
        Files.writeString(bundle.resolve("manifest.json"), "null\n");

        CommandResult result = execute(
                "service", "install", "null-manifest",
                "--bundle", bundle.toString(),
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-null-manifest").toString(),
                "--bin-dir", tempDir.resolve("bin-null-manifest").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("manifest must contain a JSON object"), result.error());
        assertFalse(result.error().contains("NullPointerException"), result.error());
    }

    @Test
    void rejectsArtifactReachedThroughSymlinkedBundleDirectory() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("symlink-bundle"));
        Path outsideApp = Files.createDirectories(tempDir.resolve("outside-app"));
        Files.writeString(outsideApp.resolve("application.jar"), "archive");
        Files.createSymbolicLink(bundle.resolve("app"), outsideApp);
        writeManifest(bundle, "jar");

        CommandResult result = execute(
                "service", "install", "symlink",
                "--bundle", bundle.toString(),
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-symlink").toString(),
                "--bin-dir", tempDir.resolve("bin-symlink").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("must stay beneath the bundle directory"), result.error());
    }

    @Test
    void rejectsManifestTypeThatDoesNotMatchArchive() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("type-bundle/app")).getParent();
        Files.writeString(bundle.resolve("app/application.jar"), "archive");
        Files.writeString(bundle.resolve("manifest.json"), """
                {
                  "schemaVersion": 1,
                  "artifact": {
                    "path": "app/application.jar",
                    "type": "war"
                  },
                  "frontend": {
                    "path": "public"
                  }
                }
                """);

        CommandResult result = execute(
                "service", "install", "wrong-type",
                "--bundle", bundle.toString(),
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-type").toString(),
                "--bin-dir", tempDir.resolve("bin-type").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("does not match"), result.error());
    }

    private Path createBundle(String type) throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve(type + "-bundle/app")).getParent();
        Files.writeString(bundle.resolve("app/application." + type), "archive");
        Files.createDirectories(bundle.resolve("public"));
        Files.writeString(bundle.resolve("public/index.html"), "<!doctype html>\n");
        writeManifest(bundle, type);
        writeChecksums(bundle);
        return bundle;
    }

    private CommandResult installDryRun(String name, Path bundle) {
        return execute(
                "service", "install", name,
                "--bundle", bundle.toString(),
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd-" + name).toString(),
                "--bin-dir", tempDir.resolve("bin-" + name).toString());
    }

    private void writeChecksums(Path bundle) throws Exception {
        List<Path> files;
        try (var paths = Files.walk(bundle)) {
            files = paths
                    .filter(path -> Files.isRegularFile(path))
                    .filter(path -> !path.equals(bundle.resolve("SHA256SUMS")))
                    .map(bundle::relativize)
                    .sorted()
                    .toList();
        }
        StringBuilder contents = new StringBuilder();
        for (Path relative : files) {
            contents.append(sha256(bundle.resolve(relative)))
                    .append("  ")
                    .append(relative.toString().replace('\\', '/'))
                    .append('\n');
        }
        Files.writeString(bundle.resolve("SHA256SUMS"), contents);
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void makeBundleArtifactReadable(Path bundle, String type) throws Exception {
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(bundle, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(bundle.resolve("app"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(
                bundle.resolve("app/application." + type), PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(bundle.resolve("manifest.json"), PosixFilePermissions.fromString("rw-r--r--"));
    }

    private void writeManifest(Path bundle, String type) throws Exception {
        Files.writeString(bundle.resolve("manifest.json"), """
                {
                  "schemaVersion": 1,
                  "artifact": {
                    "path": "app/application.%s",
                    "type": "%s"
                  },
                  "frontend": {
                    "path": "public"
                  }
                }
                """.formatted(type, type));
    }

    private void assumeRootPosixFixtureWithNobody() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        String owner = Files.getOwner(tempDir).getName();
        assumeTrue("root".equalsIgnoreCase(owner) || "0".equals(owner));
        assumeTrue(tempDir.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("nobody") != null);
    }

    private CommandResult execute(String... args) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(error));
        int exitCode = command.execute(args);
        return new CommandResult(exitCode, output.toString(), error.toString());
    }

    private record CommandResult(int exitCode, String output, String error) {
    }
}
