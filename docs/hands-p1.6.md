# Hands P1.6 implementation and device report

## Baseline

P1.4 commit `c144a097` is the code baseline. Its Android arm64 build, unit tests and
`androidTest` compilation passed in [CI run 40058335921](https://github.com/limingyi2/kestrel-app/actions/runs/40058335921).
The local Windows environment has no Java runtime or Android SDK, so those Gradle tasks
cannot run locally. The implementation keeps the existing Hands operator and its visual
single-action path.

## Execution path

The screen model may return a plan of two to six semantic steps. Plans support app launch,
click, ordinary text entry, Back, Home and scroll. The app exposes only a bounded list of
visible clickable, editable and scrollable labels to the model; editable values and likely
secret fields are omitted. Screen text remains untrusted data and is never interpreted as a
new instruction.

Before each step, the local executor checks the foreground package and the expected visible
label. A click requires exactly one visible, enabled accessibility target and reacquires that
same target immediately before `ACTION_CLICK`. An app is resolved by an exact installed label,
package name or existing alias; fuzzy app matches return to visual Hands. An app already in
front is not launched again. Ordinary text requires one visible, enabled, focused, non-password
field, uses `ACTION_SET_TEXT`, and verifies the field value. It does not touch the clipboard.

The executor subscribes to accessibility events before an action. A multi-step plan continues
only after a stable event and the next expected page label are both observed. The event wait is
bounded to three seconds; missing or unreliable nodes, a changed package, a missing expected
label, a failed text readback, a sensitive target, or cancellation stops the plan. Completed
steps are reported to the next visual turn and are not repeated. The existing screenshot and
single-action loop handles the current page. Plans cannot contain password entry, payment,
send, post, delete, install or download actions. Risk approvals, cancellation, audit traces and
the existing return-to-Kestrel policy remain in place.

## Regression coverage

New JVM cases cover bounded plan parsing, sensitive and secret rejection, target ambiguity,
expected-page failure, foreground-app reuse, focused text verification, install-button
fallback and cancellation between actions. Existing Hands action and page-state tests remain
in the regression suite. Final branch CI is the build authority because this machine lacks
the Android toolchain.

## Paired device acceptance

Run each task on the same Xiaomi 15 Pro, Android/HyperOS build, model, provider, network,
language, permission state and initial foreground app. Alternate baseline and candidate runs.
Use at least 20 runs per version and record the APK/commit hashes. Task C is read-only and
must not send a message. Task B must not download or install anything.

| Task | Result required | Baseline | P1.6 |
| --- | --- | --- | --- |
| A. Open Settings, open About phone, read the Android version | Version matches Settings; return to Kestrel when configured | Pending device measurement | Pending device measurement |
| B. Open 小黑盒, search `dsh 桌宠插件`, read results | Correct results; no download or install | Pending device measurement | Pending device measurement |
| C. Open QQ and inspect visible unread messages | Read-only; no message sent | Pending device measurement | Pending device measurement |

For every run, export the Hands trace and summary. Record instruction-to-first-action time,
each model call and duration, screenshot count and time, node lookup and action time, page
transition wait, total duration, action count, fallback count, success, wrong/duplicate taps,
and return-to-Kestrel result. Use `python scripts/android/hands-report.py <exported-hands-directory>`
for nearest-rank P50/P95 and stage shares. The report has no P1.6 device samples yet, so all
device percentiles and success comparisons remain pending. CI and unit tests are not evidence
of lower latency or real-app task success.

## APK signing

The stable test key is stored in GitHub Actions Secrets by the existing P1.4 signing change.
That workflow intentionally uses the stable key only on `main`; pull-request APK artifacts use
an ephemeral runner signature. This P1.6 branch therefore cannot produce a directly upgradeable
APK without weakening the main-only signing boundary or merging the signing change. No PR is
merged here. Do not install the ephemeral PR APK over a stable-signed Kestrel installation.
Once the signing workflow is on `main`, dispatch the arm64 Debug build there and verify its
certificate before using it for an in-place upgrade.
