package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	archivezip "github.com/season-framework/wiz-spring/helper/internal/archive"
	"github.com/season-framework/wiz-spring/helper/internal/generator"
	"github.com/season-framework/wiz-spring/helper/internal/httpapi"
	"github.com/season-framework/wiz-spring/helper/internal/project"
	"github.com/season-framework/wiz-spring/helper/internal/templatecatalog"
)

func main() {
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if len(os.Args) > 1 {
		switch {
		case len(os.Args) == 2 && os.Args[1] == "validate-templates":
			catalog, err := templatecatalog.Load(environment("WIZ_HELPER_TEMPLATE_REGISTRY", "templates/registry.json"))
			if err != nil {
				logger.Error("template registry validation failed", "error", err)
				os.Exit(1)
			}
			logger.Info("template registry is valid", "default", catalog.DefaultID(), "templates", len(catalog.Templates()))
			return
		case len(os.Args) == 4 && os.Args[1] == "stage-templates":
			catalog, err := templatecatalog.Stage(os.Args[2], os.Args[3])
			if err != nil {
				logger.Error("template bundle staging failed", "error", err)
				os.Exit(1)
			}
			logger.Info("template bundle staged", "default", catalog.DefaultID(), "templates", len(catalog.Templates()))
			return
		default:
			logger.Error("usage: wiz-spring-helper [validate-templates | stage-templates REGISTRY DESTINATION]")
			os.Exit(2)
		}
	}
	if err := run(logger); err != nil {
		logger.Error("server stopped", "error", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	settings, err := loadSettings()
	if err != nil {
		return err
	}
	catalog, err := templatecatalog.Load(settings.templateRegistry)
	if err != nil {
		return fmt.Errorf("load template registry: %w", err)
	}
	projectGenerator, err := generator.New(generator.Config{
		JavaBinary:     settings.javaBinary,
		JarPath:        settings.jarPath,
		WorkDirectory:  settings.workDirectory,
		Timeout:        settings.generationTimeout,
		MaxOutputSize:  64 * 1024,
		ExpectedSHA256: settings.jarSHA256,
		Catalog:        catalog,
		ArchiveLimits: archivezip.Limits{
			MaxEntries:          5_000,
			MaxUncompressedSize: 128 * 1024 * 1024,
			MaxCompressedSize:   64 * 1024 * 1024,
		},
	})
	if err != nil {
		return err
	}
	versionContext, cancelVersion := context.WithTimeout(context.Background(), 10*time.Second)
	err = projectGenerator.Probe(versionContext)
	cancelVersion()
	if err != nil {
		return err
	}
	for index, configured := range catalog.Templates() {
		readinessContext, cancelReadiness := context.WithTimeout(context.Background(), settings.generationTimeout)
		probeArchive, probeError := projectGenerator.Generate(readinessContext, project.Spec{
			ProjectName: fmt.Sprintf("helper-check-%02d", index+1),
			PackageName: fmt.Sprintf("com.wiz.helper.check%d", index+1),
			Template:    configured.ID,
		})
		cancelReadiness()
		if probeError != nil {
			return fmt.Errorf("template %q readiness probe failed: %w", configured.ID, probeError)
		}
		if err = probeArchive.Close(); err != nil {
			return fmt.Errorf("clean template %q readiness probe: %w", configured.ID, err)
		}
	}

	handler, err := httpapi.New(httpapi.Config{
		MaxRequestBytes: 8 * 1024,
		MaxConcurrent:   settings.maxConcurrent,
		AcquireTimeout:  settings.acquireTimeout,
		Catalog:         catalog,
	}, projectGenerator, logger)
	if err != nil {
		return err
	}

	listener, err := net.Listen("tcp", settings.address)
	if err != nil {
		return fmt.Errorf("listen on %s: %w", settings.address, err)
	}
	httpServer := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      settings.acquireTimeout + settings.generationTimeout + time.Minute,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 * 1024,
	}

	serveErrors := make(chan error, 1)
	go func() {
		serveErrors <- httpServer.Serve(listener)
	}()
	logger.Info(
		"wiz-spring helper is ready",
		"address", listener.Addr().String(),
		"generator_version", projectGenerator.Version(),
		"max_concurrent", settings.maxConcurrent,
	)

	shutdownSignal, stopSignals := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stopSignals()
	select {
	case err = <-serveErrors:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-shutdownSignal.Done():
	}

	shutdownContext, cancelShutdown := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancelShutdown()
	if err = httpServer.Shutdown(shutdownContext); err != nil {
		return fmt.Errorf("graceful shutdown: %w", err)
	}
	logger.Info("wiz-spring helper stopped")
	return nil
}

type settings struct {
	address           string
	javaBinary        string
	jarPath           string
	jarSHA256         string
	workDirectory     string
	maxConcurrent     int
	generationTimeout time.Duration
	acquireTimeout    time.Duration
	templateRegistry  string
}

func loadSettings() (settings, error) {
	maxConcurrent, err := integerEnvironment("WIZ_HELPER_MAX_CONCURRENT", 2, 1, 8)
	if err != nil {
		return settings{}, err
	}
	generationTimeout, err := durationEnvironment("WIZ_HELPER_GENERATION_TIMEOUT", 45*time.Second, time.Second, 5*time.Minute)
	if err != nil {
		return settings{}, err
	}
	acquireTimeout, err := durationEnvironment("WIZ_HELPER_ACQUIRE_TIMEOUT", 2*time.Second, 100*time.Millisecond, 30*time.Second)
	if err != nil {
		return settings{}, err
	}
	return settings{
		address:           environment("WIZ_HELPER_ADDR", "127.0.0.1:8080"),
		javaBinary:        environment("WIZ_HELPER_JAVA_BIN", "java"),
		jarPath:           environment("WIZ_SPRING_JAR", "../target/wiz-spring-1.0.0.jar"),
		jarSHA256:         strings.TrimSpace(os.Getenv("WIZ_SPRING_SHA256")),
		workDirectory:     strings.TrimSpace(os.Getenv("WIZ_HELPER_WORK_DIR")),
		maxConcurrent:     maxConcurrent,
		generationTimeout: generationTimeout,
		acquireTimeout:    acquireTimeout,
		templateRegistry:  environment("WIZ_HELPER_TEMPLATE_REGISTRY", "templates/registry.json"),
	}, nil
}

func environment(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func integerEnvironment(name string, fallback, minimum, maximum int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < minimum || value > maximum {
		return 0, fmt.Errorf("%s must be an integer between %d and %d", name, minimum, maximum)
	}
	return value, nil
}

func durationEnvironment(name string, fallback, minimum, maximum time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value < minimum || value > maximum {
		return 0, fmt.Errorf("%s must be a duration between %s and %s", name, minimum, maximum)
	}
	return value, nil
}
