package httpapi

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/season-framework/wiz-spring/helper/internal/generator"
	"github.com/season-framework/wiz-spring/helper/internal/project"
	"github.com/season-framework/wiz-spring/helper/internal/templatecatalog"
)

type fakeGenerator struct {
	generate func(context.Context, project.Spec) (*generator.Archive, error)
}

func (fake fakeGenerator) Generate(ctx context.Context, spec project.Spec) (*generator.Archive, error) {
	return fake.generate(ctx, spec)
}

func (fakeGenerator) Version() string { return generator.Version }

func TestCreateReturnsZipForJSONRequest(t *testing.T) {
	service := newTestServer(t, 2, time.Second, archiveGenerator(t))
	request := httptest.NewRequest(http.MethodPost, "/api/v1/projects", strings.NewReader(`{
		"projectName":"demo-app",
		"packageName":"com.example.demo",
		"template":"react"
	}`))
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if got := response.Header().Get("Content-Type"); got != "application/zip" {
		t.Fatalf("Content-Type = %q", got)
	}
	if got := response.Header().Get("Content-Disposition"); got != `attachment; filename="demo-app.zip"` {
		t.Fatalf("Content-Disposition = %q", got)
	}
	if got := response.Header().Get("X-Project-Template"); got != "react" {
		t.Fatalf("X-Project-Template = %q", got)
	}
	if got := response.Header().Get("X-Base-Template"); got != "react" {
		t.Fatalf("X-Base-Template = %q", got)
	}
	reader, err := zip.NewReader(bytes.NewReader(response.Body.Bytes()), int64(response.Body.Len()))
	if err != nil {
		t.Fatalf("response is not a ZIP: %v", err)
	}
	if len(reader.File) != 1 || reader.File[0].Name != "demo-app/README.md" {
		t.Fatalf("unexpected ZIP entries: %#v", reader.File)
	}
}

func TestCreateAcceptsFormAndUsesDefaultTemplate(t *testing.T) {
	var received project.Spec
	base := archiveGenerator(t)
	wrapped := fakeGenerator{generate: func(ctx context.Context, spec project.Spec) (*generator.Archive, error) {
		received = spec
		return base.generate(ctx, spec)
	}}
	service := newTestServer(t, 2, time.Second, wrapped)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/projects", strings.NewReader("projectName=demo&packageName=com.example.demo"))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if received.Template != "angular-wiz" {
		t.Fatalf("template = %q", received.Template)
	}
}

func TestCreateAcceptsQueryString(t *testing.T) {
	var received project.Spec
	base := archiveGenerator(t)
	wrapped := fakeGenerator{generate: func(ctx context.Context, spec project.Spec) (*generator.Archive, error) {
		received = spec
		return base.generate(ctx, spec)
	}}
	service := newTestServer(t, 2, time.Second, wrapped)
	query := url.Values{
		"projectName": {"query-demo"},
		"packageName": {"com.example.querydemo"},
		"template":    {"react"},
	}.Encode()
	request := httptest.NewRequest(http.MethodPost, "/api/v1/projects?"+query, nil)
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if received.ProjectName != "query-demo" || received.PackageName != "com.example.querydemo" || received.Template != "react" {
		t.Fatalf("unexpected query spec: %#v", received)
	}
	if got := response.Header().Get("X-Project-Template"); got != "react" {
		t.Fatalf("X-Project-Template = %q", got)
	}
}

func TestCreateQueryStringUsesRegistryDefault(t *testing.T) {
	var received project.Spec
	base := archiveGenerator(t)
	wrapped := fakeGenerator{generate: func(ctx context.Context, spec project.Spec) (*generator.Archive, error) {
		received = spec
		return base.generate(ctx, spec)
	}}
	service := newTestServer(t, 2, time.Second, wrapped)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/projects?projectName=query-default&packageName=com.example.querydefault", nil)
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if received.Template != "angular-wiz" {
		t.Fatalf("template = %q", received.Template)
	}
}

func TestCreateRejectsInvalidAndUnknownInput(t *testing.T) {
	service := newTestServer(t, 2, time.Second, archiveGenerator(t))
	tests := []struct {
		name        string
		contentType string
		body        string
		status      int
	}{
		{"unknown JSON", "application/json", `{"projectName":"demo","packageName":"com.example.demo","unknown":true}`, 400},
		{"package hyphen", "application/json", `{"projectName":"demo","packageName":"com.example.test-wiz"}`, 422},
		{"unsupported media", "text/plain", `demo`, 415},
		{"trailing JSON", "application/json", `{} {}`, 400},
		{"blank template", "application/json", `{"projectName":"demo","packageName":"com.example.demo","template":""}`, 422},
		{"null template", "application/json", `{"projectName":"demo","packageName":"com.example.demo","template":null}`, 422},
		{"unregistered template", "application/json", `{"projectName":"demo","packageName":"com.example.demo","template":"vue"}`, 422},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/api/v1/projects", strings.NewReader(test.body))
			request.Header.Set("Content-Type", test.contentType)
			response := httptest.NewRecorder()
			service.ServeHTTP(response, request)
			if response.Code != test.status {
				t.Fatalf("status = %d, want %d, body = %s", response.Code, test.status, response.Body.String())
			}
			if !strings.HasPrefix(response.Header().Get("Content-Type"), "application/problem+json") {
				t.Fatalf("Content-Type = %q", response.Header().Get("Content-Type"))
			}
		})
	}
}

func TestCreateRejectsInvalidOrMixedQueryString(t *testing.T) {
	service := newTestServer(t, 2, time.Second, archiveGenerator(t))
	valid := "projectName=query-demo&packageName=com.example.querydemo"
	tests := []struct {
		name             string
		path             string
		rawQueryOverride string
		body             io.Reader
		want             int
	}{
		{"unknown parameter", "/api/v1/projects?" + valid + "&unknown=true", "", nil, http.StatusBadRequest},
		{"repeated parameter", "/api/v1/projects?" + valid + "&projectName=again", "", nil, http.StatusBadRequest},
		{"malformed escape", "/api/v1/projects", "projectName=%zz&packageName=com.example.demo", nil, http.StatusBadRequest},
		{"blank template", "/api/v1/projects?" + valid + "&template=", "", nil, http.StatusUnprocessableEntity},
		{"query and body", "/api/v1/projects?" + valid, "", strings.NewReader(`{}`), http.StatusBadRequest},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, test.path, test.body)
			if test.rawQueryOverride != "" {
				request.URL.RawQuery = test.rawQueryOverride
			}
			if test.body != nil {
				request.Header.Set("Content-Type", "application/json")
			}
			response := httptest.NewRecorder()
			service.ServeHTTP(response, request)
			if response.Code != test.want {
				t.Fatalf("status = %d, want %d, body = %s", response.Code, test.want, response.Body.String())
			}
		})
	}
}

func TestCreateReturnsTooManyRequestsWhenCapacityIsBusy(t *testing.T) {
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	var calls atomic.Int32
	base := archiveGenerator(t)
	blocking := fakeGenerator{generate: func(ctx context.Context, spec project.Spec) (*generator.Archive, error) {
		calls.Add(1)
		entered <- struct{}{}
		select {
		case <-release:
			return base.generate(ctx, spec)
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}}
	service := newTestServer(t, 1, 20*time.Millisecond, blocking)
	body := `{"projectName":"demo","packageName":"com.example.demo"}`
	firstDone := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		request := httptest.NewRequest(http.MethodPost, "/api/v1/projects", strings.NewReader(body))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		service.ServeHTTP(response, request)
		firstDone <- response
	}()
	<-entered

	request := httptest.NewRequest(http.MethodPost, "/api/v1/projects", strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)
	if response.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429, body = %s", response.Code, response.Body.String())
	}
	if calls.Load() != 1 {
		t.Fatalf("generator calls = %d, want 1", calls.Load())
	}
	close(release)
	if first := <-firstDone; first.Code != http.StatusOK {
		t.Fatalf("first request status = %d", first.Code)
	}
}

func TestMetadataEndpoints(t *testing.T) {
	service := newTestServer(t, 2, time.Second, archiveGenerator(t))
	for _, path := range []string{"/", "/healthz", "/readyz", "/api/v1/version", "/api/v1/templates", "/openapi.yaml"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		response := httptest.NewRecorder()
		service.ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("%s status = %d", path, response.Code)
		}
		if response.Header().Get("X-Request-ID") == "" {
			t.Fatalf("%s has no X-Request-ID", path)
		}
	}
}

func TestTemplateMetadataComesFromCatalog(t *testing.T) {
	service := newTestServer(t, 2, time.Second, archiveGenerator(t))
	for _, path := range []string{"/", "/api/v1/templates"} {
		t.Run(path, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodGet, path, nil)
			response := httptest.NewRecorder()
			service.ServeHTTP(response, request)
			if response.Code != http.StatusOK {
				t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
			}
			if got := response.Header().Get("Content-Type"); !strings.HasPrefix(got, "application/json") {
				t.Fatalf("Content-Type = %q", got)
			}
			var catalog struct {
				Default   string                     `json:"default"`
				Templates []templatecatalog.Template `json:"templates"`
			}
			if err := json.NewDecoder(response.Body).Decode(&catalog); err != nil {
				t.Fatal(err)
			}
			if catalog.Default != "angular-wiz" || len(catalog.Templates) != 2 {
				t.Fatalf("unexpected catalog: %#v", catalog)
			}
			if catalog.Templates[1].ID != "react" || catalog.Templates[1].Base != "react" {
				t.Fatalf("unexpected template metadata: %#v", catalog.Templates[1])
			}
		})
	}
}

func newTestServer(t *testing.T, concurrent int, acquire time.Duration, generator fakeGenerator) *Server {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	registryPath := filepath.Join(t.TempDir(), "registry.json")
	registry := `{"version":1,"default":"angular-wiz","templates":[` +
		`{"id":"angular-wiz","base":"angular-wiz","description":"Angular WIZ"},` +
		`{"id":"react","base":"react","description":"React"}]}`
	if err := os.WriteFile(registryPath, []byte(registry), 0o600); err != nil {
		t.Fatal(err)
	}
	catalog, err := templatecatalog.Load(registryPath)
	if err != nil {
		t.Fatal(err)
	}
	server, err := New(Config{MaxRequestBytes: 8192, MaxConcurrent: concurrent, AcquireTimeout: acquire, Catalog: catalog}, generator, logger)
	if err != nil {
		t.Fatal(err)
	}
	return server
}

func archiveGenerator(t *testing.T) fakeGenerator {
	t.Helper()
	workspace := t.TempDir()
	return fakeGenerator{generate: func(_ context.Context, spec project.Spec) (*generator.Archive, error) {
		path := filepath.Join(workspace, spec.ProjectName+"-"+time.Now().Format("150405.000000000")+".zip")
		file, err := os.Create(path)
		if err != nil {
			return nil, err
		}
		zipWriter := zip.NewWriter(file)
		entry, err := zipWriter.Create(spec.ProjectName + "/README.md")
		if err == nil {
			_, err = entry.Write([]byte("generated"))
		}
		if closeError := zipWriter.Close(); err == nil {
			err = closeError
		}
		if closeError := file.Close(); err == nil {
			err = closeError
		}
		if err != nil {
			return nil, err
		}
		info, err := os.Stat(path)
		if err != nil {
			return nil, err
		}
		return generator.NewArchive(path, spec.ProjectName+".zip", info.Size(), func() error { return os.Remove(path) }), nil
	}}
}

func decodeProblem(t *testing.T, body io.Reader) problem {
	t.Helper()
	var value problem
	if err := json.NewDecoder(body).Decode(&value); err != nil {
		t.Fatal(err)
	}
	return value
}
