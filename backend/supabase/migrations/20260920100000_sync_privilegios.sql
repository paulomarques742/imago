-- Restores the sync privileges.
--
-- On the linked project `authenticated` was left without `execute` on the `sync_*` functions:
-- `sync_push` answered 42501 ("permission denied for function sync_push") and the app said the
-- account server had refused the request. Migration 20260915120000 is recorded as applied, but the
-- access block at its end never ran there.
--
-- This file repeats the whole access block of 20260915120000. Everything it does is idempotent and
-- on a correct project changes nothing; whatever is missing shows up as a `notice` when applying.

do $$
declare
    t text;
    p text;
begin
    foreach t in array array[
        'devices', 'libraries', 'recipes', 'derived_assets', 'saved_recipes',
        'composition_templates', 'composition_projects', 'brand_kits', 'revisions', 'sync_signals'
    ] loop
        if not (select relrowsecurity from pg_class where oid = format('public.%I', t)::regclass) then
            raise notice 'RLS desligada em public.%, a ligar.', t;
        end if;
        execute format('alter table public.%I enable row level security', t);
        execute format('revoke all on public.%I from anon, authenticated', t);
        execute format('grant select on public.%I to authenticated', t);

        p := t || '_select_own';
        if not exists (
            select 1 from pg_policies where schemaname = 'public' and tablename = t and policyname = p
        ) then
            raise notice 'Política % em falta, a criar.', p;
            execute format(
                'create policy %I on public.%I for select to authenticated using (user_id = (select auth.uid()))',
                p, t
            );
        end if;
    end loop;
end
$$;

-- Removing a device is the only direct write: the row is the user's and has no history.
grant delete on public.devices to authenticated;
do $$
begin
    if not exists (
        select 1 from pg_policies
        where schemaname = 'public' and tablename = 'devices' and policyname = 'devices_delete_own'
    ) then
        raise notice 'Política devices_delete_own em falta, a criar.';
        create policy devices_delete_own on public.devices
            for delete to authenticated using (user_id = (select auth.uid()));
    end if;
end
$$;

revoke all on sequence public.sync_seq from anon, authenticated;
revoke all on schema private from public;

-- The `sync_*` functions: only signed-in users call them. This is what was missing.
do $$
declare
    f text;
begin
    foreach f in array array[
        'public.sync_register_device(text, text)',
        'public.sync_touch_device(uuid, text)',
        'public.sync_link_library(text, text, text, uuid)',
        'public.sync_push(text, jsonb)',
        'public.sync_record_conflict(text, text, bigint, jsonb, timestamptz, uuid)',
        'public.sync_pull(text, bigint, int)'
    ] loop
        if not has_function_privilege('authenticated', f, 'execute') then
            raise notice 'O authenticated não podia chamar %, a dar execute.', f;
        end if;
        execute format('revoke all on function %s from public, anon', f);
        execute format('grant execute on function %s to authenticated', f);
    end loop;
end
$$;

revoke all on all functions in schema private from public, anon, authenticated;
