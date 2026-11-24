# Accessibility, WCAG 2.2 AA

What ADR-23 asks for accessibility: an automated check in CI and a manual check at each release. Most of what a user
meets is drawn by the components of Quadrava (NG-ZORRO). OpenDevTrack controls its screens: labels, headings, the order
of the fields, texts, the colours of the theme (`brand/`).

## Automated check

`e2e/a11y/axe.mjs` runs axe-core 4.14 (`@axe-core/playwright`) in Chromium, signed in as `mario.rossi`, at the end of
`e2e/authz/run.sh check`. It covers the 11 main screens:

- the dashboard;
- the project list and the detail of a project;
- the issue list, the detail of an issue and a new issue;
- a board, the backlog;
- the search, the notification settings, the webhooks.

The rules are those of WCAG 2.0, 2.1 and 2.2 at levels A and AA (`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`,
`wcag22aa`). A serious or critical violation fails the build unless `e2e/a11y/allowlist.json` names it, for the screens
it names, with the request that fixes it. A violation of the Content-Security-Policy of the runtime, reported by the
browser on one of the screens, fails too. The report is `e2e/authz/.work/axe.json`, uploaded by CI with the ZAP
report.

```bash
e2e/authz/run.sh up
e2e/authz/run.sh check          # A11Y_CHANNEL=msedge to use an installed Edge instead of the Chromium of Playwright
e2e/authz/run.sh down
```

### Results

On 18 November 2026, `main` with Quadrava 0.15.0:

| Screen | Violations | Serious or critical | Allowed |
| --- | ---: | ---: | --- |
| dashboard | 0 | 0 | |
| project-list | 0 | 0 | |
| project-detail | 0 | 0 | |
| issue-list | 0 | 0 | |
| issue-detail | 0 | 0 | |
| issue-new | 0 | 0 | |
| board | 0 | 0 | |
| backlog | 0 | 0 | |
| search | 0 | 0 | |
| notification-settings | 0 | 0 | |
| webhook-list | 0 | 0 | |
| my-sessions | 0 | 0 | |

No finding. Until Quadrava 0.14.0 a read-only `select` was drawn as disabled, about 1.6:1 where 4.5:1 is asked
(request Q19, released and adopted); the allow-list of `e2e/a11y` is empty. axe-core finds what can be found
automatically, about a third of the criteria; the manual check covers the rest.

## Manual check at each release

Done on the release candidate, in the development stack, with both themes and both languages. Each line is a pass, a
failure with its issue, or a request to Quadrava (Q) when the cause is a component.

### Keyboard only

- [ ] Every action of the 11 screens above is reachable with Tab, Shift+Tab, Enter, Space and the arrows.
- [ ] The focus is always visible and follows the order of the screen.
- [ ] No focus trap: dialogs, drawers, menus and the search suggestions close with Escape and give the focus back.
- [ ] The board: an issue moves to another column by keyboard.
- [ ] The backlog: an issue moves into a sprint and changes rank by keyboard.
- [ ] A skip link or landmark leads to the content past the navigation.

### Screen reader

With NVDA and Firefox (Windows) or VoiceOver and Safari (macOS):

- [ ] Creating an issue: each field is announced with its label, required state and errors.
- [ ] Editing an issue: the status, the assignee and the custom fields are announced with their values; a save or an
      error is announced.
- [ ] Comments: posting one and the mention suggestions.
- [ ] The headings of each screen form a sensible outline.

### Zoom and reflow

- [ ] Zoom at 200%: nothing is cut and no text overlaps.
- [ ] A width of 320 px: one column, no horizontal scroll except tables and the board. The actions of a form wrap
      since Quadrava 0.14.0 (request Q17).

### Contrast and colour

- [ ] Text 4.5:1 and large text and controls 3:1 in the light and the dark theme (`brand/` colours).
- [ ] No information by colour alone: statuses, priorities and the lozenges of the board have a text.

### Results

| Release | Date | Result |
| --- | --- | --- |
| 1.0.0-rc.1 | 24 November 2026 | Failed: four requests to Quadrava (Q31 to Q34); one issue here, #41, fixed; the screen reader lines are not done yet. Details below. With Quadrava 0.20.0 Q31 to Q34 pass; the badges of the board in the dark theme remain (Q35) |

#### 1.0.0-rc.1, 24 November 2026

Done on `main` after the tag (commit `109288f`, Quadrava 0.19.1), in the development stack, at 1280 px and 320 px, in
Italian, with the keyboard of the browser of the desktop app on Windows; the focus order was logged with a `focusin`
listener and contrast measured on every visible text of the 11 screens against its real background. The next
candidate repeats it.

Keyboard only:

- Failed, **Q31** (#37): the sorting and the filters of the tables (dashboard, issues, backlog, search) are not
  reachable; everything else is: the side bar, the forms, the row and bulk actions, the group rows, the tabs (arrows),
  the dialogs and the menus.
- Failed, **Q32** (#38): buttons and cell links have only the browser's default focus ring, amber on this machine and
  2.4:1 on white; the order follows the screen.
- Failed, **Q32** (#38): dialogs keep the focus inside and close with Escape, but give it to the page instead of the
  button that opened them. The menus of the board and the search suggestions (Escape closes only the list) pass.
- Passed: on the board, the «Sposta … in…» button of a card lists the other columns and «Sposta su», «Sposta giù»;
  Escape gives the focus back to the card.
- Passed: in the backlog a row goes into a sprint («Sposta nello sprint…», alone or with the selection) and to the top or
  the bottom by keyboard; the selection checkboxes have no name (Q33).
- Passed: no skip link, but a `main` landmark and six stops before the content.

Screen reader (checked from the accessibility tree; NVDA or VoiceOver still to do):

- Failed, **Q33** (#39): creating an issue, each field has its label and an error is a `role=alert`, but the required
  state is not exposed and the error is not linked to its field; the close button of a dialog is «Close» in Italian;
  the title of the browser tab never names the screen.
- Not done: editing an issue and comments, which need a screen reader to judge.
- Passed: the headings form an outline on every screen (H1 the screen, H2 its sections). The issue page said only
  «Issue»; since #41 its heading has the key («Issue WEB-4»).

Zoom and reflow: passed. At 320 px the dashboard, the backlog and the issue page have one column and no horizontal
scroll (the tabs that do not fit go into «⋯»); 200% of 1280 px is wider than that.

Contrast and colour:

- Light theme: passed, no text below 4.5:1 (3:1 when large) on the 11 screens.
- Dark theme: failed, **Q34** (#40): the colours of NG-ZORRO's dark stylesheet win over those of the theme, so buttons,
  the active tab and the processing tags are NG-ZORRO blue (white on them 4.20:1, the tags 4.1:1) and danger buttons
  2.31:1.
- Passed: statuses, priorities and the cards of the board always have a text.

#### Quadrava 0.20.0, 24 November 2026

The failed lines checked again on `main` with Quadrava 0.20.0, which releases Q31 to Q34, in the development stack, in
Italian and in both themes:

- Passed, Q31: the titles of the sortable columns are buttons (Enter sorts, `aria-sort` follows) and each filter is a
  button «Filter <column>»; Enter opens its panel with the focus on its field, Escape closes it with the focus back.
- Passed, Q32: a teal ring (`--lc-focus-ring`, #0F766E) on buttons, links, sorts and filters; after Escape the focus is
  back on the «Sposta nello sprint…» that opened the dialog.
- Passed, Q33: the selection checkboxes are named («Select WEB-2», «Select the visible rows»), the three required
  fields of a new issue carry `aria-required`, an error is linked to its field (`aria-describedby`, `aria-invalid`),
  the close button of a dialog is «Chiudi», and the browser tab reads «Backlog · OpenDevTrack».
- Passed, Q34: the dark theme has its own colours and background, and no text below 4.5:1 on ten screens (dashboard,
  projects, a project, issues, an issue, a new issue, backlog, search, webhooks, notification settings).
- Failed, **Q35** (#42): on the board in the dark theme the badges of the cards (type, priority) are white on the light
  text colours of the theme, 2.56:1 and 2.77:1; in the light theme they pass.
- Found along the way, **Q36** (#43): the chosen theme is not remembered, so a reload or the next sign-in shows the
  light theme again.

## Where a problem goes

- In a component of Quadrava: a request (Q) in [the requests](README.md#requests-to-the-dependencies), with the screen
  that shows it. OpenDevTrack does not work around it in its screens.
- In `ui/`: fixed there.
- In the brand colours: fixed in `brand/` first.
