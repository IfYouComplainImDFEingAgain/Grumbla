# Third-party notices

Grumbla's own code is MIT-licensed (see [`LICENSE`](LICENSE)). It includes or builds against the
following third-party components, each under its own license:

| Component | Where | License |
|---|---|---|
| Mumble protocol definitions (`Mumble.proto`, `MumbleUDP.proto`) | `core-protocol/src/main/proto/` (committed) | BSD-3-Clause — © The Mumble Developers, <https://www.mumble.info/LICENSE> |
| libopus | `core-audio/src/main/cpp/opus/` (fetched, not committed) | BSD-3-Clause — © Xiph.Org Foundation and contributors, <https://opus-codec.org/license/> |
| RNNoise | `core-audio/src/main/cpp/rnnoise/` (fetched, not committed) | BSD-3-Clause — © Xiph.Org Foundation, Jean-Marc Valin and contributors, <https://github.com/xiph/rnnoise/blob/main/COPYING> |
| Gradle wrapper | `gradle/wrapper/` | Apache-2.0 |

Binary distributions (APKs) statically link libopus and RNNoise and must carry their copyright
notices and license text. Library dependencies resolved through Gradle (AndroidX, Jetpack Compose,
Hilt, Room, Square Wire, BouncyCastle, Kotlin coroutines) are distributed under their own licenses
(Apache-2.0 / MIT-style).

"Mumble" is the name of the open-source voice chat project at <https://www.mumble.info/>. Grumbla is
an independent client and is not affiliated with or endorsed by the Mumble project.
