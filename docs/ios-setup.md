# iOS build & signing setup

## Why this exists

`ios/Runner.xcodeproj/project.pbxproj` used to have a hardcoded
`DEVELOPMENT_TEAM` (Apple signing team) baked into the Runner target's
build settings. Since signing is `Automatic`, Xcode rewrites that value
to whatever Apple ID/team is signed in locally every time someone builds
for iOS. Because the file was tracked in git, each developer's build
would silently overwrite the next developer's team ID — showing up as
an unrelated diff, and occasionally as a merge conflict.

Now the team ID lives in `ios/Flutter/Local.xcconfig`, a **gitignored**
file that each developer creates locally with their own team ID.
`project.pbxproj` no longer sets it directly, so nobody's build
overwrites anybody else's config.

## One-time: apply the fix to your local clone

From the repo root, on the `hotfix/runners` branch (or wherever you're
merging this):

```bash
git am 0001-fix-ios-stop-committing-per-developer-signing-team-t.patch
git am 0002-docs-add-iOS-build-signing-setup-guide.patch
```

If `git am` complains that the patch doesn't apply (e.g. you've moved
on and the base files changed), fall back to:

```bash
git apply --3way 0001-fix-ios-stop-committing-per-developer-signing-team-t.patch
git add -A
git commit -m "fix(ios): stop committing per-developer signing team"
```

This only needs to happen once per clone/branch, same as any other merge.

## One-time per developer: local signing config

Everyone who wants to build for iOS needs their own
`ios/Flutter/Local.xcconfig`, because it's not checked in.

1. Open Xcode → Settings → Accounts, and make sure you're signed in
   with your Apple ID.
2. Find your Team ID: open `app/obd_app/ios/Runner.xcworkspace` in
   Xcode → select the **Runner** target → **Signing & Capabilities** →
   **Team** dropdown. (Also visible at
   https://developer.apple.com/account/#/membership.)
3. From `app/obd_app`, copy the template:
   ```bash
   cp ios/Flutter/Local.xcconfig.example ios/Flutter/Local.xcconfig
   ```
4. Edit `ios/Flutter/Local.xcconfig` and set:
   ```
   DEVELOPMENT_TEAM = <your team id>
   ```
5. Never `git add` this file — it's in `ios/.gitignore` on purpose.

Everyone can keep sharing the same bundle identifier
(`com.luca.obdApp`); Xcode scopes the local provisioning profile by
team + bundle ID, so different teammates signing with different teams
don't collide.

## Building / running on iOS

1. `flutter pub get`
2. Start a simulator, or plug in a physical device.
3. `flutter run -d <device_id>` (or open `ios/Runner.xcworkspace` in
   Xcode and hit Run — `pod install` runs automatically either way).
4. **First run on a physical device** with a free/personal Apple ID:
   after install, go to the device's **Settings → General → VPN &
   Device Management** and trust your developer certificate before
   the app will open.
5. Free (non-paid) Apple ID builds expire after 7 days — you'll need
   to rebuild and reinstall weekly if nobody's using a paid team.

## Troubleshooting

- **"No profiles for 'com.luca.obdApp' were found"** → `Local.xcconfig`
  is missing or has no team ID. Create/fix it, then `flutter clean`
  and rebuild.
- **`git diff` shows `project.pbxproj` changing again after a build** →
  Xcode re-added a target-level `DEVELOPMENT_TEAM`. Discard that hunk
  before committing (`git checkout -- ios/Runner.xcodeproj/project.pbxproj`
  or stage everything else and drop just that line) — it should keep
  coming from `Local.xcconfig` instead.
