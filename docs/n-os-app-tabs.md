# N-OS app tabs

The side rail: **Desktop**, **Terminal**, **Network**, **Status**, Apps (only as the home launcher),
**Setup** and **Updates**. The gaming tabs (Steam, Games, Store, Components) are no longer in the rail.

## Terminal (`TerminalActivity`, `TerminalService`)
- Ubuntu shells without the desktop, in tabs. `+` opens a shell, `+ #` a fake-root shell
  (`proot -0`) for apt and dpkg.
- Shells live in `TerminalSessions` and a foreground service (`TerminalService`, notification
  "N shells running" with Stop all), so they keep running after leaving the terminal screen. The ×
  on a tab ends that shell; the session reaper (`OrphanReaper`) leaves tracked shells alone.
- The command comes from `GuestCommand.shell`: the same proot prefix, binds and environment as the
  app's other guest commands, `TERM=xterm-256color`, `bash -l`. Fake root keeps the fast path off.
- Fake root and apt were checked on an arm64 GitHub runner with the app's patched proot, the same
  flags (`--kill-on-exit --kernel-release=… -0`), and the session preloads (`libblsession`,
  `libblfastpath`): `apt-get install` works with and without seccomp acceleration.

## Network (`NetworkPage`)
- VPN: `openconnect --script-tun --script 'ocproxy -D 11080 -k 30'`, so the tunnel is a SOCKS5
  proxy on 127.0.0.1:11080 and needs no tun device or root. The page shows it as connected while the
  port answers. Protocols: AnyConnect, GlobalProtect, Fortinet, Pulse, Juniper NC.
- Quick checks open in the terminal: ping, traceroute, mtr, dig, nmap, interfaces, public address.
- SSH targets are saved in the app and written to `~/.ssh/config.d/n-os.conf` (included from
  `~/.ssh/config`) as `Host nos-<name>`, with `ProxyJump` for a jump host and
  `ProxyCommand nc -X 5 -x 127.0.0.1:11080 %h %p` for targets marked VPN.

## Status (`StatusPage`)
- CPU, resident memory and programs of the Linux side, read from `/proc` for the app's own uid
  every two seconds; device memory from `ActivityManager`; the Linux system's size from a walk of the
  rootfs.
- Stop desktop, close shells, or stop everything (both, then the leftover-process reaper).
- Desktop drawing: CPU (default) or GPU. GPU writes `~/.config/nos-desktop-gpu` = `on`;
  `droiddeck-desktop` then runs KWin on Zink (`KWIN_COMPOSE=O2`). If Plasma exits within 20 s it
  writes `~/.config/nos-desktop-gpu-failed` and starts once more on the CPU.

## Runtime ubuntu-r5
Adds Claude Code (native build, `/usr/local/bin/claude`), openconnect, ocproxy, WireGuard tools,
OpenVPN, lsof, rsync, zip and ripgrep to r4.

## Signed releases on GitHub (`.github/workflows/release-signed.yml`)
Run it with a finished "Build APK" run id and a tag; it signs with the repository secrets
`NOS_KEYSTORE_B64` (the keystore, base64) and `NOS_KEYSTORE_PASSWORD`, checks the certificate
fingerprint (`5f8135…2338`) and publishes a pre-release. The secrets are added by hand in the
repository settings (Settings → Secrets and variables → Actions).
