# Architecture

## Module graph

```
   :app ──────────► :core:designsystem ──► :data ──► :domain
     │                    │                                 ▲
     ├──► :core:common ───┘                                 │
     ├──► :feature:auth ──────────────────────���─────────────┘
     ├──► :feature:admin
     └──► :feature:staff
```

Dependencies point right only. `:domain` and `:core:common` have no project dependencies
at all; `:domain` is a plain Kotlin/JVM module with no Android dependency, which is enforced
by the module type rather than by convention.

## Layers

### `:domain` — rules, with no knowledge of Android

- `model/` — `Staff`, `AttendanceRecord`, `FaceTemplate`, `GeoLocation`, `Geofence`, `Session`, `AuditEvent`
- `face/` — the entire recognition pipeline as pure maths: `FaceDescriptorFactory`, `FacePoseEstimator`, `FaceQualityEvaluator`, `FaceMatcher`, `EnrolmentSession`, `VerificationSession`, `FaceThresholds`
- `geo/` — `Haversine`, `GeofenceEvaluator`
- `usecase/` — `SignInUseCase`, `AddStaffMemberUseCase`, `MarkPunchUseCase`, `PunchRules`, observers
- `repository/` — interfaces only
- `outcome/` — `Outcome<T>` and the closed `AppError` set
- `validation/` — `Validators`

Being Android-free is what lets 56 unit tests run in milliseconds with no emulator, and it
is why the interesting logic is testable at all.

### `:core:common` — defensive plumbing

`AppContextHolder`, `Context.isDebuggable`, `AppDispatchers`, `AppLogger`/`LogcatLogger`,
and the crash reporter (`CrashHandler`, `FileCrashLogStore`, `CrashReport`).

### `:core:designsystem` — theme, components, camera

M3 colour/type/shape, `StatusColors` for attendance states, accessibility helpers
(`minimumTouchTarget`, `semanticHeading`, `describeAs`, `MergedSemantics`), shared components
(`ErrorCard`, `StatusChip`, `EmptyState`, `LoadingState`, `FaceGuideOverlay`, `CaptureHint`),
the adaptive navigation wrapper, and `FaceCameraSession` — which lives here rather than being
duplicated in the two feature modules that need it.

### `:data` — implementations

Room (`local/`), DataStore-backed `SessionStore`, `SettingsRepositoryImpl`, `Pbkdf2PinHasher`,
`MediaPipeFaceEngine`, `PlayServicesLocationProvider`, `FileSelfieStorage`, all repository
implementations, mappers, `DatabaseSeeder`, and the Hilt modules.

### `:feature:*` — UI

Each feature is a separate Gradle module. Inside a feature, screens, ViewModels, UI state
and leaf composables are separate files; no feature has a god file.

### `:app` — composition root

`AttendanceApplication` (context install → crash handler → seeding), `MainActivity`,
`AppNavHost`, `Destinations`, `SessionViewModel`. **No business logic lives here.**

## State management

- One immutable `StateFlow<XUiState>` per screen, with derived values as computed properties
  rather than duplicated state.
- One-shot effects go through a `Channel` (`receiveAsFlow`), not state, so they are not
  replayed on rotation.
- Repository `Flow`s are the source of truth; screens hold no shadow copy, so a change made
  elsewhere appears without a manual refresh.
- `combine` + `flatMapLatest` on the session means switching accounts cancels the previous
  person's queries rather than racing two streams into one state.

## Navigation

A single `NavHost` in `:app` with typed route builders, so a renamed argument is a compile
error rather than a runtime "route not found". Sign-in pops the login destination so the
back gesture cannot return to it.

## Data flow, end to end

```
CameraX frame
  → decimate to ≤720px                    (drop frames, never queue)
  → mean luma                              (sparse sample; lighting gate only)
  → MediaPipe detectAsync (LIVE_STREAM)    (non-blocking, results on its own thread)
  → FaceObservation (478 domain landmarks)
  → FaceQualityEvaluator                   (pose, size, eyes, luma, motion → QualityIssue)
  → FaceDescriptorFactory                  (translation/scale/rotation removed, weighted)
  → EnrolmentSession | VerificationSession
  → FaceTemplate (Room)  /  MarkPunchUseCase
       → SelfieStorage (app-internal JPEG)
       → LocationProvider (advisory, 6 s cap)
       → GeofenceEvaluator
       → AttendanceRepository.record  (unique index is the real guarantee)
       → AuditRepository.log
```

## Threading

| Work | Where |
|---|---|
| Camera frames | Dedicated single-thread executor from `FaceCameraSession` |
| Face inference | `Dispatchers.Default.limitedParallelism(1)` — the graph is not thread-safe |
| Database and files | `Dispatchers.IO` via injected `AppDispatchers` |
| UI state | `Dispatchers.Main` |

No disk or database work runs on the main thread. `STRATEGY_KEEP_ONLY_LATEST` plus an
`AtomicBoolean` means at most one frame is in flight; a slow device drops frames instead of
growing an unbounded queue.

## Testing

Unit tests cover the domain exhaustively. Instrumented tests are scaffolded for Room and
Compose UI. Anything that can be pure is kept pure specifically so the important logic is
testable without a device.
