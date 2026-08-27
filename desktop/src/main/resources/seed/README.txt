MultiVPN bundled defaults
=========================

Files in this directory are OPTIONAL and ship inside the app:

  links.txt          one vless:// / trojan:// / ss:// / hy2:// share link
                     per line. Imported ONCE, on the first launch of a
                     BRAND-NEW install (a data dir that never held any
                     servers/configs). Blank lines are ignored.

  subscriptions.txt  reserved — not consumed yet.

Behaviour contract (vpn.ui.AppState.seedBundledDefaults):
  - A machine that already has user data is NEVER touched.
  - A marker file (%APPDATA%\MultiVPN\.bundled-defaults) records that the
    check ran, so deleting every config afterwards stays permanent.
  - Without links.txt nothing happens at all (and no marker is written).

CI note: links.txt may be injected at build time from the
VPN_SEED_LINKS_B64 repository/organization secret instead of being
committed here — keeping real credentials out of git history entirely.
