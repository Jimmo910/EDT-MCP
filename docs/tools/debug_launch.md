# debug_launch

Start an EDT debug session: either an existing config by launchConfigurationName (runtime client OR Attach, the latter needed to debug server-side code), or a runtime-client config matched by projectName + applicationId. If that config is already running it short-circuits with alreadyRunning:true (terminate_launch first to force a restart). Full parameters and examples: call get_tool_guide('debug_launch').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | — | string | EDT project name; required unless launchConfigurationName is given. |
| applicationId | — | string | Application ID from get_applications; required in the projectName+applicationId mode. |
| launchConfigurationName | — | string | Exact name of an EDT debug launch config (runtime client or Attach); skips projectName/applicationId. |
| updateBeforeLaunch | — | boolean | Default true: silently apply the configuration->DB update before launching so no 'Update database?' modal blocks the call (even on a Russian-locale EDT the dialog is auto-confirmed); false skips the update and the platform may then show that modal. Ignored for Attach. |

## Guide
Starts an EDT debug session for a 1C application. There are two ways to pick what to launch, plus an idempotency guard that prevents a second client over a session that is already alive.

## When to use

Use this to bring up a debuggable 1C session before setting breakpoints and stepping. For client-side code, launch a runtime-client config (spawns 1cv8c). For SERVER-side code (HTTP services, background jobs, scheduled jobs) you must use an 'Attach to 1C:Enterprise Debug Server' config — a runtime-client launch cannot hit those breakpoints. After it returns, use `debug_status` to inspect, `wait_for_break` to block until a breakpoint is hit, and `terminate_launch` to stop.

## Modes (choose ONE)

1. **launchConfigurationName** — start an existing EDT launch configuration by its EXACT name. Works for both runtime-client configs (spawns 1cv8c) AND Attach configs (attaches to ragent/rphost for server-side code). Does NOT require applicationId. This is the only mode that can start an Attach session.
2. **projectName + applicationId** — searches the runtime-client configs of that project for a match and launches it. Runtime-client only; cannot reach an Attach config. Get the applicationId from `get_applications`.

## Parameter details

- **launchConfigurationName** (string) — exact config name; if set, projectName and applicationId are ignored. Use `list_configurations` to find the name.
- **projectName** (string) — EDT project name; required when launchConfigurationName is absent.
- **applicationId** (string) — from `get_applications`; required in the projectName+applicationId mode.
- **updateBeforeLaunch** (boolean, default true) — silently apply the configuration->DB update before launching so the EDT launch delegate finds the infobase already UPDATED and shows no 'Update database?' modal. Ignored for Attach configs (nothing to update). The update analysis is shared with the YAXUnit tools: skip when already UPDATED, wait when BEING_UPDATED, otherwise incremental-update. A config without a persisted application binding (tracked under a synthetic `launch:<configName>` id) skips this programmatic update — there is no resolvable application to update — and relies on the auto-confirmer safeguard alone. As a belt-and-suspenders safeguard, the actual `config.launch(...)` is wrapped in an auto-confirmer that programmatically presses 'Update then run' if the delegate's modal still appears — in either EDT locale (English 'Application update' / Russian 'Обновление приложения'), so an unattended Russian stand never hangs on it. With `updateBeforeLaunch=false` the update is skipped and the platform may then show that modal.

## Already-running guard

If a launch of the same configuration/application is still alive, the tool short-circuits with `alreadyRunning: true` and a `mode` field, and does NOT spawn a fresh client. This also covers a launch started in RUN mode (no debug target): the tool still detects it and refuses to start a second client over it. To force a clean restart (e.g. after code changes that require a new session), call `terminate_launch` first, then `debug_launch` again.

## Examples

- Runtime client by name: `launchConfigurationName="MyApp / ThinClient"`.
- Attach to debug server-side code: `launchConfigurationName="Attach to 1C:Enterprise Debug Server"`.
- Runtime client by project + app: `projectName="MyProject"`, `applicationId="<id from get_applications>"`.
- Skip the DB update: add `updateBeforeLaunch=false`.

## Notes

- Returns JSON. On a fresh launch: `launchConfiguration`, `configurationType`, `attach`, `mode`, `status: "launching"`, `project`/`applicationId` (when known), and a `message`. The `alreadyRunning: true` short-circuit returns the same identity fields but no `status` (nothing was launched).
- The launch is ASYNCHRONOUS and non-blocking: the tool dispatches `config.launch(DEBUG_MODE, null)` onto the EDT UI thread and returns `status: "launching"` immediately, WITHOUT waiting for the 1C client to finish starting (it may show login / database-update dialogs). Poll `debug_status` until the session appears running, then use `wait_for_break`. Because the launch runs after the call returns, a launch failure is NOT reported in this response — it is written to the EDT error log instead.
- On a not-found config the error payload includes `availableConfigurations` (every debug-capable config: runtime client + attach), so you can pick a valid name.
- The launch goes through a direct `config.launch(DEBUG_MODE, null)` to avoid modal EDT dialogs that would block the MCP worker thread. While that call runs, an auto-confirmer (`LaunchUpdateDialogAutoConfirmer`) is armed to dismiss the launch delegate's 'Application update' modal non-interactively; it runs on the EDT UI thread (the modal's own nested event loop dispatches the button press), and because the MCP worker has already returned `status: "launching"`, the server is never blocked on the dialog.

## Gotchas

- Attach is reachable ONLY via launchConfigurationName; projectName+applicationId never starts an Attach session.
- `alreadyRunning: true` is a success, not an error — don't retry it; terminate first if you truly need a fresh session.
- `updateBeforeLaunch` has no effect on Attach configs.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
