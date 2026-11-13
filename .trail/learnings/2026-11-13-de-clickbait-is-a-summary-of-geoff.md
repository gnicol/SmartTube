---
title: De-clickbait is a summary of long videos first; the title check is a footnote and the guess game is gone
author: Geoff
date: 2026-11-13
branch: declickbait
cost: { minutes: 7, tokens: 57432 }
id: l-e7b09136
scope: repo
status: active
src: agent
touches:
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/ClaudeClient.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/DeclickbaitResult.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/DeclickbaitOverlay.java
  - common/src/main/res/layout/declickbait_overlay.xml
---

## Decided
- Geoff restated the goal on 2026-11-12: most of the videos he presses the button on are less clickbait than long and slow to reach the point, so the feature must summarize the key bits, led by whatever the title and thumbnail promised. Whether the title holds up is "merely a bonus but not terribly important". His examples run from 9-minute health clips to 2h46 podcast interviews, with both teaser titles and plain ones.
- The guess game was dropped on his word: "a nice idea but not needed".
- So Claude now returns a short headline (the answer the title teases, or the main takeaway), key points that each carry a full sentence, a short seekbar label and a jump time, and a one-sentence title check with a yes/partly/no verdict. The overlay is one column: headline, key points as the body, title check as a muted last line.
- A separate detail line under the headline was tried and removed: it repeated the first key point.

## Tried and ruled out
- The original single-payoff prompt (2-6 word answer, 2-3 sentences of caveats, 1-3 six-word moment labels): there was nowhere to put several takeaways, and a plain title either got squeezed into one phrase or came back as "Not in this video".
- A two-column result with a coloured verdict block for the title check: it gave the bonus as much weight as the summary, and the narrow column wrapped the headline.

## Watch out
- Prompt test, two videos (17-minute plain-title magnesium talk, 30-minute blood pressure video). Opus 5.5 at low effort: every point stated content with numbers, the first point answered the title, and jump times landed on the line where the point is said. Haiku 5.5 at medium: usable, but about half its times were not exact line starts (the app snaps them to the line before) and it led with background instead of the answer on the plain-title video.
- The cap of seven points (ten over an hour) is soft: on a "ten habits" video Opus returned eight, and ten when the wording was looser. The app does not trim.
- Not tested: a "#1 X" single-answer title, any video over an hour, and none of Geoff's eight example videos. YouTube rate limited caption downloads from the Mac (HTTP 429) part way through.
- Results saved by older builds are ignored and asked for again, because the saved shape changed.

In the code: ClaudeClient.SYSTEM_PROMPT and schema(); DeclickbaitResult.VERSION and KeyPoint; DeclickbaitOverlay.showResult; layout declickbait_overlay.xml. Key points come first in the schema so the headline is written with them in hand.
