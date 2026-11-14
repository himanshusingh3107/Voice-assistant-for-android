# Campus Hybrid Agent for Android

A native Kotlin/Jetpack Compose sample that compares **Baseline A** (every request goes to Firebase AI Logic) with a signal-driven hybrid router. Routine campus queries use local tools, sensitive requests stay on-device or are blocked, and general requests may use ML Kit Prompt or Firebase AI Logic.

## Run the Python web app

There is also a dependency-free Python version of the demo. It runs locally in a browser and reproduces the hybrid-versus-Baseline-A controls and session metrics without Android Studio.

1. Install Python 3.11 or newer.
2. From this folder, run `python app.py` in PowerShell (or `py app.py`).
3. Open `http://127.0.0.1:8000`.
4. Try the sample timetable, notes, campus-services, and private-request chips. Compare **Hybrid agent** and **Baseline A**; use **Reset session** to clear the comparison.

Without setup, cloud requests use a visibly labeled **cloud simulation** with adjustable artificial latency; it does not call an LLM. To use live Gemini, create a **new API key** in Google AI Studio, copy `.env.example` to `.env`, then set the new `GEMINI_API_KEY` in `.env`. Revoke any key shared in a chat or other public place. The `.env` file is git-ignored; the key stays on the server and is sent only to Google's API in the `x-goog-api-key` request header, never to the browser. Set `GEMINI_MODEL=gemini-3.5-flash-lite` to select the requested model (this is the default). Restart `python app.py` after editing `.env`. Live requests use Gemini Developer API and incur normal API usage/costs. You can also set `GEMINI_API_KEY` and `GEMINI_MODEL` in the process environment instead of using `.env`.

Optional: install [Ollama](https://ollama.com/) locally, run `ollama pull llama3.2`, and start the Ollama service. Sensitive requests and selected local-model routes then use that local model. If Ollama is unavailable, the app blocks private model requests rather than sending them to cloud. The local timetable, notes and campus directory are example data. Do not enter real student records in the demo; the privacy detector uses a keyword heuristic.

This is a local web demo, not an Android APK. The Android version and setup are described below.

## What is implemented

- **Baseline A control:** bypasses the router and sends every query to the Firebase AI Logic cloud model.
- **Hybrid routing:** detects timetable, notes, and campus-services queries and handles them locally. Private requests are never cloud-fallback candidates. Cost and latency preferences can select ML Kit on-device inference; battery saver favors cloud for non-private general requests.
- **Android AppFunctions:** exposes `getTimetable`, `searchStudentNotes`, and `getCampusServices` through an Android 16+ AppFunction service.
- **Session comparison:** counts cloud/local requests, private requests sent to cloud, measured route latency, and cloud calls avoided. Metrics are in-memory only; the app sends no analytics.
- **Voice assistant (gTTS & Speech Input):** spoken responses using Google Text-to-Speech (`gTTS`), audio playback endpoint (`/api/tts`), message speech buttons, and microphone voice input via the browser's Web Speech API. Also supports interactive CLI voice output.
- **Explicit failures:** unavailable local models, missing Firebase setup, and inference errors are shown in the conversation instead of silently switching routes.

The local timetable, notes, and campus directory are example data and must be replaced with authorized app data before deployment. The privacy detector is a small keyword heuristic, not a guarantee: use **Mark this request private** for sensitive content, and do not enter real student records in this demo. Battery saver is read from Android's power/battery services. Latency and cost are user-selectable optimization signals.

## Run the Android app

1. Open this directory in a recent Android Studio installation with JDK 17 and Android SDK 36.
2. Connect a Firebase Android app with application ID `com.example.campusagent` to Firebase AI Logic, and place the downloaded `google-services.json` in `app/`. The Google Services Gradle plugin is applied only when that file exists, so the project can still build without cloud setup; cloud requests then show an actionable configuration error.
3. Configure and enforce Firebase App Check in the Firebase console, following the [Firebase AI Logic Android setup](https://firebase.google.com/docs/ai-logic/get-started?api=dev). Register an appropriate debug or production attestation provider. Do not ship a debug provider in a release.
4. Run the app on a compatible Android device. ML Kit Prompt API is beta and requires a supported device/model; a selected local route reports errors rather than sending private requests to cloud when the model is unavailable.

The AndroidX AppFunctions compiler alpha currently declares newer AAR metadata than this sample's Android Gradle Plugin. The targeted metadata-check task is disabled, following the [official ML Kit GenAI sample](https://github.com/googlesamples/mlkit/tree/master/android/genai); revisit this workaround when upgrading Android Gradle Plugin and AppFunctions.

## Validation

`app/src/test/java/com/example/campusagent/RoutingPolicyTest.kt` covers the cloud-only control, local tool routing, privacy blocking, latency/cost choices, and battery-saver precedence. Run it from Android Studio after installing the Android build prerequisites.

`tests/test_campus_agent.py` and `tests/test_voice.py` cover the Python router, privacy behavior, local tools, voice assistant, and cloud-only control. Run them with `python -m unittest discover -s tests -v`.
