# OS Update Simulator — Architecture & Security Design

Stage 1 of an incremental build. This document is the design the code
follows; the sections match what was asked for before implementation.

## 1. Purpose

A virtual OS development + update simulation lab. Users build an "OS/update"
project, zip it, and import it. The app validates and simulates the update
against a **virtual device** rendered on screen. The real Android device is
never modified, flashed, rooted, or unlocked, and no privileged/system
permissions are requested.

## 2. Threat model

The uploaded ZIP is **untrusted input**, on the same footing as a file
downloaded from the internet. Assume it may contain:
- Path-traversal entries (`../../data/…`, absolute paths)
- Zip bombs / decompression bombs
- Malformed or oversized manifests
- Code that tries to loop forever, recurse infinitely, or allocate unbounded
  memory once the interpreter (Stage 7) exists
- Attempts to reference real filesystem paths, native binaries, or shell
  commands

None of that may ever reach a real Android API. The app's job is to give
such a package a convincing, fully virtual place to run and fail.

## 3. Security boundaries

```
Real Android
  ↓
OS Update Simulator (this app)
  ↓
ZIP Importer            — SAF only, reads entry metadata, never blind-extracts
  ↓
Package Validator       — structure, paths, sizes, duplicates, limits
  ↓
Static Analyzer         — (later stage) inspects code before any execution
  ↓
Virtual Filesystem      — in-memory tree; simulated paths never map to real ones
  ↓
Virtual CPU / Interpreter — bounded instruction set, hard resource caps
  ↓
Virtual OS
  ↓
Virtual Display
```

Hard rule carried through every stage: a simulated call such as
`delete("/system/Launcher")` always resolves to
`VirtualFileSystem.delete(...)`, never `java.io.File(...).delete()`.
Uploaded code never receives an Android `Context`, reflection, process
execution, or native APIs — only the `Virtual*` objects.

## 4. Project structure

```
app/src/main/java/com/oslab/simulator/
├── MainActivity.kt              # Stage 1: shell UI (status bar, terminal, display)
├── ui/theme/                    # Compose theme
├── terminal/
│   └── TerminalViewModel.kt     # owns the VirtualDevice, parses terminal commands
├── simulator/
│   ├── cpu/VirtualCpu.kt        # Stage 7: bounded instruction interpreter
│   ├── memory/VirtualRam.kt     # Stage 2: bounded in-memory arena
│   ├── filesystem/VirtualFileSystem.kt  # Stage 2/5: in-memory tree + snapshots
│   ├── process/VirtualProcessManager.kt # Stage 3: virtual process table
│   ├── display/VirtualDisplay.kt        # Stage 6: virtual framebuffer
│   ├── input/VirtualInput.kt            # Stage 8: virtual input queue
│   └── device/VirtualDevice.kt          # aggregate root wiring all of the above
├── update/
│   ├── ZipImporter.kt           # Stage 4: SAF read, entry metadata only
│   ├── UpdateManifest.kt        # manifest.json model + ManifestValidator
│   ├── PackageValidator.kt      # Stage 4: structural/size/path checks
│   └── UpdateEngine.kt          # Stage 5: snapshot → apply → boot test → rollback
└── security/
    ├── PathValidator.kt         # rejects traversal / real-device paths
    ├── ResourceLimits.kt        # every hard cap in one place
    ├── PermissionManager.kt     # simulation-only permission set
    └── SandboxController.kt     # exception boundary — a crash stays virtual
```

## 5. Data flow (once Stage 4/5 land)

1. User picks a ZIP via Storage Access Framework.
2. `ZipImporter` reads the central directory only — entry names + sizes.
3. `PackageValidator` checks entry count, per-file size, total expanded
   size, duplicate names, and every path via `PathValidator`.
4. `ManifestValidator` parses and checks `manifest.json` against the
   running virtual OS version.
5. Only if all of the above pass does `UpdateEngine` snapshot the current
   `VirtualFileSystem`, apply the new entries into it, boot the virtual OS,
   and run simulated tests — rolling back the snapshot on any failure.
6. Every step's result line is written to the terminal panel and reflected
   in the display panel.

## 6. Virtual machine design

`VirtualDevice` is the aggregate root: CPU, RAM, filesystem, process
manager, permission manager, display, and input are independent, testable
objects with no reference to any Android system service. `TerminalViewModel`
is the single place user/terminal actions are translated into calls on this
tree.

`SandboxController.runContained { }` wraps simulation execution (interpreter
runs and the update engine's boot test) so
that any exception inside a running update — including an intentionally
hostile one — becomes a `Crashed` outcome the UI can show and recover from
(Restart Simulation / Restore Snapshot / Load Another Update), never an
Android application crash.

## 7. ZIP format specification

```
MyOSUpdate.zip
├── manifest.json   { "name", "version", "targetVersion", "formatVersion" }
├── system/
├── apps/
├── config/
└── resources/
```

Rejected outright: `..` segments, entries starting with `/`, entries
targeting `/system`, `/vendor`, `/data`, `/proc`, `/dev`, `/sys`, or a
Windows drive path. Limits (see `ResourceLimits`): 64 MB compressed
package, 256 MB expanded, 16 MB per file, 5000 files max.

## 8. Android API 24 compatibility plan

- `minSdk = 24`, `targetSdk = 35`.
- SAF (`Intent.ACTION_OPEN_DOCUMENT` / `ACTION_GET_CONTENT`) works from
  API 19+, well under the floor.
- No dependency in this codebase requires anything above API 24 at runtime;
  Compose's own minimum is API 21.
- Any API added in later stages gets a `Build.VERSION.SDK_INT` guard or an
  AndroidX-provided backport rather than a hard requirement.

## 9. Step-by-step implementation plan

| Stage | Contents | Status |
|---|---|---|
| 1 | Main screen, terminal panel, display panel, status bar. | **Implemented** |
| 2 | Virtual CPU, RAM, filesystem (real read/write/delete/mkdir against an in-memory tree, backed by a bounded RAM allocator) | **Implemented** |
| 3 | Process manager (create/kill/list, capped table), virtual permissions, full device wiring | **Implemented** |
| 4 | ZIP importer (SAF → capped cache copy → `ZipFile` metadata → bounded extraction), manifest parser, package validator | **Implemented** |
| 5 | Update simulation: snapshot → apply → boot test → commit or roll back | **Implemented** |
| 6 | Virtual display (desktop of tappable app icons sourced from `apps/` in the VFS) | **Implemented** (framebuffer/pixel-level rendering deferred — the display is icon/text based, not a bitmap surface) |
| 7 | Bounded instruction interpreter (`LOAD/STORE/PUSH/ADD/SUB/JMP/JZ/CALL/RETURN/READ/WRITE/CREATE_PROCESS/EXIT`), a small two-pass assembler (`VirtualAssembly`), and hard caps on instruction count / wall-clock time / recursion depth | **Implemented** |
| 8 | Virtual input (tap → app launch), terminal ↔ device interaction, `launch`/`run`/`ps`/`kill` commands | **Implemented** |
| 9 | Virtual kernel/scheduler, process states and ticks, vCPU registers, MUL/DIV, stronger assembler branch/path validation | **Implemented** |

### What's deliberately simplified
- **Update targeting**: a package's `manifest.json` `targetVersion` must exactly match the running OS version — there's no multi-step upgrade chain.
- **"Apps"** are one `main.vasm` program each, executed once per launch and then killed — there's no persistent app state or real multi-window UI.
- **The virtual display** renders icons/text via Compose, not a pixel framebuffer an OS could draw arbitrary graphics into.
- **Static analysis** (the box between Package Validator and Virtual Filesystem in the diagram above) is folded into `PackageValidator` + `VirtualAssembly.parse` — there's no separate bytecode-scanning pass beyond what the assembler's parse step rejects.

None of these simplifications touch the security boundary: every one of them is a feature-completeness cut, not a sandboxing cut. The real device is never written to, no ZIP entry is ever extracted before validation, and every interpreter run is both instruction/time/recursion-bounded and wrapped in `SandboxController.runContained`.

## 10. Trying it end to end

`examples/MyOSUpdate.zip` is a ready-made update package: `manifest.json`
targets `MyOS 1.0` (the version the app boots with) and bumps to `MyOS 1.1`,
and it ships one app, `Calculator`, whose `main.vasm` adds two numbers and
writes the result into the virtual filesystem. Tap **Import** in the top
bar, pick that file, and the terminal will show validation → snapshot →
apply → boot test → success, the display's OS label will change to
`MyOS 1.1`, and a new `Calculator` icon will appear on the desktop — tap it
to run the program and see its output in the terminal.
