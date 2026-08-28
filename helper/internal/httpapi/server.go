package httpapi

import (
	"context"
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/season-framework/wiz-spring/helper/internal/generator"
	"github.com/season-framework/wiz-spring/helper/internal/project"
	"github.com/season-framework/wiz-spring/helper/internal/templatecatalog"
)

//go:embed openapi.yaml
var openAPIDocument []byte

type Config struct {
	MaxRequestBytes int64
	MaxConcurrent   int
	AcquireTimeout  time.Duration
	Catalog         *templatecatalog.Catalog
}

type ProjectGenerator interface {
	Generate(context.Context, project.Spec) (*generator.Archive, error)
	Version() string
}

type Server struct {
	generator      ProjectGenerator
	logger         *slog.Logger
	maxRequestSize int64
	semaphore      chan struct{}
	acquireTimeout time.Duration
	catalog        *templatecatalog.Catalog
}

func New(config Config, projectGenerator ProjectGenerator, logger *slog.Logger) (*Server, error) {
	if projectGenerator == nil {
		return nil, errors.New("project generator is required")
	}
	if logger == nil {
		return nil, errors.New("logger is required")
	}
	if config.MaxRequestBytes <= 0 {
		return nil, errors.New("maximum request size must be positive")
	}
	if config.MaxConcurrent <= 0 {
		return nil, errors.New("maximum concurrency must be positive")
	}
	if config.AcquireTimeout <= 0 {
		return nil, errors.New("acquire timeout must be positive")
	}
	if config.Catalog == nil {
		return nil, errors.New("template catalog is required")
	}
	return &Server{
		generator:      projectGenerator,
		logger:         logger,
		maxRequestSize: config.MaxRequestBytes,
		semaphore:      make(chan struct{}, config.MaxConcurrent),
		acquireTimeout: config.AcquireTimeout,
		catalog:        config.Catalog,
	}, nil
}

func (server *Server) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	requestID := newRequestID()
	writer.Header().Set("X-Request-ID", requestID)
	writer.Header().Set("X-Content-Type-Options", "nosniff")
	writer.Header().Set("Referrer-Policy", "no-referrer")
	writer.Header().Set("Cache-Control", "no-store")

	defer func() {
		if recovered := recover(); recovered != nil {
			server.logger.Error("panic while serving request", "request_id", requestID, "panic", recovered)
			writeProblem(writer, requestID, http.StatusInternalServerError, "internal_error", "Internal server error", "The request could not be completed.")
		}
	}()

	switch request.URL.Path {
	case "/":
		server.requireMethod(writer, request, requestID, http.MethodGet, server.handleTemplates)
	case "/healthz":
		server.requireMethod(writer, request, requestID, http.MethodGet, server.handleHealth)
	case "/readyz":
		server.requireMethod(writer, request, requestID, http.MethodGet, server.handleHealth)
	case "/api/v1/version":
		server.requireMethod(writer, request, requestID, http.MethodGet, server.handleVersion)
	case "/api/v1/templates":
		server.requireMethod(writer, request, requestID, http.MethodGet, server.handleTemplates)
	case "/openapi.yaml":
		server.requireMethod(writer, request, requestID, http.MethodGet, server.handleOpenAPI)
	case "/api/v1/projects":
		server.requireMethod(writer, request, requestID, http.MethodPost, server.handleCreate)
	default:
		writeProblem(writer, requestID, http.StatusNotFound, "not_found", "Not found", "The requested resource does not exist.")
	}
}

type handler func(http.ResponseWriter, *http.Request, string)

func (server *Server) requireMethod(
	writer http.ResponseWriter,
	request *http.Request,
	requestID string,
	method string,
	handle handler,
) {
	if request.Method != method {
		writer.Header().Set("Allow", method)
		writeProblem(writer, requestID, http.StatusMethodNotAllowed, "method_not_allowed", "Method not allowed", "Use "+method+" for this resource.")
		return
	}
	handle(writer, request, requestID)
}

func (server *Server) handleHealth(writer http.ResponseWriter, _ *http.Request, requestID string) {
	writeJSON(writer, requestID, http.StatusOK, map[string]any{
		"status":  "ok",
		"version": server.generator.Version(),
	})
}

func (server *Server) handleVersion(writer http.ResponseWriter, _ *http.Request, requestID string) {
	writeJSON(writer, requestID, http.StatusOK, map[string]any{
		"helperVersion":    generator.Version,
		"wizSpringVersion": server.generator.Version(),
	})
}

func (server *Server) handleTemplates(writer http.ResponseWriter, _ *http.Request, requestID string) {
	writeJSON(writer, requestID, http.StatusOK, map[string]any{
		"default":   server.catalog.DefaultID(),
		"templates": server.catalog.Templates(),
	})
}

func (server *Server) handleOpenAPI(writer http.ResponseWriter, _ *http.Request, _ string) {
	writer.Header().Set("Content-Type", "application/yaml; charset=utf-8")
	writer.Header().Set("Content-Length", strconv.Itoa(len(openAPIDocument)))
	writer.WriteHeader(http.StatusOK)
	_, _ = writer.Write(openAPIDocument)
}

func (server *Server) handleCreate(writer http.ResponseWriter, request *http.Request, requestID string) {
	request.Body = http.MaxBytesReader(writer, request.Body, server.maxRequestSize)
	input, status, decodeError := server.decodeCreateRequest(request)
	if decodeError != nil {
		writeProblem(writer, requestID, status, "invalid_request", http.StatusText(status), decodeError.Error())
		return
	}
	selectedTemplate := server.catalog.DefaultID()
	if input.TemplateProvided {
		selectedTemplate = input.Template
	}
	spec, validationError := project.Validate(input.ProjectName, input.PackageName, selectedTemplate)
	if validationError != nil {
		field := "request"
		if typed, ok := validationError.(*project.ValidationError); ok {
			field = typed.Field
		}
		writeProblem(writer, requestID, http.StatusUnprocessableEntity, "validation_failed", "Validation failed", field+": "+validationError.Error())
		return
	}
	baseTemplate, registered := server.catalog.Base(spec.Template)
	if !registered {
		ids := make([]string, 0, len(server.catalog.Templates()))
		for _, configured := range server.catalog.Templates() {
			ids = append(ids, configured.ID)
		}
		writeProblem(writer, requestID, http.StatusUnprocessableEntity, "validation_failed", "Validation failed", "template: template must be one of: "+strings.Join(ids, ", "))
		return
	}

	if !server.acquire(request.Context()) {
		if request.Context().Err() != nil {
			return
		}
		retrySeconds := max(1, int(server.acquireTimeout.Round(time.Second)/time.Second))
		writer.Header().Set("Retry-After", strconv.Itoa(retrySeconds))
		writeProblem(writer, requestID, http.StatusTooManyRequests, "capacity_exceeded", "Generation capacity exceeded", "The generator is busy. Retry shortly.")
		return
	}
	started := time.Now()
	archive, generationError := func() (*generator.Archive, error) {
		defer server.release()
		return server.generator.Generate(request.Context(), spec)
	}()
	if generationError != nil {
		if request.Context().Err() != nil {
			server.logger.Info("generation canceled", "request_id", requestID, "project", spec.ProjectName)
			return
		}
		status := http.StatusInternalServerError
		code := "generation_failed"
		detail := "The project could not be generated. Use the request ID to inspect server logs."
		if errors.Is(generationError, generator.ErrGenerationTimeout) {
			status = http.StatusGatewayTimeout
			code = "generation_timeout"
			detail = "Project generation exceeded the configured time limit."
		}
		attributes := []any{"request_id", requestID, "project", spec.ProjectName, "template", spec.Template, "error", generationError}
		var commandError *generator.CommandError
		if errors.As(generationError, &commandError) {
			attributes = append(attributes, "generator_output", commandError.Output)
		}
		server.logger.Error("project generation failed", attributes...)
		writeProblem(writer, requestID, status, code, http.StatusText(status), detail)
		return
	}
	defer func() {
		if cleanupError := archive.Close(); cleanupError != nil {
			server.logger.Warn("request workspace cleanup failed", "request_id", requestID, "error", cleanupError)
		}
	}()

	file, openError := openArchive(archive)
	if openError != nil {
		server.logger.Error("open completed archive", "request_id", requestID, "error", openError)
		writeProblem(writer, requestID, http.StatusInternalServerError, "archive_unavailable", "Archive unavailable", "The generated archive could not be opened.")
		return
	}
	defer file.Close()

	writer.Header().Set("Content-Type", "application/zip")
	writer.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, archive.Filename()))
	writer.Header().Set("Content-Length", strconv.FormatInt(archive.Size(), 10))
	writer.Header().Set("X-Wiz-Spring-Version", server.generator.Version())
	writer.Header().Set("X-Project-Template", spec.Template)
	writer.Header().Set("X-Base-Template", baseTemplate)
	writer.WriteHeader(http.StatusOK)
	written, copyError := io.Copy(writer, file)
	if copyError != nil {
		server.logger.Warn("archive response interrupted", "request_id", requestID, "written", written, "error", copyError)
		return
	}
	server.logger.Info(
		"project generated",
		"request_id", requestID,
		"project", spec.ProjectName,
		"package", spec.PackageName,
		"template", spec.Template,
		"archive_bytes", archive.Size(),
		"duration", time.Since(started),
	)
}

type createRequest struct {
	ProjectName      string
	PackageName      string
	Template         string
	TemplateProvided bool
}

type jsonCreateRequest struct {
	ProjectName string          `json:"projectName"`
	PackageName string          `json:"packageName"`
	Template    json.RawMessage `json:"template"`
}

func (server *Server) decodeCreateRequest(request *http.Request) (createRequest, int, error) {
	if request.URL.RawQuery != "" {
		if request.ContentLength != 0 || len(request.TransferEncoding) != 0 {
			return createRequest{}, http.StatusBadRequest, errors.New("query string input cannot be combined with a request body")
		}
		values, err := url.ParseQuery(request.URL.RawQuery)
		if err != nil {
			return createRequest{}, http.StatusBadRequest, fmt.Errorf("malformed query string: %w", err)
		}
		return decodeFields(values, "query parameter")
	}
	mediaType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
	if err != nil || mediaType == "" {
		return createRequest{}, http.StatusUnsupportedMediaType, errors.New("Content-Type must be application/json or application/x-www-form-urlencoded")
	}
	switch mediaType {
	case "application/json":
		decoder := json.NewDecoder(request.Body)
		decoder.DisallowUnknownFields()
		var wire jsonCreateRequest
		if err = decoder.Decode(&wire); err != nil {
			return createRequest{}, decodeStatus(err), fmt.Errorf("malformed JSON request: %w", err)
		}
		var trailing any
		if err = decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
			if err == nil {
				err = errors.New("multiple JSON values are not allowed")
			}
			return createRequest{}, decodeStatus(err), fmt.Errorf("malformed JSON request: %w", err)
		}
		input := createRequest{ProjectName: wire.ProjectName, PackageName: wire.PackageName}
		if wire.Template != nil {
			input.TemplateProvided = true
			if string(wire.Template) == "null" {
				return createRequest{}, http.StatusUnprocessableEntity, errors.New("template must be a string when provided")
			}
			if err = json.Unmarshal(wire.Template, &input.Template); err != nil {
				return createRequest{}, http.StatusBadRequest, fmt.Errorf("template must be a string: %w", err)
			}
		}
		return input, http.StatusOK, nil
	case "application/x-www-form-urlencoded":
		if err = request.ParseForm(); err != nil {
			return createRequest{}, decodeStatus(err), fmt.Errorf("malformed form request: %w", err)
		}
		return decodeFields(request.PostForm, "form field")
	default:
		return createRequest{}, http.StatusUnsupportedMediaType, fmt.Errorf("unsupported Content-Type %q", mediaType)
	}
}

func decodeFields(values url.Values, fieldKind string) (createRequest, int, error) {
	allowed := map[string]struct{}{"projectName": {}, "packageName": {}, "template": {}}
	for key := range values {
		if _, ok := allowed[key]; !ok {
			return createRequest{}, http.StatusBadRequest, fmt.Errorf("unknown %s %q", fieldKind, key)
		}
		if len(values[key]) != 1 {
			return createRequest{}, http.StatusBadRequest, fmt.Errorf("%s %q must appear once", fieldKind, key)
		}
	}
	return createRequest{
		ProjectName:      values.Get("projectName"),
		PackageName:      values.Get("packageName"),
		Template:         values.Get("template"),
		TemplateProvided: values.Has("template"),
	}, http.StatusOK, nil
}

func decodeStatus(err error) int {
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) || strings.Contains(err.Error(), "request body too large") {
		return http.StatusRequestEntityTooLarge
	}
	return http.StatusBadRequest
}

func (server *Server) acquire(ctx context.Context) bool {
	timer := time.NewTimer(server.acquireTimeout)
	defer timer.Stop()
	select {
	case server.semaphore <- struct{}{}:
		return true
	case <-timer.C:
		return false
	case <-ctx.Done():
		return false
	}
}

func (server *Server) release() {
	<-server.semaphore
}

type readableArchive interface {
	Path() string
}

func openArchive(archive readableArchive) (io.ReadCloser, error) {
	return openFile(archive.Path())
}

var openFile = func(path string) (io.ReadCloser, error) {
	return os.Open(path)
}

type problem struct {
	Type      string `json:"type"`
	Title     string `json:"title"`
	Status    int    `json:"status"`
	Detail    string `json:"detail"`
	Code      string `json:"code"`
	RequestID string `json:"requestId"`
}

func writeProblem(writer http.ResponseWriter, requestID string, status int, code, title, detail string) {
	writer.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(problem{
		Type:      "about:blank",
		Title:     title,
		Status:    status,
		Detail:    detail,
		Code:      code,
		RequestID: requestID,
	})
}

func writeJSON(writer http.ResponseWriter, _ string, status int, value any) {
	writer.Header().Set("Content-Type", "application/json; charset=utf-8")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}

func newRequestID() string {
	contents := make([]byte, 12)
	if _, err := rand.Read(contents); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(contents)
}
