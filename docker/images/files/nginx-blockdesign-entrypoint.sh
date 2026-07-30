#!/usr/bin/env bash
# BlockDesign 2.0 — Container entrypoint
#
# Injects runtime configuration into /var/www/app/js/config.js before
# starting nginx.  Mirrors the logic of the official penpot nginx-entrypoint.sh
# but adapted for our single-file nginx setup (no templates).

set -e

CONFIG_JS="/var/www/app/js/config.js"

# ── Inject PENPOT_FLAGS ────────────────────────────────────────────────────
# The flags control feature toggles: disable-registration, enable-login-with-oidc, etc.
if [ -n "$PENPOT_FLAGS" ]; then
  sed -i \
    -e "s|^//var penpotFlags = .*;|var penpotFlags = \"$PENPOT_FLAGS\";|g" \
    "$CONFIG_JS"
fi

# ── Inject PENPOT_PUBLIC_URI ──────────────────────────────────────────────
if [ -n "$PENPOT_PUBLIC_URI" ]; then
  echo "var penpotPublicURI = \"$PENPOT_PUBLIC_URI\";" >> "$CONFIG_JS"
fi

# ── Inject PENPOT_OIDC_NAME ───────────────────────────────────────────────
# This sets the label on the SSO login button (e.g. "Daily Assistant").
if [ -n "$PENPOT_OIDC_NAME" ]; then
  sed -i \
    -e "s|^//var penpotOIDCName = .*;|var penpotOIDCName = \"$PENPOT_OIDC_NAME\";|g" \
    "$CONFIG_JS"
fi

exec "$@"
