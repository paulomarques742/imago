-- The weights of the on-device mask models (subject, background, sky), and the manifest that lists them.
--
-- They are free for everyone and their licence lets anyone redistribute them, so the bucket is public: the
-- app downloads with no account and checks each file's SHA-256 against the manifest before using it. Nothing
-- here is written by a client. No policy on storage.objects names this bucket, so only the service role
-- uploads, when a model or the manifest changes.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('ml-models', 'ml-models', true, 67108864, array['application/octet-stream', 'application/json'])
on conflict (id) do nothing;
