# Ubuntu 26.04 arm64 runtime for the app's proot session: KDE Plasma, and GNOME when built with DESKTOP_SHELL=gnome.
FROM --platform=linux/arm64 ubuntu:26.04

ARG DEBIAN_FRONTEND=noninteractive
ARG RUNTIME_VERSION=nos-ubuntu-26.04-r3
ARG DESKTOP_TAG=desktop-kde-r2
# gnome also installs GNOME; DEFAULT_SHELL is what droiddeck-desktop starts unless ~/.config/nos-desktop-shell says otherwise
ARG DESKTOP_SHELL=kde
ARG DEFAULT_SHELL=kde

# Snaps need systemd, which a proot session does not have.
RUN printf 'Package: snapd\nPin: release a=*\nPin-Priority: -10\n' > /etc/apt/preferences.d/no-snapd \
 && printf 'APT::Install-Recommends "false";\nAPT::Install-Suggests "false";\n' > /etc/apt/apt.conf.d/90-no-recommends \
 && apt-get update \
 && apt-get install -y ca-certificates curl gnupg locales tzdata \
 && locale-gen en_US.UTF-8 C.UTF-8

# Ubuntu's firefox package only installs the snap.
RUN install -d -m 0755 /etc/apt/keyrings \
 && curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg -o /etc/apt/keyrings/packages.mozilla.org.asc \
 && echo 'deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list \
 && printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla

COPY packages.txt /tmp/packages.txt
RUN apt-get update \
 && apt-get install -y $(grep -v '^\s*#' /tmp/packages.txt | xargs) \
 && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/packages.txt

COPY packages-extra.txt /tmp/packages-extra.txt
RUN apt-get update \
 && apt-get install -y $(grep -v '^\s*#' /tmp/packages-extra.txt | xargs) \
 && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/packages-extra.txt

COPY packages-netops.txt /tmp/packages-netops.txt
RUN apt-get update \
 && apt-get install -y $(grep -v '^\s*#' /tmp/packages-netops.txt | xargs) \
 && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/packages-netops.txt

COPY packages-gnome.txt /tmp/packages-gnome.txt
RUN if [ "$DESKTOP_SHELL" = gnome ]; then apt-get update \
 && apt-get install -y $(grep -v '^\s*#' /tmp/packages-gnome.txt | xargs) \
 && apt-get clean && rm -rf /var/lib/apt/lists/*; fi; rm -f /tmp/packages-gnome.txt

COPY files/ /
RUN chmod 0755 /opt/android-host/proot /opt/android-host/loader /usr/local/bin/nos-gnome-session \
 && mkdir -p /usr/share/droiddeck && echo "$DEFAULT_SHELL" > /usr/share/droiddeck/desktop-shell \
 && echo "$RUNTIME_VERSION" > /.version \
 && echo "$DESKTOP_TAG" > /.droiddeck-pkg-desktop-kde \
 && ln -sf /usr/share/zoneinfo/UTC /etc/localtime \
 && echo 'LANG=en_US.UTF-8' > /etc/default/locale \
 && mkdir -p /root/Desktop /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix \
 && rm -f /.dockerenv
