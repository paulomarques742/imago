// Deletes the caller's account.
//
// Only the server can delete a user from auth.users, and only with the service key, which lives here
// and never in the app. Deleting the user deletes everything that belongs to them in cascade (the
// tables reference auth.users with `on delete cascade`).
//
// A stolen token is not enough: the request has to come right after a password sign-in. The app asks
// for the password and signs in again immediately before calling this function.

import { createClient } from "jsr:@supabase/supabase-js@2";

const RECENT_SIGN_IN_MS = 5 * 60 * 1000;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function reply(body: Record<string, unknown>, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (request.method !== "POST") return reply({ error: "method_not_allowed" }, 405);

  const token = request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return reply({ error: "not_signed_in" }, 401);

  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) return reply({ error: "not_signed_in" }, 401);

  const lastSignIn = data.user.last_sign_in_at ? Date.parse(data.user.last_sign_in_at) : 0;
  if (Date.now() - lastSignIn > RECENT_SIGN_IN_MS) return reply({ error: "sign_in_again" }, 403);

  const { error: deleteError } = await admin.auth.admin.deleteUser(data.user.id);
  if (deleteError) return reply({ error: "delete_failed" }, 500);
  return reply({ deleted: true }, 200);
});
