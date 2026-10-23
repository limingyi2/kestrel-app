# Kestrel initialization

This fork initializes the Android app as Kestrel. It does not release an APK or deploy a relay. See [Kestrel architecture](kestrel-architecture.md) for implemented boundaries, the proposed protocol and the remaining integration work.

| Brand surface | Decision |
|---|---|
| Android launcher, localized app text, default agent name, foreground notifications | Kestrel, including all 18 existing resource locale directories |
| Application ID / shortcut target | `io.github.limingyi2.kestrel` |
| External deep link | `kestrel://`, normalized to existing internal `minis://` |
| Updates | Both Android update paths use `limingyi2/kestrel-app`; the upstream download index is no longer consulted |
| Source, issue, share and license links | Kestrel repository; upstream attribution link remains nanoMuse |
| Internal packages, Skills, MCP, sandbox paths, socket transport | Retained; socket automatically follows application ID |
| Icon, avatar and palette | Existing upstream assets are temporary, replaceable assets, not the final Kestrel logo |
| Formal logo | Pending approved gold ring with transparent background; no final logo designed here |
| nanoMuse Cloud / Desktop references | Actual external upstream products keep their names; not presented as a deployed Kestrel service |
| iOS, Python, web, upstream desktop | Inherited sources retained, outside this Android initialization |

`scripts/rebrand.py` is the supported idempotent entry point; `--check` is read-only. Its original nanoMuse implementation remains for merge review, while `scripts/kestrel_brand.py` applies the Android allowlist. Do not use a repository-wide string replacement. Resource keys, legal comments, URLs for external products and existing storage paths are not product display names.

LICENSE, NOTICE, THIRD_PARTY_NOTICES.md and submodule references retain their upstream content. Kestrel modifications are available in this fork's Git history under the same GPL-3.0-or-later terms. Upstream files use `// nanoMuse:` markers to keep their existing merge-review convention. The inherited README content below each fork notice documents nanoMuse, including its downloads; those are not Kestrel release links.

Before release: replace temporary icons with approved assets, verify Kestrel UI on a device, perform the Linux/Skills/MCP smoke tests, deploy and configure the intended Seoul relay, implement/review the Vigil Bridge and protocol adapter, and decide Kestrel's coordinated versioning and signing policy. There are intentionally no Kestrel releases yet. A GitHub update check may report no release or an unavailable latest version until the first approved release exists.
