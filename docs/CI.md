# Automatic internal testing releases

Pushes to `develop` run [the Play internal testing workflow](../.github/workflows/develop-internal.yml). The workflow tests the app, runs Android lint, signs an App Bundle with the existing Play upload key, and publishes **com.tbse.london** to the **internal** test track. It does not promote releases to production.

The workflow also supports a manual run with `develop` selected. Runs targeting another branch skip the publishing job. Uploads are serialized; an active release finishes before the next starts. Rapid pushes can replace an older pending run with the newest commit.

## First develop push

The CI setup is committed to `main`. No `develop` branch existed when it was configured. Create `develop` from a commit containing this workflow, commit the app changes you want testers to receive, then push it. For example, from a clean checkout:

```sh
git fetch origin
git switch -c develop origin/main
# Commit the app changes intended for this internal release.
git push -u origin develop
```

The separate v2 feature edits remain uncommitted in the `v2-trainer` worktree. This setup does not automatically commit those edits or publish an older app build just to test the trigger.

## GitHub configuration

The `play-internal` environment is configured to allow deployments only from the `develop` branch, without a manual approval gate. It contains these encrypted environment secrets:

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded existing upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Upload-key alias |
| `ANDROID_KEY_PASSWORD` | Upload-key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Existing Play publishing service-account JSON |

The GitHub Actions token needs only `contents: read`. Secret files are created with private permissions under `RUNNER_TEMP`, after tests pass, and removed in an always-run cleanup step. They are not written into the checkout, Gradle cache, or uploaded artifacts. Publishing disables the Gradle configuration cache.

To rotate a secret, use the repository's `play-internal` environment settings or `gh secret set NAME --repo elliptic1/LondonTrainer --env play-internal`, supplying its value privately through stdin.

## Signing and version codes

Gradle accepts CI signing values through `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`, and the Play JSON path through `PLAY_SERVICE_ACCOUNT_FILE`. Local builds can continue using the existing private files under `~/.android/keystores/` and `~/.android/play-credentials/`.

The workflow passes `-PrequirePlaySigning=true`: missing signing material or credentials cause a failure instead of a debug-signed upload. Local release builds without publishing credentials still support the existing debug-signing fallback for sideload testing.

The existing [Gradle Play Publisher 3.12.1 `AUTO` strategy](https://github.com/Triple-T/gradle-play-publisher/blob/3.12.1/README.md#handling-version-conflicts) selects a higher version code when necessary. Release names identify the develop commit; internal release notes live in `app/src/main/play/release-notes/en-US/internal.txt`.

## Verification

- Actionlint 1.7.12 validates the workflow.
- Four credential-script tests pass, including missing/invalid credentials and private file permissions.
- All 52 tests for the current v2 working tree, Android lint, and a signed release-bundle build pass with the publishing configuration.
- The signed bundle's certificate matches the existing Play upload certificate.
- The configured service account authenticated and read the existing version 3 generated-APK metadata (HTTP 200).
- The publish task graph validates in Gradle dry-run mode. Missing signing or service-account files fail closed in strict publishing mode.
- No Play release was uploaded during setup. The first end-to-end GitHub upload happens when `develop` is pushed.

Workflow runs retain test/lint reports and the signed bundle as GitHub Actions artifacts for 14 days.
