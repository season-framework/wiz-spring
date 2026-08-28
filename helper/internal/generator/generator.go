package generator

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	archivezip "github.com/season-framework/wiz-spring/helper/internal/archive"
	"github.com/season-framework/wiz-spring/helper/internal/project"
	"github.com/season-framework/wiz-spring/helper/internal/templatecatalog"
)

const Version = "1.0.0"

var ErrGenerationTimeout = errors.New("project generation timed out")

type Config struct {
	JavaBinary     string
	JarPath        string
	WorkDirectory  string
	Timeout        time.Duration
	MaxOutputSize  int
	ArchiveLimits  archivezip.Limits
	ExpectedSHA256 string
	Catalog        *templatecatalog.Catalog
}

// CommandError contains bounded diagnostic output for server logs. HTTP
// responses intentionally do not expose it because it can contain host paths.
type CommandError struct {
	Output string
	Err    error
}

func (e *CommandError) Error() string {
	return fmt.Sprintf("wiz-spring create failed: %v", e.Err)
}

func (e *CommandError) Unwrap() error {
	return e.Err
}

// Archive owns a completed ZIP and its isolated request directory.
type Archive struct {
	path     string
	filename string
	size     int64
	cleanup  func() error
	once     sync.Once
	closeErr error
}

func NewArchive(path, filename string, size int64, cleanup func() error) *Archive {
	if cleanup == nil {
		cleanup = func() error { return nil }
	}
	return &Archive{path: path, filename: filename, size: size, cleanup: cleanup}
}

func (archive *Archive) Path() string     { return archive.path }
func (archive *Archive) Filename() string { return archive.filename }
func (archive *Archive) Size() int64      { return archive.size }

func (archive *Archive) Close() error {
	archive.once.Do(func() { archive.closeErr = archive.cleanup() })
	return archive.closeErr
}

// CommandGenerator invokes a fixed JAR without a shell and archives only the
// fresh-project target created below its private temporary directory.
type CommandGenerator struct {
	javaBinary    string
	jarPath       string
	workDirectory string
	timeout       time.Duration
	maxOutputSize int
	archiveLimits archivezip.Limits
	catalog       *templatecatalog.Catalog
}

func New(config Config) (*CommandGenerator, error) {
	if strings.TrimSpace(config.JavaBinary) == "" {
		config.JavaBinary = "java"
	}
	javaBinary, err := executablePath(config.JavaBinary)
	if err != nil {
		return nil, fmt.Errorf("resolve Java executable: %w", err)
	}
	config.JavaBinary = javaBinary
	if strings.TrimSpace(config.JarPath) == "" {
		return nil, errors.New("WIZ Spring JAR path is required")
	}
	jarPath, err := filepath.Abs(config.JarPath)
	if err != nil {
		return nil, fmt.Errorf("resolve WIZ Spring JAR: %w", err)
	}
	info, err := os.Stat(jarPath)
	if err != nil {
		return nil, fmt.Errorf("inspect WIZ Spring JAR: %w", err)
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("WIZ Spring JAR is not a regular file: %s", jarPath)
	}
	if err = verifySHA256(jarPath, config.ExpectedSHA256); err != nil {
		return nil, err
	}
	if config.Timeout <= 0 {
		return nil, errors.New("generation timeout must be positive")
	}
	if config.MaxOutputSize <= 0 {
		return nil, errors.New("maximum command output must be positive")
	}
	if config.Catalog == nil {
		return nil, errors.New("template catalog is required")
	}
	if config.WorkDirectory != "" {
		workDirectory, resolveError := filepath.Abs(config.WorkDirectory)
		if resolveError != nil {
			return nil, fmt.Errorf("resolve work directory: %w", resolveError)
		}
		if makeError := os.MkdirAll(workDirectory, 0o700); makeError != nil {
			return nil, fmt.Errorf("create work directory: %w", makeError)
		}
		config.WorkDirectory = workDirectory
	}

	return &CommandGenerator{
		javaBinary:    config.JavaBinary,
		jarPath:       jarPath,
		workDirectory: config.WorkDirectory,
		timeout:       config.Timeout,
		maxOutputSize: config.MaxOutputSize,
		archiveLimits: config.ArchiveLimits,
		catalog:       config.Catalog,
	}, nil
}

func executablePath(value string) (string, error) {
	value = strings.TrimSpace(value)
	resolved := value
	var err error
	if !filepath.IsAbs(value) && !strings.ContainsAny(value, `/\\`) {
		resolved, err = exec.LookPath(value)
		if err != nil {
			return "", err
		}
	}
	resolved, err = filepath.Abs(resolved)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("not a regular file: %s", resolved)
	}
	return resolved, nil
}

func (generator *CommandGenerator) Version() string { return Version }

// Probe verifies that the configured executable is the expected generator
// release. The actual create call still performs its canonical JDK/Node/npm
// preflight for every request.
func (generator *CommandGenerator) Probe(ctx context.Context) error {
	probeContext, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	stdout := &boundedBuffer{maximum: generator.maxOutputSize}
	stderr := &boundedBuffer{maximum: generator.maxOutputSize}
	command := exec.CommandContext(probeContext, generator.javaBinary, "-jar", generator.jarPath, "--version")
	configureCommand(command)
	command.Stdout = stdout
	command.Stderr = stderr
	if err := command.Run(); err != nil {
		if errors.Is(probeContext.Err(), context.DeadlineExceeded) {
			return errors.New("WIZ Spring version probe timed out")
		}
		return fmt.Errorf("WIZ Spring version probe failed: %w: %s", err, strings.TrimSpace(stdout.String()+"\n"+stderr.String()))
	}
	want := "wiz-spring " + Version
	if actual := strings.TrimSpace(stdout.String()); actual != want {
		return fmt.Errorf("unexpected WIZ Spring version %q (required: %q)", actual, want)
	}
	return nil
}

func (generator *CommandGenerator) Generate(ctx context.Context, spec project.Spec) (*Archive, error) {
	baseTemplate, registered := generator.catalog.Base(spec.Template)
	if !registered {
		return nil, fmt.Errorf("template %q is not registered", spec.Template)
	}
	requestRoot, err := os.MkdirTemp(generator.workDirectory, "wiz-spring-helper-")
	if err != nil {
		return nil, fmt.Errorf("create request workspace: %w", err)
	}
	keepWorkspace := false
	defer func() {
		if !keepWorkspace {
			_ = os.RemoveAll(requestRoot)
		}
	}()

	target := filepath.Join(requestRoot, spec.ProjectName)
	if filepath.Dir(target) != requestRoot {
		return nil, errors.New("validated project name escaped the request workspace")
	}
	commandContext, cancel := context.WithTimeout(ctx, generator.timeout)
	defer cancel()
	output := &boundedBuffer{maximum: generator.maxOutputSize}
	command := exec.CommandContext(
		commandContext,
		generator.javaBinary,
		"-Xms32m",
		"-Xmx256m",
		"-Djava.io.tmpdir="+requestRoot,
		"-jar",
		generator.jarPath,
		"create",
		target,
		"--package",
		spec.PackageName,
		"--template",
		baseTemplate,
	)
	command.Dir = requestRoot
	configureCommand(command)
	command.Stdout = output
	command.Stderr = output
	if err = command.Run(); err != nil {
		if errors.Is(commandContext.Err(), context.DeadlineExceeded) {
			return nil, ErrGenerationTimeout
		}
		if contextError := ctx.Err(); contextError != nil {
			return nil, contextError
		}
		return nil, &CommandError{Output: generator.sanitizeOutput(output.String(), requestRoot), Err: err}
	}
	if err = generator.catalog.Apply(commandContext, target, spec.Template, templatecatalog.Variables{
		ProjectName: spec.ProjectName,
		PackageName: spec.PackageName,
	}); err != nil {
		if errors.Is(commandContext.Err(), context.DeadlineExceeded) {
			return nil, ErrGenerationTimeout
		}
		if contextError := ctx.Err(); contextError != nil {
			return nil, contextError
		}
		return nil, fmt.Errorf("apply template customization: %w", err)
	}

	archivePath := filepath.Join(requestRoot, spec.ProjectName+".zip")
	if _, err = archivezip.Create(commandContext, target, archivePath, spec.ProjectName, generator.archiveLimits); err != nil {
		if errors.Is(commandContext.Err(), context.DeadlineExceeded) {
			return nil, ErrGenerationTimeout
		}
		if contextError := ctx.Err(); contextError != nil {
			return nil, contextError
		}
		return nil, fmt.Errorf("archive generated project: %w", err)
	}
	archiveInfo, err := os.Stat(archivePath)
	if err != nil {
		return nil, fmt.Errorf("inspect generated archive: %w", err)
	}
	keepWorkspace = true
	return NewArchive(
		archivePath,
		spec.ProjectName+".zip",
		archiveInfo.Size(),
		func() error { return os.RemoveAll(requestRoot) },
	), nil
}

func verifySHA256(path, expected string) error {
	expected = strings.ToLower(strings.TrimSpace(expected))
	if expected == "" {
		return nil
	}
	decoded, err := hex.DecodeString(expected)
	if err != nil || len(decoded) != sha256.Size {
		return errors.New("WIZ Spring SHA-256 must be exactly 64 hexadecimal characters")
	}
	input, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open WIZ Spring JAR for checksum: %w", err)
	}
	defer input.Close()
	hash := sha256.New()
	if _, err = io.Copy(hash, input); err != nil {
		return fmt.Errorf("hash WIZ Spring JAR: %w", err)
	}
	actual := hex.EncodeToString(hash.Sum(nil))
	if actual != expected {
		return fmt.Errorf("WIZ Spring JAR SHA-256 mismatch: got %s", actual)
	}
	return nil
}

func (generator *CommandGenerator) sanitizeOutput(output, requestRoot string) string {
	output = strings.ReplaceAll(output, requestRoot, "<request-workspace>")
	output = strings.ReplaceAll(output, generator.jarPath, "<wiz-spring.jar>")
	return strings.TrimSpace(output)
}

type boundedBuffer struct {
	contents  []byte
	maximum   int
	truncated bool
}

func (buffer *boundedBuffer) Write(contents []byte) (int, error) {
	originalLength := len(contents)
	remaining := buffer.maximum - len(buffer.contents)
	if remaining > 0 {
		if len(contents) > remaining {
			contents = contents[:remaining]
			buffer.truncated = true
		}
		buffer.contents = append(buffer.contents, contents...)
	} else if originalLength > 0 {
		buffer.truncated = true
	}
	return originalLength, nil
}

func (buffer *boundedBuffer) String() string {
	if !buffer.truncated {
		return string(buffer.contents)
	}
	return string(buffer.contents) + "\n[output truncated]"
}

var _ io.Writer = (*boundedBuffer)(nil)
