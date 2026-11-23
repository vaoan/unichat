# Every free linter, enforced — design

## Intent
The person (2026-11-23): "agrega tantos linters como puedas. quiero la mayor cantidad de calidad forzada y gratis posible".

The trigger was an `if (Boolean.TRUE) return at;` left in the working tree while an agent mutated code. Nothing in the build would have stopped it from being committed. Every free tool that catches defects automatically runs on every build and fails it.

## Goals
Every tool below fails `./mvnw verify`, locally and in CI.

| Tool | Catches | How |
|---|---|---|
| **javac `-Xlint:all -Werror`** | unchecked/raw types, deprecation, fallthrough, missing `serialVersionUID`, … | compiler args (narrow `-Xlint:-x` only for a listed reason) |
| **Error Prone** (Google) | constant conditions, wrong `equals`, ignored futures and returns, bad formats, reference equality, … | javac plugin; ERROR and WARNING both fail |
| **NullAway** (Uber, on Error Prone) | null dereferences at compile time | `@NullMarked` packages (JSpecify); phase 2 |
| **PMD** (full rule set chosen below) | unconditional `if`, dead code, unused vars and params, empty catch, `Boolean` misuse, complexity, … | `pmd:check`, custom `pmd-rules.xml`; the CPD check stays |
| **SpotBugs + FindSecBugs** | bytecode bugs: nulls, resources not closed, threading, plus security (injection, weak crypto, path traversal) | `spotbugs:check`, effort Max, threshold Low |
| **Checkstyle** (full) | more than sizes: naming, imports, `equals`/`hashCode`, magic in `switch`, modifiers order, unused imports | extend `checkstyle.xml`, without forcing a formatting style the code doesn't use |
| **Maven Enforcer** | Java version, dependency convergence, banned dependencies, no SNAPSHOTs, plugin versions pinned | `enforcer:enforce` |
| **Modernizer** | legacy APIs with modern replacements (`Vector`, `new Date()`, Guava where the JDK has it) | `modernizer:modernizer` |
| **OWASP Dependency-Check** | dependencies with known CVEs (fails on CVSS ≥ 7) | CI job (needs the NVD feed, so not on every local build); cached |
| **CodeQL** (GitHub, free for public repos) | security and quality queries over the code | `.github/workflows/codeql.yml` on push and PR |
| **JaCoCo** | coverage that doesn't drop | report every build; fail under the measured baseline of each package |
| **ArchUnit, LessonsTest, I18nTest, EnglishFirstTest** | already there | kept |

Adding all of these may slow the build. Measure before and after, and keep the local `verify` under twice its current time by caching results (SpotBugs and PMD incremental) and by moving only Dependency-Check to CI.

## Rules for adopting them
- **Every finding the tools report on the existing code is fixed.** No blanket exclusions and no baseline file that hides them.
- **Suppressions are narrow,** with a reason in the code (`@SuppressWarnings("…") // why`, `// NOPMD: why`). A test fails when a suppression has no reason.
- **Fixes don't change behaviour.** The existing tests stay green, and a fix that changes behaviour (a real bug found) gets its own test and a line in the folder's lessons.
- **Agents breaking code to watch a test fail** (mutation checks) do it in a separate worktree, never in the person's working tree. This goes in the implementer instructions and `src/test/CLAUDE.md`.

## Phases (each a branch, merged when green)
1. javac lint, Error Prone, PMD, Enforcer, Modernizer: compile-time and source checks, fixing all findings.
2. SpotBugs with FindSecBugs, the full Checkstyle and JaCoCo's floor.
3. NullAway with JSpecify `@NullMarked`, package by package, starting with `support`, `model` and `settings`.
4. CI: CodeQL and Dependency-Check.

## Sequencing
This starts after the "English first" plan merges. Both touch most files, and running them at once would conflict.

## Non-goals
- Paid tools: SonarCloud is free for public repos, but it needs an account and token; it can be added later if the person wants it.
- A formatter that rewrites code: Spotless or google-java-format would reformat the whole codebase. That's not wanted unless asked.
