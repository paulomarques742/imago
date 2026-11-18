-- Tells support by email when the images of a recipe link are reported (RECEITAS-13).
--
-- After the first report of a link, and when the third hides the images, the database calls the
-- `report-notify` Edge Function (pg_net), which sends the email. The call is best effort: a missing
-- extension, a missing setting or a failed request never stops a report from being recorded.
--
-- The address of the function and its shared secret are not in this file. Whoever deploys it puts them
-- in, once, in a table that nobody but the database can read:
--
--   insert into private.notify_settings (name, value) values
--       ('report_notify_url', 'https://<project>.supabase.co/functions/v1/report-notify'),
--       ('report_notify_secret', '<the value of REPORT_NOTIFY_SECRET>');

create table private.notify_settings (
    name text primary key,
    value text not null
);

create function private.shared_recipe_report_notify() returns trigger
    language plpgsql security definer set search_path = '' as $$
declare
    v_count int;
    v_hidden boolean;
    v_url text;
    v_secret text;
begin
    select count(*) into v_count from private.shared_recipe_reports where link_id = new.link_id;
    -- The first report, and the one that hides the images. The others would only fill an inbox.
    if v_count <> 1 and v_count <> 3 then
        return new;
    end if;
    select value into v_url from private.notify_settings where name = 'report_notify_url';
    select value into v_secret from private.notify_settings where name = 'report_notify_secret';
    if v_url is null or v_secret is null or to_regnamespace('net') is null then
        return new;
    end if;
    -- The images are hidden right after this insert, in the same transaction: the third report is the one that does it.
    v_hidden := v_count >= 3;
    begin
        execute 'select net.http_post(url := $1, headers := $2, body := $3)' using
            v_url,
            jsonb_build_object('Content-Type', 'application/json', 'x-notify-secret', v_secret),
            jsonb_build_object(
                'link_id', new.link_id,
                'reports', v_count,
                'hidden', coalesce(v_hidden, false),
                'reasons', (select coalesce(jsonb_agg(distinct reason), '[]'::jsonb) from private.shared_recipe_reports where link_id = new.link_id)
            );
    exception when others then
        null;
    end;
    return new;
end
$$;

create trigger shared_recipe_report_notify after insert on private.shared_recipe_reports
    for each row execute function private.shared_recipe_report_notify();

revoke all on private.notify_settings from public, anon, authenticated;
revoke all on all functions in schema private from public, anon, authenticated;
