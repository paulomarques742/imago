# Sync backend (Supabase)

The schema behind IMAGO's multi-device sync. Everything in the Supabase project is created by the
migrations in this folder: nothing is made by hand in the dashboard.

```
supabase/migrations/   SQL applied in order by the Supabase CLI
supabase/functions/    Edge Functions
supabase/templates/    account emails
tests/                 migration tests on an in-memory Postgres (PGlite), without Docker
```

## Testing

```sh
cd backend/tests
npm install
npm test
```

The tests load `tests/supabase-stub.sql` — `auth.users`, `auth.uid()` and the `anon` and
`authenticated` roles, with the default privileges Supabase grants — and then every migration.
They cover the send and receive protocol, conflicts, history, deletion marks and the main privacy
invariant: no user reads or writes another user's data.

## Applying to a project

With the [Supabase CLI](https://supabase.com/docs/guides/cli) (`npx supabase` works too):

```sh
cd backend
npx supabase login
npx supabase link --project-ref <project-ref>
npx supabase db push       # applies the migrations that are missing
```

## Deleting an account (Edge Function)

`supabase/functions/delete-account` deletes the user from `auth.users` with the service key, which
only exists on the server; the cascade deletes the rest. It only accepts a request made up to five
minutes after a password sign-in — the app asks for the password and signs in again first.

```sh
cd backend
npx supabase functions deploy delete-account --use-api
```

`--use-api` bundles the function on Supabase's servers instead of in a local container, so Docker
is not needed.

## Authentication (Supabase dashboard)

In **Authentication → Sign In / Providers → Email**: email and password enabled, *Confirm email*
on.

In **Authentication → URL Configuration → Redirect URLs**, add:

```
imago://auth/callback
imago://auth/recovery
```

The first is the account confirmation link; the second is password recovery. The app uses the
PKCE flow, so the link has to be opened on the device where it was requested.

### Account emails

The templates live in `supabase/templates/` and are the source: the dashboard holds a copy. In
**Authentication → Email Templates → Confirm signup**, the subject is `Confirm your IMAGO account`
and the body is the content of `templates/confirmation.html`, pasted as is. `config.toml` points to
the same file, but `supabase config push` is not used to send it: it would also push `site_url` and
the rest of the local configuration.

They are HTML for email clients — tables, inline styles, hex colours — and the symbol is made of
table cells, because Gmail shows neither SVG nor embedded images. Opening the file in a browser
shows the layout before pasting a change; what counts is a real email received in Gmail and
Outlook.

Supabase's default sender has tight sending limits and is not meant for production; a production
project needs its own SMTP (**Authentication → SMTP Settings**).

## In the app

In `local.properties` (outside git; see `local.properties.example` at the repository root):

```properties
SUPABASE_URL=https://<ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
# Optional, for release builds pointing at the prod project:
SUPABASE_RELEASE_URL=https://<ref-prod>.supabase.co
SUPABASE_RELEASE_PUBLISHABLE_KEY=sb_publishable_...
```

Without these lines the app compiles and works without an account: Settings says the account is
not available in this version.

## Keys

- **In the app:** only the project URL and the *publishable key* (`sb_publishable_…`). It is public
  by design; row-level security is what protects the data.
- **Never in the app or the repository:** the *secret key* (`sb_secret_…`) and the database
  password. They bypass row-level security.

## Protocol shape

Every syncable entity has the same shape — `key`, `payload`, `revision`, `seq`, `edited_at`,
`edited_by_device`, `deleted_at` — and the client only writes through functions:

| Function | What for |
|---|---|
| `sync_register_device(name, platform)` | Registers this installation; returns the device id. |
| `sync_touch_device(device, name?)` | Updates `last_seen_at` and, if given, the name. |
| `sync_link_library(provider, fingerprint, display_name, device?)` | Returns the remote library for that photo account, creating it if needed. |
| `sync_push(entity, changes)` | Up to 50 changes; one `applied`, `conflict` or `rejected` result per change. |
| `sync_record_conflict(entity, key, revision, payload, edited_at, device)` | Stores the version that lost a conflict. |
| `sync_pull(entity, cursor, limit)` | What changed since the cursor, in `seq` order. `RECIPE_CONFLICT` returns the lost versions of recipes. |

`entity` is `RECIPE`, `DERIVED_ASSET`, `SAVED_RECIPE`, `TEMPLATE`, `PROJECT` or `BRAND_KIT`.
`devices` and `libraries` are read directly from their tables (row-level security shows only the
account's own), and removing a device is a `delete` on `devices`. Realtime only publishes
`sync_signals`, one small row per entity that wakes the other devices.
