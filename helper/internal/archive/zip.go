package archive

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

var ErrLimitExceeded = errors.New("archive limit exceeded")

// Limits bound the fixed-template archive and protect the server if a supplied
// generator artifact is unexpectedly large.
type Limits struct {
	MaxEntries          int
	MaxUncompressedSize int64
	MaxCompressedSize   int64
}

type Stats struct {
	Entries          int
	UncompressedSize int64
	CompressedSize   int64
}

// Create writes a complete ZIP with rootName as its only top-level directory.
// Symlinks and non-regular filesystem objects are rejected, and Unix modes such
// as the executable bit on mvnw are preserved by archive/zip.
func Create(ctx context.Context, sourceRoot, destination, rootName string, limits Limits) (stats Stats, returnedError error) {
	if rootName == "" || strings.ContainsAny(rootName, `/\\`) || rootName == "." || rootName == ".." {
		return Stats{}, fmt.Errorf("invalid archive root name %q", rootName)
	}
	if limits.MaxEntries <= 0 || limits.MaxUncompressedSize <= 0 || limits.MaxCompressedSize <= 0 {
		return Stats{}, errors.New("archive limits must be positive")
	}

	root, err := filepath.Abs(sourceRoot)
	if err != nil {
		return Stats{}, fmt.Errorf("resolve source root: %w", err)
	}
	rootInfo, err := os.Lstat(root)
	if err != nil {
		return Stats{}, fmt.Errorf("inspect source root: %w", err)
	}
	if !rootInfo.IsDir() {
		return Stats{}, errors.New("archive source root is not a directory")
	}

	paths := make([]string, 0, 256)
	err = filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkError error) error {
		if contextError := ctx.Err(); contextError != nil {
			return contextError
		}
		if walkError != nil {
			return walkError
		}
		if path == root {
			return nil
		}
		info, infoError := os.Lstat(path)
		if infoError != nil {
			return infoError
		}
		mode := info.Mode()
		if mode&os.ModeSymlink != 0 {
			return fmt.Errorf("refusing symlink in generated project: %s", path)
		}
		if !mode.IsDir() && !mode.IsRegular() {
			return fmt.Errorf("refusing non-regular entry in generated project: %s", path)
		}
		if len(paths)+2 > limits.MaxEntries {
			return fmt.Errorf("%w: more than %d entries", ErrLimitExceeded, limits.MaxEntries)
		}
		paths = append(paths, path)
		return nil
	})
	if err != nil {
		return Stats{}, fmt.Errorf("walk generated project: %w", err)
	}
	sort.Slice(paths, func(left, right int) bool {
		return filepath.ToSlash(paths[left]) < filepath.ToSlash(paths[right])
	})
	if contextError := ctx.Err(); contextError != nil {
		return Stats{}, contextError
	}

	output, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return Stats{}, fmt.Errorf("create archive: %w", err)
	}
	defer func() {
		closeError := output.Close()
		if returnedError == nil && closeError != nil {
			returnedError = closeError
		}
		if returnedError != nil {
			_ = os.Remove(destination)
		}
	}()

	counter := &limitedWriter{writer: output, maximum: limits.MaxCompressedSize}
	zipWriter := zip.NewWriter(counter)
	closeZip := func() error {
		if err := zipWriter.Close(); err != nil {
			return fmt.Errorf("finish archive: %w", err)
		}
		return nil
	}

	rootHeader, err := zip.FileInfoHeader(rootInfo)
	if err != nil {
		return Stats{}, err
	}
	rootHeader.Name = rootName + "/"
	rootHeader.Method = zip.Store
	rootHeader.SetMode(rootInfo.Mode())
	if _, err = zipWriter.CreateHeader(rootHeader); err != nil {
		return Stats{}, fmt.Errorf("write archive root: %w", err)
	}
	stats.Entries = 1

	for _, path := range paths {
		if contextError := ctx.Err(); contextError != nil {
			return Stats{}, contextError
		}
		info, infoError := os.Lstat(path)
		if infoError != nil {
			return Stats{}, infoError
		}
		relative, relativeError := filepath.Rel(root, path)
		if relativeError != nil || relative == "." || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			return Stats{}, fmt.Errorf("generated entry escapes source root: %s", path)
		}

		header, headerError := zip.FileInfoHeader(info)
		if headerError != nil {
			return Stats{}, headerError
		}
		header.Name = rootName + "/" + filepath.ToSlash(relative)
		header.SetMode(info.Mode())
		if info.IsDir() {
			header.Name += "/"
			header.Method = zip.Store
		} else {
			header.Method = zip.Deflate
			stats.UncompressedSize += info.Size()
			if stats.UncompressedSize > limits.MaxUncompressedSize {
				return Stats{}, fmt.Errorf("%w: uncompressed data exceeds %d bytes", ErrLimitExceeded, limits.MaxUncompressedSize)
			}
		}

		entryWriter, createError := zipWriter.CreateHeader(header)
		if createError != nil {
			return Stats{}, fmt.Errorf("create archive entry %s: %w", relative, createError)
		}
		if info.Mode().IsRegular() {
			input, openError := os.Open(path)
			if openError != nil {
				return Stats{}, openError
			}
			_, copyError := io.Copy(entryWriter, &contextReader{ctx: ctx, reader: input})
			closeError := input.Close()
			if copyError != nil {
				return Stats{}, fmt.Errorf("archive %s: %w", relative, copyError)
			}
			if closeError != nil {
				return Stats{}, closeError
			}
		}
		stats.Entries++
	}

	if contextError := ctx.Err(); contextError != nil {
		return Stats{}, contextError
	}
	if err = closeZip(); err != nil {
		return Stats{}, err
	}
	stats.CompressedSize = counter.written
	return stats, nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader *contextReader) Read(contents []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(contents)
}

type limitedWriter struct {
	writer  io.Writer
	maximum int64
	written int64
}

func (writer *limitedWriter) Write(contents []byte) (int, error) {
	remaining := writer.maximum - writer.written
	if remaining <= 0 || int64(len(contents)) > remaining {
		return 0, fmt.Errorf("%w: compressed data exceeds %d bytes", ErrLimitExceeded, writer.maximum)
	}
	written, err := writer.writer.Write(contents)
	writer.written += int64(written)
	return written, err
}
