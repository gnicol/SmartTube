# SmartTube ✦ (de-clickbait fork)

A side-by-side build of SmartTube with one extra player button, **✦ De-clickbait**. Press it on a long
video, or one with a teasing title ("THIS Is the #1 Food Linked to…"), and Claude reads the transcript and
shows an overview: a headline that answers the title and thumbnail, then a summary to read (up and down
scroll it), ending with a one-line check of whether the title holds up. Moments worth jumping to peek in
from the right edge: press right to slide them out, OK to jump, left or Back to return to the overview.
The same moments appear as amber marks on the seekbar and a "✦ Key moments" row under the player.

When a video bases its advice on research ("trials show…", a named study), the summary ends with a
**Check N research claims** button (down from the summary reaches it). It runs a second request, with web search,
that looks up the paper behind each claim and shows what the paper actually found, whether the video matches or
overstates it, and how strong the evidence is. It takes about a minute, with a running clock and each search and the
paper it found listed as they happen. It always uses Opus (whatever the model setting) and is saved with the summary. Videos that don't lean on research, like interviews, get no button.

The goal is the substance of the video without sitting through it. Many videos aren't clickbait so much as
slow to reach the point, so the summary comes first and the title check is the bonus.

It installs next to stock SmartTube (`org.smarttube.stable.declick`, launcher name "SmartTube ✦"),
so the original stays as a fallback.

## Using it

1. Settings → **De-clickbait** → **Claude API key**. Paste it with the phone keyboard in the Fire TV or
   Google TV app, or over ADB with `adb shell input text '<key>'` while the text box is open.
   Make one key per device in the Claude Console so you can revoke one at a time.
2. **Test key** checks it with a tiny request.
3. In the player, press **✦** (long press opens these settings).

### Bringing over your SmartTube accounts and settings

SmartTube ✦ is a separate app, so it starts empty. On Android 11+ it can't see stock SmartTube's
backup folder, so copy the backup across with ADB:

1. Stock SmartTube: Settings → Backup/Restore → Local backup → **Backup**.
2. `adb shell cp -r /sdcard/Android/media/org.smarttube.stable/data/org.smarttube.stable /sdcard/Android/media/org.smarttube.stable.declick/data/`
3. SmartTube ✦: Settings → Backup/Restore → **Restore** → pick `org.smarttube.stable`. The app closes
   after "Done" (that's how SmartTube applies a restore); reopen it. The Claude key isn't touched.

On Android 7–10 (the bedroom stick) both apps use `/sdcard/data/<package>/Backup`, so step 2 is usually unnecessary.

Other settings: model (Opus 5.5 by default, Haiku 5.5 cheaper), hide the button, clear saved summaries.
Summaries are saved per video, so re-opening one is instant and free.

The key is stored in Android's `no_backup` folder, which SmartTube's Google Drive backup and Android's
own backup both skip.

## What's changed from upstream

Almost everything is in new files:

| Where | What |
|---|---|
| `common/.../common/declickbait/` | Controller, overlay, Claude client (plain OkHttp + org.json), caption fetcher, cache, settings |
| `common/src/main/res/**/declickbait_*`, `drawable/*declickbait*` | Layout, strings, icons |
| `smarttubetv/.../actions/DeclickbaitAction.java` | The ✦ player button |
| `smarttubetv/declick.gradle`, `smarttubetv/src/declick/` | `declick` build type: app ID, name, signing, update feed |
| `.github/workflows/declick-*.yml` | Release builds and daily upstream sync |

One-line hooks in upstream files, each tagged `// declickbait`:
`PlaybackPresenter.java` (register controller), `AppDataSourceManager.java` (settings tile),
`VideoPlayerGlue.java` (button, two places), `smarttubetv/build.gradle` (`apply from: "declick.gradle"`).
One small override, also tagged: `tweaks/ControlBar.java#focusSearch` makes left on the first player control wrap
to the last (where ✦ sits) and right on the last wrap to the first.

Why not the official Anthropic Java SDK: it needs Android 8+ (Jackson 2.19) and OkHttp 4, but SmartTube
pins OkHttp 3.12 and the bedroom Fire TV Stick 4K (1st gen) runs Android 7.1.

## One-time GitHub setup

1. Fork `yuliskov/SmartTube`, push the `declickbait` branch, and make it the fork's **default branch**
   (scheduled workflows only run from the default branch).
2. Add repository secrets from the local key in `~/.config/smarttube-declick/`:
   - `DECLICK_KEYSTORE_B64`: `base64 -i ~/.config/smarttube-declick/release.jks`
   - `DECLICK_STORE_PASSWORD`, `DECLICK_KEY_PASSWORD`, `DECLICK_KEY_ALIAS`: from `keystore.properties`
3. Enable Actions on the fork. Upstream's own workflows can stay on: CI only runs on pushes to `master`,
   stale is manual, cleanup skips forks, and VirusTotal isn't triggered by releases our workflow creates.
4. Run **SmartTube ✦ release** once. Installed apps then update themselves from the `latest-declick`
   release (`smarttubetv/src/declick/res/values/declick_update_urls.xml`).

**Back up `~/.config/smarttube-declick/`.** Every future build must be signed with that key, or devices
won't accept the update.

## Staying current with upstream

The fork follows upstream's **stable** releases, not `master` (which runs ahead as the beta). Upstream tags
stable release commits `NN.NNs` (e.g. `32.71s`). `declick-sync.yml` runs daily: when a newer stable tag
appears it rebases `declickbait` onto it and starts a release build. If the rebase conflicts, the job fails
and GitHub emails you; rebase locally (`git rebase --onto <new>s <old>s`), fix the hook, and push.

Why stable: beta 32.74 tries YouTube's WEB client first, whose captions need a PO token that the Fire OS 6
stick can't make, so captions (and the player's own CC) broke there while stable 32.71 worked.
`TranscriptFetcher` also falls back to the visionOS / Android VR clients for when stable picks that up.

## Building locally

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
git submodule update --init --recursive
./gradlew -I declick/arm64-protoc.init.gradle :smarttubetv:assembleStstableDeclick
adb install -r smarttubetv/build/outputs/apk/ststable/declick/SmartTube_stable_*_armeabi-v7a.apk
```

On Fire OS 6 (the bedroom Fire TV Stick 4K, Android 7.1), `adb install` of a ~27 MB APK crashed the stick's
network ADB until ADB Debugging was toggled off and on. Copy first instead, or use Downloader with the
release URL (`.../releases/download/latest-declick/smarttube_declick_armeabi-v7a.apk`):

```sh
adb push smarttube_declick_armeabi-v7a.apk /data/local/tmp/declick.apk
adb shell pm install -r /data/local/tmp/declick.apk
```

`declick/arm64-protoc.init.gradle` is only for Apple Silicon Macs without Rosetta: upstream's protoc
3.17.3 "osx-aarch_64" artifact is really x86_64, so local builds use protobuf 3.21.12 instead.
CI on Linux builds with upstream's 3.17.3.
