// Tests of the images on a recipe link and their reports (20261115120000_shared_recipes_images.sql): only a
// supporter puts them on a link of their own, anyone reports them without an account, three reporters hide
// them until the administrator looks, and what the administrator hides the owner cannot bring back.

import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PGlite } from '@electric-sql/pglite'

const here = dirname(fileURLToPath(import.meta.url))
const migrations = join(here, '..', 'supabase', 'migrations')

let db
let admin
let ana
let bea
let anon
let n = 0

beforeEach(async () => {
  db = new PGlite()
  await db.exec(readFileSync(join(here, 'supabase-stub.sql'), 'utf8'))
  for (const file of readdirSync(migrations).filter((name) => name.endsWith('.sql')).sort()) {
    await db.exec(readFileSync(join(migrations, file), 'utf8'))
  }
  admin = await user('admin@example.test')
  ana = await user('ana@example.test')
  bea = await user('bea@example.test')
  anon = session(null, 'anon')
  await db.query('insert into private.forum_admins (user_id) values ($1)', [admin.id])
  await db.query(`select public.supporter_record_payment('manual', 'p1', 'one_off', 'ana@example.test', 1, 'EUR', now())`)
})

function session(id, role, headers = null) {
  return async (sql, params = []) =>
    db.transaction(async (tx) => {
      if (id) await tx.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify({ sub: id, role })])
      if (headers) await tx.query(`select set_config('request.headers', $1, true)`, [JSON.stringify(headers)])
      await tx.exec(`set local role ${role}`)
      return (await tx.query(sql, params)).rows
    })
}

async function user(email) {
  const id = (await db.query('insert into auth.users (email) values ($1) returning id', [email])).rows[0].id
  return Object.assign(session(id, 'authenticated'), { id })
}

const visitor = (address) => session(null, 'anon', { 'x-forwarded-for': address })
const recipe = { format: 'imago', version: 1, kind: 'recipes', recipes: [{ name: 'Warm', origin: null, processVersion: 24, recipe: {} }] }
const link = async (who) => (await who('select public.shared_recipe_create($1, $2::jsonb) as id', [`r${++n}`, JSON.stringify(recipe)]))[0].id
const upload = (who, name) => who(`insert into storage.objects (bucket_id, name) values ('gallery-images', $1)`, [name])
const fails = (promise, hint) => assert.rejects(promise, (error) => {
  assert.equal(error.hint, hint, error.message)
  return true
})

async function withImages(who, id) {
  const [before, after] = [`${who.id}/b${++n}.jpg`, `${who.id}/a${n}.jpg`]
  await upload(who, before)
  await upload(who, after)
  await who('select public.shared_recipe_set_images($1, $2, $3)', [id, before, after])
  return [before, after]
}

const images = async (id) => (await anon('select * from public.shared_recipe_images($1)', [id]))[0]
const report = (who, id, reason = 'sexual') => who('select public.shared_recipe_report($1, $2)', [id, reason])
const reportCount = async () => (await db.query('select count(*)::int as n from private.shared_recipe_reports')).rows[0].n

test('a supporter puts images on a link of their own, and anyone sees them', async () => {
  const id = await link(ana)
  assert.equal(await images(id), undefined)
  const [before, after] = await withImages(ana, id)
  assert.deepEqual(await images(id), { before_image: before, after_image: after, hidden: false })
})

test('only a supporter, only on their own link, only with images they uploaded', async () => {
  const mine = await link(bea)
  // The bucket refuses a non-supporter's upload, so the objects are put there as the server would.
  for (const name of ['b.jpg', 'a.jpg']) await db.query(`insert into storage.objects (bucket_id, name) values ('gallery-images', $1)`, [`${bea.id}/${name}`])
  await fails(bea('select public.shared_recipe_set_images($1, $2, $3)', [mine, `${bea.id}/b.jpg`, `${bea.id}/a.jpg`]), 'shared_recipe_not_supporter')

  const anas = await link(ana)
  await fails(bea('select public.shared_recipe_set_images($1, null, null)', [anas]), 'shared_recipe_not_found')
  await fails(ana('select public.shared_recipe_set_images($1, $2, $3)', [anas, `${ana.id}/missing.jpg`, `${ana.id}/missing2.jpg`]), 'shared_recipe_image_missing')
  await fails(ana('select public.shared_recipe_set_images($1, $2, $3)', [anas, `${bea.id}/b.jpg`, `${bea.id}/a.jpg`]), 'shared_recipe_image_missing')
  await assert.rejects(anon('select public.shared_recipe_set_images($1, null, null)', [anas]), /permission denied/)
})

test('one image cannot serve two links', async () => {
  const first = await link(ana)
  const second = await link(ana)
  const [before, after] = await withImages(ana, first)
  await fails(ana('select public.shared_recipe_set_images($1, $2, $3)', [second, before, after]), 'shared_recipe_image_missing')
})

test('taking the images off returns them, and revoking leaves nothing public', async () => {
  const id = await link(ana)
  const [before, after] = await withImages(ana, id)
  assert.deepEqual((await ana('select public.shared_recipe_set_images($1, null, null) as old', [id]))[0].old, [before, after])
  assert.equal(await images(id), undefined)
  await withImages(ana, id)
  await ana('select public.shared_recipe_revoke($1)', [id])
  assert.equal(await images(id), undefined)
})

test('the owner cannot delete images a link uses, and can delete the ones it let go', async () => {
  const id = await link(ana)
  const [before] = await withImages(ana, id)
  const del = async (name) => (await ana(`delete from storage.objects where bucket_id = 'gallery-images' and name = $1 returning name`, [name])).length
  assert.equal(await del(before), 0)
  await ana('select public.shared_recipe_set_images($1, null, null)', [id])
  assert.equal(await del(before), 1)
})

test('three different reporters hide the images, the same one twice does not', async () => {
  const id = await link(ana)
  await withImages(ana, id)
  await report(visitor('203.0.113.1'), id)
  await report(visitor('203.0.113.1'), id, 'violence')
  await report(visitor('203.0.113.2'), id)
  assert.equal((await images(id)).hidden, false)
  await report(visitor('203.0.113.3'), id)
  assert.deepEqual(await images(id), { before_image: null, after_image: null, hidden: true })
})

test('a report says nothing about whether the link exists, refuses a reason it does not know, and keeps no address', async () => {
  const id = await link(ana)
  await withImages(ana, id)
  await report(visitor('203.0.113.9'), id)
  await report(visitor('203.0.113.9'), 'doesnotexistdoesnotexist')
  await fails(report(visitor('203.0.113.9'), id, 'because'), 'shared_recipe_report_invalid')
  assert.equal(await reportCount(), 1)
  const stored = (await db.query('select reporter from private.shared_recipe_reports')).rows[0].reporter
  assert.equal(stored.includes('203.0.113.9'), false)
})

test('a link without images takes no report, with the same answer', async () => {
  const id = await link(ana)
  await report(visitor('203.0.113.9'), id)
  assert.equal(await reportCount(), 0)
})

test('the same address reports at most twenty times a day', async () => {
  const id = await link(ana)
  await withImages(ana, id)
  const address = '198.51.100.7'
  await db.query(
    `insert into public.shared_recipes (id, owner_id, recipe_id, content, before_image, after_image)
     select 'filler' || g || repeat('x', 12), $1, 'f' || g, '{}'::jsonb, 'b' || g, 'a' || g from generate_series(1, 20) g`,
    [ana.id],
  )
  await db.query(
    `insert into private.shared_recipe_reports (link_id, reporter, reason)
     select id, 'ip:' || md5($1), 'other' from public.shared_recipes where id like 'filler%'`,
    [address],
  )
  await fails(report(visitor(address), id, 'other'), 'shared_recipe_report_limit')
  await report(visitor('198.51.100.8'), id, 'other')
})

test('the administrator reads the reports and hides for good; the owner cannot undo that', async () => {
  const id = await link(ana)
  await withImages(ana, id)
  await report(visitor('203.0.113.1'), id, 'sexual')
  await fails(ana('select * from public.shared_recipe_reports()'), 'shared_recipe_forbidden')
  const [row] = await admin('select * from public.shared_recipe_reports()')
  assert.equal(row.link_id, id)
  assert.equal(row.reports, 1)
  assert.deepEqual(row.reasons, ['sexual'])

  await fails(ana('select public.shared_recipe_review($1, true)', [id]), 'shared_recipe_forbidden')
  await admin('select public.shared_recipe_review($1, true)', [id])
  assert.equal((await images(id)).hidden, true)
  await fails(ana('select public.shared_recipe_set_images($1, $2, $3)', [id, `${ana.id}/x.jpg`, `${ana.id}/y.jpg`]), 'shared_recipe_images_blocked')

  await admin('select public.shared_recipe_review($1, false)', [id])
  assert.equal((await images(id)).hidden, false)
  assert.equal((await admin('select * from public.shared_recipe_reports()')).length, 0)
})

test('new images from the owner start without reports, after the reports hid the old ones', async () => {
  const id = await link(ana)
  await withImages(ana, id)
  for (const address of ['203.0.113.1', '203.0.113.2', '203.0.113.3']) await report(visitor(address), id)
  assert.equal((await images(id)).hidden, true)
  await withImages(ana, id)
  assert.equal((await images(id)).hidden, false)
})

test('nobody touches the new columns or the reports directly', async () => {
  const id = await link(ana)
  await withImages(ana, id)
  for (const who of [anon, ana, bea, admin]) {
    await assert.rejects(who('select before_image from public.shared_recipes'))
    await assert.rejects(who('select * from private.shared_recipe_reports'))
  }
})

test('reports tell support through the function, on the first and on the one that hides, never otherwise', async () => {
  // PGlite has no pg_net: a stand-in `net.http_post` records the calls the trigger makes.
  await db.exec(`create schema net; create table public.net_calls (body jsonb, headers jsonb);
    create function net.http_post(url text, headers jsonb, body jsonb) returns bigint language sql as
    $$ insert into public.net_calls values (body, headers) returning 1::bigint $$;`)
  const id = await link(ana)
  await withImages(ana, id)
  await report(visitor('203.0.113.1'), id)
  assert.equal((await db.query('select count(*)::int as n from public.net_calls')).rows[0].n, 0, 'nothing is set up yet')

  await db.exec(`insert into private.notify_settings values ('report_notify_url', 'https://example.test/f'), ('report_notify_secret', 's3cret')`)
  const other = await link(ana)
  await withImages(ana, other)
  for (const address of ['203.0.113.1', '203.0.113.2', '203.0.113.3', '203.0.113.4']) await report(visitor(address), other, 'violence')
  const calls = (await db.query('select * from public.net_calls order by body ->> $$reports$$')).rows
  assert.deepEqual(calls.map((c) => [c.body.reports, c.body.hidden]), [[1, false], [3, true]])
  assert.equal(calls[0].body.link_id, other)
  assert.deepEqual(calls[0].body.reasons, ['violence'])
  assert.equal(calls[0].headers['x-notify-secret'], 's3cret')
  for (const who of [anon, ana, admin]) await assert.rejects(who('select * from private.notify_settings'))
})

test('a report still counts when the notification cannot be sent', async () => {
  await db.exec(`insert into private.notify_settings values ('report_notify_url', 'https://example.test/f'), ('report_notify_secret', 's3cret')`)
  await db.exec(`create schema net; create function net.http_post(url text, headers jsonb, body jsonb) returns bigint language plpgsql as $$ begin raise exception 'down'; end $$;`)
  const id = await link(ana)
  await withImages(ana, id)
  await report(visitor('203.0.113.1'), id)
  assert.equal(await reportCount(), 1)
})
