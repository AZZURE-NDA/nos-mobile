# N-OS layouts for KDE

`tools/linuxfs/desktop/nos-kde-layout` runs before Plasma starts (from `droiddeck-desktop`) and puts
an N-OS look on KDE at native speed. `~/.config/nos-kde-layout` picks the layout:

| Value | Layout |
|---|---|
| `atelier` (default) | macOS-style, after the Atelier web desktop: light menu bar on top (N-OS menu, app menus, tray, date and clock), a floating dock at the bottom (apps, Launchpad, Trash), traffic-light window buttons on the left with a centred title, blue accent, the Flow wallpaper |
| `ubuntu` | dark top bar with the clock in the centre, dock on the left, orange accent |
| `classic` | Plasma's own layout, restored from the backup taken when an N-OS layout was first applied |

![The Atelier layout in a nested Plasma 6.6 session](images/nos-kde-atelier-layout.png)

![The Ubuntu layout](images/nos-kde-ubuntu-layout.png)

- Every session it installs the look-and-feel package (`org.nos.desktop`), the `NOS-Yaru` icon theme
  (Yaru, falling back to Breeze for the KDE icons Yaru lacks), the wallpapers, the menu and Launchpad
  icons and the `N-OS-Atelier` Aurorae window decoration.
- When the chosen layout or `VERSION` changes, it backs up the user's Plasma files as `*.pre-nos`
  (first time only), removes the saved layout so Plasma builds the new one, and writes the settings.
- The traffic-light decoration needs KWin's Aurorae engine (`kwin-style-aurorae`, runtime r4 and
  newer); without it the layout keeps Breeze with the buttons on the left.

Tested in an Ubuntu 26.04 chroot: Plasma nested in a headless Sway (the app's compositor stand-in).

## Lock screen with a PIN

The runtime has no working Unix passwords (the session runs as the app's Android user), so the
KDE lock screen's PAM service (`/etc/pam.d/kde`) checks an N-OS PIN instead, through
`pam_exec` and `tools/linuxfs/desktop/nos-pin`:

- `nos-pin set` chooses the PIN (4+ digits, stored as a salted PBKDF2 hash in `~/.config/nos-pin`).
  With a PIN set, the desktop locks when it opens, after 10 minutes idle, and from Lock in the menu.
- `nos-pin clear` removes it; without a PIN the desktop opens directly and locking is disabled, since
  a lock screen without a PIN could never be unlocked.
- `nos-pin setup` runs before Plasma starts and writes the PAM service and lock settings.

Log Out ends the session and returns to the app's home screen instead of reporting a crash.
