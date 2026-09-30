# Bhanu Attendance

Selfie-based attendance app for Android, built for the SalaryBox hiring assignment
(*Android Attendance App — Hiring Assignment, v2.00, 21 September 2026*).

**Submitted by:** Bhanu Nagpure
**Package:** `com.bhanu.attendance`
**Licence:** MIT (see [LICENSE](LICENSE))

---

## 1. What it does

| Assignment requirement | Where it lives |
|---|---|
| 1. Login for Admin and Staff | [`feature/auth`](feature/auth/src/main/kotlin/com/bhanu/attendance/feature/auth) — role selector, PBKDF2-verified PIN |
| 2. Admin views staff list, adds staff, opens a profile | [`feature/admin`](feature/admin/src/main/kotlin/com/bhanu/attendance/feature/admin) — adaptive list-detail |
| 3. Admin enrols a staff face | [`feature/admin/…/enrol`](feature/admin/src/main/kotlin/com/bhanu/attendance/feature/admin/enrol) — guided 5-sample capture |
| 4. Staff marks attendance only if the face matches | [`feature/staff`](feature/staff/src/main/kotlin/com/bhanu/attendance/feature/staff) — multi-frame verification |
| 5. Stores timestamp, selfie and location | [`data/local`](data/src/main/kotlin/com/bhanu/attendance/data/local) + [`data/storage`](data/src/main/kotlin/com/bhanu/attendance/data/storage) |

Beyond the brief: punch-in **and** punch-out, an admin-configurable geofence, a
privacy-respecting on-device audit trail, and a crash reporter that explains itself
instead of dying silently.

---

## 2. Demo credentials

Seeded on first launch, and shown on the login screen.

| Role | Identifier | PIN |
|---|---|---|
| Admin | — | `1234` |
| Staff | `EMP001` (Ravi Kumar) | `1111` |
| Staff | `EMP002` (Priya Sharma) | `1111` |

Both staff start **not enrolled**, so the enrolment flow can be demonstrated end to end.

---

## 3. How to run

Requires **JDK 17** and the **Android SDK** (compileSdk 37).

```bash
# 1. Point the build at your SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. Install the app onto a connected device
./gradlew installDebug

# 3. Or build an APK
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/  (unsigned, see §8)

# 4. Tests
./gradlew test                 # 56 JVM unit tests, no device needed
./gradlew connectedAndroidTest # instrumented tests (needs a device)
```

**Requirements:** Android 8.0 (API 26) or newer, a front camera, ~80 MB free
(the face model is bundled in `app/src/main/assets`).

---

## 4. Architecture

Eight modules with a strict one-way dependency rule — arrows point right only.

```
   :app ──────────► :core:designsystem ──► :data ──► :domain
     │                    │                                     ▲
     ├──► :core:common ───┘                                     │
     ├──► :feature:auth ──────────────────────────────���──────────┘
     ├──► :feature:admin
     └──► :feature:staff
```

| Module | Responsibility | May depend on |
|---|---|---|
| `:domain` | Models, repository interfaces, use cases, **all face maths**, geofence maths. **Pure Kotlin/JVM — no Android.** | nothing |
| `:core:common` | `Outcome`/`AppError`, dispatchers, logging, crash reporting | nothing |
| `:core:designsystem` | M3 theme, components, accessibility helpers, camera session | `:domain`, `:data`, `:core:common` |
| `:data` | Room, DataStore, PBKDF2, MediaPipe engine, location, selfie storage, repository impls | `:domain`, `:core:common` |
| `:feature:*` | Screens, ViewModels, UI state | `:domain`, `:core:*` |
| `:app` | DI graph, Application, Activity, navigation. **No business logic.** | everything |

`:domain` being a plain JVM module is the load-bearing decision: the face-matching
maths, the punch-in/punch-out rules and the geofence logic are all testable in
milliseconds with no emulator, which is why 56 unit tests can guard the parts that
actually matter.

Full detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Every non-obvious choice, with the alternative rejected and why:
[`docs/DECISIONS.md`](docs/DECISIONS.md).

### Layering rules

- `:domain` has **no Android dependency at all** — enforced by the module type, not convention.
- ViewModels expose one immutable `StateFlow<XUiState>` and never leak domain objects to composables.
- One-shot navigation events go through a `Channel`, not state, so they are not replayed on rotation.
- Every failure path is a typed `AppError`, so `when` over it is exhaustive and the UI is
  *forced* by the compiler to render a specific message.

---

## 5. The face pipeline

This is the part the assignment is really about, and the part most worth reading.

### Engine choice: MediaPipe `FaceLandmarker`, not ML Kit

MediaPipe runs entirely on-device from a model bundled in `assets/`, needs **no Google
Play services**, and emits **478 3-D landmarks**. ML Kit's face API requires Play
services, so it would silently not work on devices without them, and cannot be reasoned
about offline. The model is committed to the repository so the build works with no network
and so the exact model that produced a stored template is pinned in version control.

### Descriptor

`FaceDescriptorFactory` turns 478 landmarks into a comparable vector, removing three
nuisance factors that would otherwise dominate matching:

1. **Translation** — subtract the centroid.
2. **Scale** — divide by RMS radius, so distance from the camera is irrelevant.
3. **In-plane rotation** — build an orthonormal basis from the eye axis and the
   eye-line→nose direction, then express every landmark in it.

Per-landmark **regional weights** then make the iris and the eye/nose/mouth structure
dominate over cheeks and forehead, which change with expression, hair and lighting.

Out-of-plane rotation (yaw/pitch) is deliberately **not** normalised away — it is bounded
by quality gates instead, which refuse to match a badly turned head.

### Enrolment

Five quality-gated samples are captured, **averaged** into one template, and the samples'
*mutual* similarity is recorded. That worst-pair number calibrates the live threshold, so
each staff member gets a threshold derived from their own enrolment rather than a
hardcoded constant. The enrolment screen shows the derived threshold on completion.

### Verification

Per-frame cosine similarity, then:

- at least **3 of the last 5** frames must clear the threshold, **and**
- at least **3 consecutive** frames must clear it.

Consecutive agreement is the cheapest real defence against someone holding a printed
photo to the lens: a still photo produces one plausible frame followed by frames that
fail the motion and inter-frame-stability checks, so it cannot accumulate a run.

**This is not liveness detection.** See §9 for the honest threat model.

### Quality gates

Exactly one face · apparent size in range · roll < 18° · yaw proxy < 0.30 · pitch proxy
0.20–0.68 · eyes open (EAR) · mean luma in band · inter-frame motion below threshold.

Every rejection maps to a specific `QualityIssue`, which becomes specific on-screen
guidance — *"Move a little closer"*, *"Keep your eyes open"*, *"Too dark — find more
light"*. No path in this app degrades to a generic failure.

---

## 6. Version matrix

**Validated by building, not by assumption.** Four constraints here are not discoverable
from documentation and cost real time to find:

| | Version | Note |
|---|---|---|
| Gradle | 9.5.0 | |
| AGP | 9.3.0 | **has built-in Kotlin** — applying `org.jetbrains.kotlin.android` *fails* |
| Kotlin | 2.4.20 | supplied by AGP, not by a plugin |
| KSP | 2.3.12 | **KSP moved to standalone versioning**; `2.4.20-x` does not exist |
| compileSdk / targetSdk | 37 | `core-ktx 1.19.0` *requires* 37 |
| minSdk | 26 | `java.time` is native, so **no desugaring** |
| Compose BOM | 2026.09.00 (material3 1.4.0) | |
| material3-adaptive | `adaptive:1.3.0` | **artifact renamed**; `material3-adaptive` 404s |
| Hilt | 2.60.1 | **`androidx.hilt:hilt-android` was merged into `com.google.dagger`** |
| Room | 2.8.5 | schemas exported for real migrations |
| CameraX | 1.6.2 | `setTargetResolution` is deprecated; `setResolutionSelector` is not |
| MediaPipe tasks-vision | 1.0.0 | |
| Coroutines | 1.11.0 | |

Two API details that contradict the common assumption, found by reading the source:

- **`detectForVideo` is `VIDEO`-mode only.** In `LIVE_STREAM` it throws
  `MediaPipeException(FAILED_PRECONDITION)`. The live camera path uses
  **`detectAsync` + a result listener**, which is non-blocking — necessary because camera
  frames arrive faster than inference completes, and a blocking call would back up the
  preview.
- `currentWindowAdaptiveInfo()` is deprecated; **`currentWindowAdaptiveInfoV2()`** is the
  replacement and also understands the L/XL width classes.

`gradle.properties` pins `-Xmx4096m`; Gradle's 512 MiB default thrashes the GC.

---

## 7. Correctness guarantees

These are enforced by the **database**, not by the UI:

- `UNIQUE(staff_id, local_date, punch_type)` — a double punch is impossible even under a
  race. The repository translates the constraint violation into a typed error.
- `UNIQUE(employee_id)` — two concurrent "add staff" taps cannot collide.
- Foreign keys are explicitly enabled with `PRAGMA foreign_keys=ON`; SQLite defaults them
  **off**, which would make every foreign key in the schema decorative.
- `WAL` journal mode, so the observed attendance list can be read while a punch is written.
- **No `fallbackToDestructiveMigration`.** Wiping someone's attendance on a schema change
  is worse than failing loudly.

`PunchRules` is the single source of truth for the punch sequence. The button label and
the repository are driven by the same object, which is why there is no "already punched in"
error path in the UI. Location is **advisory**: a missing GPS fix records
`LOCATION_UNAVAILABLE` and never blocks attendance, and is never reported as "outside the
geofence", which would be a false accusation.

---

## 8. Security and privacy

- PINs are hashed with **PBKDF2-HMAC-SHA256**, 120 000 iterations, per-staff random salt,
  constant-time comparison. The JDK's own PBKDF2 is used deliberately rather than bcrypt or
  Argon2: no extra dependency or native library on an APK that already ships MediaPipe's
  `.so` files.
- Sign-in is **timing-safe** — a failed lookup still performs a verification against a
  dummy hash, so "no such employee" is not distinguishable from "wrong PIN" by response time.
- The **admin is a seeded database row, not a hardcoded branch**, so both roles are
  authenticated by identical code.
- Face templates and selfies are **excluded from cloud backup and device transfer**
  (`data_extraction_rules.xml`). Biometric data must not silently migrate to a new handset.
- The app makes **no network calls**. See the caveat below — this is a code property, not
  something the OS enforces.
- Crashes are recorded to app-internal storage with **no PINs, descriptors or coordinates**.

### Known caveat: INTERNET permission

I intended to declare no `INTERNET` permission. `aapt2` on the built APK disproves it:

```
uses-permission: android.permission.INTERNET
uses-permission: android.permission.ACCESS_NETWORK_STATE
```

Both arrive **transitively** via `mediapipe → media3-common → datatransport` (Google's CCT
telemetry), not from any code in this repository. Removing them with
`tools:node="remove"` is possible and is the stronger guarantee, but it risks a runtime
`SecurityException` from Play services location that I could not yet verify on a device, so
I left them in place and documented the chain instead. Verify with:

```bash
./gradlew assembleDebug
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

---

## 9. Limitations — stated honestly

1. **Not liveness detection.** Landmark geometry is defeated by a good deepfake or a 3-D
   mask. Consecutive-frame agreement raises the bar for a printed photo; it does not prove a
   live person. A production system needs a trained embedding model (ArcFace/MobileFaceNet),
   an active liveness challenge, and server-side verification.
2. **The similarity threshold needs calibrating on real faces.** The shipped default (0.95
   floor, calibrated from the worst enrolment pair and clamped to 0.90–0.9995) is derived from
   measurements on a synthetic cohort, where same-person scored >0.9999 and different people
   topped out near 0.97. **Real captures must be used to re-tune it** — see
   [`FaceDiscriminationTest`](domain/src/test/kotlin/com/bhanu/attendance/domain/face/FaceDiscriminationTest.kt),
   which is the harness for doing that.
3. **Geometric descriptors are weaker than learned embeddings** for distinguishing lookalikes.
   Acceptable for a single-site verification check; not for high-security identity.
4. **No server.** Everything is on-device by design, so there is no multi-device sync, no
   central revocation, and data is lost if the app is uninstalled. `allowBackup=false` is
   deliberate (see §8) but it does mean a factory reset destroys the attendance record.
5. **Location accuracy is coarse.** `PRIORITY_BALANCED_POWER_ACCURACY` is chosen because a
   200 m geofence does not need metre precision and the high-power radio would be a real
   battery cost. A fix is capped at 6 s and a timeout is not an error.
6. **Single admin, no PIN change flow.** The admin PIN is a seeded constant. There is an
   admin-initiated *staff* PIN reset; an admin self-service PIN change is not built.

---

## 10. Testing

```bash
./gradlew test
```

57 JVM unit tests, no device required:

| Suite | Tests | What it guards |
|---|---|---|
| `FaceDescriptorFactoryTest` | 9 | Invariance to translation, scale, roll; degenerate input |
| `FaceDiscriminationTest` | 5 | **Same-person beats different-person, with margin** |
| `FaceDescriptorTest` | 7 | Normalisation, NaN safety, defensive copies |
| `PunchRulesTest` | 8 | The whole punch-in/punch-out state machine, incl. punch-out off |
| `GeofenceTest` | 8 | Haversine, inside/outside, missing-fix handling |
| `ValidatorsTest` | 13 | PIN/name/employee-ID rules |
| `OutcomeTest` | 6 | Typed errors; `CancellationException` must not be swallowed |
| `DescriptorStorageTest` | 1 | An enrolled face round-trips as bytes and is not stored empty |

The most important of these is `FaceDiscriminationTest`. An earlier version of the test
fixture reported a separation of **0.004 with overlap** — i.e. a face check that would have
accepted almost anyone. That drove four real fixes: the meaningless 0.70 default threshold,
a duplicate-rejection rule that could never be satisfied, a motion gate comparing against the
wrong frame, and a pitch proxy biased by the hairline landmark. Synthetic fixtures do not
replace testing on real faces, but they catch an entire class of "this looks like it works"
bugs in milliseconds.

---

## 11. Accessibility

- Real `contentDescription` on meaningful elements, `null` on decorative ones.
- `mergeDescendants` on logical groups, with an explicit audit for the nested-clickable
  case that silently defeats a merge.
- All interactive targets **≥ 48 dp**.
- `stateDescription` + `role` on anything toggle-like, so TalkBack says "toggle", not
  "double tap to activate".
- Status is never conveyed by colour alone; the label carries the same information.
- `liveRegion` on the enrolment progress and punch button, so progress is announced.
- Layouts survive **3.5× font scale** and small screens — no fixed heights, no
  `maxLines = 1` on user-facing text, scrollable content on the punch screen.
- Every failure message is a specific sentence with a retry action where retrying could work.

---

## 12. Adaptive layout

- **Staff management** is a list-detail layout: two panes at ≥ 600 dp, and on compact
  widths the detail replaces the list with a back gesture returning to it — the documented
  canonical semantics. The split is inset by a vertical hinge so no pane straddles a
  foldable's fold.
- **Attendance history** is a feed: `GridCells.Adaptive(minSize = 280.dp)`, so the column
  count follows the width with no branching in the composable.
- The shared camera session is the same on every form factor; a `PreviewView` inside
  `AndroidView` handles the rendering.

---

## 13. Known issues and next steps

1. **Re-tune `FaceThresholds` on real captures** — the most valuable next step. Use
   `FaceDiscriminationTest` as the harness.
2. `enrolment/staff/{id}?name=` passes the name as a URL-encoded argument; pass only the id
   and read the name from the repository instead.
3. Add Room **migrations with instrumented tests** once schema v2 exists (schemas are
   already exported, so this is ready to go).
4. Add a **baseline profile** — `profileinstaller` is already in the dependency graph via
   Compose, and the startup path is short enough that the gain will be small.
5. The scrim around the face guide leaves a faint rectangular edge where the bounding box
   meets the oval corners. Cosmetic; a `Path`-based even-odd scrim would remove it.
6. `HistoryScope.ALL` is wired but unreachable from the UI — the admin dashboard is the
   intended consumer.

---

## 14. AI conversation export

Required by the assignment: [`docs/AI_CONVERSATION.json`](docs/AI_CONVERSATION.json).

That file also records how the AI-generated code was **reviewed and corrected** before
shipping — including the six defects found by running the app on a real device and the four
found by the discrimination test, none of which a compiler would have caught.
