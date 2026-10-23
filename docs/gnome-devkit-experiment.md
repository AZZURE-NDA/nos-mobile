# GNOME 50 inside the app compositor (devkit route)

Tested 2026-10-23 on x86_64 with an Ubuntu 26.04 (resolute) chroot: gnome-shell 50.1, mutter 50.1.
Weston `--backend=headless` stood in for the app's compositor. Not yet tried on the Pad (arm64, proot).

## Result
GNOME Shell 50 runs and its picture shows up as an ordinary window on the host compositor.
It renders in software ("surfaceless renderer without GPU") and needs no logind, no GDM and no DRM.

## What it takes
- Launch: `gnome-shell --devkit --virtual-monitor 1280x800 --no-x11`
  with `WAYLAND_DISPLAY` pointing at the host compositor.
  - Not `--headless --devkit`: with `--headless` the viewer is never started.
  - GNOME 50 has no `--nested`; `--devkit` replaces it.
- Mutter starts `/usr/libexec/mutter-devkit` itself. It comes from **`mutter-dev-bin`** (not pulled
  in by `gnome-shell`). It is a GTK4/libadwaita Wayland client on the host display.
- **PipeWire + WirePlumber must run first.** The viewer receives the desktop as a PipeWire screencast
  stream; without it the viewer logs "Failed to connect pipewire context" and exits, and the shell
  exits with it.
- **A system D-Bus must be running** (`dbus-daemon --system`). Without one the shell crashes at start
  (LoginManager and TimeLimitsManager open the system bus).
- **`/run/systemd/seats` must not exist.** The shell picks logind only when that directory exists;
  without it, it uses its built-in dummy login manager. When it exists and there is no logind, the
  shell crashes calling `org.freedesktop.login1`. Installing `systemd` creates it, so the runtime
  must remove it before launching the shell.
- Session D-Bus: `dbus-run-session` is enough.
- Apps for the GNOME session connect to the shell's own display (`WAYLAND_DISPLAY=wayland-0` inside
  the shell's environment), not to the host one.

Harmless at start: no GDM, no ibus-daemon, no evolution data server (calendar), polkit actions not
registered, no a11y bus.

## Packages (on top of the current Ubuntu runtime list)
`gnome-shell mutter-dev-bin pipewire wireplumber fonts-cantarell adwaita-icon-theme gnome-backgrounds`
(`yaru-theme-gnome-shell` for the Ubuntu look.)

## Still to test
1. The same on the Pad: arm64 Ubuntu in proot, inside the app compositor, Turnip/Zink instead of
   software rendering.
2. Input: weston-headless has no seat, so keyboard/pointer forwarding through the viewer is untested
   (the viewer logged `gdk_seat_get_keyboard` criticals here).
3. Frame rate and latency of the PipeWire stream at the Pad's resolution.
4. The viewer has its own title bar; it would need to run fullscreen with the virtual monitor sized
   to the app surface.

If 1–3 fail, the fallback stays KDE styled to look like Ubuntu.
