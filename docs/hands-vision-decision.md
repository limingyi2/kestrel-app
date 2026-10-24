# Hands: single-model vision decisions and continuous operation

Root-cause audit and the minimal fixes that came out of it. Baseline: `main` after
PR #6 (`5fb427ed`). Device context: Xiaomi 15 Pro, Android; the reported symptom is a
Pinduoduo search task that lingers on “Looking at the screen…” and repeats observation
without tapping.

## The real call chain

```
user instruction → chat agent loop (ChatViewModel, chat model)
  → model emits shell_execute: nanomuse-hands run --task …
  → NativeOffloadServer → HandsOffloadHandler.run  (blocks for the whole task)
  → HandsOperator.run, once per step:
       capsule status “Looking at the screen…”        (nm_hands_looking)
       screenshot over the accessibility service      (capsule hidden, 2 attempts)
       JPEG 720 px encode + grey fingerprint          (loop-watch, black-frame check)
       ScreenWords.visibleControls                    (bounded live label list)
       provider.sendMessage(…, AgentContentPart.ImageData)   ← the raw screenshot
                                                              goes to the user's
                                                              configured hands model
       HandsAction.parse                              (one action, or a 2–6 step plan)
       stale-observation gate                         (see R2 — the hot spot)
       local execution                                (unique-node click or gesture,
                                                       risk gate, verified typing)
       event-driven settle                            (HandsPageObserver, 0.9–2.8 s)
  → result JSON {outcome, answer, steps, last_screen, trace, log} → chat agent
  → the chat model may call read_image on last_screen (model-initiated, not automatic)
```

## Answers to the audit questions

1. **Does Hands send the raw screenshot to the configured multimodal model?** Yes.
   `HandsOperator.buildMessages` wraps the current screenshot in
   `AgentContentPart.ImageData` and calls the user's configured hands model
   (`Hands.screenModel`) directly through the normal `ProviderFactory` path. There is
   no intermediate describing model anywhere in the Hands loop. The Vision Group only
   matters to the *chat* agent's `read_image` tool when the chat model lacks vision.
2. **Are model actions executed locally?** Yes. Taps are grounded on one uniquely
   matching visible clickable accessibility node when possible (otherwise the model's
   grid coordinate), sent through the risk gate, then performed as an accessibility
   click or an injected gesture. Typing uses `ACTION_SET_TEXT` with value verification.
3. **Why does each step trigger a new model request?** By design: one
   perceive→decide→act cycle per step, because each action changes the screen. On top
   of that, three paths burn extra requests without acting: the stale-observation skip
   (R2), model-format errors (up to 3), and model errors (up to 3 with a 1.5 s backoff).
4. **Is `read_image` auto-invoked by the outer agent?** No code path calls it
   automatically. But the system-prompt paragraph told the agent that `last_screen`
   “is a picture you can show with `![screen](<last_screen>)`”, and models often
   choose `read_image` to look before showing — that is the duplicate read the user
   saw. Fixed by prompt wording plus an explicit note in the tool result (F3).
5. **What does each phase cost?** Already instrumented per step in
   `trace.jsonl` (`timings_ms`: screenshot capture/encode/save, accessibility controls,
   model, risk approval, aim wait, android action, settle wait) and per run in
   `run-summary.json` (totals, counts, milestones, return attempt).
6. **Repeated reading/judging of the same page?** No screenshot is read twice, but the
   same page was re-observed and re-judged many times through R2 below, and the loop
   guard (`HandsLoopWatch`) never saw those steps because skipped steps did not reach
   it. Timeout-then-retry re-inference exists for model errors only (bounded at 3).

## Where “Requested focus” comes from

`ChatViewModel.executeReadImageTool` (ChatViewModel.kt:9858). When the chat model has
native vision and the `read_image` call carried a `prompt`, the tool echoes
`[Requested focus: …]` into its own text output. It is metadata on the tool result; it
does not itself trigger another model call.

## Root causes

- **R1 — one status label for everything.** The capsule shows `nm_hands_looking`
  (“正在看屏幕…”) from the start of a step until the model replies, i.e. through the
  whole screenshot + model call (up to the 90 s model timeout), and again after every
  approval. A slow model call is indistinguishable from being stuck.
- **R2 — the stale-observation skip discards good decisions.** A fresh
  `HandsPageObserver` is created at the top of every step, *before* the screenshot. Any
  accessibility content event between the observer start and the model's decision
  (animations, clocks, carousels — Pinduoduo's home page fires these constantly) marks
  `changed()`, and the decided action is thrown away with
  `stale_observation_skipped` unless the click happens to re-ground on a unique node.
  The next step re-screenshots and re-decides on a near-identical screen. Because the
  skip `continue`s *before* `HandsLoopWatch.step`, this can repeat far past the loop
  guard's limits — the model “keeps looking and never taps”.
- **R3 — the outer agent re-reads the final screenshot.** Prompt wording invited
  treating `last_screen` as something to inspect; `read_image` then re-decodes a JPEG
  the hands model already saw and costs another model turn.
- **R4 — fixed settle minima add latency.** `TAP_SETTLE_MS` (900 ms),
  `SWIPE_SETTLE_MS` (1100 ms) and `OPEN_APP_SETTLE_MS` (1800 ms) are minima; the page
  observer usually ends earlier on a stable event, but a quiet page without events
  always pays the full minimum. Left as is — the event path already implements
  target-condition waiting, and shortening the floor risks tapping mid-transition.

## Fixes

- **F1 — act on live targets; verify coordinates before they tap (R2).** What happens after
  a content event is decided per grounding (`HandsStalePolicy`, pure + unit-tested): a tap
  grounded on a unique live accessibility node acts, because the executor re-resolves the
  node at act time; a tap falling back to the model's screenshot coordinate must first pass
  a byte-identical freshness check against the screenshot the model saw (`VERIFY`), and a
  coordinate that cannot be re-verified never taps (`stale_coordinate_rejected`). A window
  switch always takes a fresh look — including under a `status` verdict, whose completion
  statement would otherwise be accepted about a screen that no longer exists. Non-tap
  actions keep their own live revalidation (focused field for typing and Enter), and
  dangerous taps keep the approval-time freshness check. Skips are counted in the trace and
  run summary.
  The verify path is bounded for pages with constant motion: when the pixels differ, a
  *live* accessibility label at the coordinate that still names the model's target is
  accepted as proof the point is not stale and the tap proceeds (`ACT_ON_LABEL` — a
  payment-or-send tap is never rescued this way); without that proof the step re-observes,
  and after `MAX_COORDINATE_VERIFY_FAILS` (3) failed attempts the run ends with
  `coordinate_verify_exhausted` and the reason in the answer, for the user to intervene.
  The budget is per target: a model that changes tack, or a real page switch, starts it
  over. A stale coordinate is never tapped.
- **F2 — per-phase capsule status (R1).** New strings separate observing, deciding,
  acting, waiting for the page and verifying, in English, Simplified and Traditional
  Chinese. The capsule shows the generic working title instead of “Step 0” between steps.
- **F3 — no duplicate `read_image`, enforced (R3).** The prompt paragraph tells the agent
  the run result text is authoritative and `last_screen` is shown inline
  (`![screen](<last_screen>)`), never re-read with `read_image`; the run result JSON
  carries the same note. On top of the prompt, `ReadImageDedup` enforces it behaviorally,
  scoped to one user turn: the `last_screen` of a *completed* run (`outcome: done`,
  registered by the shell tool result itself) is not read at all this turn — even the
  first `read_image` of it is answered with a note, because the hands model already judged
  those pixels and the answer text is on the table; failed, stopped and needs-user runs
  are not registered, so the agent can look when a run went wrong. Any other Hands
  screenshot is readable once per turn, and repeats get the note. The gate is keyed on the
  run id and file name, so the `minis://` URL of the result and the host path the read
  resolves to land on the same entry. Ordinary images are never gated, the Vision Group
  routing is untouched, and a new user message reopens both gates. Every `read_image`
  execution is logged with its origin for troubleshooting.
- **F4 — diagnostics (§6 of the task).** `run-summary.json` gains
  `stale_observation_skips`; the trace distinguishes `stale_observation_skipped` from
  `stale_coordinate_rejected`.

Everything else — the model choice order, the multimodal request path, the action
protocol, plans, the risk gate, cancellation, audit traces, the return-to-Kestrel
policy — is unchanged.

## Tests

JVM unit tests cover the extracted decision points: the stale-observation policy
(window change vs. content change, node-grounded tap vs. coordinate fallback that must
prove freshness, re-verification of a completion statement after a window switch, moved
node, cancellation), the bounded coordinate fallback as a simulated end-to-end run of the
per-step decision sequence on a constantly animated page (a matching live label taps on
every attempt; a mismatching one re-observes exactly MAX_COORDINATE_VERIFY_FAILS times and
then ends the run), and the behavior-level Hands-screenshot gate (a completed run's
`last_screen` is not read even the first time, across the `minis://` and host path forms;
failed or stopped runs stay readable; ordinary images are never gated; a new user turn
reopens), plus the prompt contract that forbids re-reading `last_screen` with
`read_image`. The existing Hands action, plan, page-state and loop-watch suites must keep
passing. Device timings are not claimed anywhere: CI and a Xiaomi 15 Pro run must be
reported separately.
