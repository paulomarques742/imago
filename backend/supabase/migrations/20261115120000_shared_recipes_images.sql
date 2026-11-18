-- A before and an after on a recipe link's page, and a way to report them (RECEITAS-13).
--
-- Only a supporter puts images on a link, into the gallery's bucket and the account's own folder, as for
-- the gallery. The images are a reduced JPEG of the photo, with the recipe on it and without it. Whoever
-- opens the link sees them, so anyone can report them, with no account: three different reporters hide
-- the images until the administrator looks, and the administrator can also hide them for good.
-- Hiding only takes the images off the page; the recipe and the link stay.

alter table public.shared_recipes
    add column before_image text,
    add column after_image text,
    add column images_hidden_at timestamptz,
    add column images_hidden_by text check (images_hidden_by in ('reports', 'admin'));

-- What is in the bucket and used by a link or a post is not an orphan: the owner cannot delete it.
create or replace function public.gallery_image_orphan(p_name text) returns boolean
    language sql stable security definer set search_path = '' as $$
    select auth.uid() is not null
       and split_part(p_name, '/', 1) = auth.uid()::text
       and not exists (select 1 from public.gallery_posts where p_name in (before_image, after_image))
       and not exists (select 1 from public.shared_recipes where p_name in (before_image, after_image))
$$;

create table private.shared_recipe_reports (
    link_id text not null references public.shared_recipes (id) on delete cascade,
    -- The account, or a hash of the address for someone without one: the address itself is never kept.
    reporter text not null,
    reason text not null check (reason in ('sexual', 'violence', 'harassment', 'illegal', 'privacy', 'copyright', 'other')),
    created_at timestamptz not null default now(),
    primary key (link_id, reporter)
);
create index shared_recipe_reports_reporter_idx on private.shared_recipe_reports (reporter, created_at);

-- ---------------------------------------------------------------------------------------------
-- The owner
-- ---------------------------------------------------------------------------------------------

-- Sets the two images of a link of the account's own, already uploaded, or takes them off (both null).
-- Returns the images it replaces, for the app to delete them from the bucket.
create function public.shared_recipe_set_images(p_id text, p_before text, p_after text) returns text[]
    language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := private.shared_recipe_owner();
    v_row public.shared_recipes;
begin
    select * into v_row from public.shared_recipes where id = p_id and owner_id = v_owner and revoked_at is null for update;
    if not found then
        raise exception 'No such link.' using errcode = 'P0002', hint = 'shared_recipe_not_found';
    end if;
    if p_before is not null or p_after is not null then
        if not private.is_supporter(v_owner) then
            raise exception 'Only supporters put images on a link.' using errcode = '42501', hint = 'shared_recipe_not_supporter';
        end if;
        if v_row.images_hidden_by = 'admin' then
            raise exception 'The images of this link were hidden.' using errcode = '42501', hint = 'shared_recipe_images_blocked';
        end if;
        if p_before is null or p_after is null or p_before = p_after
           or (select count(*) from storage.objects
               where bucket_id = 'gallery-images' and name in (p_before, p_after)
                 and split_part(name, '/', 1) = v_owner::text) <> 2
           or exists (select 1 from public.shared_recipes where id <> p_id and (before_image in (p_before, p_after) or after_image in (p_before, p_after)))
           or exists (select 1 from public.gallery_posts where before_image in (p_before, p_after) or after_image in (p_before, p_after)) then
            raise exception 'Both images have to be uploaded first, and not be used elsewhere.' using errcode = '22023', hint = 'shared_recipe_image_missing';
        end if;
    end if;
    update public.shared_recipes
        set before_image = p_before, after_image = p_after, updated_at = now(),
            -- New images start clean; what the administrator hid stays hidden (refused above).
            images_hidden_at = null, images_hidden_by = null
        where id = p_id;
    delete from private.shared_recipe_reports where link_id = p_id;
    return array_remove(array[v_row.before_image, v_row.after_image], null);
end
$$;

-- Revoking also takes the images off, so that nothing public stays behind.
create or replace function public.shared_recipe_revoke(p_id text) returns void
    language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := private.shared_recipe_owner();
begin
    update public.shared_recipes
        set revoked_at = now(), content = '{}'::jsonb, before_image = null, after_image = null
        where id = p_id and owner_id = v_owner and revoked_at is null;
    if not found then
        raise exception 'No such link.' using errcode = 'P0002', hint = 'shared_recipe_not_found';
    end if;
end
$$;

-- ---------------------------------------------------------------------------------------------
-- Whoever opens the link
-- ---------------------------------------------------------------------------------------------

-- The images of a live link. Hidden ones come back as such, without their paths; a link that does not
-- exist or was revoked gives nothing, as `shared_recipe_get` does.
create function public.shared_recipe_images(p_id text)
returns table (before_image text, after_image text, hidden boolean)
    language sql stable security definer set search_path = '' as $$
    select case when s.images_hidden_at is null then s.before_image end,
           case when s.images_hidden_at is null then s.after_image end,
           s.images_hidden_at is not null
    from public.shared_recipes s
    where s.id = p_id and s.revoked_at is null and s.before_image is not null
$$;

-- A report of the images of a link, from anyone. One per reporter and link, and a limited number a day.
-- The answer is the same whether or not the link has images, so that it tells nothing.
create function public.shared_recipe_report(p_id text, p_reason text) returns void
    language plpgsql security definer set search_path = '' as $$
declare
    v_account uuid := auth.uid();
    v_address text := split_part(coalesce(nullif(current_setting('request.headers', true), '')::json ->> 'x-forwarded-for', ''), ',', 1);
    v_reporter text := coalesce(v_account::text, 'ip:' || md5(trim(v_address)));
begin
    if p_reason is null or p_reason not in ('sexual', 'violence', 'harassment', 'illegal', 'privacy', 'copyright', 'other') then
        raise exception 'Invalid reason.' using errcode = '22023', hint = 'shared_recipe_report_invalid';
    end if;
    if (select count(*) from private.shared_recipe_reports where reporter = v_reporter and created_at > now() - interval '1 day') >= 20 then
        raise exception 'Too many reports.' using errcode = 'P0001', hint = 'shared_recipe_report_limit';
    end if;
    if not exists (select 1 from public.shared_recipes where id = p_id and revoked_at is null and before_image is not null) then
        return;
    end if;
    insert into private.shared_recipe_reports (link_id, reporter, reason) values (p_id, v_reporter, p_reason)
        on conflict do nothing;
    -- Three different people are enough to take the images off until the administrator looks.
    if (select count(*) from private.shared_recipe_reports where link_id = p_id) >= 3 then
        update public.shared_recipes set images_hidden_at = coalesce(images_hidden_at, now()),
                                         images_hidden_by = coalesce(images_hidden_by, 'reports')
            where id = p_id;
    end if;
end
$$;

-- ---------------------------------------------------------------------------------------------
-- The administrator
-- ---------------------------------------------------------------------------------------------

-- Links with reports or hidden images, the most reported first.
create function public.shared_recipe_reports()
returns table (link_id text, reports int, reasons text[], hidden_by text, before_image text, after_image text, last_report timestamptz)
    language plpgsql stable security definer set search_path = '' as $$
begin
    if not private.forum_is_admin() then
        raise exception 'Only the administrator moderates.' using errcode = '42501', hint = 'shared_recipe_forbidden';
    end if;
    return query
        select s.id, count(r.reporter)::int, coalesce(array_agg(distinct r.reason) filter (where r.reason is not null), '{}'),
               s.images_hidden_by, s.before_image, s.after_image, max(r.created_at)
        from public.shared_recipes s
        left join private.shared_recipe_reports r on r.link_id = s.id
        where s.revoked_at is null and s.before_image is not null
        group by s.id
        having count(r.reporter) > 0 or s.images_hidden_at is not null
        order by count(r.reporter) desc, max(r.created_at) desc nulls last;
end
$$;

-- Hides the images for good (`p_hidden`), or clears the reports and shows them again.
create function public.shared_recipe_review(p_id text, p_hidden boolean) returns void
    language plpgsql security definer set search_path = '' as $$
begin
    if not private.forum_is_admin() then
        raise exception 'Only the administrator moderates.' using errcode = '42501', hint = 'shared_recipe_forbidden';
    end if;
    update public.shared_recipes
        set images_hidden_at = case when p_hidden then coalesce(images_hidden_at, now()) end,
            images_hidden_by = case when p_hidden then 'admin' end
        where id = p_id and revoked_at is null;
    if not found then
        raise exception 'No such link.' using errcode = 'P0002', hint = 'shared_recipe_not_found';
    end if;
    delete from private.shared_recipe_reports where link_id = p_id;
end
$$;

revoke all on function
    public.shared_recipe_set_images(text, text, text),
    public.shared_recipe_images(text),
    public.shared_recipe_report(text, text),
    public.shared_recipe_reports(),
    public.shared_recipe_review(text, boolean)
    from public, anon, authenticated;
grant execute on function public.shared_recipe_set_images(text, text, text), public.shared_recipe_reports(), public.shared_recipe_review(text, boolean) to authenticated;
grant execute on function public.shared_recipe_images(text), public.shared_recipe_report(text, text) to anon, authenticated;
revoke all on all functions in schema private from public, anon, authenticated;
