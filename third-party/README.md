# Third-party notices

Bellpull's own code is MIT licensed; see [LICENSE](../LICENSE).

Hermes Agent code copied or adapted in `server-patches/`, `server-host/`, and
`plugins/bellpull/` retains its Nous Research MIT notice in each bundle's
`LICENSE.hermes-agent`. Moving that code into the plugin does not relicense it.

Android uses CommonMark Java 0.30.1 and its GFM tables extension, licensed under
BSD-2-Clause by Robin Stocker. The full notice is in `LICENSE.commonmark-java`
and packaged with the APK under `assets/licenses/` alongside Bellpull's MIT terms.
Parser source: https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.30.1

Android packages the user-approved Moonshine Voice CPU SDK 0.1.5
(`ai.moonshine:moonshine-voice:0.1.5`). Its source code and the selected English
streaming models use MIT terms. The complete upstream license, including exceptions
for other models, is preserved in `LICENSE.moonshine-voice`. Upstream native
third-party notices are copied in `NOTICES.moonshine-voice`; including a notice does
not imply that Bellpull uses every upstream component. Both files are also packaged
in the APK under `assets/licenses/Moonshine-Voice-*`. Unused Google Material and
SDK WorkManager dependencies are excluded.
SDK source: https://github.com/moonshine-ai/moonshine/tree/v0.1.5

Existing Android dependencies retain their own licenses: AndroidX/Compose,
Kotlin/kotlinx serialization, and OkHttp use Apache-2.0. Test/build dependencies
are development tooling and are not shipped as application features.
