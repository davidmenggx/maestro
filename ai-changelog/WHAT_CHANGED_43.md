# Stateless mode dropped

Maestro now has one node mode: stateful. Stateless mode (a trimmed world snapshot sent with every
request, spread by power-of-2-choices) was never built; the mod refused it at startup. It is now
gone from the spec and the config. The reason: the server keeps no copy of the world, so each
snapshot would be read from the live world on the main thread, and a zombie's snapshot covers about
5,600 columns, several times the cells vanilla's own search reads. Offloading would then cost the
main thread about as much as the search it replaces, at about 1000 times the bandwidth of a
request, and a snapshot would not fit one datagram. Stateless mode would only win on recovery time,
elasticity and skewed load; those can be measured on the stateful side instead. No wire, node,
controller or DST change.

## Changes

- **Config.** `MaestroConfig` loses the `mode` setting and the `Mode` enum. Since unknown keys
  abort startup, a `maestro.json` that still has `"mode"` now fails with "`mode` is not a setting"
  until the line is deleted. This was chosen over keeping `mode` as a setting with one allowed value
  or ignoring it silently.
- **Mod.** `MaestroServer` drops the startup check that refused stateless and `mode=` from its
  startup log line.
- **Tests.** `MaestroConfigTest` uses `subMode` where it used `mode` for bad and partial values.
  The unknown-key test now checks that the removed `mode` key is rejected.
- **e2e.** `tools/e2e-test.sh` no longer writes `"mode"`.
- **CLAUDE.md.** The stateless paragraph is removed and the "NODE MODES" section is now "NODE
  STATE".

## Decisions made without explicit instruction

- **Section renamed to "NODE STATE".** With one mode, "NODE MODES" no longer fits. The alternative
  was to keep the old header.
- **`subMode` keeps its name.** With no mode, "sub-mode" is a slight misnomer, but renaming it
  (for example to `dispatch`) would break every config again and touch the Makefile, e2e script
  and DST wording for no behavior change.
- **Local dev config not edited.** `server/run/config/maestro.json` is untracked and still has
  `"mode"`; it is left for the user to fix.

## Verification

- Mod unit tests (59) and `mixinTest` (66) pass. The controller and node have no change.
