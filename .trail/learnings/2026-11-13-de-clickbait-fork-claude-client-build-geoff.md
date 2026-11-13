---
title: "De-clickbait fork: Claude client, build and storage choices"
author: Geoff
date: 2026-11-13
branch: declickbait
cost: { minutes: 1, tokens: 3989 }
id: l-6e87473a
scope: repo
status: active
src: agent
---

The Anthropic Java SDK was ruled out for ClaudeClient: it depends on Jackson 2.19 (Android API 26+) and OkHttp 4, while SharedModules/constants.gradle forces OkHttp 3.12.13 app-wide and the bedroom Fire TV Stick 4K (1st gen) runs Android 7.1, so ClaudeClient posts raw JSON with OkHttp + org.json. In a prompt test on four clickbait videos, Haiku 5.5 at low effort pointed 3 of 4 jumps at the tease instead of the answer; at medium effort it matched Opus 5.5 at low, which is why DeclickbaitPrefs.getEffort differs per model. The API key lives in no_backup because GDriveBackupManager zips shared_prefs and files, and the manifest has allowBackup=true. The seekbar holds a single segment list shared by YouTube chapters and SponsorBlock, so DeclickbaitController redraws chapter ticks alongside its own marks; its Key moments row stays out of row 0 when chapters exist because SuggestionsController.focusCurrentChapter assumes row 0 is the chapters row. Apple Silicon Macs without Rosetta can't run protoc 3.17.3 (its osx-aarch_64 artifact is x86_64), hence declick/arm64-protoc.init.gradle for local builds only. The declick build type disables google-services and crashlytics tasks because upstream's google-services.json doesn't list the .declick app ID.
