# Security policy

## Reporting a vulnerability

Please report security problems **privately**: open the repository's **Security** tab → **Report a vulnerability**
(GitHub private vulnerability reporting). If that button is not available, open an issue titled "Security contact" without
any details and a private channel will be arranged. Include the app version, what an attacker could do and how to
reproduce it.

## Threat model in short

* **ps4debug / ps4debug-NG are unauthenticated debug services.** Anyone who can reach TCP port 744 on the console can
  read and write process memory. Run them only on a trusted local network and never forward the port to the internet.
  The app warns about this and refuses non-local addresses unless the user explicitly allows them.
* The app sends nothing anywhere except to the console address you enter: no telemetry, no accounts, no cloud.
* The event log never contains credentials; it stays on the phone until you export it.
* Release builds are signed with a key kept in GitHub Actions secrets (see
  [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)); test builds are debug-signed and labelled as such. Verify
  downloads with the release's `SHA256SUMS.txt`.
