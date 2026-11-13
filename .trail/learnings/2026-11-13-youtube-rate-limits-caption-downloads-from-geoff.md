---
title: "YouTube rate limits caption downloads from the Mac, so local prompt tests get only a few videos"
author: Geoff
date: 2026-11-13
branch: declickbait
cost: { minutes: 6, tokens: 237314 }
id: l-670ee0b6
scope: repo
status: active
src: agent
touches:
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/TranscriptFetcher.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/ClaudeClient.java
---

## Watch out
- Testing the Claude prompt on the Mac needs captions, and YouTube cut them off fast. On 2026-11-12 yt-dlp fetched captions for two videos (two tracks each, `en` and `en-orig`), then every caption request for any video returned HTTP 429. It was still 429 at 08:32 the next morning, about 11 hours later. Search and video details (title, chapters, length) kept working throughout; only the caption download was refused.
- Why it matters: Geoff's eight example videos could not be tested locally, so prompt changes were judged on two videos picked before the limit hit.
- The Mac and both TVs share the home's public address. Nobody checked whether captions on the TVs were affected while the Mac was limited.

## Decided
- Ask for one caption track per video (`--sub-langs en`), not several, and keep every transcript that downloads so prompt iterations re-use it instead of fetching again.
- To test a specific list of videos, run them on a TV, where the app fetches its own captions.

## Tried and ruled out
- Pointing yt-dlp at a different YouTube client to get around the limit: the session's permission rules blocked it as bot-check circumvention. Don't plan on it.

In the code: nothing in the repo does local testing; the test script and the two transcripts lived in session scratch and are not kept. TranscriptFetcher is the app's own caption path on the TV.
