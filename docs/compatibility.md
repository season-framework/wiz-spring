[English](compatibility.md) | [한국어](compatibility.ko.md)

# WIZ Spring 1.0 compatibility

WIZ Spring 1.0 is a new project model. It is not an in-place upgrade from 0.2.x,
including 0.2.8.

## What is intentionally not migrated

The 1.0 generator does not detect, convert, or preserve these 0.2.x concepts:

- the WIZ backend source transformation and runtime dispatcher;
- legacy runtime configuration and generated build artifacts;
- existing bundle layouts or systemd units;
- frontend layouts that do not match one of the 1.0 templates;
- project-local MCP mutation workflows.

The `create --path`, `create --uri`, and `service` commands are not migration
commands for a 0.2.x workspace or bundle.

## Supported transition

Choose one of these paths:

1. Keep the matching 0.2.x runtime and tooling for the existing application.
2. Create a fresh 1.0 project, then port application code into its standard Spring
   and selected frontend layout.
3. Import an existing repository only after it already follows the 1.0 contract.

Any imported Java source must already live under `src/main/java` and use the requested
Java package. The source must also match the selected template's frontend root.
Validation runs before the target is published; WIZ Spring does not guess, relocate,
or partially rewrite an incompatible source tree.

See [Project generation](project-generation.md#importing-existing-source) for the
accepted layouts and [the 1.0.0 release note](../release-log/1.0.0.md) for the complete
release boundary.
