-- The heart and last use on the app's presets.
--
-- The presets themselves are code and never sync; the person's marks on them do, one row per
-- preset id, with the same shape and the same rules as every other entity. Apps that predate this
-- table never ask for it, so it changes nothing for them.

create table public.built_in_recipe_marks (like public.saved_recipes including all);
alter table public.built_in_recipe_marks
    add foreign key (user_id) references auth.users on delete cascade;

create index built_in_recipe_marks_seq_idx on public.built_in_recipe_marks (user_id, seq);

alter table public.built_in_recipe_marks enable row level security;
revoke all on public.built_in_recipe_marks from anon, authenticated;
grant select on public.built_in_recipe_marks to authenticated;
create policy built_in_recipe_marks_select_own on public.built_in_recipe_marks
    for select to authenticated using (user_id = (select auth.uid()));

create trigger keep_revision before update on public.built_in_recipe_marks
    for each row execute function private.keep_revision('BUILT_IN_RECIPE_MARK');
create trigger signal_change after insert or update on public.built_in_recipe_marks
    for each row execute function private.signal_change('BUILT_IN_RECIPE_MARK');

create or replace function private.sync_table(p_entity text) returns text
    language sql immutable set search_path = '' as $$
    select case p_entity
        when 'RECIPE' then 'recipes'
        when 'DERIVED_ASSET' then 'derived_assets'
        when 'SAVED_RECIPE' then 'saved_recipes'
        when 'TEMPLATE' then 'composition_templates'
        when 'PROJECT' then 'composition_projects'
        when 'BRAND_KIT' then 'brand_kits'
        when 'BUILT_IN_RECIPE_MARK' then 'built_in_recipe_marks'
    end
$$;
