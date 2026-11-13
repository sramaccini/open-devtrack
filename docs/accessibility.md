| backlog | 0 | 0 | || issue-detail | 0 | 0 | || issue-list | 0 | 0 | || project-detail | 0 | 0 | |# Accessibility, WCAG 2.2 AA

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
it names, with the request that fixes it. The report is `e2e/authz/.work/axe.json`, uploaded by CI with the ZAP
report.

```bash
e2e/authz/run.sh up
e2e/authz/run.sh check          # A11Y_CHANNEL=msedge to use an installed Edge instead of the Chromium of Playwright
e2e/authz/run.sh down
```

### Results

On 13 November 2026, `main` with Quadrava 0.14.0:

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
| 1.0 | | Not done yet: the first manual check is for the first release candidate |

## Where a problem goes

- In a component of Quadrava: a request (Q) in [the requests](README.md#requests-to-the-dependencies), with the screen
  that shows it. OpenDevTrack does not work around it in its screens.
- In `ui/`: fixed there.
- In the brand colours: fixed in `brand/` first.
