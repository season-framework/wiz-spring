package generator

import (
	"archive/zip"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	archivezip "github.com/season-framework/wiz-spring/helper/internal/archive"
	"github.com/season-framework/wiz-spring/helper/internal/project"
	"github.com/season-framework/wiz-spring/helper/internal/templatecatalog"
)

func TestGenerateInvokesFixedCommandAndCleansArchive(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("test uses a POSIX test executable")
	}
	workspace := t.TempDir()
	java := fakeJava(t, workspace, false)
	jar := filepath.Join(workspace, "wiz-spring.jar")
	if err := os.WriteFile(jar, []byte("test"), 0o600); err != nil {
		t.Fatal(err)
	}
	generator, err := New(testConfig(t, java, jar, workspace, time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if err = generator.Probe(context.Background()); err != nil {
		t.Fatalf("Probe returned an error: %v", err)
	}

	archive, err := generator.Generate(context.Background(), project.Spec{
		ProjectName: "demo-app",
		PackageName: "com.example.demo",
		Template:    "company-react",
	})
	if err != nil {
		t.Fatalf("Generate returned an error: %v", err)
	}
	archivePath := archive.Path()
	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	foundPackage := false
	foundTemplate := false
	for _, entry := range reader.File {
		if entry.Name == "demo-app/package.txt" {
			foundPackage = true
		}
		if entry.Name == "demo-app/template.txt" {
			contents, readError := entry.Open()
			if readError != nil {
				t.Fatal(readError)
			}
			value, readError := io.ReadAll(contents)
			contents.Close()
			if readError != nil {
				t.Fatal(readError)
			}
			foundTemplate = string(value) == "react"
		}
	}
	reader.Close()
	if !foundPackage {
		t.Fatal("archive does not contain generated package.txt")
	}
	if !foundTemplate {
		t.Fatal("custom template id was not mapped to react base")
	}
	if err = archive.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(archivePath); !os.IsNotExist(err) {
		t.Fatalf("archive workspace still exists: %v", err)
	}
}

func TestGenerateReportsTimeout(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("test uses a POSIX test executable")
	}
	workspace := t.TempDir()
	java := fakeJava(t, workspace, true)
	jar := filepath.Join(workspace, "wiz-spring.jar")
	if err := os.WriteFile(jar, []byte("test"), 0o600); err != nil {
		t.Fatal(err)
	}
	generator, err := New(testConfig(t, java, jar, workspace, 20*time.Millisecond))
	if err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	_, err = generator.Generate(context.Background(), project.Spec{
		ProjectName: "demo",
		PackageName: "com.example.demo",
		Template:    "html",
	})
	if !errors.Is(err, ErrGenerationTimeout) {
		t.Fatalf("error = %v, want ErrGenerationTimeout", err)
	}
	if elapsed := time.Since(started); elapsed > 500*time.Millisecond {
		t.Fatalf("timeout took %s; child process was not terminated with the command", elapsed)
	}
}

func TestNewVerifiesConfiguredChecksum(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("test uses a POSIX test executable")
	}
	workspace := t.TempDir()
	java := fakeJava(t, workspace, false)
	jar := filepath.Join(workspace, "wiz-spring.jar")
	if err := os.WriteFile(jar, []byte("test"), 0o600); err != nil {
		t.Fatal(err)
	}
	config := testConfig(t, java, jar, workspace, time.Second)
	config.ExpectedSHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
	if _, err := New(config); err == nil {
		t.Fatal("New accepted a mismatched checksum")
	}
}

func testConfig(t *testing.T, java, jar, workspace string, timeout time.Duration) Config {
	t.Helper()
	catalogPath := filepath.Join(workspace, "registry.json")
	registry := `{"version":1,"default":"html","templates":[` +
		`{"id":"html","base":"html","description":"HTML"},` +
		`{"id":"company-react","base":"react","description":"Company React"}]}`
	if err := os.WriteFile(catalogPath, []byte(registry), 0o600); err != nil {
		t.Fatal(err)
	}
	catalog, err := templatecatalog.Load(catalogPath)
	if err != nil {
		t.Fatal(err)
	}
	return Config{
		JavaBinary:    java,
		JarPath:       jar,
		WorkDirectory: workspace,
		Timeout:       timeout,
		MaxOutputSize: 4096,
		ArchiveLimits: archivezip.Limits{MaxEntries: 100, MaxUncompressedSize: 1024 * 1024, MaxCompressedSize: 1024 * 1024},
		Catalog:       catalog,
	}
}

func fakeJava(t *testing.T, workspace string, slow bool) string {
	t.Helper()
	path := filepath.Join(workspace, "fake-java")
	sleep := ""
	if slow {
		sleep = "sleep 1\n"
	}
	script := "#!/bin/sh\n" +
		"for value in \"$@\"; do\n" +
		"  if [ \"$value\" = \"--version\" ]; then echo 'normal JVM notice' >&2; echo 'wiz-spring 1.1.0'; exit 0; fi\n" +
		"done\n" +
		sleep +
		"previous=''\n" +
		"target=''\n" +
		"package=''\n" +
		"template=''\n" +
		"for value in \"$@\"; do\n" +
		"  if [ \"$previous\" = 'create' ]; then target=\"$value\"; fi\n" +
		"  if [ \"$previous\" = '--package' ]; then package=\"$value\"; fi\n" +
		"  if [ \"$previous\" = '--template' ]; then template=\"$value\"; fi\n" +
		"  previous=\"$value\"\n" +
		"done\n" +
		"mkdir -p \"$target\"\n" +
		"printf '%s' \"$package\" > \"$target/package.txt\"\n" +
		"printf '%s' \"$template\" > \"$target/template.txt\"\n" +
		"printf '{\"wiz\":{\"frontend\":\"%s\"}}' \"$template\" > \"$target/package.json\"\n"
	if err := os.WriteFile(path, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	return path
}
