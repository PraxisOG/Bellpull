# Third-party notices

Bellpull's own code is MIT licensed; see [LICENSE](../LICENSE).

Hermes Agent code copied or adapted in `server-patches/`, `server-host/`, and
`plugins/bellpull/` retains its Nous Research MIT notice in each bundle's
`LICENSE.hermes-agent`. Moving that code into the plugin does not relicense it.

Android uses CommonMark Java 0.30.1 and its GFM tables extension, licensed under
BSD-2-Clause by Robin Stocker. The full notice is in `LICENSE.commonmark-java`
and packaged with the APK under `assets/licenses/` alongside Bellpull's MIT terms.
Parser source: https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.30.1

Existing Android dependencies retain their own licenses: AndroidX/Compose,
Kotlin/kotlinx serialization, and OkHttp use Apache-2.0. Test/build dependencies
are development tooling and are not shipped as application features.
