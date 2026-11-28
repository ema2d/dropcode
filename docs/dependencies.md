# Dependency updates

Dependabot keeps dependencies current. Configuration: [`.github/dependabot.yml`](../.github/dependabot.yml).

| Kind | When | How it is merged |
|---|---|---|
| **Security updates** | As soon as GitHub detects a vulnerability (Dependabot alerts + security updates are enabled in the repo settings) | Review, check that CI is green, merge as soon as possible |
| **Minor / patch** | Monthly, grouped into one PR per ecosystem (backend, frontend, GitHub Actions) | Merge when CI is green |
| **Major** | Monthly, one PR per dependency | **Never automatically.** Read the release notes and breaking changes, test locally (`./gradlew check`, `npm run typecheck && npm test && npm run test:e2e`, and the real-R2 IT for storage changes), then merge |

## Ignored major versions

| Dependency | Why | Revisit when |
|---|---|---|
| `typescript` 7 | `openapi-typescript` (generates `src/lib/schema.d.ts`) only supports TypeScript 5 | `openapi-typescript` supports 7 |
| `jsdom` 27+ | Needs Node ^22.22 / ^24.15; local development runs Node 23 | Local Node moves to 24 LTS |

To stop ignoring one, remove its entry under `ignore:` in `dependabot.yml`.

## Testing a Dependabot PR locally

```bash
git fetch origin pull/NUMBER/head:dep-test
git switch dep-test
```

If the PR is behind `main`, comment `@dependabot rebase` on it so CI runs against the latest code before merging.
