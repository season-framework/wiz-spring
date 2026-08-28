package templatecatalog

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestLoadPreservesRegistryAllowlistOrderAndDefault(t *testing.T) {
	root := t.TempDir()
	registry := `{
  "version": 1,
  "default": "company-react",
  "templates": [
    {"id":"company-react","base":"react","description":"Company React"},
    {"id":"html","base":"html","description":"Static HTML"}
  ]
}`
	catalog := loadTestCatalog(t, root, registry)
	if catalog.DefaultID() != "company-react" {
		t.Fatalf("default = %q", catalog.DefaultID())
	}
	templates := catalog.Templates()
	if len(templates) != 2 || templates[0].ID != "company-react" || templates[1].ID != "html" {
		t.Fatalf("unexpected templates: %#v", templates)
	}
	if base, ok := catalog.Base("company-react"); !ok || base != "react" {
		t.Fatalf("base = %q, ok = %v", base, ok)
	}
	if _, ok := catalog.Base("jsp"); ok {
		t.Fatal("omitted jsp template remains registered")
	}
}

func TestApplyRemovesOverlaysSubstitutesAndKeepsLoadedSnapshot(t *testing.T) {
	root := t.TempDir()
	overlay := filepath.Join(root, "company", "overlay")
	mustMkdirAll(t, filepath.Join(overlay, "src", "main", "java", "__WIZ_PACKAGE_PATH__"))
	mustWriteFile(t, filepath.Join(overlay, "README.md"), []byte("# __WIZ_PROJECT_NAME__\n__WIZ_TEMPLATE_ID__ on __WIZ_BASE_TEMPLATE__\n"), 0o644)
	mustWriteFile(t, filepath.Join(overlay, "src", "main", "java", "__WIZ_PACKAGE_PATH__", "marker.txt"), []byte("__WIZ_PACKAGE_ROOT__"), 0o644)
	mustWriteFile(t, filepath.Join(overlay, "run.sh"), []byte("#!/bin/sh\necho __WIZ_PROJECT_NAME__\n"), 0o755)
	binary := []byte{0xff, 0x00, '_', '_', 'W', 'I', 'Z', '_'}
	mustWriteFile(t, filepath.Join(overlay, "binary.dat"), binary, 0o644)

	registry := `{"version":1,"default":"company-react","templates":[{
  "id":"company-react","base":"react","description":"Company React",
  "remove":["old.txt"],"overlay":"company/overlay"
}]}`
	catalog := loadTestCatalog(t, root, registry)
	// A running process applies the startup snapshot, not later filesystem edits.
	mustWriteFile(t, filepath.Join(overlay, "README.md"), []byte("changed after load"), 0o644)

	target := filepath.Join(root, "generated")
	mustMkdirAll(t, target)
	mustWriteFile(t, filepath.Join(target, "package.json"), []byte(`{"wiz":{"frontend":"react"}}`), 0o644)
	mustWriteFile(t, filepath.Join(target, "old.txt"), []byte("remove me"), 0o644)
	if err := catalog.Apply(context.Background(), target, "company-react", Variables{
		ProjectName: "demo-app",
		PackageName: "com.example.demo",
	}); err != nil {
		t.Fatalf("Apply returned an error: %v", err)
	}
	if _, err := os.Stat(filepath.Join(target, "old.txt")); !os.IsNotExist(err) {
		t.Fatalf("old.txt still exists: %v", err)
	}
	readme := mustReadFile(t, filepath.Join(target, "README.md"))
	if !strings.Contains(string(readme), "# demo-app") || !strings.Contains(string(readme), "company-react on react") {
		t.Fatalf("unexpected README: %s", readme)
	}
	marker := mustReadFile(t, filepath.Join(target, "src", "main", "java", "com", "example", "demo", "marker.txt"))
	if string(marker) != "com.example.demo" {
		t.Fatalf("marker = %q", marker)
	}
	if actual := mustReadFile(t, filepath.Join(target, "binary.dat")); !bytes.Equal(actual, binary) {
		t.Fatalf("binary changed: %v", actual)
	}
	info, err := os.Stat(filepath.Join(target, "run.sh"))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o755 {
		t.Fatalf("run.sh mode = %o", info.Mode().Perm())
	}
}

func TestApplyAllowsPlaceholderShapedUserValues(t *testing.T) {
	root := t.TempDir()
	overlay := filepath.Join(root, "overlay")
	mustMkdirAll(t, overlay)
	mustWriteFile(t, filepath.Join(overlay, "value.txt"), []byte("__WIZ_PACKAGE_ROOT__"), 0o644)
	registry := `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom","overlay":"overlay"}]}`
	catalog := loadTestCatalog(t, root, registry)
	target := filepath.Join(root, "generated")
	mustMkdirAll(t, target)
	mustWriteFile(t, filepath.Join(target, "package.json"), []byte(`{"wiz":{"frontend":"html"}}`), 0o644)
	packageName := "com.__WIZ_PROJECT_NAME__"
	if err := catalog.Apply(context.Background(), target, "custom", Variables{ProjectName: "demo", PackageName: packageName}); err != nil {
		t.Fatalf("Apply returned an error: %v", err)
	}
	if actual := string(mustReadFile(t, filepath.Join(target, "value.txt"))); actual != packageName {
		t.Fatalf("value = %q, want %q", actual, packageName)
	}
}

func TestStageIncludesOnlySelectedRegistryAssets(t *testing.T) {
	root := t.TempDir()
	overlay := filepath.Join(root, "active", "overlay")
	mustMkdirAll(t, overlay)
	mustWriteFile(t, filepath.Join(overlay, "active.txt"), []byte("active"), 0o644)
	mustWriteFile(t, filepath.Join(root, "unused-secret.txt"), []byte("must not ship"), 0o600)
	mustWriteFile(t, filepath.Join(root, "registry.unused.json"), []byte(`{"secret":true}`), 0o600)
	registry := `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom","overlay":"active/overlay"}]}`
	registryPath := filepath.Join(root, "registry.company.json")
	mustWriteFile(t, registryPath, []byte(registry), 0o600)
	destination := filepath.Join(t.TempDir(), "bundle")
	catalog, err := Stage(registryPath, destination)
	if err != nil {
		t.Fatalf("Stage returned an error: %v", err)
	}
	if catalog.DefaultID() != "custom" {
		t.Fatalf("default = %q", catalog.DefaultID())
	}
	for _, expected := range []string{"registry.json", "active/overlay/active.txt"} {
		if _, err = os.Stat(filepath.Join(destination, filepath.FromSlash(expected))); err != nil {
			t.Fatalf("missing staged asset %s: %v", expected, err)
		}
	}
	for _, unwanted := range []string{"unused-secret.txt", "registry.unused.json"} {
		if _, err = os.Stat(filepath.Join(destination, unwanted)); !os.IsNotExist(err) {
			t.Fatalf("unselected asset was staged: %s", unwanted)
		}
	}
	if _, err = Stage(registryPath, destination); err == nil {
		t.Fatal("Stage replaced an existing destination")
	}
}

func TestLoadRejectsInvalidRegistryContracts(t *testing.T) {
	tests := []struct {
		name     string
		registry string
		contains string
	}{
		{"unknown field", `{"version":1,"default":"html","unknown":true,"templates":[{"id":"html","base":"html","description":"HTML"}]}`, "unknown field"},
		{"trailing JSON", `{"version":1,"default":"html","templates":[{"id":"html","base":"html","description":"HTML"}]} {}`, "multiple JSON"},
		{"missing default", `{"version":1,"default":"react","templates":[{"id":"html","base":"html","description":"HTML"}]}`, "not present"},
		{"duplicate id", `{"version":1,"default":"html","templates":[{"id":"html","base":"html","description":"HTML"},{"id":"html","base":"html","description":"Again"}]}`, "duplicate id"},
		{"unsupported base", `{"version":1,"default":"company","templates":[{"id":"company","base":"vue","description":"Vue"}]}`, "must be one of"},
		{"built-in mismatch", `{"version":1,"default":"react","templates":[{"id":"react","base":"html","description":"Wrong"}]}`, "same base"},
		{"traversal remove", `{"version":1,"default":"html","templates":[{"id":"html","base":"html","description":"HTML","remove":["../secret"]}]}`, "unsafe component"},
		{"Windows absolute remove", `{"version":1,"default":"html","templates":[{"id":"html","base":"html","description":"HTML","remove":["C:/secret"]}]}`, "safe forward-slash"},
		{"unknown placeholder", `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"HTML","remove":["__WIZ_SECRET__/file"]}]}`, "unsupported placeholder"},
		{"overlapping remove", `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"HTML","remove":["docs","docs/old.md"]}]}`, "overlaps"},
		{"missing overlay", `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"HTML","overlay":"missing"}]}`, "inspect"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			path := filepath.Join(root, "registry.json")
			mustWriteFile(t, path, []byte(test.registry), 0o600)
			_, err := Load(path)
			if err == nil || !strings.Contains(err.Error(), test.contains) {
				t.Fatalf("error = %v, want substring %q", err, test.contains)
			}
		})
	}
}

func TestLoadRejectsOverlaySymlinkAndUnknownTextPlaceholder(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink permissions vary on Windows")
	}
	for _, test := range []struct {
		name  string
		setup func(*testing.T, string)
		want  string
	}{
		{
			name: "symlink",
			setup: func(t *testing.T, overlay string) {
				if err := os.Symlink("/etc/passwd", filepath.Join(overlay, "leak")); err != nil {
					t.Fatal(err)
				}
			},
			want: "symbolic links",
		},
		{
			name: "unknown placeholder",
			setup: func(t *testing.T, overlay string) {
				mustWriteFile(t, filepath.Join(overlay, "bad.txt"), []byte("__WIZ_UNKNOWN__"), 0o644)
			},
			want: "unsupported placeholder",
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			overlay := filepath.Join(root, "overlay")
			mustMkdirAll(t, overlay)
			test.setup(t, overlay)
			registry := `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom","overlay":"overlay"}]}`
			path := filepath.Join(root, "registry.json")
			mustWriteFile(t, path, []byte(registry), 0o600)
			_, err := Load(path)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error = %v, want substring %q", err, test.want)
			}
		})
	}
}

func TestApplyFailsClosedOnBaseDriftAndForbiddenState(t *testing.T) {
	tests := []struct {
		name     string
		registry string
		prepare  func(*testing.T, string)
		contains string
	}{
		{
			name:     "missing remove path",
			registry: `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom","remove":["missing.txt"]}]}`,
			prepare:  func(*testing.T, string) {},
			contains: "does not exist",
		},
		{
			name:     "manifest base mismatch",
			registry: `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom"}]}`,
			prepare: func(t *testing.T, target string) {
				mustWriteFile(t, filepath.Join(target, "package.json"), []byte(`{"wiz":{"frontend":"react"}}`), 0o644)
			},
			contains: "must remain",
		},
		{
			name:     ".wiz directory",
			registry: `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom"}]}`,
			prepare: func(t *testing.T, target string) {
				mustMkdirAll(t, filepath.Join(target, ".wiz"))
			},
			contains: ".wiz paths",
		},
		{
			name:     "escaped forbidden package dependency",
			registry: `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom"}]}`,
			prepare: func(t *testing.T, target string) {
				mustWriteFile(t, filepath.Join(target, "package.json"), []byte(`{"wiz":{"frontend":"html"},"dependencies":{"@season-framework\u002fwiz-frontend":"1.0.0"}}`), 0o644)
			},
			contains: "must not depend",
		},
		{
			name:     "escaped forbidden package lock entry",
			registry: `{"version":1,"default":"custom","templates":[{"id":"custom","base":"html","description":"Custom"}]}`,
			prepare: func(t *testing.T, target string) {
				mustWriteFile(t, filepath.Join(target, "package-lock.json"), []byte(`{"packages":{"node_modules/@season-framework\u002fwiz-frontend":{"version":"1.0.0"}}}`), 0o644)
			},
			contains: "must not depend",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			catalog := loadTestCatalog(t, root, test.registry)
			target := filepath.Join(root, "generated")
			mustMkdirAll(t, target)
			mustWriteFile(t, filepath.Join(target, "package.json"), []byte(`{"wiz":{"frontend":"html"}}`), 0o644)
			test.prepare(t, target)
			err := catalog.Apply(context.Background(), target, "custom", Variables{ProjectName: "demo", PackageName: "com.example.demo"})
			if err == nil || !strings.Contains(err.Error(), test.contains) {
				t.Fatalf("error = %v, want substring %q", err, test.contains)
			}
		})
	}
}

func loadTestCatalog(t *testing.T, root, registry string) *Catalog {
	t.Helper()
	path := filepath.Join(root, "registry.json")
	mustWriteFile(t, path, []byte(registry), 0o600)
	catalog, err := Load(path)
	if err != nil {
		t.Fatalf("Load returned an error: %v", err)
	}
	return catalog
}

func mustMkdirAll(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatal(err)
	}
}

func mustWriteFile(t *testing.T, path string, contents []byte, mode os.FileMode) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, contents, mode); err != nil {
		t.Fatal(err)
	}
}

func mustReadFile(t *testing.T, path string) []byte {
	t.Helper()
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return contents
}
