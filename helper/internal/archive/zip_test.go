package archive

import (
	"archive/zip"
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestCreatePreservesRootAndExecutableMode(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, "demo")
	if err := os.MkdirAll(filepath.Join(source, "src"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "mvnw"), []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "src", "file.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}

	destination := filepath.Join(workspace, "demo.zip")
	stats, err := Create(context.Background(), source, destination, "demo", Limits{100, 1024, 4096})
	if err != nil {
		t.Fatalf("Create returned an error: %v", err)
	}
	if stats.Entries != 4 {
		t.Fatalf("entries = %d, want 4", stats.Entries)
	}

	reader, err := zip.OpenReader(destination)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()

	seen := make(map[string]os.FileMode)
	for _, file := range reader.File {
		seen[file.Name] = file.Mode()
	}
	if _, ok := seen["demo/"]; !ok {
		t.Fatalf("archive does not contain demo/: %#v", seen)
	}
	if mode := seen["demo/mvnw"]; mode&0o111 == 0 {
		t.Fatalf("mvnw mode = %v, executable bits were lost", mode)
	}
}

func TestCreateRejectsSymlink(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, "demo")
	if err := os.Mkdir(source, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("outside", filepath.Join(source, "link")); err != nil {
		t.Fatal(err)
	}
	_, err := Create(context.Background(), source, filepath.Join(workspace, "demo.zip"), "demo", Limits{100, 1024, 4096})
	if err == nil {
		t.Fatal("Create accepted a symlink")
	}
}

func TestCreateEnforcesUncompressedLimit(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, "demo")
	if err := os.Mkdir(source, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "large"), make([]byte, 128), 0o644); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(workspace, "demo.zip")
	_, err := Create(context.Background(), source, destination, "demo", Limits{100, 64, 4096})
	if !errors.Is(err, ErrLimitExceeded) {
		t.Fatalf("error = %v, want ErrLimitExceeded", err)
	}
	if _, statError := os.Stat(destination); !os.IsNotExist(statError) {
		t.Fatalf("partial archive remains: %v", statError)
	}
}

func TestCreateHonorsCanceledContext(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, "demo")
	if err := os.Mkdir(source, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "file"), []byte("contents"), 0o644); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	destination := filepath.Join(workspace, "demo.zip")
	_, err := Create(ctx, source, destination, "demo", Limits{100, 1024, 4096})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("error = %v, want context.Canceled", err)
	}
	if _, statError := os.Stat(destination); !os.IsNotExist(statError) {
		t.Fatalf("archive was created after cancellation: %v", statError)
	}
}

func TestCreateStopsAtEntryLimitBeforeOpeningDestination(t *testing.T) {
	workspace := t.TempDir()
	source := filepath.Join(workspace, "demo")
	if err := os.Mkdir(source, 0o755); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"one", "two"} {
		if err := os.WriteFile(filepath.Join(source, name), []byte(name), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	destination := filepath.Join(workspace, "demo.zip")
	_, err := Create(context.Background(), source, destination, "demo", Limits{MaxEntries: 2, MaxUncompressedSize: 1024, MaxCompressedSize: 4096})
	if !errors.Is(err, ErrLimitExceeded) {
		t.Fatalf("error = %v, want ErrLimitExceeded", err)
	}
	if _, statError := os.Stat(destination); !os.IsNotExist(statError) {
		t.Fatalf("destination was opened before entry-limit failure: %v", statError)
	}
}
