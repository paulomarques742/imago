-- The minimum of Supabase the migrations depend on, to test them on a Postgres without Supabase.
-- `auth.uid()` reads `sub` from the request claims, like the real function.

create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;

create schema auth;
create table auth.users (id uuid primary key default gen_random_uuid(), email text);

create function auth.uid() returns uuid language sql stable as $$
    select coalesce(
        nullif(current_setting('request.jwt.claim.sub', true), ''),
        nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub'
    )::uuid
$$;

grant usage on schema public, auth to anon, authenticated, service_role;
grant execute on function auth.uid() to anon, authenticated;

-- Supabase grants every privilege on new tables to anon and authenticated by default. The
-- migrations have to revoke them, and the test only proves that if it starts from the same place.
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;

-- Storage: buckets and objects, with RLS on and the privileges Supabase grants. The Storage API
-- writes with the caller's role, and that is what the tests mimic.
create schema storage;
create table storage.buckets (
    id text primary key,
    name text not null,
    public boolean default false,
    file_size_limit bigint,
    allowed_mime_types text[]
);
create table storage.objects (
    id uuid primary key default gen_random_uuid(),
    bucket_id text references storage.buckets,
    name text not null,
    owner uuid,
    created_at timestamptz not null default now(),
    unique (bucket_id, name)
);
alter table storage.objects enable row level security;
grant usage on schema storage to anon, authenticated, service_role;
grant all on storage.objects to anon, authenticated, service_role;
grant select on storage.buckets to anon, authenticated, service_role;
