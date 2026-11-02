# ADR 0017: Dark-mode WebView loading mask

- Date: 2026-11-02
- Status: Accepted

## Context

The WebView was configured with a white background regardless of the app theme. This can be visible before a document renders. Simply changing the WebView background to black risks making pages that leave their background transparent and use dark text unreadable. The app's dark surface is black, while light-mode page rendering must remain unchanged.

## Decision

In dark mode, cover the WebView with a black Compose layer during initial loading and main-frame navigation. Show it immediately for app-initiated reloads and bookmark navigation, because WebView can defer `onPageStarted` while awaiting a response. Do not pre-mask a fragment-only bookmark navigation: such navigation can omit page-load callbacks. Keep the WebView's white background and page CSS unchanged, and do not show the mask in light mode.

After `onPageCommitVisible` or `onPageFinished`, request a WebView visual-state callback; dismiss the mask only for the current navigation. A generation token rejects previously scheduled callbacks after a new navigation starts. The callback indicates that the relevant state is ready to draw, not that it has been displayed on screen.

## Validation and limitations

On an API 34 emulator, a delayed HTTP page was black during dark loading and white during light loading; a delayed dark-mode reload was black and the page appeared after the response. An HTTP 302 and a connection error were also checked. The fragment-only decision has JVM regression coverage; the end-to-end bookmark-switch interaction and DSH were not checked in this release preparation. A missing WebView visual callback may leave the mask in place; the error pane remains above it. Page-driven navigations may leave the old page visible until `onPageStarted` is received.
