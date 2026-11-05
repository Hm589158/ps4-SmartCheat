# Security policy

## Reporting a vulnerability

Please report security problems **privately** through
[GitHub security advisories](https://github.com/Hm589158/ps4-SmartCheat/security/advisories/new) instead of a public
issue. Include the app version, what an attacker could do and how to reproduce it.

## Threat model in short

* **ps4debug / ps4debug-NG are unauthenticated debug services.** Anyone who can reach TCP port 744 on the console can
  read and write process memory. Run them only on a trusted local network and never forward the port to the internet.
  The app warns about this and refuses non-local addresses unless the user explicitly allows them.
* The app sends nothing anywhere except to the console address you enter: no telemetry, no accounts, no cloud.
* The event log never contains credentials; it stays on the phone until you export it.
* Release builds are signed with a key kept in GitHub Actions secrets (see
  [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)); test builds are debug-signed and labelled as such. Verify
  downloads with the release's `SHA256SUMS.txt`.
