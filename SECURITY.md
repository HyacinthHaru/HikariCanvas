# Security Policy

## Supported Versions

HikariCanvas is pre-1.0 software. Security fixes land on the latest released
version only. There is no back-porting to older snapshots before 1.0.

| Version | Supported |
|---------|-----------|
| Latest `0.9.x` release | ✅ |
| Older pre-releases | ❌ |

## Reporting a Vulnerability

**Please do not open public GitHub issues for security vulnerabilities.**

Report privately through **GitHub Security Advisories**:
[Report a vulnerability](https://github.com/HyacinthHaru/HikariCanvas/security/advisories/new)

Please include:

- A description of the issue and its impact.
- Steps to reproduce (a minimal proof of concept if possible).
- The plugin version, Paper version, and Java version you tested on.

### Response targets

- **Acknowledgement:** within 5 days of your report.
- **Triage and severity assessment:** within 10 days.
- **Fix or mitigation plan:** communicated once triage completes.

### Disclosure

We follow coordinated disclosure. Details of a vulnerability are kept private
until a fix is released, and are made public **7 days after** the fixed
release ships. Security-relevant releases are tagged `[SECURITY]` in their
release notes.

## Scope and Boundaries

HikariCanvas defends the web editor's session/auth layer, input validation,
file import paths, and the scripting runtime. The following are **explicitly
out of scope** and are the responsibility of the server operator / hosting
environment:

- **Transport encryption.** The plugin does not ship TLS. The web server binds
  to `127.0.0.1` by default. **Any public deployment must sit behind a reverse
  proxy (nginx / Caddy) that terminates TLS.** Running the editor on a public
  interface without TLS is unsafe and unsupported for production.
- **Reverse proxy and firewall configuration.** Misconfigured proxies, open
  firewall rules, and real-client-IP forwarding are the operator's
  responsibility.
- **SSRF on URL image import.** The `POST /api/upload/url` endpoint does **not**
  guarantee SSRF protection. Operators exposing it must block the plugin
  process from reaching internal network ranges at the proxy / firewall layer,
  or disable URL upload entirely (withhold the `canvas.upload` permission).
- Minecraft protocol-level attacks, OS security, and player account takeover.

For the full threat model, see `docs/security.md` in the repository.
