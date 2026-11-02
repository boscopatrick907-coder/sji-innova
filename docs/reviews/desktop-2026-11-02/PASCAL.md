# Pascal: publish translations and prepare GUI acceptance

**Status: not assessed, because no translation deliverables were published at the
review snapshot.** This does not mean you have not worked on your own computer.
Read your [original brief](../../team/PASCAL.md) and [full review](REVIEW.md).

## First deliverable: publish your work

- [ ] Preserve local work; check your branch and latest main before pulling.
- [ ] Publish your translation branch and open a PR for review when ready.
- [ ] Add both UTF-8 bundles under
      desktop/src/main/resources/cm/sji/innova/desktop/i18n/:
      messages_en.properties and messages_fr.properties.
- [ ] Include the product keys in your original task brief.
- [ ] Include Charles's shell.* keys listed in his docs/desktop/APP_SHELL.md file
      on PR #11; coordinate with him if those files are not on main yet.
- [ ] Add tests that load both languages, compare key sets and reject blank values.
- [ ] Agree any MessageFormat placeholders with Charles. Test French apostrophes
      and accented labels, especially patterns with inserted folder/shop values.

A --lang fr option and fallback English text are not a completed French interface.
Do not rewrite Java screens or database code just to supply resource bundles.

## Second deliverable: manual checks after integration

- [ ] Test the real window in both languages, including accents and long text.
- [ ] Check keyboard navigation and labels.
- [ ] Save a product with all details; close/reopen and verify them.
- [ ] Check unknown versus zero buying price and invalid/overflow price feedback.
- [ ] Check duplicate specification names and double-click Save behavior.
- [ ] Verify Charles's identity recovery and Willy's slow-save correction using
      safe developer-provided setups; never remove/corrupt a real shop's files.
- [ ] Record results in docs/qa/desktop-pilot.md: commit, OS/JDK, steps, expected
      result, actual result, passed/failed/unrun and screenshots where helpful.

Use disposable data. Never mark a check as passed unless you actually ran it.
Coordinate error simulations with developers; don't create destructive experiments.

## Evidence to fill after working

- Branch / PR:
- Resource tests and results:
- App commit / Windows version / JDK:
- GUI checks performed and results:
- Remaining missing keys / bugs / unrun checks:
