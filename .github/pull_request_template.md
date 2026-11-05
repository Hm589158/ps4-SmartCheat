## What and why

<!-- Short description of the change and the problem it solves. -->

## Checks

- [ ] `./gradlew :core:test` passes locally
- [ ] Protocol changes are verified in upstream source and documented in `docs/PROTOCOL.md`
- [ ] Safety rules kept: read-only default, every write confirmed and verified, no automatic re-application
- [ ] `CHANGELOG.md` updated
- [ ] Hardware results claimed only if actually observed (otherwise: mock-tested)
