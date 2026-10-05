# AI Upscaler (Android) + Colab backend

Android (Kotlin/Compose) ⇄ your Google Drive folder "AI Upscaler" ⇄ Colab T4 worker running the original Proteus V3 code.

## Build
1. Android Studio (Koala+), open this folder, let Gradle sync (it generates the wrapper).
2. Google Cloud Console → new project → enable **Google Drive API**.
3. OAuth consent screen → External → add your Google account under **Test users**.
4. Credentials → Create **OAuth client ID → Android**: package `app.upscaler`, SHA-1 from
   `./gradlew signingReport` (debug) or your release keystore. No client ID is placed in code;
   Google matches package + SHA-1.
5. Run on a device with Google Play services. Release APK/AAB: Build ▸ Generate Signed Bundle/APK.

Known: while the OAuth app is in "Testing", Google expires consent after ~7 days -> sign in again.
The `drive` scope is "restricted"; publishing for other users requires Google verification.

## Use
Sign in once -> the app copies the bundled notebook into Drive/AI Upscaler. Tap **Start backend**
(opens it in a Chrome Custom Tab), choose **Runtime > Run all**, tap **Allow**, return to the app.
If Colab's mobile page hides the menu, use Chrome's "Desktop site". Optional: add a Colab Secret
`HF_TOKEN` (your own) if the pip-cache dataset in Cell 1 is private.

## Protocol (Drive: My Drive/AI Upscaler)
backend.json (heartbeat/5s) · jobs/job_<ts>/{input.ext, job.json, status.json, cancel, KZM.FX.*output}

## Test checklist (not yet run by me)
small + large video · 1x/2x · each preset/encoder · minimize · screen off · airplane-mode blip
· close Colab tab mid-job · cancel during upload and during processing · failure (bad file) · output in Movies/AI Upscaler
