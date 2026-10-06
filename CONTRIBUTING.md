# Contributing to IMAGO

Thank you for helping. Bug reports, fixes and new tools are all welcome.

## How changes land

This repository is published from the maintainers' working repository. Pull requests are reviewed
here, but they are not merged with GitHub's button: an accepted pull request is applied upstream
with your authorship kept, and it comes back to this repository in the next mirrored commit. The
pull request is then closed with a link to that commit.

Every commit must be signed off under the
[Developer Certificate of Origin](https://developercertificate.org/):

```sh
git commit -s
```

By signing off, you also agree that your contribution is licensed under the same terms as the
rest of the code: the GNU Affero General Public License version 3 with the additional permission
stated in [NOTICE](NOTICE).

## Conventions

- **English** for code, comments, test names, commit messages and documentation.
- **Comments explain why**, not what: the code already says what.
- **No user-visible text in Kotlin.** Strings go to the module's
  `composeResources/values/strings.xml` in English, with the Portuguese translation in
  `values-pt/`. Use the typographic apostrophe `’`, never `\'`. The data layer does not write
  sentences: it throws `UserMessageException(UserMessage.X)`. The tests in
  `:core:designsystem:desktopTest` check this.
- **Only what works is shown.** A tool that is not finished is left out of the interface, not
  shown disabled.
- **Immich endpoints are never written by hand** in the client: `:core:immich:generateImmichContract`
  extracts them from the OpenAPI specifications in `open-api/` and checks that they match across
  the pinned versions.
- **Behaviour changes come with a test.**
- **No secrets** in any file: no Supabase secret keys, keystores or `local.properties`.

## Before opening a pull request

```sh
./gradlew testDebugUnitTest :desktop:test
cd backend/tests && npm test   # if you changed backend/
```
