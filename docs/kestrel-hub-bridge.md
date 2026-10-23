# Kestrel Hub and Vigil Bridge

Kestrel's cross-device connection reuses the existing nanoMuse Cloud account endpoints, `/v1/devices` discovery, and versioned `WS /v1/hub` frames. There is no new pairing or task protocol. The account is optional: provider credentials, chat history, sessions, Linux sandbox access, Skills, and MCP stay on their own devices unless a person explicitly uses an existing feature that sends data elsewhere.

## Source audit

| Cloud switch or setting | Hub effect | P4 recommendation |
|---|---|---|
| `cloud_service` | Gates account/auth endpoints and closes every live Hub socket. | Keep enabled. It cannot be disabled while using account auth or Hub. |
| `hub` runtime control | Gates `/v1/hub` and `/v1/devices`. | Keep enabled for device discovery. |
| `HUB_ENABLED` | Omits the Hub WebSocket route and also disables the `/app` console. | Leave enabled. |
| `sync` | Gates `/v1/sync/*`; Hub presence, calls, and device listing remain available. | Safe to disable when cross-device conversation synchronization is not wanted. |
| `free_allowance` | Gates metered model calls, not sign-in or Hub. | Safe to disable for a relay that only provides account identity and device routing. |
| `signups` | Blocks account creation and code requests for new identifiers; existing accounts keep signing in. | Leave enabled while the owner creates accounts, then it may be disabled if no more accounts are needed. |
| `UPSTREAM_KEY` | Supplies Cloud-hosted model requests. It is not used by Hub. | Leave unset for this deployment. Kestrel model provider keys never need to reach the Relay. |

The Relay is still a general-purpose service when `cloud_service` is on: its auth, profile, account, model, media, and administration routes remain in the application. No Hub-only API allowlist exists today. With `UPSTREAM_KEY` unset, hosted model calls fail closed, but the deployment should still use the existing admin controls, strong `CLOUD_SECRET`, firewall, and log review. The Bridge does not expose Vigil's HTTP API. It initiates an outbound TLS WebSocket and responds only to the existing `info` action. All other remote actions are refused by the Bridge.

Hub credentials are per installation and account-scoped. Android obtains a separate phone credential using the existing email-code endpoint with `device_kind=phone` and its persistent installation ID. Vigil does the same with `device_kind=computer`; its token is stored encrypted with Windows DPAPI. The Relay sees account identifiers and device metadata needed for sign-in and discovery. It does not receive model API keys, conversation history, or profile sync from this Hub-only Android flow.

## Seoul deployment preparation

No server or DNS deployment has been performed. `relay.palimpsestai.xyz` is a planned hostname, not a verified live endpoint.

1. Point the domain's DNS A/AAAA record at the Ubuntu host and confirm public reachability before starting Caddy.
2. On an Ubuntu host, use the repository's single-process Docker Compose deployment in `cloud/`. Set `CLOUD_DOMAIN=relay.palimpsestai.xyz`, `PUBLIC_BASE=https://relay.palimpsestai.xyz`, a randomly generated `CLOUD_SECRET`, and an operator-controlled `ADMIN_TOKEN` in an untracked `.env` file with mode `0600`.
3. Leave `HUB_ENABLED=1`; set the persistent runtime controls `cloud_service=on`, `hub=on`, and `sync=off`. Leave `UPSTREAM_KEY` empty and turn `free_allowance` off. Keep sign-ups on only until the intended accounts are created.
4. Publish only TCP 80 and 443 for Caddy/ACME. Keep SSH limited to operator IPs; do not publish the Relay's 8787 container port or an admin API port.
5. Check `https://relay.palimpsestai.xyz/healthz`, then verify code sign-in, `/v1/devices`, WebSocket reconnect, `ping/pong`, and read-only `info` with two test accounts/devices before sharing the service.
6. Keep `cloud/data/` and Caddy's named volumes persistent. Back up the SQLite data directory and Compose/.env separately (encrypt the secret backup); validate a restore on a separate host before upgrades. Roll back by restoring the previous image/repository revision and its matching database backup.

The runtime controls are stored in SQLite and can be changed in the existing admin console. A P4 release should add a Hub-only endpoint allowlist before exposing a long-lived Relay to the public Internet; this PR documents the current boundaries without changing the upstream Relay API.

## Use

- Android: open **Devices** from the chat drawer, enter the Relay URL, request an email code, and sign in. This only joins the Hub; it does not add a Cloud model provider.
- Windows Vigil: install the updated Brain requirements, then run `python brain/kestrel_hub_bridge.py login` from the repository root. Use the same account email and the same Relay. Restart Vigil Brain. `python brain/kestrel_hub_bridge.py status` checks local configuration; `logout` revokes the session when reachable and removes the local DPAPI credential.
- The Android Devices list shows connected and offline account devices. A Reach `info` request returns the computer's name, OS, and Vigil version. The Bridge advertises only `info` and refuses shell or any other call.

The Bridge uses `websocket-client` and the existing Brain process lifecycle. It is inert when no local Relay credential exists. It does not change Vigil's Brain, tool permissions, local HTTP API, or agent execution protocol.
