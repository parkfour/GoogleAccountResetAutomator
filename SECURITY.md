# Security Policy

## Reporting a vulnerability

Please report security issues privately through GitHub's
[private vulnerability reporting](../../security/advisories/new) (the
**Security** tab → **Report a vulnerability**), not in a public issue.

Only the latest release is supported; fixes ship in a new release.

## Scope

This app runs an Accessibility Service that can read and tap the Google
app's UI. Of particular interest are anything that lets another app trigger
the account switch or read the saved account email, and anything that
widens what the Accessibility Service can see or do beyond the Google app.
