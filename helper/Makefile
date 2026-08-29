GO ?= go
WIZ_SPRING_JAR ?= ../target/wiz-spring-1.1.0.jar

.PHONY: check-jar verify-jar-boundary fmt test test-container build run e2e e2e-custom e2e-custom-server docker-build docker-up docker-down

check-jar:
	@test -f "$(WIZ_SPRING_JAR)" || { echo "Missing WIZ Spring JAR: $(WIZ_SPRING_JAR)" >&2; exit 1; }

verify-jar-boundary: check-jar
	@if jar tf "$(WIZ_SPRING_JAR)" | grep -Eq '(^|/)helper(/|$$)|wiz-spring-helper|templatecatalog'; then \
		echo "Helper content must not be packaged in $(WIZ_SPRING_JAR)" >&2; exit 1; \
	fi

fmt:
	$(GO) fmt ./...

test:
	$(GO) test ./...

test-container:
	docker run --rm -v "$(CURDIR):/src:ro" -w /src golang:1.24-bookworm sh -c 'unformatted_files="$$(gofmt -l .)"; test -z "$$unformatted_files" || { printf "%s\n" "$$unformatted_files"; exit 1; }; go vet ./...; go test ./...'

build:
	mkdir -p bin
	CGO_ENABLED=0 $(GO) build -trimpath -o bin/wiz-spring-helper ./cmd/wiz-spring-helper

run: check-jar
	WIZ_SPRING_JAR="$(WIZ_SPRING_JAR)" $(GO) run ./cmd/wiz-spring-helper

e2e:
	./scripts/e2e.sh

e2e-custom: check-jar
	./scripts/e2e-custom-image.sh

e2e-custom-server:
	./scripts/e2e-custom.sh

docker-build:
	docker compose build

docker-up: check-jar
	docker compose up -d --build --wait

docker-down:
	docker compose down
