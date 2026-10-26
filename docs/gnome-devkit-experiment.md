# GNOME 50 inside the app compositor (devkit route)

Tested 2026-10-23 on x86_64 with an Ubuntu 26.04 (resolute) chroot: gnome-shell 50.1, mutter 50.1.
Weston `--backend=headless` stood in for the app's compositor. Not yet tried on the Pad (arm64, proot).

## Result
GNOME Shell 50 runs and its picture shows up as an ordinary window on the host compositor.
It renders in software ("surfaceless renderer without GPU") and needs no logind, no GDM and no DRM.

## What it takes
- Launch: `gnome-shell --devkit` with `WAYLAND_DISPLAY` pointing at the host compositor.
  - No `--virtual-monitor`: the viewer creates the monitor it shows. An extra one becomes the
    primary monitor, so the top bar and dock end up on a monitor nobody sees.
  - Not `--headless --devkit`: with `--headless` the viewer is never started.
  - GNOME 50 has no `--nested`; `--devkit` replaces it.
- Mutter starts `/usr/libexec/mutter-devkit` itself. It comes from **`mutter-dev-bin`** (not pulled
  in by `gnome-shell`). It is a GTK4/libadwaita Wayland client on the host display.
- **PipeWire + WirePlumber must run first.** The viewer receives the desktop as a PipeWire screencast
  stream; without it the viewer logs "Failed to connect pipewire context" and exits, and the shell
  exits with it.
- **A system D-Bus must be running.** On the device the stock `dbus-daemon --system` cannot start: the
  app writes its own /etc/passwd without a `messagebus` user. The launcher starts one from the app's
  no-user config (the same one droiddeck-session uses). Without one the shell crashes at start
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

## In the repo (branch `exp/gnome-devkit`)
- `tools/ubuntu-rootfs/packages-gnome.txt`, installed when the image is built with
  `DESKTOP_SHELL=gnome` (Ubuntu session mode, Ubuntu dock, Yaru shell theme included).
- `tools/linuxfs/desktop/nos-gnome-session`: the steps above. Shipped and staged by the APK, so a
  launcher fix needs no new runtime image.
- `droiddeck-desktop` starts it when `~/.config/nos-desktop-shell` or
  `/usr/share/droiddeck/desktop-shell` says `gnome`; otherwise Plasma, as before. When GNOME exits
  with an error within two minutes it opens Plasma instead, so a broken GNOME never locks the user
  out of the terminal needed to switch back.
  Back to KDE on a GNOME image: `echo kde > ~/.config/nos-desktop-shell`.
  This file is staged by the app, so it needs a new APK.
- `.github/workflows/build-ubuntu-rootfs.yml` builds the arm64 image on GitHub's arm runner and
  uploads it as an artifact (no release).

Verified with that launcher (x86_64 chroot, root, Weston host): Ubuntu-mode GNOME with top bar,
Ubuntu dock, app grid and overview inside the viewer. Cosmetic: a "screen is being shared" indicator
(the viewer is a screencast) and a "logged in as a privileged user" notice (the runtime runs as root).

## Still to test
1. The same on the Pad: arm64 Ubuntu in proot, inside the app compositor, Turnip/Zink instead of
   software rendering.
2. Input: weston-headless has no seat, so keyboard/pointer forwarding through the viewer is untested
   (the viewer logged `gdk_seat_get_keyboard` criticals here).
3. Frame rate and latency of the PipeWire stream at the Pad's resolution.
4. The viewer has its own title bar; it would need to run fullscreen with the virtual monitor sized
   to the app surface.

If 1–3 fail, the fallback stays KDE styled to look like Ubuntu.
