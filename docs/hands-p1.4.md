# Hands P1.4 measurement and review

## Diagnosis before changes

Baseline: PR #3, `02e7594c`, branch `feat/kestrel-hands-optimization`. This change
branches from that PR without merging it. No device trace was supplied. The reported
ten-second delay cannot be attributed to the model from source inspection alone.

The Android chat stream collects a complete tool call before tool execution, then
the shell/offload dispatcher invokes `HandsOffloadHandler`, checks readiness and claims
the operator. `HandsOperator` resolves the model and apps, optionally launches an app,
captures/encodes/saves a screen, calls the visual model, approves the action, displays
the aiming indicator, dispatches an Android action, and waits before the next screen.
Existing provider first-chunk diagnostics measure provider TTFB, not instruction to
first Android action. PR #3 already records step durations; those fields are reused.

Source findings:

- Every click, including a uniquely grounded accessibility click, requests another
  screen and visual model response for the next decision. There is no action queue.
- PR #3's settle logic sleeps 900 ms for taps, 1,100 ms for swipes or 1,800 ms for
  app launches, then polls events. Its event boundary is after dispatch, potentially
  missing the transition itself. The app hint always sleeps 1,800 ms.
- A model failure reused a screenshot across a retry and a 1,500 ms delay. The live
  page could change in that interval. Retries now capture the current page.
- App launch did not check whether the named package was already in front.
- Approval can take arbitrarily longer than model inference, making saved coordinates
  stale even when the original target was correct.

## Implemented behavior

Listeners subscribe before observation and action. Window/content/scroll/text events
are distinguished; notification and Kestrel overlay events do not establish readiness.
A change plus 250 ms of quiet, visible content and no visible ProgressBar establishes
a candidate for the next screenshot. This is a debounce policy, not proof a network
load is finished. A continuous animation remains bounded. Without usable events or
nodes, the old action-specific fallback remains, with at most 1,000 ms extra for an
active transition. The following model decision always sees a fresh screenshot.

Window changes invalidate observations after inference. Content-only changes permit
a simple click to reacquire a live unique node; other actions reobserve. Taps recheck changes and unique
target bounds after approval and aiming. A unique visible enabled exact node uses
`ACTION_CLICK`, after the same risk gate. Ambiguous labels retain visual coordinates. A dangerous coordinate tap requires a fresh
image identical to the approved observation after approval; otherwise it reobserves.
There is no fallback coordinate tap if a direct node click fails. Long/double gestures
retain their existing Android gestures. Enter rechecks page changes after approval.
Already-foreground apps are not relaunched. Stop, risk approval and PR #3's confirmed
return/stay policy remain active.

Skipping the next visual decision solely because a node exists was considered and
deferred: a unique label does not establish the user's next intended action. No
settings-specific path or inferred destructive action sequence was added.

## Trace and reporting

`run-summary.json` adds monotonic `milestones_ms`, relative to the accepted user turn:
instruction received, first visible agent text or complete tool response, Hands call,
tool dispatch, native receive, operator start, first screenshot, first visual response,
first action dispatch, page change, next action dispatch and task finish (after return
confirmation). `android_action_N` records every attempted dispatch. Direct shell or
background calls have no instruction milestone; missing measurements remain missing.
The task finish is the Hands task finish, not the main agent's subsequent final prose.
Provider first-byte diagnostics and PR #3 step timing fields are not duplicated.

Run `python scripts/android/hands-report.py <exported-hands-directory>`. The report
outputs nearest-rank P50/P95, sample counts, every model and page-wait duration,
attempt counts, return results and outcome rate. Leaf-stage shares use total operator
elapsed time. `action_and_wait_ms` is deliberately excluded because it contains other
timings; unattributed time remains visible. No trace means `pending_device_measurement`,
null percentiles and null success rate. Legacy PR #3 traces can supply step timings,
but cannot reconstruct instruction-to-first-action latency.

## Paired physical-device acceptance

Task: “打开系统设置，进入关于手机，读取 Android 版本。”

Use the same arm64 phone, Android build, model/provider, network, credentials, language,
permission grants, Hands preferences and initial foreground state for both versions.
Keep app-hint use identical. Record exact baseline and candidate commit/APK hashes.
Alternate baseline/candidate runs, with at least 20 measured runs per version and an
explicit warm/cold label. Never uninstall an existing install without backing up local
data; compare signing certificates before installing.

Export each run's trace and summary through the device's available attachment/file
export. Annotate device model, Android version, model id, network, expected version,
observed version, mis-taps, duplicate taps and actual foreground package. Report both
operator `done` rate and human-verified version-reading success rate. Check return-on
and stay modes separately, and test cancellation during model/wait/approval, denial,
model timeout, screenshot failure, duplicate labels, dialogs, animation, missing nodes
and a foreground Settings app. Compile/unit tests cannot replace these measurements.

| Required result | Baseline | P1.4 |
| --- | --- | --- |
| Instruction to first action P50/P95 | Pending device measurement | Pending device measurement |
| Model calls and per-call latency | Pending device measurement | Pending device measurement |
| Page transition latency | Pending device measurement | Pending device measurement |
| Action attempts, mis-taps, duplicate taps | Pending device measurement | Pending device measurement |
| Verified success and return/stay behavior | Pending device measurement | Pending device measurement |

No performance improvement or task success rate is claimed without paired device data.

## Accessibility switch investigation

Reported device: Xiaomi 15 Pro, HyperOS 4. The user reports that the system switch
became off, rather than an enabled service showing a connection failure. APK version,
incident time and device logs are not yet available; the specific cause is unresolved.

The source contains no `disableSelf()` or automatic permission-off write in Hands.
The existing recovery path only adds an enabled service after a user chooses repair.
The generic authorized Shizuku tool can force-stop a supplied package or write secure
settings; therefore tool history must also be checked before excluding an agent action.
[AOSP's force-stop handler](https://android.googlesource.com/platform/frameworks/base.git/+/6953b903c2457a26cd1288691261a2b02ac70a46/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)
removes a stopped package's service from the enabled list. This is a possible mechanism,
not evidence of what happened on this phone. A normal disconnected service and a removed
grant require different diagnoses; a crash or low-memory exit alone does not identify
who changed a system setting.

`nanomuse-hands status`, refused calls and run summaries now include read-only
`accessibility_health`: the grant state, actual service connection, the last 16 lifecycle
events and up to five Android process exit reasons/timestamps. The lifecycle log contains
no UI content. Missing/read-failed evidence stays unknown. `onInterrupt` is recorded but
does not itself mark the connection or grant disabled. No automatic permission recovery
was added.

After installing this build, collect `nanomuse-hands status` immediately after recurrence,
before re-enabling the service, plus the installed APK version and approximate incident
time. Correlate `permission_disabled` versus `enabled_disconnected`, lifecycle and process
exits with the chat's shell/Shizuku calls. If ADB is available, save these read-only outputs:

```sh
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility
adb shell dumpsys activity exit-info io.github.limingyi2.kestrel
adb logcat -d -v threadtime
```

Inspect system force-stop lines, caller/reason, service disconnection, crash/ANR and
tool invocations around the incident. Redact unrelated apps, screen contents and private
data before sharing logs. Background autostart and battery settings are investigation
variables, not a verified fix for this incident.
