# Bundled assets

## `face_landmarker.task`

The MediaPipe Face Landmarker model, bundled **in the repository on purpose**.

* Source: <https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task>
* Size: ~3.6 MB
* Contains the BlazeFace short-range detector, FaceMesh-V2 (478 landmarks) and the
  blendshape model in a single bundle.

It is committed rather than downloaded at build time so that:

1. `./gradlew assembleDebug` works with no network access, matching the app's own
   offline-first design.
2. The exact model that produced the enrolled templates is pinned in version control. A
   silently-upgraded model would change the descriptor geometry and invalidate every stored
   face template.
3. Nothing is fetched from Google at runtime, so the app needs no Play services.

Swapping the model requires bumping `EnrolmentSession.MODEL_VERSION` so existing templates are
detected as stale and re-enrolled rather than silently mis-matched.
