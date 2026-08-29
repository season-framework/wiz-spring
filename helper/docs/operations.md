# Operations

[English](operations.md) | [한국어](operations.ko.md) · [Helper README](../README.md)

Supported deployments run the helper as a disposable Docker workload. The
image supplies the HTTP binary, full JDK 21, Node.js `24.15.0`, npm, and one
immutable template bundle. The WIZ Spring generator JAR is supplied separately.

## Runtime model

```text
client
  │
  ▼
HTTP validation ── concurrency gate ── wiz-spring 1.1.0 JAR
                                            │
                                            ▼
                                   generated base project
                                            │
                                            ▼
                                   remove + image overlay
                                            │
                                            ▼
                                     bounded ZIP response
```

The generator JAR is intentionally absent from the helper image, while helper
sources and assets are absent from the published WIZ Spring JAR. Compose mounts
`target/wiz-spring-1.1.0.jar` read-only at `/opt/wiz-source/wiz-spring.jar`. At
startup, the entrypoint copies it into container tmpfs and starts the service as
UID/GID `10001`.

Replacing the generator JAR only requires recreating the container, not
rebuilding the helper image. Changing a registry or overlay requires a new
image because template assets are fixed at build time.

## Build and run

From the repository root:

```bash
./mvnw clean package
docker compose -f helper/docker-compose.yaml up -d --build --wait
```

Inspect the service and logs:

```bash
curl --fail-with-body http://127.0.0.1:8080/readyz
docker compose -f helper/docker-compose.yaml logs -f helper
```

Stop and remove the container:

```bash
docker compose -f helper/docker-compose.yaml down
```

Build only the default image:

```bash
docker build \
  -f helper/Dockerfile \
  -t wiz-spring-helper:1.1.0 \
  helper
```

See the [template registry guide](template-registry.md) for custom images.

## Image and Compose settings

| Setting | Default | Purpose |
| --- | --- | --- |
| Build arg `WIZ_HELPER_TEMPLATE_FILE` | `registry.json` | Registry filename below `helper/templates` staged into the image |
| `WIZ_HELPER_PORT` | `8080` | Host loopback port published by Compose |
| `WIZ_HELPER_MAX_CONCURRENT` | `2` | Concurrent project generations, range 1–8 |
| `WIZ_HELPER_GENERATION_TIMEOUT` | `45s` | Per-generation deadline, range 1s–5m |
| `WIZ_SPRING_SHA256` | Empty | Optional expected SHA-256 of the mounted JAR |

Copy [`.env.example`](../.env.example) to `helper/.env`, adjust it, and pass
it explicitly:

```bash
docker compose \
  --env-file helper/.env \
  -f helper/docker-compose.yaml \
  up -d --build --wait
```

The Compose file binds only to `127.0.0.1` by default. Change deployment
networking deliberately when placing a reverse proxy or ingress in front of it.

## Helper process settings

These variables are also available when developing the Go service directly.
Values shown are process defaults; the Dockerfile or Compose file overrides
some of them.

| Variable | Process default | Notes |
| --- | --- | --- |
| `WIZ_HELPER_ADDR` | `127.0.0.1:8080` | Listen address; image/Compose use `0.0.0.0:8080` |
| `WIZ_SPRING_JAR` | `../target/wiz-spring-1.1.0.jar` | Generator path used by the Go process |
| `WIZ_SPRING_SOURCE_JAR` | `/opt/wiz-source/wiz-spring.jar` | Container entrypoint's read-only source mount |
| `WIZ_SPRING_SHA256` | Empty | Optional 64-character hexadecimal checksum |
| `WIZ_HELPER_JAVA_BIN` | `java` | Java executable |
| `WIZ_HELPER_WORK_DIR` | OS temporary directory | Parent for isolated request workspaces; Compose uses `/work` |
| `WIZ_HELPER_MAX_CONCURRENT` | `2` | Range 1–8 |
| `WIZ_HELPER_GENERATION_TIMEOUT` | `45s` | Range 1s–5m |
| `WIZ_HELPER_ACQUIRE_TIMEOUT` | `2s` | Wait for a generation slot, range 100ms–30s |
| `WIZ_HELPER_TEMPLATE_REGISTRY` | `templates/registry.json` | Local-development registry; the image entrypoint fixes its staged registry |

To add a process setting that Compose does not currently forward, extend the
service's `environment` section or supply it with an equivalent deployment
configuration.

## Startup and health

Before opening the listener, the helper:

1. validates the staged registry and overlays;
2. verifies an optional JAR SHA-256;
3. requires `java -jar ... --version` to return exactly
   `wiz-spring 1.1.0`; and
4. generates, customizes, archives, and cleans a disposable project for every
   registered template.

Startup fails if any template or required toolchain component is broken.
`/healthz` reports process liveness and `/readyz` is used by the Docker
healthcheck after those startup probes have passed.

To pin a generator artifact:

```bash
sha256sum target/wiz-spring-1.1.0.jar
# Put the 64-character value in WIZ_SPRING_SHA256.
```

Reproducible output requires the same helper image digest, mounted JAR digest,
and request fields.

## Resource limits

The service applies fixed bounds before and during generation:

| Resource | Limit |
| --- | --- |
| Request body | 8 KiB |
| Captured generator output | 64 KiB |
| Concurrent generations | 2 by default, maximum 8 |
| Generator JVM heap | 256 MiB maximum |
| ZIP entries | 5,000 |
| ZIP uncompressed data | 128 MiB |
| ZIP compressed data | 64 MiB |
| HTTP header bytes | 16 KiB |

The supplied Compose profile also limits the container to 2 CPUs, 1 GiB RAM,
256 PIDs, a 64 MiB `/tmp` tmpfs, and a 512 MiB `/work` tmpfs. Temporary
workspaces and partial archives are removed after success, failure, timeout, or
response completion.

## Container and generation hardening

The supplied deployment keeps a narrow execution boundary:

- read-only root filesystem with bounded writable tmpfs mounts;
- loopback-only published port by default;
- `no-new-privileges`, all capabilities dropped by default, and only
  `DAC_READ_SEARCH`, `SETUID`, and `SETGID` added for the entrypoint to copy a
  potentially mode-`0600` mounted JAR and switch to UID/GID `10001`;
- fixed generator artifact, template allowlist, and built-in base passed as
  process arguments without shell interpolation;
- isolated per-request directories with no user-selected filesystem or Git URI
  imports;
- symlinks and non-regular files rejected from overlays and generated ZIPs;
- a complete bounded ZIP built before the `200` response begins; and
- stateless operation with no database, persistent project cache, or uploads.

Deployments are responsible for choosing their own external access controls,
network policy, TLS termination, rate limits, and audit retention appropriate
to their environment.

## Development and verification

For changes to the helper itself, use the containerized Go checks so no host Go
installation is required:

```bash
make -C helper test-container
make -C helper verify-jar-boundary
```

With the default Compose service running, verify all five built-in templates
and all three request formats:

```bash
make -C helper e2e
```

Build and exercise the example custom image in an isolated temporary container:

```bash
make -C helper e2e-custom
```

Host-side development requires Go `1.24`, full JDK 21 or newer, supported
Node.js/npm, and `target/wiz-spring-1.1.0.jar`:

```bash
make -C helper test
make -C helper run
```

`verify-jar-boundary` confirms that helper sources, registry code, and helper
assets were not packaged into the WIZ Spring JAR.
