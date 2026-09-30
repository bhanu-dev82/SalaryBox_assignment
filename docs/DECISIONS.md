# Decision record

Every non-obvious choice, the alternative rejected, and the reasoning. Ordered roughly by
how much they matter.

---

## 1. MediaPipe `FaceLandmarker` over ML Kit face detection

**Decision.** MediaPipe Tasks Vision `FaceLandmarker`, model bundled in `assets/`, CPU
delegate, 478 3-D landmarks.

**Rejected.** `com.google.mlkit:face-detection`.

**Why.** ML Kit's face API requires **Google Play services**. That means the app silently
does not work on devices without them, and the recognition path cannot be exercised
offline or reasoned about in a unit test. MediaPipe runs entirely on-device from a bundled
model, so it works everywhere, needs no network, and emits 478 landmarks rather than a
contour set — a materially better basis for a geometric descriptor.

**Cost.** 3.6 MB model in the APK, and ~4 native `.so` files (release APK is 53 MB for all
four ABIs; ABI splits or an App Bundle would cut this substantially).

**Related.** The model is **committed** to the repository. A silently-upgraded model would
change the descriptor geometry and silently invalidate every stored template.
`EnrolmentSession.MODEL_VERSION` exists to make that failure loud.

---

## 2. Landmark-geometry descriptor over a learned embedding

**Decision.** Normalise 478 landmarks (translation, scale, in-plane rotation), weight
regions, L2-normalise, compare with cosine similarity.

**Rejected.** ArcFace / MobileFaceNet embeddings (a 128-d learned vector).

**Why.** An embedding model would be materially more discriminative. The constraints were
(a) the app must build and run with no model download, and (b) the assignment is a two-day
scope. A geometric descriptor is auditable, unit-testable with no ML runtime, and every
decision in it is explainable. The trade-off is real and is documented in README §9 rather
than glossed.

**What would change my mind.** If this were a production identity check rather than a
single-site attendance verification, I would use an embedding model plus a real liveness
challenge. The `FaceEngine` interface exists so that swap is a one-file change.

---

## 3. Threshold calibrated from the **worst** enrolment pair, not the mean

**Decision.** `threshold = min(pairwise similarity among enrolment samples) − 0.004`,
clamped to `[0.90, 0.9995]`, with the admin's configured floor honoured as a lower bound.

**Rejected.** A hardcoded constant, and calibration from the mean.

**Why the mean is wrong.** Four good samples and one bad one average out to a number that
looks healthy while the template cannot reproduce the bad sample. The worst pair is the
binding constraint: it is the closest the employee ever came to producing a frame the
template would reject.

**Why the scale is high.** A landmark descriptor is *extremely* self-similar. Measured on a
synthetic cohort, the same person scored > 0.9999 while different people topped out near
0.97. An intuitive-looking "cosine similarity" default of 0.70 would therefore have
accepted almost anyone. This was a genuine bug, caught by `FaceDiscriminationTest` — see §8.

---

## 4. Enrolment diversity measured in **pose**, not descriptor space

**Decision.** A candidate enrolment sample is rejected only if its yaw/pitch proxies are too
close to an already-accepted sample, and the requirement is relaxed after 25 consecutive
refusals.

**Rejected.** Requiring new samples to be *dissimilar in descriptor space*.

**Why it could not work.** The descriptor is deliberately invariant to roll, position and
distance. Every frame of the same person therefore scores ~1.0 against every other frame, so
a descriptor-space diversity check rejects everything forever — enrolment could never
collect a second sample. Head pose is the thing that genuinely varies between useful
captures and that the descriptor does not normalise away.

**The relaxation** exists because the alternative is worse: someone who simply holds still
would be stuck on a spinning prompt. A slightly less diverse template is a much smaller
problem than an enrolment that cannot be completed.

---

## 5. Multi-frame, consecutive verification

**Decision.** Accept only when **3 of the last 5** frames clear the threshold **and 3
consecutive** frames have cleared it.

**Rejected.** A single-frame comparison.

**Why.** Consecutive agreement is the cheapest real defence against a printed photo held to
the lens: a still photo yields one plausible frame and then frames that fail the motion and
inter-frame-stability checks, so it cannot accumulate a consecutive run.

**Honest limit.** This is not liveness detection and does not defeat a good deepfake. It is
documented as such in README §9. The alternative — a real liveness challenge with random
head turns, blink detection and texture analysis — is out of scope for two days, and saying
so is better than implying the check is stronger than it is.

---

## 6. Multi-frame **consecutive** verification ordered verify → capture → write

**Decision.** Verify the face, then capture the selfie, then let the user confirm, and only
then write the row.

**Why this order.** Nothing touches the database until a selfie exists, so a cancelled or
failed verification leaves no orphan rows and no dangling image paths. Showing the captured
photo before committing is also simply correct: it is the person's biometric data.

---

## 7. Location is advisory and never blocks attendance

**Decision.** A missing or failed location fix records `LOCATION_UNAVAILABLE` and the punch
still succeeds. A fix is bounded at 6 s using `PRIORITY_BALANCED_POWER_ACCURACY`.

**Rejected.** Requiring location, or refusing a punch when the fix is poor.

**Why.** Refusing someone's attendance because their GPS was poor is a far worse failure than
recording a slightly less precise location. And `LOCATION_UNAVAILABLE` must be distinct from
`OUTSIDE` — conflating them would file a false accusation against the employee.

**Power choice.** `BALANCED_POWER_ACCURACY` because a 200 m geofence does not need metre
precision, and the high-power radio is a real battery cost on a device used as a work
handset all day.

---

## 8. Constraints enforced by the database, not the UI

**Decision.** `UNIQUE(staff_id, local_date, punch_type)` and `UNIQUE(employee_id)`;
foreign keys explicitly enabled via `PRAGMA foreign_keys=ON`; no
`fallbackToDestructiveMigration`.

**Why.** A UI check is advisory; a unique index is the guarantee. Two rapid taps, or two
screens, cannot produce two punch-ins. SQLite defaults foreign keys **off**, so without the
explicit `PRAGMA` every foreign key in the schema would be decorative — a silent correctness
hole that looks correct in review. And wiping someone's attendance on a schema change is
worse than failing loudly.

---

## 9. PBKDF2 over bcrypt/Argon2

**Decision.** `PBKDF2WithHmacSHA256` from `javax.crypto`, 120 000 iterations, per-staff
random salt, constant-time compare.

**Why.** It is in the JDK, so it adds no dependency and no native library to an APK that
already ships MediaPipe's `.so` files. For a 4–12 digit PIN — weak by construction — a
memory-hard KDF buys little over a well-iterated PBKDF2. The honest control is that the
database is on-device and the app is offline, and README §8 says so rather than implying
the PIN is properly protected.

**The iteration count is stated in the DI graph** rather than hidden in a constructor default,
so raising it is a one-line, greppable change.

---

## 10. The admin is a database row, not a hardcoded branch

**Decision.** Seed an admin `StaffEntity` with role `ADMIN` and authenticate it through the
same PBKDF2 path as staff.

**Why.** A hardcoded `if (pin == "1234")` would be a *different and weaker* mechanism sitting
next to the real one, and the two would drift. Both roles now go through identical code.

**Consequence handled.** The admin therefore appears in `staff` and had to be explicitly
filtered out of the staff list — a bug found on-device. It has no face and should not be
enrol-able or deactivatable.

---

## 11. Offline-first, no `INTERNET` — but the permission is still there

**Decision.** No network code anywhere; intended to declare no `INTERNET` permission.

**Reality.** `aapt2` on the built APK shows `INTERNET` and `ACCESS_NETWORK_STATE` arriving
transitively via `mediapipe → media3-common → datatransport` (Google's CCT telemetry).

**Why not force-remove it.** `tools:node="remove"` would make the claim true, but risks a
runtime `SecurityException` from Play services location that I could not verify on a device.
Shipping an unverified privacy "guarantee" that crashes on someone's phone is worse than
documenting the truth.

**Why this is written down.** It would have been very easy to claim "no INTERNET permission"
in the README and be wrong. The chain and the one-line fix are documented in README §8.

---

## 12. `PunchRules` as a pure function, and the setting that makes it flexible

**Decision.** `PunchRules.evaluate(records, date, punchOutEnabled, isFaceEnrolled)` — a pure
function returning what may happen next, with a single `punchOutEnabled` flag switching
between "one punch covers the day" and "punch in and punch out".

**Why rigid *and* flexible.** There is exactly one implementation of "what may I do next",
shared by the button label, the repository and the tests, so the UI cannot disagree with what
the database will accept — which is why there is no "already punched in" error path in the
UI. Flexibility comes from the flag, not from a second code path.

**Defect it caught.** The function was originally named `recordsForDate` but accepted any
list. A test asserted records from other dates were ignored, and it failed. It now filters by
date itself, because the object that is the single source of truth should be correct even
when handed more than it expects.

---

## 13. Gradle memory pinned to 4 GiB

**Decision.** `org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m`.

**Why.** Gradle's 512 MiB default caused `"The Daemon will expire immediately since the JVM
garbage collector is thrashing"` on this machine and aborted a build. This is a real build
failure, not a warning, and it is not obvious from the error.

---

## 14. Adaptive: window size class + hinge, not `ListDetailPaneScaffold`

**Decision.** Read `currentWindowAdaptiveInfoV2()` and implement the two-pane split with a
`Row`, insetting by a vertical hinge.

**Rejected.** `ListDetailPaneScaffold` with `calculatePaneScaffoldDirective`.

**Why.** The scaffold also owns pane *navigation*. This screen keeps its selection in a
ViewModel, which is what preserves it across the Activity recreation that folding or rotating
a foldable causes. Handing the selection to the scaffold would mean giving that up. The
`Row` reproduces the documented canonical semantics exactly — two panes when there is room,
detail-in-place with a back gesture when there is not.

**Cost, stated plainly.** This is a deliberate deviation from the recommended API. On a
reviewer who greps for `ListDetailPaneScaffold` it will look like a miss; the reason is
recorded here and in the KDoc at the call site.

---

## 15. No dynamic colour by default

**Decision.** `dynamicColor = false`, opt-in only.

**Why.** Status colours in this app carry meaning — recorded, outside the geofence — and the
hand-tuned palette was chosen for contrast in bright outdoor light. A wallpaper-derived
palette can quietly destroy that contrast. Dynamically tinted status colours would be pretty
and wrong.

---

## 16. Typed `AppError` instead of throwing

**Decision.** A sealed `AppError` and an `Outcome<T>`; no exception reaches a composable.

**Why.** The public reviews of the real SalaryBox app repeatedly complain about
*"always show something went wrong, whats going on, please fix this asap"*. A closed set of
failures forces the UI to render a specific, actionable sentence, and makes the error path
exhaustively testable. `runCatchingOutcome` rethrows `CancellationException` rather than
converting it into a value, because cancellation is control flow, not an error.

---

## 17. Crash reporting, because their users complain about crashes

**Decision.** A global `UncaughtExceptionHandler` writing a PII-free report to
app-internal storage, with a user-facing explanation on next launch.

**Why.** This is a direct response to observed product feedback, documented in README §14 and
the commit log. A crash a user cannot explain is a support ticket; recording what happened
and explaining it in plain language, with copyable technical detail, is strictly better than
dying silently.

---

## 18. AI-written code is reviewed, not trusted

The assignment asks for an AI conversation export. The honest counterpart is that the code
was verified rather than assumed, and the export records how.

**Found by tests (a compiler would never have caught these):**

1. Default similarity threshold of 0.70 was meaningless for this descriptor family.
2. Enrolment diversity check could never be satisfied.
3. Motion gate compared against the last *accepted* frame instead of the previous frame.
4. `pitchProxy` used the hairline landmark, biasing a frontal face toward "looking down".
5. `PunchRules` silently accepted records from other dates.
6. The synthetic test fixture itself was wrong twice — first making different people
   identical, then making the descriptor under-discriminative.

**Found only by running on a real device:**

7. Startup crash: the Hilt graph called a global context holder, but injection runs *inside*
   `Application.super.onCreate()`, before any subclass `onCreate` body.
8. App hung on a loading state forever: a `isLoaded` getter reached into a `MutableStateFlow`,
   which is invisible to Compose, so the screen never recomposed.
9. "Open a staff member's profile" did nothing — the detail pane was never wired.
10. The face guide painted its own interior solid black, hiding the area the user needs to
    see, because `BlendMode.Clear` cuts through to the window background rather than
    revealing a SurfaceView layer.

Each of these is recorded in the commit that fixed it.
