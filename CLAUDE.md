# CLAUDE.md

## Project

scala-cli-nix: Nix packaging for scala-cli applications with per-artifact FOD granularity.

Two phases: **lock** (outside Nix, with network) and **build** (inside Nix sandbox, no network). Entry points: CLI in `cli/scala-cli-nix.scala` (Scala 3, self-hosting), build library in `lib.nix`, overlay in `flake.nix`.

## Key rules

- If you're EVER about to `rm -rf` anything, STOP processing immediately - just tell me what you intended to do and stop doing any work. This also applies to writing such commands into files.
- Read `CONTRIBUTING.md` for architecture details before making changes. **Keep it up to date** when you change how the lock format, build process, or overlay structure works.
- The CLI shells out to a `scala-cli` binary at lock time. It reads the path from `SCALA_CLI_NIX_SCALA_CLI` (set by the Nix wrapper to a kubukoz/scala-cli fork release) and falls back to `scala-cli` on PATH otherwise. The fork is internal: never on the user's PATH, never used inside the Nix sandbox.
- `--library` (not `--standalone`) is intentional for JVM builds — it produces a tiny JAR with only user code. Dependencies stay as individual Nix store paths on the classpath.
- Both JARs and POMs must be in the lockfile. POMs are needed for offline Coursier resolution but filtered out of the runtime classpath.
- Lockfile version is **9** (checked in `lib.nix` as `supportedVersion`). Multi-target `targets` map (even for single-target projects). The shape is discriminated by a top-level `kind` field: `"scala-cli"` (default, project sources) or `"coursier-app"` (Coursier coordinates only).
- `lib.nix` exposes: `buildScalaCliApp` (single derivation, requires `target` if multi-target), `buildScalaCliApps` (attrset for cross projects), `buildCoursierApp` (for `kind = "coursier-app"` lockfiles), and `collectChecks` (flattens `passthru.tests` into `checks.<system>`).
- `--platform` and `--scala-version` are always passed to `scala-cli package` and `scala-cli export --json` to select the correct target from multi-platform sources.
- CLI subcommands: `lock` (project sources), `lock-coords` (Coursier app channels or raw `--dep` coords), `init` (scaffold local) or `init <github-url>` (scaffold external build with `--src` semantics).

## Commands

```bash
nix flake check --print-build-logs   # Build + test all examples
nix develop                          # Enter devShell with wrapped scala-cli + scn on PATH
scn lock                             # Regenerate scala.lock.json (alias for scala-cli-nix lock)
nix build .#<example-name>           # Build a single example
```

# Scala coding guide

- Prefer purely functional programming. Unmanaged side effects (outside an effect system) must be avoided at all cost. Local mutation, vars and loops should be replaced with tail recursion or something akin to a State monad.
- When working with binary data, prefer the Scodec library, using as much of its combinator syntax as possible.
- Use Cellar skill to find latest dependency versions, dependency modules, available functions, sources etc.
- For concurrency, use Cats Effect IO - parTraverse, parTraverseN, Ref/Deferred, cats.effect.std etc
- Use braces (no whitespace-sensitive syntax)
- No milestone, snapshot, or otherwise unstable dependency versions unless explicitly asked for
- Ignore unused imports warnings until it's time to commit (fix them then)
- Run scala-cli fmt on the modified files before commit
