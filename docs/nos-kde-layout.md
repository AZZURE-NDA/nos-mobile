# N-OS Ubuntu layout for KDE

`tools/linuxfs/desktop/nos-kde-layout` runs before Plasma starts (from `droiddeck-desktop`) and gives
KDE an Ubuntu look at native speed: a dark top bar with the clock in the centre and the tray on the
right, a dark dock on the left (browser, files, terminal, settings) with the app grid at the bottom,
the Yaru icons and GTK theme, the Ubuntu font, an orange accent and an original N-OS wallpaper.

![The layout in a nested Plasma 6.6 session](images/nos-kde-ubuntu-layout.png)

- It installs a look-and-feel package (`org.nos.ubuntu.desktop`), the `NOS-Yaru` icon theme (Yaru,
  falling back to Breeze for the KDE icons Yaru lacks) and the wallpaper, every session.
- The first time (per `VERSION`), it backs up the user's Plasma layout and settings as `*.pre-nos`,
  removes the saved layout so Plasma builds the new one from the package, and writes the theme
  settings.
- `echo classic > ~/.config/nos-kde-layout` restores the backed-up layout at the next desktop start;
  `echo ubuntu > ~/.config/nos-kde-layout` brings the N-OS layout back.

Tested in an Ubuntu 26.04 chroot: Plasma nested in a headless Sway (the app's compositor stand-in).
