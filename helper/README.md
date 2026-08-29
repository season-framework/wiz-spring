# WIZ Spring Project Helper

[English](README.md) | [한국어](README.ko.md)

A small, Docker-only HTTP service that turns WIZ Spring `1.1.1` project
templates into downloadable ZIP archives. It is intended for internal tooling,
CI, and `curl`-based project bootstrapping.

> [!IMPORTANT]
> This helper creates new WIZ Spring `1.1.1` projects. It is not compatible
> with, and does not migrate, `0.2.x` workspaces such as `0.2.8`.

## Quick start

### Requirements

- Docker with Compose
- The WIZ Spring repository
- A locally built `target/wiz-spring-1.1.1.jar`

The helper runs only as a Docker workload in supported deployments. The
generator JAR stays outside the image and is mounted read-only at runtime.

### 1. Build the generator

From the repository root:

```bash
./mvnw clean package
```

### 2. Start the helper

```bash
docker compose -f helper/docker-compose.yaml up -d --build --wait
```

The default Compose configuration publishes the service at
`http://127.0.0.1:8080`.

### 3. Discover templates

```bash
curl --fail-with-body http://127.0.0.1:8080/
```

The root endpoint returns the registry default and every template available in
the running image:

```json
{
  "default": "angular-wiz",
  "templates": [
    {
      "id": "angular-wiz",
      "base": "angular-wiz",
      "description": "Angular with WIZ source layout"
    }
  ]
}
```

### 4. Generate a project

Send a `POST` request and save the response as a ZIP:

```bash
curl --fail-with-body \
  -X POST \
  'http://127.0.0.1:8080/api/v1/projects?projectName=demo-app&packageName=com.example.demo&template=angular-wiz' \
  -o demo-app.zip
```

`projectName` and `packageName` are required. `template` is optional and falls
back to the registry default. JSON and form requests are also supported; use
exactly one input style per request.

The archive contains a single top-level directory named after the project. The
generated project is standalone: the helper is not needed to build or run it.

### 5. Stop the helper

```bash
docker compose -f helper/docker-compose.yaml down
```

## Documentation

| Guide | Covers |
| --- | --- |
| [API reference](docs/api.md) | Endpoints, request formats, validation, responses, and errors |
| [Template registry](docs/template-registry.md) | Custom IDs, base templates, overlays, placeholders, and image builds |
| [Operations](docs/operations.md) | Configuration, limits, container hardening, checksums, and development checks |
| [OpenAPI 3.1](internal/httpapi/openapi.yaml) | Machine-readable HTTP contract |

Korean versions are available alongside each guide:
[API](docs/api.ko.md), [template registry](docs/template-registry.ko.md), and
[operations](docs/operations.ko.md).

## License

[MIT](LICENSE)
