# Signed releases

Without signing secrets the release workflow publishes a **debug-signed TEST pre-release**
(`PS4-SmartCheat-<version>-build.<n>-TEST-debug.apk`, package `com.ps4smartcheat.app.debug`). To publish a
**signed release APK** (package `com.ps4smartcheat.app`) the repository owner creates a signing key once and stores
it as GitHub Actions secrets. The key never enters the repository.

## 1. Create the key (once, on your computer)

Requires a JDK (`keytool`). Keep the file and both passwords safe: **an app signed with a lost key can never be
updated in place** — users would have to uninstall first.

```sh
keytool -genkeypair -v -keystore smartcheat-release.jks -alias smartcheat \
  -keyalg RSA -keysize 4096 -validity 10000
```

## 2. Store it as repository secrets

Encode the keystore as one line of Base64:

```sh
base64 -w0 smartcheat-release.jks > smartcheat-release.jks.b64     # Linux
base64 -i smartcheat-release.jks -o smartcheat-release.jks.b64     # macOS
```

GitHub → repository → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `SMARTCHEAT_KEYSTORE_BASE64` | content of `smartcheat-release.jks.b64` |
| `SMARTCHEAT_KEYSTORE_PASSWORD` | keystore password |
| `SMARTCHEAT_KEY_ALIAS` | `smartcheat` (the `-alias` above) |
| `SMARTCHEAT_KEY_PASSWORD` | key password (same as the keystore password if you pressed Enter) |

Delete the `.b64` file afterwards.

## 3. Publish

Every successful CI run on `main` publishes `v<versionName>-build.<run>`; with the secrets present it is a signed
release built with `assembleRelease`, otherwise the TEST build. A manual run (Actions → Release → Run workflow,
e.g. tag `v1.0.0`) publishes that tag. The workflow decodes the keystore into the runner's temp directory, builds,
deletes it, writes `SHA256SUMS.txt`, and never overwrites an existing release.

## Installing

* A signed release and a TEST build are different packages and can be installed side by side.
* Updating from one signed release to the next keeps data. Moving from the TEST build to the signed build does not
  carry data over (different package); export the cheat library and address lists first and import them.
* Verify a download: `sha256sum -c SHA256SUMS.txt`.
