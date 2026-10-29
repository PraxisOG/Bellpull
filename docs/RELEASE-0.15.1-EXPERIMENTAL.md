# Bellpull 0.15.1 experimental

Fixes two settings controls that appeared to do nothing in build43. Android
**0.15.1-experimental / build44** uses the unchanged **0.7.0-integration-preview**
plugin. No server plugin update is required for these repairs.

## Reply notifications

The UI allowed enabling notifications when either push-relay events or planning
were supported, but its handler accepted only relay support. Stock Hermes with
planning and no relay therefore immediately changed the switch back to off.
The display and handler now share the same availability check. The existing
foreground connection service uses its one-minute polling path when no relay is
available; the settings text describes this behavior. The entire row is tappable
and exposes one accessible switch.

Denied or blocked Android notification permission now leaves an inline reason
and an Android notification settings button. After allowing notifications there,
turn on Reply notifications again. Permission-result ownership and the displayed
problem survive activity recreation. Failed service startup also leaves an inline
reason instead of silently reverting.

## Choose default assistant

Bellpull requested `ROLE_ASSISTANT` after checking that the role existed. Android
can expose that role while marking it non-requestable, so the request activity
closed without showing a picker. The button now opens the public assistant
settings page, with a Default apps fallback when a device cannot open that page.
Selection remains an explicit action in Android settings.

Platform evidence: [Android role policy](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml)
and [public voice-input settings action](https://developer.android.com/reference/android/provider/Settings#ACTION_VOICE_INPUT_SETTINGS).

## Validation and installation

Both failures were reproduced on the physical phone using the unmodified build43
behavior in a disposable package and a loopback stock Hermes fixture. All three
physical regression checks then passed: both controls, settings persistence/service
start-stop, and denied permission → Android settings → permission recovery.

763 Android checks passed with one existing opt-in skip. Screenshot verification,
lint and release assembly passed. The notification-settings screenshot baseline
was reviewed and updated for the added Android-settings button. Five new JVM
checks cover accessible toggling, permission feedback and settings-intent fallbacks.
Plugin runtime is unchanged; no plugin retest or deployment was needed.

Results and installation evidence are recorded in
[evidence/settings-actions-20261029](evidence/settings-actions-20261029/).
The tests do not change the default assistant or contact personal Hermes servers.

[Download experimental app and matching plugin](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.15.1-experimental.1).

Installed in place with UID10308 and the existing data directory preserved.
Installed APK SHA-256: `be34c4972312ff24b3d4d15aa121dc0af2bccf4f654892e2f94ce609d8d97601`.
