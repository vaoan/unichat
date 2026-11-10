---
paths:
  - "src/main/java/app/unichat/ui/ThreadPane.java"
  - "src/main/java/app/unichat/ui/ThreadRows.java"
  - "src/main/java/app/unichat/ui/TimelineScroll.java"
  - "src/main/java/app/unichat/ui/MessageRowView.java"
  - "src/main/java/app/unichat/ui/MessageLine.java"
  - "src/main/java/app/unichat/ui/MessageMedia.java"
  - "src/main/java/app/unichat/ui/HtmlView.java"
  - "src/main/java/app/unichat/ui/RichText.java"
  - "src/main/java/app/unichat/matrix/MessageHtml.java"
  - "src/main/java/app/unichat/links/**"
---
# The open chat (timeline) and message HTML

- Following the newest message (`TimelineScroll`): the person going towards older messages (wheel/trackpad up, ↑ PgUp Home, dragging the thumb up) stops it at once; it follows again only when the newest message is wholly in view, in pixels; a growing photo decides nothing. No time windows nor "near the bottom" fractions: 0.2 % of a long chat is hundreds of pixels and pulled the person back down (`AutoScrollUiTest.aSmallScrollUpInALongChatIsNotUndone`).
- Updates: `ThreadRows.update` touches only rows that differ (`MessageRow.revision`, from `Message.revision()`: a new drawn field must go in there), since replacing the whole list redraws all that's on screen; `ThreadPane.redraw` is for changes of appearance; a width change rebuilds the rows only when it changes the bubbles' width (`MessageRowView.bubbleWidth`, within 200–560 px), and a rebuilt row shows a photo decoded before at once (`Stills`): opening the side panel used to rebuild every row and every photo blinked (`TimelineWidthUiTest`); `MainView` updates the open chat at most every 250 ms. `ScrollPerfUiTest` (`-Dunichat.perfTests=true`, the real display: only the person runs it; ffmpeg) prints before/after numbers for a giant chat; compare with the Mac under similar load, which moves them a lot.
- Message HTML is written by strangers. `HtmlView` draws `formatted_body` tag by tag, as a browser would (jsoup, so badly nested HTML reads the same), with no cases per network or bridge: Discord embeds are just HTML, and `matrix/MessageHtml` writes `com.beeper.linkpreviews` as HTML too. Pictures only from `mxc://`, links only http(s), no scripts or frames. `Message.body` stays plain text for lists, replies and editing (`withoutEmbedText` drops the "> " lines the bridge repeats an embed in).
- Discord's bridge drops the video of a rich embed, so `links/LinkInspector` opens each link when its message shows (`MediaSource.linkMedia`; at most 5 per message) and asks the server what it is, or the page's `og:video`; `MessageMedia.linkPlayers` shows it. Those links come from anyone: no cookies, and every hop refuses this machine and its network; `MediaDownloader` fetches all web addresses through it too. A browser's User-Agent gets redirected by FixupX, hence "compatible". Ajustes → Privacidad → Conexiones can turn it off (`privacy.linkPreviews`, checked in `MediaDownloader.linkMedia`).
