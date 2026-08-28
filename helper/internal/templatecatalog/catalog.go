package templatecatalog

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	registryVersion      = 1
	maxRegistryBytes     = 1 << 20
	maxTemplates         = 64
	maxDescriptionBytes  = 256
	maxRelativePathBytes = 512
	maxOverlayEntries    = 2_000
	maxOverlayFileBytes  = 8 << 20
	maxOverlayTotalBytes = 32 << 20
	maxPackageLockBytes  = 16 << 20
	defaultDirectoryMode = 0o755
	defaultFileMode      = 0o644
	executableFileMode   = 0o755
)

var (
	templateIDPattern  = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$`)
	placeholderPattern = regexp.MustCompile(`__WIZ_[A-Za-z0-9_]+__`)
	builtInBases       = map[string]struct{}{
		"angular-wiz": {},
		"angular":     {},
		"react":       {},
		"html":        {},
		"jsp":         {},
	}
	knownPlaceholders = map[string]string{
		"__WIZ_PROJECT_NAME__":  "example-project",
		"__WIZ_PACKAGE_ROOT__":  "com.example.project",
		"__WIZ_PACKAGE_PATH__":  "com/example/project",
		"__WIZ_TEMPLATE_ID__":   "example-template",
		"__WIZ_BASE_TEMPLATE__": "react",
	}
)

// Template is the public, immutable template metadata returned by the API.
type Template struct {
	ID          string `json:"id"`
	Base        string `json:"base"`
	Description string `json:"description"`
}

// Variables are the fixed literal substitutions supported in remove paths and
// UTF-8 overlay paths/files. No environment, shell, regexp, or template engine
// expansion is performed.
type Variables struct {
	ProjectName string
	PackageName string
}

type registryFile struct {
	Version   int                `json:"version"`
	Default   string             `json:"default"`
	Templates []registryTemplate `json:"templates"`
}

type registryTemplate struct {
	ID          string   `json:"id"`
	Base        string   `json:"base"`
	Description string   `json:"description"`
	Remove      []string `json:"remove,omitempty"`
	Overlay     string   `json:"overlay,omitempty"`
}

type definition struct {
	template    Template
	remove      []string
	overlayPath string
	overlay     []overlayEntry
}

type overlayEntry struct {
	path       string
	contents   []byte
	directory  bool
	executable bool
}

// Catalog is a startup-loaded allowlist. Its overlay contents are held in
// memory so a running container never rereads mutable customization files.
type Catalog struct {
	defaultID   string
	templates   []Template
	definitions map[string]*definition
}

// Load parses and fully validates a registry and all referenced overlays.
func Load(filename string) (*Catalog, error) {
	filename = strings.TrimSpace(filename)
	if filename == "" {
		return nil, errors.New("template registry path is required")
	}
	absolute, err := filepath.Abs(filename)
	if err != nil {
		return nil, fmt.Errorf("resolve template registry: %w", err)
	}
	info, err := os.Lstat(absolute)
	if err != nil {
		return nil, fmt.Errorf("inspect template registry: %w", err)
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("template registry must be a regular file: %s", absolute)
	}
	contents, err := readBoundedFile(absolute, maxRegistryBytes)
	if err != nil {
		return nil, fmt.Errorf("read template registry: %w", err)
	}
	var registry registryFile
	decoder := json.NewDecoder(bytes.NewReader(contents))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&registry); err != nil {
		return nil, fmt.Errorf("decode template registry: %w", err)
	}
	if err = requireJSONEnd(decoder); err != nil {
		return nil, fmt.Errorf("decode template registry: %w", err)
	}
	if registry.Version != registryVersion {
		return nil, fmt.Errorf("template registry version must be %d", registryVersion)
	}
	if len(registry.Templates) == 0 || len(registry.Templates) > maxTemplates {
		return nil, fmt.Errorf("template registry must contain between 1 and %d templates", maxTemplates)
	}

	registryDirectory, err := filepath.EvalSymlinks(filepath.Dir(absolute))
	if err != nil {
		return nil, fmt.Errorf("resolve template registry directory: %w", err)
	}
	catalog := &Catalog{
		defaultID:   strings.TrimSpace(registry.Default),
		templates:   make([]Template, 0, len(registry.Templates)),
		definitions: make(map[string]*definition, len(registry.Templates)),
	}
	for index, configured := range registry.Templates {
		compiled, compileError := compileDefinition(registryDirectory, configured)
		if compileError != nil {
			return nil, fmt.Errorf("template[%d]: %w", index, compileError)
		}
		if _, duplicate := catalog.definitions[compiled.template.ID]; duplicate {
			return nil, fmt.Errorf("template[%d]: duplicate id %q", index, compiled.template.ID)
		}
		catalog.definitions[compiled.template.ID] = compiled
		catalog.templates = append(catalog.templates, compiled.template)
	}
	if !templateIDPattern.MatchString(catalog.defaultID) {
		return nil, errors.New("template registry default must be a valid template id")
	}
	if _, exists := catalog.definitions[catalog.defaultID]; !exists {
		return nil, fmt.Errorf("template registry default %q is not present in templates", catalog.defaultID)
	}
	return catalog, nil
}

func compileDefinition(registryDirectory string, configured registryTemplate) (*definition, error) {
	id := strings.TrimSpace(configured.ID)
	if !templateIDPattern.MatchString(id) {
		return nil, errors.New("id must be a 1-64 character lowercase ASCII slug")
	}
	base := strings.TrimSpace(configured.Base)
	if _, supported := builtInBases[base]; !supported {
		return nil, fmt.Errorf("base for %q must be one of angular-wiz, angular, react, html, jsp", id)
	}
	if _, builtInID := builtInBases[id]; builtInID && id != base {
		return nil, fmt.Errorf("built-in id %q must use the same base", id)
	}
	description := strings.TrimSpace(configured.Description)
	if description == "" || len(description) > maxDescriptionBytes {
		return nil, fmt.Errorf("description for %q must be between 1 and %d bytes", id, maxDescriptionBytes)
	}
	for _, character := range description {
		if unicode.IsControl(character) {
			return nil, fmt.Errorf("description for %q must not contain control characters", id)
		}
	}

	remove := make([]string, 0, len(configured.Remove))
	seenRemove := make(map[string]struct{}, len(configured.Remove))
	for _, candidate := range configured.Remove {
		if err := validateTemplatedRelativePath(candidate, "remove path"); err != nil {
			return nil, fmt.Errorf("template %q: %w", id, err)
		}
		if _, duplicate := seenRemove[candidate]; duplicate {
			return nil, fmt.Errorf("template %q has duplicate remove path %q", id, candidate)
		}
		seenRemove[candidate] = struct{}{}
		remove = append(remove, candidate)
	}
	if err := rejectOverlappingPaths(remove, knownPlaceholders); err != nil {
		return nil, fmt.Errorf("template %q remove paths: %w", id, err)
	}

	var overlay []overlayEntry
	overlayPath := strings.TrimSpace(configured.Overlay)
	if overlayPath != "" {
		if err := validateRelativePath(overlayPath, "overlay"); err != nil {
			return nil, fmt.Errorf("template %q: %w", id, err)
		}
		if overlayPath == "registry.json" || strings.HasPrefix(overlayPath, "registry.json/") {
			return nil, fmt.Errorf("template %q: overlay must not use reserved path registry.json", id)
		}
		loadedOverlay, loadError := loadOverlay(registryDirectory, overlayPath)
		if loadError != nil {
			return nil, fmt.Errorf("template %q overlay: %w", id, loadError)
		}
		overlay = loadedOverlay
	}
	return &definition{
		template:    Template{ID: id, Base: base, Description: description},
		remove:      remove,
		overlayPath: overlayPath,
		overlay:     overlay,
	}, nil
}

func loadOverlay(registryDirectory, relative string) ([]overlayEntry, error) {
	root := filepath.Join(registryDirectory, filepath.FromSlash(relative))
	if err := rejectSymlinkedComponents(registryDirectory, relative); err != nil {
		return nil, err
	}
	info, err := os.Lstat(root)
	if err != nil {
		return nil, fmt.Errorf("inspect %q: %w", relative, err)
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("%q must be a directory", relative)
	}

	entries := make([]overlayEntry, 0)
	totalBytes := int64(0)
	err = filepath.WalkDir(root, func(candidate string, entry fs.DirEntry, walkError error) error {
		if walkError != nil {
			return walkError
		}
		if candidate == root {
			return nil
		}
		if len(entries) >= maxOverlayEntries {
			return fmt.Errorf("overlay exceeds %d entries", maxOverlayEntries)
		}
		relativeCandidate, relError := filepath.Rel(root, candidate)
		if relError != nil {
			return relError
		}
		relativeCandidate = filepath.ToSlash(relativeCandidate)
		if pathError := validateTemplatedRelativePath(relativeCandidate, "overlay entry"); pathError != nil {
			return pathError
		}
		entryInfo, infoError := entry.Info()
		if infoError != nil {
			return infoError
		}
		mode := entryInfo.Mode()
		if mode&os.ModeSymlink != 0 {
			return fmt.Errorf("symbolic links are not allowed: %s", relativeCandidate)
		}
		if entry.IsDir() {
			entries = append(entries, overlayEntry{path: relativeCandidate, directory: true})
			return nil
		}
		if !mode.IsRegular() {
			return fmt.Errorf("special files are not allowed: %s", relativeCandidate)
		}
		if entryInfo.Size() > maxOverlayFileBytes {
			return fmt.Errorf("overlay file exceeds %d bytes: %s", maxOverlayFileBytes, relativeCandidate)
		}
		totalBytes += entryInfo.Size()
		if totalBytes > maxOverlayTotalBytes {
			return fmt.Errorf("overlay exceeds %d total bytes", maxOverlayTotalBytes)
		}
		contents, readError := os.ReadFile(candidate)
		if readError != nil {
			return readError
		}
		if utf8.Valid(contents) {
			if unresolved := unknownPlaceholder(string(contents)); unresolved != "" {
				return fmt.Errorf("unsupported placeholder %s in %s", unresolved, relativeCandidate)
			}
		}
		entries = append(entries, overlayEntry{
			path:       relativeCandidate,
			contents:   contents,
			executable: mode.Perm()&0o111 != 0,
		})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Slice(entries, func(left, right int) bool { return entries[left].path < entries[right].path })
	return entries, nil
}

// DefaultID returns the template selected when the request omits template.
func (catalog *Catalog) DefaultID() string { return catalog.defaultID }

// Templates returns a copy in the exact order configured by the image.
func (catalog *Catalog) Templates() []Template {
	result := make([]Template, len(catalog.templates))
	copy(result, catalog.templates)
	return result
}

// Base returns the fixed WIZ Spring CLI base for a public template id.
func (catalog *Catalog) Base(id string) (string, bool) {
	configured, ok := catalog.definitions[id]
	if !ok {
		return "", false
	}
	return configured.template.Base, true
}

// Stage creates a minimal immutable-image input containing only the selected
// registry and overlays referenced by that registry. The destination must not
// already exist, preventing accidental replacement of unrelated files.
func Stage(registryFilename, destination string) (*Catalog, error) {
	catalog, err := Load(registryFilename)
	if err != nil {
		return nil, err
	}
	destination, err = filepath.Abs(destination)
	if err != nil {
		return nil, fmt.Errorf("resolve template bundle destination: %w", err)
	}
	if err = os.MkdirAll(filepath.Dir(destination), defaultDirectoryMode); err != nil {
		return nil, fmt.Errorf("create template bundle parent: %w", err)
	}
	if err = os.Mkdir(destination, defaultDirectoryMode); err != nil {
		return nil, fmt.Errorf("create template bundle: %w", err)
	}
	complete := false
	defer func() {
		if !complete {
			_ = os.RemoveAll(destination)
		}
	}()
	registryContents, err := readBoundedFile(registryFilename, maxRegistryBytes)
	if err != nil {
		return nil, fmt.Errorf("read selected template registry: %w", err)
	}
	if err = os.WriteFile(filepath.Join(destination, "registry.json"), registryContents, defaultFileMode); err != nil {
		return nil, fmt.Errorf("stage selected template registry: %w", err)
	}

	for _, summary := range catalog.templates {
		configured := catalog.definitions[summary.ID]
		if configured.overlayPath == "" {
			continue
		}
		overlayRoot := filepath.Join(destination, filepath.FromSlash(configured.overlayPath))
		if err = os.MkdirAll(overlayRoot, defaultDirectoryMode); err != nil {
			return nil, fmt.Errorf("stage overlay %q: %w", configured.overlayPath, err)
		}
		for _, entry := range configured.overlay {
			candidate := filepath.Join(overlayRoot, filepath.FromSlash(entry.path))
			if entry.directory {
				if err = os.MkdirAll(candidate, defaultDirectoryMode); err != nil {
					return nil, fmt.Errorf("stage overlay directory %s: %w", entry.path, err)
				}
				continue
			}
			if err = os.MkdirAll(filepath.Dir(candidate), defaultDirectoryMode); err != nil {
				return nil, fmt.Errorf("stage overlay parent %s: %w", entry.path, err)
			}
			mode := fs.FileMode(defaultFileMode)
			if entry.executable {
				mode = executableFileMode
			}
			if err = os.WriteFile(candidate, entry.contents, mode); err != nil {
				return nil, fmt.Errorf("stage overlay file %s: %w", entry.path, err)
			}
			if err = os.Chmod(candidate, mode); err != nil {
				return nil, fmt.Errorf("set staged overlay file mode %s: %w", entry.path, err)
			}
		}
	}
	complete = true
	return catalog, nil
}

// Apply removes and overlays files for an already-generated built-in base,
// then verifies that the resulting project still honors that base's build
// contract.
func (catalog *Catalog) Apply(ctx context.Context, target, templateID string, variables Variables) error {
	configured, ok := catalog.definitions[templateID]
	if !ok {
		return fmt.Errorf("template %q is not registered", templateID)
	}
	target, err := filepath.Abs(target)
	if err != nil {
		return fmt.Errorf("resolve generated project: %w", err)
	}
	info, err := os.Lstat(target)
	if err != nil {
		return fmt.Errorf("inspect generated project: %w", err)
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("generated project must be a real directory")
	}
	if err = inspectProjectTree(ctx, target); err != nil {
		return fmt.Errorf("inspect generated project before customization: %w", err)
	}

	replacements := map[string]string{
		"__WIZ_PROJECT_NAME__":  variables.ProjectName,
		"__WIZ_PACKAGE_ROOT__":  variables.PackageName,
		"__WIZ_PACKAGE_PATH__":  strings.ReplaceAll(variables.PackageName, ".", "/"),
		"__WIZ_TEMPLATE_ID__":   configured.template.ID,
		"__WIZ_BASE_TEMPLATE__": configured.template.Base,
	}
	renderedRemove := make([]string, 0, len(configured.remove))
	for _, candidate := range configured.remove {
		rendered, renderError := renderPath(candidate, replacements)
		if renderError != nil {
			return fmt.Errorf("render remove path %q: %w", candidate, renderError)
		}
		renderedRemove = append(renderedRemove, rendered)
	}
	if err = rejectOverlappingPaths(renderedRemove, nil); err != nil {
		return fmt.Errorf("rendered remove paths: %w", err)
	}
	for _, relative := range renderedRemove {
		if err = ctx.Err(); err != nil {
			return err
		}
		candidate := filepath.Join(target, filepath.FromSlash(relative))
		if _, err = os.Lstat(candidate); err != nil {
			if os.IsNotExist(err) {
				return fmt.Errorf("remove path does not exist in base %q: %s", configured.template.Base, relative)
			}
			return fmt.Errorf("inspect remove path %s: %w", relative, err)
		}
		if err = os.RemoveAll(candidate); err != nil {
			return fmt.Errorf("remove %s: %w", relative, err)
		}
	}

	renderedOverlay := make([]overlayEntry, 0, len(configured.overlay))
	seenOverlay := make(map[string]struct{}, len(configured.overlay))
	for _, entry := range configured.overlay {
		renderedPath, renderError := renderPath(entry.path, replacements)
		if renderError != nil {
			return fmt.Errorf("render overlay path %q: %w", entry.path, renderError)
		}
		if _, duplicate := seenOverlay[renderedPath]; duplicate {
			return fmt.Errorf("overlay paths collide after rendering: %s", renderedPath)
		}
		seenOverlay[renderedPath] = struct{}{}
		rendered := entry
		rendered.path = renderedPath
		if !entry.directory && utf8.Valid(entry.contents) {
			rendered.contents = []byte(renderText(string(entry.contents), replacements))
		}
		renderedOverlay = append(renderedOverlay, rendered)
	}
	sort.Slice(renderedOverlay, func(left, right int) bool {
		leftDepth := strings.Count(renderedOverlay[left].path, "/")
		rightDepth := strings.Count(renderedOverlay[right].path, "/")
		if leftDepth != rightDepth {
			return leftDepth < rightDepth
		}
		if renderedOverlay[left].path != renderedOverlay[right].path {
			return renderedOverlay[left].path < renderedOverlay[right].path
		}
		return renderedOverlay[left].directory
	})
	for _, entry := range renderedOverlay {
		if err = ctx.Err(); err != nil {
			return err
		}
		candidate := filepath.Join(target, filepath.FromSlash(entry.path))
		if entry.directory {
			if err = ensureOverlayDirectory(candidate); err != nil {
				return fmt.Errorf("overlay directory %s: %w", entry.path, err)
			}
			continue
		}
		if err = ensureOverlayParent(filepath.Dir(candidate)); err != nil {
			return fmt.Errorf("overlay parent for %s: %w", entry.path, err)
		}
		if existing, statError := os.Lstat(candidate); statError == nil {
			if !existing.Mode().IsRegular() {
				return fmt.Errorf("overlay file conflicts with non-file target: %s", entry.path)
			}
		} else if !os.IsNotExist(statError) {
			return fmt.Errorf("inspect overlay target %s: %w", entry.path, statError)
		}
		mode := fs.FileMode(defaultFileMode)
		if entry.executable {
			mode = executableFileMode
		}
		if err = os.WriteFile(candidate, entry.contents, mode); err != nil {
			return fmt.Errorf("write overlay file %s: %w", entry.path, err)
		}
		if err = os.Chmod(candidate, mode); err != nil {
			return fmt.Errorf("set overlay file mode %s: %w", entry.path, err)
		}
	}
	if err = inspectProjectTree(ctx, target); err != nil {
		return fmt.Errorf("inspect customized project: %w", err)
	}
	if err = validateManifest(target, configured.template.Base); err != nil {
		return err
	}
	return nil
}

func renderPath(value string, replacements map[string]string) (string, error) {
	rendered := renderText(value, replacements)
	if err := validateRelativePath(rendered, "rendered path"); err != nil {
		return "", err
	}
	return rendered, nil
}

func renderText(value string, replacements map[string]string) string {
	return placeholderPattern.ReplaceAllStringFunc(value, func(placeholder string) string {
		if replacement, exists := replacements[placeholder]; exists {
			return replacement
		}
		return placeholder
	})
}

func unknownPlaceholder(value string) string {
	for _, placeholder := range placeholderPattern.FindAllString(value, -1) {
		if _, known := knownPlaceholders[placeholder]; !known {
			return placeholder
		}
	}
	return ""
}

func validateTemplatedRelativePath(value, field string) error {
	if unresolved := unknownPlaceholder(value); unresolved != "" {
		return fmt.Errorf("%s contains unsupported placeholder %s", field, unresolved)
	}
	rendered := renderText(value, knownPlaceholders)
	return validateRelativePath(rendered, field)
}

func validateRelativePath(value, field string) error {
	if value == "" || len(value) > maxRelativePathBytes {
		return fmt.Errorf("%s must be between 1 and %d bytes", field, maxRelativePathBytes)
	}
	if strings.Contains(value, `\`) || strings.Contains(value, ":") {
		return fmt.Errorf("%s must use safe forward-slash relative paths", field)
	}
	for _, character := range value {
		if unicode.IsControl(character) {
			return fmt.Errorf("%s must not contain control characters", field)
		}
	}
	if path.IsAbs(value) || path.Clean(value) != value || value == "." || strings.ContainsAny(value, "*?[]{}") {
		return fmt.Errorf("%s must be a normalized, non-glob relative path", field)
	}
	for _, component := range strings.Split(value, "/") {
		if component == "" || component == "." || component == ".." {
			return fmt.Errorf("%s contains an unsafe component", field)
		}
		if component == ".wiz" {
			return fmt.Errorf("%s must not contain a .wiz component", field)
		}
	}
	return nil
}

func rejectOverlappingPaths(values []string, replacements map[string]string) error {
	rendered := make([]string, 0, len(values))
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		if replacements != nil {
			value = renderText(value, replacements)
		}
		if _, duplicate := seen[value]; duplicate {
			return fmt.Errorf("duplicate path %q", value)
		}
		seen[value] = struct{}{}
		rendered = append(rendered, value)
	}
	sort.Strings(rendered)
	for left := 0; left < len(rendered); left++ {
		for right := left + 1; right < len(rendered); right++ {
			if strings.HasPrefix(rendered[right], rendered[left]+"/") {
				return fmt.Errorf("path %q overlaps parent %q", rendered[right], rendered[left])
			}
		}
	}
	return nil
}

func rejectSymlinkedComponents(root, relative string) error {
	candidate := root
	for _, component := range strings.Split(relative, "/") {
		candidate = filepath.Join(candidate, component)
		info, err := os.Lstat(candidate)
		if err != nil {
			return fmt.Errorf("inspect %q: %w", relative, err)
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("symbolic links are not allowed in overlay path %q", relative)
		}
	}
	return nil
}

func ensureOverlayDirectory(candidate string) error {
	info, err := os.Lstat(candidate)
	if err == nil {
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("target exists with a different file type")
		}
		return os.Chmod(candidate, defaultDirectoryMode)
	}
	if !os.IsNotExist(err) {
		return err
	}
	return os.MkdirAll(candidate, defaultDirectoryMode)
}

func ensureOverlayParent(candidate string) error {
	if err := os.MkdirAll(candidate, defaultDirectoryMode); err != nil {
		return err
	}
	info, err := os.Lstat(candidate)
	if err != nil {
		return err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("parent is not a real directory")
	}
	return nil
}

func inspectProjectTree(ctx context.Context, root string) error {
	return filepath.WalkDir(root, func(candidate string, entry fs.DirEntry, walkError error) error {
		if walkError != nil {
			return walkError
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if candidate == root {
			return nil
		}
		relative, err := filepath.Rel(root, candidate)
		if err != nil {
			return err
		}
		relative = filepath.ToSlash(relative)
		for _, component := range strings.Split(relative, "/") {
			if component == ".wiz" {
				return fmt.Errorf(".wiz paths are not allowed: %s", relative)
			}
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("symbolic links are not allowed: %s", relative)
		}
		if !info.IsDir() && !info.Mode().IsRegular() {
			return fmt.Errorf("special files are not allowed: %s", relative)
		}
		return nil
	})
}

func validateManifest(root, base string) error {
	manifestPath := filepath.Join(root, "package.json")
	contents, err := readBoundedFile(manifestPath, maxRegistryBytes)
	if err != nil {
		return fmt.Errorf("read customized package.json: %w", err)
	}
	var manifest map[string]any
	decoder := json.NewDecoder(bytes.NewReader(contents))
	if err = decoder.Decode(&manifest); err != nil {
		return fmt.Errorf("decode customized package.json: %w", err)
	}
	if err = requireJSONEnd(decoder); err != nil {
		return fmt.Errorf("decode customized package.json: %w", err)
	}
	if containsForbiddenPackage(manifest) {
		return errors.New("customized package.json must not depend on @season-framework/wiz-frontend")
	}
	wizMetadata, ok := manifest["wiz"].(map[string]any)
	if !ok {
		return errors.New("customized package.json must contain wiz metadata")
	}
	frontend, _ := wizMetadata["frontend"].(string)
	if frontend != base {
		return fmt.Errorf("customized package.json wiz.frontend must remain %q, got %q", base, frontend)
	}
	lockPath := filepath.Join(root, "package-lock.json")
	if lockContents, lockError := readBoundedFile(lockPath, maxPackageLockBytes); lockError == nil {
		var lock any
		lockDecoder := json.NewDecoder(bytes.NewReader(lockContents))
		if decodeError := lockDecoder.Decode(&lock); decodeError != nil {
			return fmt.Errorf("decode customized package-lock.json: %w", decodeError)
		}
		if decodeError := requireJSONEnd(lockDecoder); decodeError != nil {
			return fmt.Errorf("decode customized package-lock.json: %w", decodeError)
		}
		if containsForbiddenPackage(lock) {
			return errors.New("customized package-lock.json must not depend on @season-framework/wiz-frontend")
		}
	} else if !os.IsNotExist(lockError) {
		return fmt.Errorf("read customized package-lock.json: %w", lockError)
	}
	return nil
}

func containsForbiddenPackage(value any) bool {
	const forbidden = "@season-framework/wiz-frontend"
	switch typed := value.(type) {
	case map[string]any:
		for key, child := range typed {
			if strings.Contains(key, forbidden) || containsForbiddenPackage(child) {
				return true
			}
		}
	case []any:
		for _, child := range typed {
			if containsForbiddenPackage(child) {
				return true
			}
		}
	case string:
		return strings.Contains(typed, forbidden)
	}
	return false
}

func readBoundedFile(filename string, maximum int64) ([]byte, error) {
	file, err := os.Open(filename)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	contents, err := io.ReadAll(io.LimitReader(file, maximum+1))
	if err != nil {
		return nil, err
	}
	if int64(len(contents)) > maximum {
		return nil, fmt.Errorf("file exceeds %d bytes", maximum)
	}
	return contents, nil
}

func requireJSONEnd(decoder *json.Decoder) error {
	var trailing any
	err := decoder.Decode(&trailing)
	if errors.Is(err, io.EOF) {
		return nil
	}
	if err == nil {
		return errors.New("multiple JSON values are not allowed")
	}
	return err
}
