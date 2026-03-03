# goal
- Setup a GitHub Action workflow to produce a release for the `agama` repository.
- Build process: `bash travis/build.sh`.
- Target platform: **Linux aarch64 only** (disable all other platforms to avoid errors).
- Release binaries location: `gama.product/target`.

# constraints/assumptions
- Use Java 21 (based on existing workflows).
- Build requires significant memory (MAVEN_OPTS).
- Binaries are in `gama.product/target`.
- Trigger on manual dispatch and/or tags.

# key decisions
- Create a new, simple workflow `.github/workflows/release.yml`.
- Use `actions/setup-java` for environment.
- Use `softprops/action-gh-release` for publishing assets.

# state
- Done: Investigated existing workflows and project structure.
- Done: Created and refined `.github/workflows/release.yml`.
- Done: Fixed `x86_64` vs `aarch64` path errors in existing CI.
- Done: Disabled Windows and MacOS packaging as requested.
- Done: Disabled redundant 'Testing built GAMA' and 'Testing Linux package' jobs that were failing due to hardcoded x86 paths.
- Now: Completed setup and cleanup.
- Next: User can test by pushing a tag.

# working set
- .github/workflows/release.yml
- travis/build.sh
