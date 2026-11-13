---
title: Captions empty on devices without PO tokens since SmartTube 32.74
author: Geoff
date: 2026-11-13
branch: declickbait
cost: { minutes: 44, tokens: 227778 }
id: l-6c799fa2
scope: repo
status: active
src: agent
touches:
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/TranscriptFetcher.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/Transcript.java
---

On the bedroom Fire TV Stick 4K (Fire OS 6, Android 7.1) caption downloads returned HTTP 200 with an empty body for json3 and VTT, with or without the pot/c params, and SmartTube's own CC crashed the player (getClass() on null) on the same video, while stock 32.71 showed captions fine. Cause: MediaServiceCore's FormatInfoWrapper (32.74) now tries AppClient.WEB through the innertube provider first, where 32.71's VideoInfoService started with TV; WEB caption URLs need a PO token that devices without WebView pot support can't make. User-Agent and stripping pot did not help; the same URLs work from a Mac. TranscriptFetcher.fetchForVideo therefore falls back to caption tracks from VideoInfoService with AppClient.VISIONOS then ANDROID_VR (yt-dlp got token-free captions from visionOS). AppClient is Kotlin-internal but usable from Java. Separately, Transcript.Builder used to append the cue that ended a group to the old line, labelling it up to a gap too early; a late cue now starts the new line, and snapToLine matches whole-second stamps against the full second.
