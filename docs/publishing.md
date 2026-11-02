# Publishing London Trainer to Google Play

London Trainer publishes Android App Bundles through Gradle Play Publisher
3.12.1. Run the commands below from the repository root. The application ID is
`com.tbse.london`; the default destination is **internal testing**.

The usual flow is: validate locally, upload to internal testing, test the
Play-installed build, then promote that same version code to another track.
Uploading a bundle, uploading the store listing, and submitting changes for
Google review are separate steps.

## Local setup

The publishing configuration lives in [app/build.gradle.kts](../app/build.gradle.kts).
The root [build.gradle.kts](../build.gradle.kts) pins the plugin version.

Two credential files must exist outside the repository:

| File | Purpose |
| --- | --- |
| `~/.android/keystores/londontrainer-play.properties` | Configures the upload key used to sign release builds. |
| `~/.android/play-credentials/londontrainer-play-service-account.json` | Authenticates publishing requests to the Google Play Developer API. |

The signing properties file contains these keys; values below are placeholders:

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=REPLACE_LOCALLY
keyAlias=REPLACE_LOCALLY
keyPassword=REPLACE_LOCALLY
```

Use the app's registered upload key. The service account needs access to this app
in Play Console and permission to publish to the intended track. Keep the
keystore, passwords, and service-account JSON out of Git and terminal output.

Check that the files are present without printing their contents:

```sh
test -s "$HOME/.android/keystores/londontrainer-play.properties" && \
test -s "$HOME/.android/play-credentials/londontrainer-play-service-account.json"
```

Both files are required for publishing even though ordinary builds can run
without them. If signing properties are absent, this project falls back to the
debug signing key. If the service-account file is absent, publishing is disabled
by the `play.enabled` configuration. A successful local release build alone does
not establish that it is signed for Play.

## Prepare and validate a release

1. Update `versionName` in `app/build.gradle.kts` for the user-visible release.
2. Review `versionCode`. The project enables `ResolutionStrategy.AUTO`, so the
   publishing build can resolve conflicts with codes already uploaded to Play.
   Record the actual uploaded code from the publishing output and Console;
   do not assume it equals the source value.
3. Update `app/src/main/play/release-notes/en-US/default.txt`.
4. Run the tests and build the release APK for device testing:

```sh
./gradlew testDebugUnitTest :app:assembleRelease --console=plain
```

The local APK is `app/build/outputs/apk/release/app-release.apk`. Check drag and
drop, tap moves, hints, wrong-move feedback, line completion, and the layout on a
short screen. These checks supplement the unit tests.

## Publish to internal testing

This command builds and uploads the release bundle and commits a completed
release to the internal track:

```sh
./gradlew :app:publishReleaseBundle \
  --track internal \
  --release-status completed \
  --console=plain
```

The bundle output is `app/build/outputs/bundle/release/app-release.aab`. The
publish task handles the bundle build; a separate `bundleRelease` invocation is
not required. Record the version code shown in output such as:

```text
Updating [completed] release (com.tbse.london:[4]) in track 'internal'
```

Here `4` is an example from the November 1, 2026 release, not a value to reuse for
new uploads. Re-running a publish command can upload another version; verify the
existing release before retrying after an uncertain result.

The build configuration also accepts `-PplayTrack=internal` as a track default.
The examples use explicit task flags so the destination is visible in the
command.

### Give testers access

In Play Console, open **Test and release → Testing → Internal testing → Testers**.
Select the tester email list or Google Group, save it, and copy the opt-in link.
Testers must use an eligible Google account and join through that link before
installing from Play. Uploading a bundle does not add testers.

Internal testing supports up to 100 testers. Distribution can take time,
especially for the first release; do not rely on public Store search to find an
internal test. See Google's [testing setup instructions](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en).

Verify the installed Play build, not just the locally installed APK. A local APK
signed with a different key may need to be uninstalled before Play can install
the app; uninstalling removes local training progress.

## Publish store assets separately

The checked-in metadata is under `app/src/main/play/`:

| Path relative to that directory | Contents |
| --- | --- |
| `listings/en-US/title.txt` | Store title |
| `listings/en-US/short-description.txt` | Short description |
| `listings/en-US/full-description.txt` | Full description |
| `listings/en-US/graphics/icon/` | Store icon |
| `listings/en-US/graphics/feature-graphic/` | Feature graphic |
| `listings/en-US/graphics/phone-screenshots/` | Ordered phone screenshots |
| `release-notes/en-US/default.txt` | Release notes |
| `contact-email.txt`, `contact-website.txt`, `default-language.txt` | App details |

Upload changed listing content with:

```sh
./gradlew :app:publishReleaseListing --console=plain
```

Listing content is app-wide, not a separate storefront for each test track.
Review the pending changes in Console before submission. The bundle upload
command does not replace this listing task.

## Promote an already tested bundle

Promotion reuses an uploaded artifact. It avoids rebuilding a different binary
after testing. Set `TESTED_VERSION_CODE` to the code verified in Console:

```sh
TESTED_VERSION_CODE=4 # Example only: replace with the tested code.
./gradlew :app:promoteReleaseArtifact \
  --from-track internal \
  --promote-track alpha \
  --version-code "$TESTED_VERSION_CODE" \
  --release-status completed \
  --console=plain
```

The standard API track IDs are `internal`, `alpha` (closed testing), `beta`
(open testing), and `production`. For a custom closed track, use its exact track
ID from Console. Configure that track's testers and availability in Console too.

For a production draft prepared for Console review:

```sh
./gradlew :app:promoteReleaseArtifact \
  --from-track internal \
  --promote-track production \
  --version-code "$TESTED_VERSION_CODE" \
  --release-status draft \
  --console=plain
```

These commands change the destination track. Run only the promotion appropriate
to the intended release. For an established app, `--release-status completed`
requests a full rollout; it does not mean Google has approved or published it.

## Finish the release in Play Console

For London Trainer's first production submission on November 1, 2026, the API
rejected a completed production release with:

```text
Only releases with status draft may be created on draft app.
```

The successful path was:

1. Promote the internal bundle to production with `--release-status draft`.
2. In **Production → Countries / regions**, select all available countries and
   regions, including **Rest of World**, and save. Worldwide launch is the
   owner's chosen configuration for this app.
3. Open the production draft, continue to **Preview and confirm**, resolve any
   blocking errors, and save the release.
4. In **Publishing overview**, inspect all pending changes. This includes the
   listing and required app-content declarations, not just the bundle.
5. Submit the changes for review and confirm the submission dialog.
6. Verify Console reports **Changes in review** or queued checks, with the
   expected release and countries listed.

At that submission, version **1.3 (code 4)** was completed on internal testing,
and the production release, listing, and worldwide availability were submitted
for review. This is a historical record, not a live status check.

Managed publishing was off at submission. Check its current setting in
**Publishing overview** to understand whether approved changes publish
automatically or wait for a manual publish action. Google's checks and review
can still delay availability after submission.

## Verify and troubleshoot

After every publish or promotion, open the target track's **Releases** tab and
confirm the version name, version code, release status, and notes. Also check
**Publishing overview** for pending changes, review status, or errors. Finally,
have an eligible tester install or update through Play.

| Symptom | What to check |
| --- | --- |
| Build succeeds but nothing appears on the track | Confirm publishing ran rather than being skipped, credentials exist, and the command targeted the right track. |
| API returns 401 or 403 | Check service-account credentials, app access, and track publishing permissions. |
| Version code conflict | Confirm the upload's actual code and the configured AUTO strategy. Promote an existing tested code instead of uploading it again. |
| Draft-app error | Use the production draft and Console submission flow above. |
| No countries selected | Save track country availability before confirming the release. |
| Tester cannot install | Check the tester list, Google account, opt-in, release availability, device compatibility, and any local signing-key conflict. |
| Missing native debug symbols warning | This was non-blocking for code 4; inspect the release's current validation messages separately. |
| Gradle reports a failed commit | Do not claim the release succeeded from an earlier “Updating” log line. Read the track back in Console before retrying. |

Inspect the installed plugin's available flags without publishing anything:

```sh
./gradlew help --task publishReleaseBundle
./gradlew help --task publishReleaseListing
./gradlew help --task promoteReleaseArtifact
```

For plugin behavior beyond this project's configuration, see the
[Gradle Play Publisher documentation](https://github.com/Triple-T/gradle-play-publisher#managing-artifacts).
Use the local task help above to confirm flags supported by the pinned version.
