# API reference

[English](api.md) | [한국어](api.ko.md) · [Helper README](../README.md)

The helper exposes a deliberately small HTTP API for creating fresh projects.
It does not expose the CLI's filesystem or remote-URI import options.

## Endpoints

| Method | Path | Response |
| --- | --- | --- |
| `GET` | `/` | Registry default and available templates |
| `POST` | `/api/v1/projects` | Generated project ZIP |
| `GET` | `/api/v1/templates` | Same template payload as `/` |
| `GET` | `/api/v1/version` | Helper and generator versions |
| `GET` | `/healthz` | Liveness status |
| `GET` | `/readyz` | Readiness status |
| `GET` | `/openapi.yaml` | Embedded OpenAPI 3.1 document |

Every response includes `X-Request-ID`. Keep it when investigating a failed
request in server logs.

## List templates

```bash
curl --fail-with-body http://127.0.0.1:8080/
```

```json
{
  "default": "angular-wiz",
  "templates": [
    {
      "id": "angular-wiz",
      "base": "angular-wiz",
      "description": "Angular with WIZ source layout"
    },
    {
      "id": "react",
      "base": "react",
      "description": "React"
    }
  ]
}
```

Template IDs are defined by the image's registry. Always discover them from the
running instance instead of assuming that the default registry is installed.

## Create a project

`POST /api/v1/projects` accepts exactly one of the following input styles. Do
not combine a query string with a request body.

### Query string

```bash
curl --fail-with-body \
  -X POST \
  'http://127.0.0.1:8080/api/v1/projects?projectName=demo-app&packageName=com.example.demo&template=react' \
  -o demo-app.zip
```

Query fields must occur no more than once. Unknown fields are rejected.

### JSON

```bash
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/projects \
  -H 'Content-Type: application/json' \
  -d '{
    "projectName": "demo-app",
    "packageName": "com.example.demo",
    "template": "react"
  }' \
  -o demo-app.zip
```

The body must contain one JSON object. Unknown properties and trailing JSON
values are rejected.

### Form

```bash
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/projects \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'projectName=demo-app' \
  --data-urlencode 'packageName=com.example.demo' \
  --data-urlencode 'template=react' \
  -o demo-app.zip
```

Form fields must occur no more than once. Unknown fields are rejected.

## Request fields

| Field | Required | Rules |
| --- | --- | --- |
| `projectName` | Yes | 1–64 lowercase ASCII characters; starts and ends with a letter or digit; `.`, `_`, and `-` allowed inside |
| `packageName` | Yes | 1–255 characters; dot-separated ASCII Java 25 identifiers; Java keywords and the `java` namespace are rejected |
| `template` | No | 1–64 character lowercase ASCII slug registered in the image; omitted means the registry default |

A project name may contain a hyphen. A Java package segment may not, because a
hyphen is not valid in a Java identifier. The HTTP boundary intentionally uses
a conservative ASCII identifier subset; use the local CLI if Unicode Java
identifiers are required.

An omitted `template` selects the registry default. An explicit empty value or
JSON `null` is invalid.

## Success response

A successful create request returns `200 OK` with `Content-Type:
application/zip`. The ZIP contains exactly one top-level directory named after
`projectName`; executable modes such as `mvnw` are preserved.

Create responses include these metadata headers:

| Header | Meaning |
| --- | --- |
| `Content-Disposition` | Suggested `<projectName>.zip` filename |
| `X-Request-ID` | Request correlation ID |
| `X-Wiz-Spring-Version` | Generator version |
| `X-Project-Template` | Public registry template ID |
| `X-Base-Template` | Built-in template used before customization |

## Error response

Errors use `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": "packageName: packageName segment ...",
  "code": "validation_failed",
  "requestId": "9ee5d9ef30ed674b5a7e2f4d"
}
```

| Status | Typical cause |
| --- | --- |
| `400` | Malformed or mixed input, duplicate or unknown fields |
| `413` | Request body exceeds 8 KiB |
| `415` | Missing or unsupported body `Content-Type` |
| `422` | Invalid field value or unregistered template |
| `429` | All generation slots remain busy past the acquire timeout |
| `500` | Generator, customization, or archive failure |
| `504` | Generation exceeds its configured timeout |

Unknown routes return `404`; unsupported methods return `405` with an `Allow`
header. Generator command output is logged in bounded, sanitized form and is
not exposed in HTTP errors.

## Health and version

```bash
curl --fail-with-body http://127.0.0.1:8080/healthz
curl --fail-with-body http://127.0.0.1:8080/readyz
curl --fail-with-body http://127.0.0.1:8080/api/v1/version
```

The process starts listening only after the JAR version check and disposable
generation probe for every registered template have passed. The Compose
healthcheck uses `/readyz`.

## OpenAPI

The canonical contract is available in the repository at
[`internal/httpapi/openapi.yaml`](../internal/httpapi/openapi.yaml) and from a
running instance at `/openapi.yaml`.
