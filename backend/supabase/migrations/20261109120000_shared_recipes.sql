-- A recipe published under a link (RECEITAS-09).
--
-- The link is a snapshot: editing the recipe afterwards changes nothing until its owner updates the
-- link. Nobody touches the table directly: creating, updating and revoking go through functions that
-- filter by auth.uid(), and reading goes through one that is open to anon and only sees live links.
-- Whoever has the link can read the recipe, which is why the identifier cannot be guessed.

create table public.shared_recipes (
    id text primary key,
    owner_id uuid not null references auth.users on delete cascade,
    recipe_id text not null,
    content jsonb not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    revoked_at timestamptz
);

-- One live link per recipe: sharing it again is the same link.
create unique index shared_recipes_live_idx on public.shared_recipes (owner_id, recipe_id) where revoked_at is null;

alter table public.shared_recipes enable row level security;
revoke all on public.shared_recipes from anon, authenticated;

-- Far above any real recipe, brush strokes included, and low enough that a public table stays small.
create function private.shared_recipe_check(p_content jsonb) returns void
    language plpgsql immutable set search_path = '' as $$
begin
    if octet_length(p_content::text) > 1048576 then
        raise exception 'The recipe is too large.' using errcode = '22023', hint = 'shared_recipe_too_large';
    end if;
    if jsonb_typeof(p_content) is distinct from 'object'
        or p_content ->> 'format' is distinct from 'imago'
        or p_content ->> 'kind' is distinct from 'recipes'
        or jsonb_typeof(p_content -> 'recipes') is distinct from 'array'
        or jsonb_array_length(p_content -> 'recipes') <> 1 then
        raise exception 'Not one recipe.' using errcode = '22023', hint = 'shared_recipe_invalid';
    end if;
end
$$;

-- 154 random bits, from two UUIDs because gen_random_uuid() is the source every Postgres here has.
-- A UUID alone is 122, under the 128 this identifier has to have.
create function private.shared_recipe_new_id() returns text
    language sql volatile set search_path = '' as $$
    select translate(
        rtrim(encode(decode(left(
            replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''), 40
        ), 'hex'), 'base64'), '='),
        '+/', '-_'
    )
$$;

create function private.shared_recipe_owner() returns uuid
    language plpgsql stable set search_path = '' as $$
declare
    v_owner uuid := (select auth.uid());
begin
    if v_owner is null then
        raise exception 'An account is needed.' using errcode = '42501', hint = 'shared_recipe_no_account';
    end if;
    return v_owner;
end
$$;

create function public.shared_recipe_create(p_recipe_id text, p_content jsonb) returns text
    language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := private.shared_recipe_owner();
    v_id text;
begin
    if p_recipe_id is null or length(p_recipe_id) not between 1 and 200 then
        raise exception 'Invalid recipe.' using errcode = '22023', hint = 'shared_recipe_invalid';
    end if;
    perform private.shared_recipe_check(p_content);
    -- Two devices at once must not both slip under the limit.
    perform pg_advisory_xact_lock(hashtextextended(v_owner::text, 742));

    select id into v_id from public.shared_recipes
        where owner_id = v_owner and recipe_id = p_recipe_id and revoked_at is null;
    if v_id is not null then
        return v_id;
    end if;
    if (select count(*) from public.shared_recipes where owner_id = v_owner and revoked_at is null) >= 20 then
        raise exception 'Too many links.' using errcode = 'P0001', hint = 'shared_recipe_limit';
    end if;

    v_id := private.shared_recipe_new_id();
    insert into public.shared_recipes (id, owner_id, recipe_id, content) values (v_id, v_owner, p_recipe_id, p_content);
    return v_id;
end
$$;

create function public.shared_recipe_update(p_id text, p_content jsonb) returns void
    language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := private.shared_recipe_owner();
begin
    perform private.shared_recipe_check(p_content);
    update public.shared_recipes set content = p_content, updated_at = now()
        where id = p_id and owner_id = v_owner and revoked_at is null;
    if not found then
        raise exception 'No such link.' using errcode = 'P0002', hint = 'shared_recipe_not_found';
    end if;
end
$$;

create function public.shared_recipe_revoke(p_id text) returns void
    language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := private.shared_recipe_owner();
begin
    update public.shared_recipes set revoked_at = now(), content = '{}'::jsonb
        where id = p_id and owner_id = v_owner and revoked_at is null;
    if not found then
        raise exception 'No such link.' using errcode = 'P0002', hint = 'shared_recipe_not_found';
    end if;
end
$$;

-- What the person has shared, without the content: the menu only needs to know which recipes.
create function public.shared_recipe_mine() returns table (id text, recipe_id text, updated_at timestamptz)
    language plpgsql stable security definer set search_path = '' as $$
declare
    v_owner uuid := private.shared_recipe_owner();
begin
    return query select s.id, s.recipe_id, s.updated_at from public.shared_recipes s
        where s.owner_id = v_owner and s.revoked_at is null order by s.created_at;
end
$$;

-- Null for a link that does not exist and for one that was revoked: the two look the same from outside.
create function public.shared_recipe_get(p_id text) returns jsonb
    language sql stable security definer set search_path = '' as $$
    select content from public.shared_recipes where id = p_id and revoked_at is null
$$;

revoke all on function
    public.shared_recipe_create(text, jsonb),
    public.shared_recipe_update(text, jsonb),
    public.shared_recipe_revoke(text),
    public.shared_recipe_mine(),
    public.shared_recipe_get(text)
    from public, anon, authenticated;
grant execute on function
    public.shared_recipe_create(text, jsonb),
    public.shared_recipe_update(text, jsonb),
    public.shared_recipe_revoke(text),
    public.shared_recipe_mine()
    to authenticated;
grant execute on function public.shared_recipe_get(text) to anon, authenticated;
revoke all on all functions in schema private from public, anon, authenticated;
