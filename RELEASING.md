# Release policy

This document states which checks are mandatory on every build, when a **live** Claude
integration run is required for a release candidate, and when existing evidence may be
reused instead of re-running the same paid suite.

## Two test tiers

The suite is split by JUnit tag, not by workflow convention, so the split holds locally as
well as in CI.

| Tier | What it is | Needs a credential? | When it runs |
|---|---|---|---|
| **Deterministic** | The surefire suite, including the `NoPromptConnectRegressionTest` stub-CLI regression, plus `CLIFlagParityIT` | No | Every build |
| **Live** | The `@Tag("live")` failsafe classes, which drive a real Claude CLI against the Anthropic API | Yes — `ANTHROPIC_API_KEY` | On demand only |

`CLIFlagParityIT` sits in the deterministic tier on purpose: it only reads `claude --help`,
which is a local, offline, unauthenticated call. It needs the CLI binary, not an account.

The parent POM sets `failsafe.excluded.groups` to `live`, so the paid tier is **excluded by
default**. Opt in explicitly:

```bash
./mvnw verify                              # deterministic; no credential, no cost
./mvnw verify -Dfailsafe.excluded.groups=  # adds the live tier; spends money
```

## Mandatory on every build

These are gates. A candidate that fails any of them is not releasable.

1. The full deterministic surefire suite.
2. The no-prompt stub regression (`NoPromptConnectRegressionTest`) — a shell stub stands in
   for the CLI, so it is deterministic and free.
3. `CLIFlagParityIT.criticalSdkFlagsShouldBeInCli` — verifies that every flag the SDK
   actually emits still exists in the installed CLI.
4. The standalone-consumer gate (`scripts/standalone-consumer-gate.sh`) — catches the
   flattened-POM consumer shape that let 1.4.0 ship vulnerable Jackson floors with a green
   build.
5. Release packaging.

Note what is deliberately **not** a gate: `CLIFlagParityIT.allCliFlagsShouldHaveSdkSupport`
reports newly shipped CLI flags as a warning. A flag appearing upstream is not a defect in
this SDK, and failing the required build on one turns an Anthropic release into an
unrelated red build here. An SDK breaks when a flag it *uses* disappears — which is gate 3
— not when a flag it *ignores* appears. Every unmodelled flag remains reachable through
`CLIOptions.extraArgs`.

## Credential exposure

`ANTHROPIC_API_KEY` is passed to exactly one workflow: **Live Claude Integration Tests**
(`.github/workflows/live-integration.yml`), which is `workflow_dispatch` only. Starting it
requires a human with write access, and that is the authorization boundary for spending
money on provider calls.

CI, snapshot publication, and the release job are credential-free with respect to the
provider. In particular:

- CI uses the `pull_request` trigger, **not** `pull_request_target`. Fork PRs therefore run
  without access to repository secrets. Do not switch this trigger to make a fork PR see a
  secret — that is the standard exfiltration path.
- A development snapshot never costs a paid call.

## When a fresh live run is required

Run **Live Claude Integration Tests** against the exact candidate SHA before releasing when
the candidate changes any of:

- **subprocess** behaviour — process spawn, argument construction, environment, teardown,
  `destroyProcessTree`;
- **protocol** behaviour — stream-JSON parsing, control requests/responses, message types;
- **session** behaviour — resume, continue, fork, session identity;
- **hook** behaviour — `HookRegistry`, hook callbacks, hook lifecycle events;
- **permission** behaviour — permission modes, `ToolPermissionCallback`, permission prompts;
- **MCP** behaviour — server config, transport, tool naming;
- **shutdown** behaviour — close, interrupt, cancellation, resource cleanup.

A release that touches none of these — documentation, build plumbing, dependency bumps with
no behavioural surface, additive options that are off by default — does not require a fresh
live run.

## When existing evidence may be reused

A completed Live Claude Integration Tests run is valid evidence for **the exact SHA it
recorded, and only that SHA**. It may be reused for the release of that SHA rather than
re-running the same suite in Build, Snapshot, and Release.

Reuse is permitted when all of these hold:

1. The run recorded the candidate SHA and it equals the SHA being released.
2. The run completed — a cancelled or partial run is not evidence.
3. The recorded census shows no failures or errors.
4. Nothing in the behavioural list above changed after that SHA.

If the candidate SHA moves for any reason — a rebase, an added commit, a version bump
commit that alters behaviour — the prior evidence no longer applies and a fresh run is
required. A version-bump-only commit that changes nothing but the POM version does not
require re-running, but the reused run must be cited by its own SHA.

Cite reused evidence in the release notes by run ID, candidate SHA, and CLI version, all of
which the workflow writes into its run summary.

## Publication authority

Publication for this project is centralised in `agent-release-manager`. The workflows here
are the mechanism, not the authority to fire them.
