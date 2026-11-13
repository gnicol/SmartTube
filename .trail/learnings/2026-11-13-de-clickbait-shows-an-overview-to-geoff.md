---
title: "De-clickbait shows an overview to read, with jump points kept separate and terse"
author: Geoff
date: 2026-11-13
branch: declickbait
cost: { minutes: 7, tokens: 92265 }
id: l-2ca66587
scope: repo
status: active
replaces: l-e7b09136
src: agent
touches:
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/ClaudeClient.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/DeclickbaitOverlay.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/DeclickbaitResult.java
---

## Decided
- Goal (Geoff, 2026-11-12): summarize long, slow videos, led by what the title and thumbnail promised. Whether the title holds up is "merely a bonus", and the guess game was dropped as not needed.
- Summary text and timestamps are separate (Geoff, 2026-11-13): tying every finding to a timestamp was too restrictive, because ideas span a video and often read better as bullets. Claude writes a free-form overview in a small Markdown subset; the moments to jump to are their own short list.
- Jump labels are five words or fewer because the same labels are the card titles in the player's Key moments row and the marks on the seekbar.
- The summary's length follows the content. Geoff wants a ten-item video to get all ten items, so 80 to 150 words is a guide, never a cap.
- There is no "The short version" heading, and the title check is the last line of the summary text; both leave more height for the summary.
- The jump rail slides over the text instead of squeezing it, so the text is never laid out again on the slow bedroom stick.

## Tried and ruled out
- One list where every point carried a sentence and a jump time: it merged or dropped items on a ten-habit video and could not express ideas that run through a whole video.
- A detail line under the headline: it repeated the first point.
- The original single-answer prompt: nowhere for several takeaways, and plain titles came back as "Not in this video".

## Watch out
- Tested on two videos only (17-minute magnesium talk, 30-minute blood pressure video), because YouTube then rate limited captions. Opus 5.5 at low effort covered all ten habits in 217 words with jump labels of two to four words. Haiku 5.5 at medium was wordier (251 words), returned ten jump points against a cap of eight, and most of its times were not exact line starts (the app snaps them).
- Not tested: Geoff's eight example videos, anything over an hour, a "#1 X" title, and the overlay on a real TV. The focus handling and the slide have never run on a device.

In the code: ClaudeClient.SYSTEM_PROMPT and schema(); SummaryText turns the Markdown subset into spans; DeclickbaitOverlay holds the rail and its key handling; DeclickbaitResult.VERSION makes older saved results get asked for again.
