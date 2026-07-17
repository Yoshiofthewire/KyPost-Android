# Security Fixes (WebView + Pairing) Progress Ledger

**Plan:** docs/superpowers/plans/2026-07-17-security-fixes-webview-pairing.md
**Spec:** docs/superpowers/specs/2026-07-17-security-fixes-webview-pairing-design.md
**Base commit:** 981d0e0
**Start date:** 2026-07-17

## Tasks

- [x] Task 1: Disable JavaScript in the email-reading WebView
- [x] Task 2: Confirm before applying a deep-link pairing

## Completed

- Task 1: complete (commit 33cba94, spec ✅ quality ✅). One-line flip,
  `javaScriptEnabled = true` → `false` in `EmailDetailActivity.kt`. Correctly landed on
  this worktree branch (verified before/after commit, unlike the prior plan's cwd mistake).
  Clean review — confirmed no other code path re-enables JS, no dead code from the change.

- Task 2: complete (commit 447b3bd, spec ✅ quality ✅). Extracted `applyParsedPairing` in
  `PushHomeViewModel`, added public `applyDeepLinkPairing`, added an `AlertDialog`
  confirmation in `PushPairingActivity.consumeDeepLink` gated on the user tapping "Pair".
  Reviewer did a full control-flow trace: confirmed the QR-scan path (`pairFromLink`/
  `scanQr()`) is byte-identical in behavior, confirmed no path (onCreate/onNewIntent) can
  reach the apply logic for a deep link without the dialog, confirmed AndroidManifest.xml
  untouched, confirmed string format args match. No findings of any severity.

## Final Whole-Branch Review

Ready to merge: Yes. No Critical/Important issues. Reviewer independently traced both fix
control flows against the live worktree (not just the diff): confirmed no code path
reintroduces WebView JS execution, confirmed no deep-link path can reach the pairing-apply
logic without the confirmation dialog, confirmed AndroidManifest.xml untouched, confirmed
QR-scan path byte-identical. Two Minor items noted, both explicitly out-of-scope/pre-existing
and not applied: remote-image tracking-pixel loading (Fix 1's spec deliberately deferred
this), and the deep-link intent not being consumed after handling (Fix 2 — pre-existing
behavior, and strictly improved by this change since the old code re-paired silently on
every onCreate; now it's gated by the dialog even on a re-trigger).

## Prior plan (complete, superseded by this ledger)

Archive Subfolder Menu (docs/superpowers/plans/2026-07-17-archive-subfolder-menu.md) —
complete and merged to main as of 2026-07-17 (commit 24200b9, landed directly on main
rather than through its worktree due to an implementer cwd mistake — see git history).
This ledger file is a tracked, project-shared file reused across plans, not a record of
this branch's own history.
