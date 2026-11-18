# opentunnel-native

JNI wrapper around the Rust [`opentunnel`](https://crates.io/crates/opentunnel) client,
packaged into the app as `libopentunnel_native.so` for **arm64-v8a** and
**armeabi-v7a**.

The wrapper creates or resumes a tunnel identity, connects it to the local web
server, and exposes a small polling status surface to Kotlin. It is built by
the `buildOpenTunnelNative` Gradle task using the Android NDK's clang linker
for each ABI:

```sh
./gradlew :app:assembleDebug
```

The task writes the libraries to `app/build/rustJniLibs/<abi>`, which the app's
`jniLibs` source set merges into the APK. The Docker builder installs both the
NDK and the Rust `aarch64-linux-android` and `armv7-linux-androideabi` targets.

Tunnel files are kept in the app's private files directory, mirroring the
OpenTunnel CLI's `tunnel-data` layout:

```text
files/opentunnel/config/opentunnel/default.toml
files/opentunnel/data/opentunnel/default/{tunnel.json,token,certificate.pem,chain.pem,private-key.pem}
files/opentunnel/state/opentunnel/default/daemon.log
```
