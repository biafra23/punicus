# Android release signing

(Inherited from biafra23/myotis's docs/android-release-signing.md; the
mechanism is identical.)

Every release carries two Android APKs once the release key is set up, and the
debug one alone until then:

| asset | app | package | signed with |
|---|---|---|---|
| `Punicus-<tag>-android.apk` | **Punicus** (dark icon) | `eth.serotine` | the Punicus **release key**, held only in GitHub Actions secrets |
| `Punicus-<tag>-android-debug.apk` | **Punicus Debug** (amber icon) | `eth.serotine.debug` | the **debug key**, `android-app/debug.keystore`, which is public |

## Why two apps

Android accepts an update only from the signer of the installed app. The debug
keystore and its passwords are committed (`android-app/build.gradle.kts`), so
anyone can sign with it, and anything it signs can be "updated" by anyone. A
lookalike APK under the release package id could install over Punicus as an
ordinary update and keep its data — for a wallet, its accounts and whatever the
key store holds. (Myotis shipped a debug-signed build under its release id once
and had to walk its users through an uninstall; Punicus starts out right.)

So the release app is signed with a key that never enters the repository, and the
debug build type is a separate app (`applicationIdSuffix = ".debug"`, its own
label and amber launcher icon in `android-app/src/debug/res`). The public key can
then reach only the debug app. Both apps install side by side.

## How CI signs

`.github/workflows/android-apk.yml`:

1. **`build`** (every run) builds `assembleDebug` and `assembleRelease`. The
   release build type has no `signingConfig`, so it writes
   `android-app-release-unsigned.apk`. The minSdk-29 check
   (`scripts/check_apk_min_api.py`) runs on both APKs. On a tag the unsigned
   APK is uploaded as an artifact (only when the tree pins a release certificate).
2. **`sign-release`** (v* tags only) is the only job that ever holds the key.
   - Its secrets live in the `android-release` **environment**, whose deployment
     rule admits v* tags only. A PR branch can't read them, whatever workflow it
     carries.
   - It checks out nothing but `scripts/sign_release_apk.sh` and
     `android-app/release-signing-cert.sha256`, and signs with the runner's
     `apksigner`. No Gradle or plugin build script runs while the key is on
     disk.
   - The script refuses an input that is already signed. After signing, it
     requires exactly one signer, equal to the pinned certificate (and the pin
     must not be the debug certificate).
3. **`release`** (v* tags only, `contents: write`, no key) checks the two package
   ids with `aapt2` and uploads both APKs (the debug one alone without a pin).

Until the release key is set up, a tag publishes the debug APK alone. A tree
without `android-app/release-signing-cert.sha256` has no release app to sign:
`sign-release` is skipped and `release` uploads `Punicus-<tag>-android-debug.apk`
only. Every earlier run of the `build` job warns while the pin file is missing.

Once the pin is committed, it fails closed. Without the secrets, or with a key
that doesn't match the pin, `sign-release` fails and `release` is skipped. That
tag then publishes **no** Android asset, the debug APK included: a broken key is
never quietly turned into a debug-only release.

## One-time setup (owner)

1. **Generate the key on your own machine**, never in CI or a shared session.
   `keytool` prompts for the password:

   ```bash
   keytool -genkeypair -v -keystore serotine-release.p12 -storetype PKCS12 \
     -alias serotine-release -keyalg RSA -keysize 4096 -validity 10950 \
     -dname "CN=Punicus"
   ```

   **Back it up offline**, with its password, in at least two places. A lost key
   cannot be replaced: every installed copy would have to be uninstalled (losing
   its data) to move to a new key. A leaked key lets anyone ship "updates".
   Never commit it. `.gitignore` refuses `*.p12` / `*.jks` as a backstop, but run
   this outside the checkout anyway.

2. **Create the environment**, restricted to v* tags, **with a required
   reviewer**:

   ```bash
   gh api -X PUT repos/biafra23/punicus/environments/android-release \
     -F 'deployment_branch_policy[protected_branches]=false' \
     -F 'deployment_branch_policy[custom_branch_policies]=true' \
     -F "reviewers[][type]=User" -F "reviewers[][id]=$(gh api user --jq .id)"
   gh api -X POST repos/biafra23/punicus/environments/android-release/deployment-branch-policies \
     -f name='v*' -f type=tag
   ```

   Then restrict who may create `v*` tags with a tag ruleset (Settings → Rules → Rulesets →
   New tag ruleset: target `v*`, restrict creations, bypass list: you).

   Both are part of the setup, not extras. The tag rule alone keeps PR branches
   out, not anyone holding a `contents: write` credential: such a tag can point
   at a commit whose `android-apk.yml` prints the secrets, and that run still
   gets them. Throwaway tags like `release-version-guard.yml`'s `v9.9.9-guardtest`
   are such tags too. The required reviewer makes every signing wait for your
   click in the run's page, one click per release, and you see which commit
   you approve.

   Know the limit: both controls key on your account. Agent sessions on your
   machine act with your own `gh` token, which can also push tags and approve a
   pending deployment through the API. So these stop other collaborators and
   stray tags; against a session holding your token, they rest on CLAUDE.md's
   rule that agents never push a `v*` tag. Closing that too means giving agents
   a credential of their own, without tag or approval rights.

3. **Store the secrets in that environment**, not as repository secrets. `gh`
   prompts for each value, so a password never lands in your shell history:

   ```bash
   base64 -i serotine-release.p12 | gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --env android-release --repo biafra23/punicus
   gh secret set ANDROID_RELEASE_KEYSTORE_PASSWORD --env android-release --repo biafra23/punicus
   gh secret set ANDROID_RELEASE_KEY_ALIAS --env android-release --repo biafra23/punicus --body serotine-release
   ```

   `ANDROID_RELEASE_KEY_PASSWORD` is needed only for a JKS keystore whose key
   has its own password. PKCS12 has one password, and the script defaults to it.

4. **Pin the certificate.** Its SHA-256 is public. Commit it so every signing
   run checks the key against it. Write it the way `apksigner` prints it
   (lowercase, no colons), so a user can compare the two by eye:

   ```bash
   keytool -list -v -keystore serotine-release.p12 -alias serotine-release \
     | sed -n 's/.*SHA256: //p' | tr -d : | tr A-F a-f
   ```

   That line goes into `android-app/release-signing-cert.sha256`; lines
   starting with `#` are comments. (The script also accepts keytool's
   `AB:CD:…` form.) The release notes point users there to verify a download.

A tag push runs the workflow files from the tagged commit, so all of this must be
on the tagged commit (the pin) or in the repo settings (the rest) before the
first tag after this change.

## Verifying a download

```bash
apksigner verify --print-certs Punicus-<tag>-android.apk
```

The `Signer #1 certificate SHA-256 digest` must equal
`android-app/release-signing-cert.sha256`. On a phone, the AppVerifier app shows
the same fingerprint for an installed app.

## Local builds

`./gradlew :android-app:installDebug` installs **Punicus Debug** (the `.debug`
package) next to a release install. `assembleRelease` gives the unsigned APK. To
sign it yourself, run the CI script with the same variables. `read -s` keeps the
password out of your shell history:

```bash
export ANDROID_HOME=~/Library/Android/sdk   # or point ANDROID_BUILD_TOOLS at a 35.0.0+ build-tools dir
read -rs ANDROID_RELEASE_KEYSTORE_PASSWORD && export ANDROID_RELEASE_KEYSTORE_PASSWORD
ANDROID_RELEASE_KEYSTORE_BASE64="$(base64 -i serotine-release.p12)" \
ANDROID_RELEASE_KEY_ALIAS=serotine-release \
  scripts/sign_release_apk.sh android-app/build/outputs/apk/release/android-app-release-unsigned.apk Punicus.apk
```

## Rotating the key

APK Signature Scheme v3 supports key rotation (`apksigner rotate`, then sign
with `--lineage`). Installs accept an update signed with the new key only through
that lineage, which needs the **old** key, another reason the backup in step 1
matters. Rotating means updating the secrets, the pin, and `sign_release_apk.sh`
(lineage flag) together.
