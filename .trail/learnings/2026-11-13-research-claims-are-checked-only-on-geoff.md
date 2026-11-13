---
title: "Research claims are checked only on request, as a second pass after the summary"
author: Geoff
date: 2026-11-13
branch: declickbait
cost: { minutes: 27, tokens: 376606 }
id: l-0c8e4ea6
scope: repo
status: active
src: agent
touches:
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/ClaudeClient.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/DeclickbaitOverlay.java
  - common/src/main/java/com/liskovsoft/smartyoutubetv2/common/declickbait/DeclickbaitController.java
---

## Decided
- Geoff's workflow (2026-11-13): the summary pass also flags up to four claims the video bases on research. Only then does the overview offer "Check N research claims". Pressing it runs a second request with web search that finds the paper behind each claim, compares it with what the video says (matches, overstates, misrepresents, not found), and rates the evidence.
- The check is on request because it costs about ten times a summary (around 40 seconds, roughly 25–30¢ against about 3¢) and most videos don't need it: a 2h46 Rick Rubin interview flagged no claims, while the magnesium and blood pressure videos flagged three and four.
- The research pass always uses Opus 5.5, whatever the model setting. The web search version that filters results is not listed for Haiku 5.5, and the setting exists to keep the per-video summary cheap.
- The second pass gets each claim with about two minutes of transcript around it (30 seconds before, 90 after), not the whole transcript. That context is saved with the summary, so the check also works from a saved result.

## Watch out
- Videos usually say "trials show" without naming a paper. The check then names the likely source and says it is a guess; the prompt forbids inventing citations. A video that names its study gives a firmer match. That case has not been tested.
- Tested end to end on one video (magnesium) with the app's prompts: the summary took 11 seconds, the check 40 seconds with 4 searches. The papers it found (Zhang 2016, Veronese 2016, Peikert 1996) match what I know of them; the links were not opened. Not yet run on a TV.

- Progress comes from streaming the research request. With web_search_20260209, Claude writes a short program that runs several searches, so search and result blocks arrive in bursts when that code finishes (about 16 s and 31 s into a 35 s check), not one by one. The block that carries the program shows up around 10 s, hence the "Planning the searches" step, and the clock covers the quiet gaps.
- The repo's Robolectric unit tests fail before any test code runs (NoClassDefFoundError: android/webkit/RoboCookieManager), the existing ones included. The stream parser was checked by compiling MessageStream and ResearchProgressSteps alone with javac and the org.json jar from the Gradle cache, then replaying a recorded stream.

In the code: ClaudeClient.RESEARCH_PROMPT, researchSchema() and checkResearch() (its own OkHttp client with a 240-second read timeout, and a pause_turn loop); DeclickbaitResult.ResearchClaim and ResearchCheck (saved result VERSION 3); overlay research mode with Back returning to the summary.
