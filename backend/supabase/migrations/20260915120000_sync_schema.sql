-- Multi-device sync — schema, RLS and functions.
--
-- Rules that run through the whole file:
--   * Every row belongs to a user (`user_id`), and deleting the account deletes everything in cascade.
--   * The client only reads its own rows (RLS) and never writes to a table directly: writes go
--     through the `sync_*` functions, which are `security definer` and always filter by `auth.uid()`.
--   * The backend receives no credentials, addresses or pixels. It receives the edits' JSON, the
--     originals' SHA-1 and, for recipes of photos on the device, recognition hints.

create schema if not exists private;
revoke all on schema private from public;

-- A single counter for every entity: the order of arrival at the backend, which is each device's
-- cursor. It is reassigned on every write, so `seq > cursor` also catches changes to old records.
create sequence public.sync_seq;

-- ---------------------------------------------------------------------------------------------
-- Aparelhos e bibliotecas
-- ---------------------------------------------------------------------------------------------

create table public.devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users on delete cascade,
    name text not null check (char_length(name) between 1 and 80),
    platform text not null default 'android' check (char_length(platform) between 1 and 20),
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);
create index devices_user_id_idx on public.devices (user_id);

-- A library's identity is the account in the photo service, never the address. For Immich,
-- `account_fingerprint` is an HMAC of the account id keyed by `user_id`, computed on the device.
create table public.libraries (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users on delete cascade,
    provider text not null check (provider ~ '^[a-z][a-z0-9_-]{0,31}$'),
    account_fingerprint text not null check (char_length(account_fingerprint) between 1 and 200),
    display_name text not null default '' check (char_length(display_name) <= 80),
    device_id uuid null references public.devices on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, provider, account_fingerprint)
);

-- ---------------------------------------------------------------------------------------------
-- Syncable entities
--
-- They all have the same shape — `key`, `payload` and the sync columns — so that the protocol is
-- a single one. What is specific to each entity comes out of `payload` in generated columns.
-- Deletion marks keep the last `payload`: it is what feeds the key's generated columns.
-- ---------------------------------------------------------------------------------------------

create table public.recipes (
    user_id uuid not null references auth.users on delete cascade,
    key text not null check (key ~ '^[0-9a-f]{40}$'),               -- SHA-1 do original
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    hints jsonb null check (hints is null or jsonb_typeof(hints) = 'object'),
    schema_version int generated always as ((payload ->> 'schemaVersion')::int) stored,
    process_version int generated always as ((payload ->> 'processVersion')::int) stored,
    revision bigint not null default 1,
    seq bigint not null default nextval('public.sync_seq'),
    edited_at timestamptz not null,
    edited_by_device uuid null,
    deleted_at timestamptz null,
    primary key (user_id, key)
);

create table public.derived_assets (
    user_id uuid not null references auth.users on delete cascade,
    key text not null,                                              -- <library_id>/<derived_asset_id>
    payload jsonb not null check (
        jsonb_typeof(payload) = 'object'
        and payload ->> 'libraryId' ~ '^[0-9a-f-]{36}$'
        and char_length(payload ->> 'derivedAssetId') between 1 and 200
        and char_length(payload ->> 'originalAssetId') between 1 and 200
    ),
    library_id uuid generated always as ((payload ->> 'libraryId')::uuid) stored
        references public.libraries on delete cascade,
    derived_asset_id text generated always as (payload ->> 'derivedAssetId') stored,
    original_asset_id text generated always as (payload ->> 'originalAssetId') stored,
    revision bigint not null default 1,
    seq bigint not null default nextval('public.sync_seq'),
    edited_at timestamptz not null,
    edited_by_device uuid null,
    deleted_at timestamptz null,
    primary key (user_id, key),
    unique (library_id, derived_asset_id),
    check (key = library_id::text || '/' || derived_asset_id)
);

create table public.saved_recipes (
    user_id uuid not null references auth.users on delete cascade,
    key text not null check (char_length(key) between 1 and 200),
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    revision bigint not null default 1,
    seq bigint not null default nextval('public.sync_seq'),
    edited_at timestamptz not null,
    edited_by_device uuid null,
    deleted_at timestamptz null,
    primary key (user_id, key)
);

create table public.composition_templates (like public.saved_recipes including all);
create table public.composition_projects (like public.saved_recipes including all);
alter table public.composition_templates
    add foreign key (user_id) references auth.users on delete cascade;
alter table public.composition_projects
    add foreign key (user_id) references auth.users on delete cascade;

create table public.brand_kits (
    user_id uuid not null references auth.users on delete cascade,
    key text not null default 'brand-kit' check (key = 'brand-kit'),  -- one per user
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    revision bigint not null default 1,
    seq bigint not null default nextval('public.sync_seq'),
    edited_at timestamptz not null,
    edited_by_device uuid null,
    deleted_at timestamptz null,
    primary key (user_id, key)
);

create index recipes_seq_idx on public.recipes (user_id, seq);
create index derived_assets_seq_idx on public.derived_assets (user_id, seq);
create index saved_recipes_seq_idx on public.saved_recipes (user_id, seq);
create index composition_templates_seq_idx on public.composition_templates (user_id, seq);
create index composition_projects_seq_idx on public.composition_projects (user_id, seq);
create index brand_kits_seq_idx on public.brand_kits (user_id, seq);

-- The previous versions of each record. `superseded` is written by the trigger on every change, up
-- to 20 per record; `conflict` is the version that lost a conflict, sent by the device that resolved
-- it, and has a `seq` so the other devices receive it.
create table public.revisions (
    id bigint generated always as identity primary key,
    user_id uuid not null references auth.users on delete cascade,
    entity text not null,
    key text not null,
    revision bigint not null,
    payload jsonb not null,
    edited_at timestamptz not null,
    edited_by_device uuid null,
    reason text not null check (reason in ('superseded', 'conflict')),
    created_at timestamptz not null default now(),
    seq bigint not null default nextval('public.sync_seq')
);
create index revisions_record_idx on public.revisions (user_id, entity, key, reason, id);
create index revisions_seq_idx on public.revisions (user_id, seq);

-- Realtime's wake-up signal: a small row per entity, instead of sending payloads of up to 1 MB over
-- the websocket. The device that receives it pulls.
create table public.sync_signals (
    user_id uuid not null references auth.users on delete cascade,
    entity text not null,
    seq bigint not null,
    primary key (user_id, entity)
);

-- ---------------------------------------------------------------------------------------------
-- Access: each user reads what is theirs; nobody writes directly
-- ---------------------------------------------------------------------------------------------

do $$
declare
    t text;
begin
    foreach t in array array[
        'devices', 'libraries', 'recipes', 'derived_assets', 'saved_recipes',
        'composition_templates', 'composition_projects', 'brand_kits', 'revisions', 'sync_signals'
    ] loop
        execute format('alter table public.%I enable row level security', t);
        execute format('revoke all on public.%I from anon, authenticated', t);
        execute format('grant select on public.%I to authenticated', t);
        execute format(
            'create policy %I on public.%I for select to authenticated using (user_id = (select auth.uid()))',
            t || '_select_own', t
        );
    end loop;
end
$$;

-- Removing a device is the only direct write: the row is the user's and has no history.
grant delete on public.devices to authenticated;
create policy devices_delete_own on public.devices
    for delete to authenticated using (user_id = (select auth.uid()));

revoke all on sequence public.sync_seq from anon, authenticated;

-- ---------------------------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------------------------

create function private.keep_revision() returns trigger
    language plpgsql security definer set search_path = '' as $$
begin
    insert into public.revisions (user_id, entity, key, revision, payload, edited_at, edited_by_device, reason)
    values (old.user_id, tg_argv[0], old.key, old.revision, old.payload, old.edited_at, old.edited_by_device, 'superseded');
    delete from public.revisions
    where id in (
        select id from public.revisions
        where user_id = old.user_id and entity = tg_argv[0] and key = old.key and reason = 'superseded'
        order by id desc
        offset 20
    );
    return new;
end
$$;

create function private.signal_change() returns trigger
    language plpgsql security definer set search_path = '' as $$
begin
    insert into public.sync_signals (user_id, entity, seq)
    values (new.user_id, tg_argv[0], new.seq)
    on conflict (user_id, entity) do update set seq = excluded.seq;
    return null;
end
$$;

do $$
declare
    pair text[];
begin
    foreach pair slice 1 in array array[
        array['recipes', 'RECIPE'],
        array['derived_assets', 'DERIVED_ASSET'],
        array['saved_recipes', 'SAVED_RECIPE'],
        array['composition_templates', 'TEMPLATE'],
        array['composition_projects', 'PROJECT'],
        array['brand_kits', 'BRAND_KIT']
    ] loop
        execute format(
            'create trigger keep_revision before update on public.%I for each row execute function private.keep_revision(%L)',
            pair[1], pair[2]
        );
        execute format(
            'create trigger signal_change after insert or update on public.%I for each row execute function private.signal_change(%L)',
            pair[1], pair[2]
        );
    end loop;
end
$$;

create trigger signal_change after insert on public.revisions
    for each row when (new.reason = 'conflict') execute function private.signal_change('RECIPE_CONFLICT');

-- Realtime only exists on Supabase; on other Postgres instances (tests) the publication is not there.
do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        alter publication supabase_realtime add table public.sync_signals;
    end if;
end
$$;

-- ---------------------------------------------------------------------------------------------
-- Functions the app calls
-- ---------------------------------------------------------------------------------------------

create function private.sync_table(p_entity text) returns text
    language sql immutable set search_path = '' as $$
    select case p_entity
        when 'RECIPE' then 'recipes'
        when 'DERIVED_ASSET' then 'derived_assets'
        when 'SAVED_RECIPE' then 'saved_recipes'
        when 'TEMPLATE' then 'composition_templates'
        when 'PROJECT' then 'composition_projects'
        when 'BRAND_KIT' then 'brand_kits'
    end
$$;

create function private.require_user() returns uuid
    language plpgsql stable set search_path = '' as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null then
        raise exception 'Sessão em falta.' using errcode = '28000';
    end if;
    return v_user;
end
$$;

-- Registers this device in the account. Every installation is a new device.
create function public.sync_register_device(p_name text, p_platform text default 'android')
    returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_id uuid;
begin
    insert into public.devices (user_id, name, platform)
    values (private.require_user(), p_name, p_platform)
    returning id into v_id;
    return v_id;
end
$$;

-- Says the device is alive and, if a name comes, changes it.
create function public.sync_touch_device(p_device uuid, p_name text default null)
    returns void language plpgsql security definer set search_path = '' as $$
begin
    update public.devices
    set last_seen_at = now(), name = coalesce(p_name, name)
    where id = p_device and user_id = private.require_user();
    if not found then
        raise exception 'Aparelho desconhecido nesta conta.' using errcode = 'P0002';
    end if;
end
$$;

-- The remote library for this photo account, created the first time a device links it.
create function public.sync_link_library(
    p_provider text, p_fingerprint text, p_display_name text, p_device uuid default null
) returns uuid language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := private.require_user();
    v_id uuid;
begin
    if p_device is not null and not exists (
        select 1 from public.devices where id = p_device and user_id = v_user
    ) then
        raise exception 'Aparelho desconhecido nesta conta.' using errcode = 'P0002';
    end if;
    insert into public.libraries (user_id, provider, account_fingerprint, display_name, device_id)
    values (v_user, p_provider, p_fingerprint, coalesce(p_display_name, ''), p_device)
    on conflict (user_id, provider, account_fingerprint)
        do update set display_name = excluded.display_name, updated_at = now()
    returning id into v_id;
    return v_id;
end
$$;

-- Sends up to 50 changes of one entity and returns one result per change, in the same order.
--
-- Each change: {key, baseRevision, payload, editedAt, deviceId, deleted, hints}.
-- Results:
--   {status: "applied",  key, revision, seq}
--   {status: "conflict", key, remote: {...}}  — nothing was saved; the sender resolves it
--   {status: "rejected", key, reason}         — the change is invalid and not worth retrying
--
-- A change applies if the record does not exist or if `baseRevision` is the current revision. An
-- invalid change does not stop the others in the same batch.
create function public.sync_push(p_entity text, p_changes jsonb)
    returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := private.require_user();
    v_table text := private.sync_table(p_entity);
    v_results jsonb := '[]'::jsonb;
    v_change jsonb;
    v_key text;
    v_base bigint;
    v_payload jsonb;
    v_hints jsonb;
    v_edited timestamptz;
    v_device uuid;
    v_deleted boolean;
    v_current jsonb;
    v_revision bigint;
    v_seq bigint;
begin
    if v_table is null then
        raise exception 'Entidade desconhecida: %', p_entity using errcode = '22023';
    end if;
    if jsonb_typeof(p_changes) is distinct from 'array' or jsonb_array_length(p_changes) > 50 then
        raise exception 'Envie uma lista de até 50 alterações.' using errcode = '22023';
    end if;

    for v_change in select value from jsonb_array_elements(p_changes) loop
        v_key := v_change ->> 'key';
        begin
            v_base := (v_change ->> 'baseRevision')::bigint;
            v_payload := v_change -> 'payload';
            v_hints := case when jsonb_typeof(v_change -> 'hints') = 'object' then v_change -> 'hints' end;
            v_device := (v_change ->> 'deviceId')::uuid;
            v_deleted := coalesce((v_change ->> 'deleted')::boolean, false);
            -- The device's clock decides who wins; one that is in the future cannot win everything
            -- that comes after.
            v_edited := (v_change ->> 'editedAt')::timestamptz;
            if v_edited is null or v_edited > now() + interval '5 minutes' then
                v_edited := now();
            end if;

            if v_key is null or jsonb_typeof(v_payload) is distinct from 'object' then
                v_results := v_results || jsonb_build_object('status', 'rejected', 'key', v_key, 'reason', 'invalid_change');
                continue;
            end if;
            if octet_length(v_payload::text) > 1048576 then
                v_results := v_results || jsonb_build_object('status', 'rejected', 'key', v_key, 'reason', 'payload_too_large');
                continue;
            end if;
            if not exists (select 1 from public.devices where id = v_device and user_id = v_user) then
                v_results := v_results || jsonb_build_object('status', 'rejected', 'key', v_key, 'reason', 'unknown_device');
                continue;
            end if;
            if p_entity = 'DERIVED_ASSET' and not exists (
                select 1 from public.libraries
                where user_id = v_user and id::text = v_payload ->> 'libraryId'
            ) then
                v_results := v_results || jsonb_build_object('status', 'rejected', 'key', v_key, 'reason', 'unknown_library');
                continue;
            end if;

            execute format(
                'select to_jsonb(t) - ''user_id'' from public.%I t where user_id = $1 and key = $2 for update',
                v_table
            ) into v_current using v_user, v_key;

            if v_current is not null and (v_current ->> 'revision')::bigint is distinct from v_base then
                v_results := v_results || jsonb_build_object('status', 'conflict', 'key', v_key, 'remote', v_current);
                continue;
            end if;

            -- Only recipes have hints; in the other tables the column does not exist and `$7` goes unused.
            if v_current is null then
                execute format(
                    'insert into public.%I (user_id, key, payload, edited_at, edited_by_device, deleted_at%s)
                     values ($1, $2, $3, $4, $5, $6%s) returning revision, seq',
                    v_table,
                    case when v_table = 'recipes' then ', hints' else '' end,
                    case when v_table = 'recipes' then ', $7' else '' end
                ) into v_revision, v_seq
                using v_user, v_key, v_payload, v_edited, v_device, case when v_deleted then v_edited end, v_hints;
            else
                execute format(
                    'update public.%I
                     set payload = $3, revision = revision + 1, seq = nextval(''public.sync_seq''),
                         edited_at = $4, edited_by_device = $5, deleted_at = $6%s
                     where user_id = $1 and key = $2 returning revision, seq',
                    v_table,
                    case when v_table = 'recipes' then ', hints = $7' else '' end
                ) into v_revision, v_seq
                using v_user, v_key, v_payload, v_edited, v_device, case when v_deleted then v_edited end, v_hints;
            end if;

            v_results := v_results || jsonb_build_object('status', 'applied', 'key', v_key, 'revision', v_revision, 'seq', v_seq);
        exception
            -- Two devices creating the same record at the same time: the second one sees a conflict.
            when unique_violation then
                execute format('select to_jsonb(t) - ''user_id'' from public.%I t where user_id = $1 and key = $2', v_table)
                    into v_current using v_user, v_key;
                v_results := v_results || jsonb_build_object('status', 'conflict', 'key', v_key, 'remote', v_current);
            when check_violation or not_null_violation or invalid_text_representation
                or foreign_key_violation or datetime_field_overflow or invalid_datetime_format then
                v_results := v_results || jsonb_build_object('status', 'rejected', 'key', v_key, 'reason', 'invalid_change');
        end;
    end loop;
    return v_results;
end
$$;

-- Stores the version that lost a conflict. Only the recipe has a history with an interface, but
-- any entity can store its own.
create function public.sync_record_conflict(
    p_entity text, p_key text, p_revision bigint, p_payload jsonb, p_edited_at timestamptz, p_device uuid
) returns bigint language plpgsql security definer set search_path = '' as $$
declare
    v_user uuid := private.require_user();
    v_id bigint;
begin
    if private.sync_table(p_entity) is null then
        raise exception 'Entidade desconhecida: %', p_entity using errcode = '22023';
    end if;
    if jsonb_typeof(p_payload) is distinct from 'object' or octet_length(p_payload::text) > 1048576 then
        raise exception 'Versão inválida.' using errcode = '22023';
    end if;
    insert into public.revisions (user_id, entity, key, revision, payload, edited_at, edited_by_device, reason)
    values (v_user, p_entity, p_key, coalesce(p_revision, 0), p_payload, least(p_edited_at, now() + interval '5 minutes'), p_device, 'conflict')
    returning id into v_id;
    delete from public.revisions
    where id in (
        select id from public.revisions
        where user_id = v_user and entity = p_entity and key = p_key and reason = 'conflict'
        order by id desc
        offset 20
    );
    return v_id;
end
$$;

-- What changed in an entity since the cursor, in `seq` order, deletion marks included.
-- `RECIPE_CONFLICT` returns the versions lost in recipe conflicts.
-- Result: {rows: [...], cursor: <seq of the last row or the cursor received>}.
create function public.sync_pull(p_entity text, p_cursor bigint, p_limit int default 500)
    returns jsonb language plpgsql security definer set search_path = '' stable as $$
declare
    v_user uuid := private.require_user();
    v_table text := private.sync_table(p_entity);
    v_limit int := greatest(1, least(coalesce(p_limit, 500), 500));
    v_rows jsonb;
begin
    if p_entity = 'RECIPE_CONFLICT' then
        select coalesce(jsonb_agg(r order by (r ->> 'seq')::bigint), '[]'::jsonb) into v_rows
        from (
            select to_jsonb(v) - 'user_id' as r
            from public.revisions v
            where v.user_id = v_user and v.entity = 'RECIPE' and v.reason = 'conflict' and v.seq > coalesce(p_cursor, 0)
            order by v.seq
            limit v_limit
        ) page;
    elsif v_table is null then
        raise exception 'Entidade desconhecida: %', p_entity using errcode = '22023';
    else
        execute format(
            'select coalesce(jsonb_agg(r order by (r ->> ''seq'')::bigint), ''[]''::jsonb) from (
                 select to_jsonb(t) - ''user_id'' as r from public.%I t
                 where t.user_id = $1 and t.seq > $2 order by t.seq limit $3
             ) page',
            v_table
        ) into v_rows using v_user, coalesce(p_cursor, 0), v_limit;
    end if;
    return jsonb_build_object(
        'rows', v_rows,
        'cursor', coalesce((v_rows -> -1 ->> 'seq')::bigint, coalesce(p_cursor, 0))
    );
end
$$;

revoke all on function
    public.sync_register_device(text, text),
    public.sync_touch_device(uuid, text),
    public.sync_link_library(text, text, text, uuid),
    public.sync_push(text, jsonb),
    public.sync_record_conflict(text, text, bigint, jsonb, timestamptz, uuid),
    public.sync_pull(text, bigint, int)
from public, anon;
grant execute on function
    public.sync_register_device(text, text),
    public.sync_touch_device(uuid, text),
    public.sync_link_library(text, text, text, uuid),
    public.sync_push(text, jsonb),
    public.sync_record_conflict(text, text, bigint, jsonb, timestamptz, uuid),
    public.sync_pull(text, bigint, int)
to authenticated;
revoke all on all functions in schema private from public, anon, authenticated;
