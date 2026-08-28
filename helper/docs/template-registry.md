# Template registry

[English](template-registry.md) | [한국어](template-registry.ko.md) · [Helper README](../README.md)

The helper's public template catalog is fixed when its Docker image is built.
There is no runtime registry mutation: change a registry or overlay, then build
a new image. This makes template behavior attributable to an image digest.

## How customization works

For each request, the helper:

1. resolves the public template `id` from the image registry;
2. invokes `wiz-spring create` with that entry's built-in `base`;
3. removes configured paths;
4. applies the embedded overlay and literal placeholders;
5. verifies the generated project's build contract; and
6. returns a ZIP while preserving executable file modes.

The five supported bases are `angular-wiz`, `angular`, `react`, `html`, and
`jsp`. A custom template composes one of these bases rather than introducing a
new generator implementation.

## Registry format

The default registry is [`templates/registry.json`](../templates/registry.json).
An image can select any registry directly below `helper/templates`.

```json
{
  "version": 1,
  "default": "company-react",
  "templates": [
    {
      "id": "react",
      "base": "react",
      "description": "React"
    },
    {
      "id": "company-react",
      "base": "react",
      "description": "Company React starter",
      "remove": ["README.md"],
      "overlay": "examples/company-react/overlay"
    }
  ]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `version` | Yes | Registry schema version; currently `1` |
| `default` | Yes | Existing template `id` selected when a request omits `template` |
| `templates` | Yes | Ordered allowlist exposed by the API; 1–64 entries |
| `templates[].id` | Yes | Public 1–64 character lowercase ASCII slug |
| `templates[].base` | Yes | One of the five built-in generator templates |
| `templates[].description` | Yes | Non-empty API description, at most 256 bytes |
| `templates[].remove` | No | Paths to remove from the generated base before overlaying |
| `templates[].overlay` | No | Overlay directory relative to the registry file |

IDs must be unique. A built-in ID such as `react` must retain the matching
`react` base. A new ID such as `company-react` may use any supported base.

Remove a template by omitting its entry. It will disappear from the listing and
requests for it will return `422`. Replace an existing template's defaults by
adding `remove` and `overlay` to its entry, or add a separate custom ID.

## Overlays

An overlay is copied onto the generated project after every `remove` path has
been deleted. Existing regular files are replaced, missing directories are
created, and Unix executable bits are preserved. A configured remove path must
exist in its selected base so registry drift fails visibly.

Remove paths, plus UTF-8 overlay paths and file contents, support fixed literal
placeholders:

| Placeholder | Replacement |
| --- | --- |
| `__WIZ_PROJECT_NAME__` | Requested project name |
| `__WIZ_PACKAGE_ROOT__` | Requested Java package |
| `__WIZ_PACKAGE_PATH__` | Package with dots replaced by `/` |
| `__WIZ_TEMPLATE_ID__` | Selected public template ID |
| `__WIZ_BASE_TEMPLATE__` | Selected built-in base |

Binary files are copied without substitution. Unknown `__WIZ_*__`
placeholders fail registry validation instead of being left unresolved.

The repository includes a working example:

- [`templates/registry.example.json`](../templates/registry.example.json)
  removes `jsp` and adds `company-react`;
- [`templates/examples/company-react/overlay`](../templates/examples/company-react/overlay)
  provides its overlay.

## Build a custom image

Registry files and their overlays must live under the Docker build context at
`helper/templates`. From the repository root:

```bash
cp helper/templates/registry.example.json \
  helper/templates/registry.company.json

docker build \
  --build-arg WIZ_HELPER_TEMPLATE_FILE=registry.company.json \
  -f helper/Dockerfile \
  -t company/wiz-spring-helper:1.0.0 \
  helper
```

Or pass the same selection through Compose:

```bash
WIZ_HELPER_TEMPLATE_FILE=registry.company.json \
  docker compose -f helper/docker-compose.yaml up -d --build --wait
```

For a persistent local setting:

```bash
cp helper/.env.example helper/.env
# Set WIZ_HELPER_TEMPLATE_FILE=registry.company.json in helper/.env.
docker compose \
  --env-file helper/.env \
  -f helper/docker-compose.yaml \
  up -d --build --wait
```

The image staging step validates the selected registry and copies only that
registry plus its referenced overlays. Other registries and unused company
assets in the build context are not present in the final image.

## Safety rules and limits

Registry, remove, and overlay paths are treated as untrusted build input:

- paths must be normalized forward-slash relative paths;
- absolute paths, `..`, glob syntax, control characters, and `.wiz` components
  are rejected;
- each relative path is limited to 512 bytes;
- overlapping or duplicate remove paths and post-render overlay collisions are
  rejected;
- symlinks, devices, sockets, and other special files are rejected;
- an overlay may contain at most 2,000 entries, 8 MiB per file, and 32 MiB in
  total; and
- the registry file itself is limited to 1 MiB.

After customization, `package.json` must remain valid, contain
`wiz.frontend` equal to the selected base, and must not depend on
`@season-framework/wiz-frontend`. If present, `package-lock.json` is checked for
the same forbidden dependency. This preserves each base template's standalone
build contract.

## Validate changes

The custom end-to-end suite builds the example image, verifies that only the
selected registry bundle was staged, and exercises both the removed and added
template contracts:

```bash
make -C helper e2e-custom
```

See the [operations guide](operations.md) for the complete validation set.
