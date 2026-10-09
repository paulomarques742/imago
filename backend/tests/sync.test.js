// Tests of the sync schema (backend/supabase/migrations) on an in-memory Postgres.
// They run with `npm test` in this folder. They cover the send and receive protocol and the privacy
// invariant: RLS and privileges stop anyone reading or writing another user's data.

import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PGlite } from '@electric-sql/pglite'

const here = dirname(fileURLToPath(import.meta.url))
const migrations = join(here, '..', 'supabase', 'migrations')
const SHA_A = 'a9993e364706816aba3e25717850c26c9cd0d89d'
const SHA_B = '81fe8bfe87576c3ecb22426f8e57847382917acf'

let db
let alice
let bob

beforeEach(async () => {
  db = new PGlite()
  await db.exec(readFileSync(join(here, 'supabase-stub.sql'), 'utf8'))
  for (const file of readdirSync(migrations).filter((name) => name.endsWith('.sql')).sort()) {
    await db.exec(readFileSync(join(migrations, file), 'utf8'))
  }
  alice = await user('alice@example.test')
  bob = await user('bob@example.test')
})

async function user(email) {
  const id = (await db.query('insert into auth.users (email) values ($1) returning id', [email])).rows[0].id
  const session = (role = 'authenticated') => async (sql, params = []) =>
    db.transaction(async (tx) => {
      await tx.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify({ sub: id, role })])
      await tx.exec(`set local role ${role}`)
      return (await tx.query(sql, params)).rows
    })
  const as = session()
  const device = (await as(`select public.sync_register_device('Pixel 8') as id`))[0].id
  return { id, as, device }
}

const anon = async (sql, params = []) =>
  db.transaction(async (tx) => {
    await tx.exec('set local role anon')
    return (await tx.query(sql, params)).rows
  })

async function push(who, entity, changes) {
  return (await who.as('select public.sync_push($1, $2) as r', [entity, JSON.stringify(changes)]))[0].r
}

async function pull(who, entity, cursor = 0, limit = 500) {
  return (await who.as('select public.sync_pull($1, $2, $3) as r', [entity, cursor, limit]))[0].r
}

const change = (who, key, payload, extra = {}) => ({
  key,
  baseRevision: null,
  payload,
  editedAt: '2026-09-15T10:00:00Z',
  deviceId: who.device,
  ...extra,
})

test('a new record applies with revision 1 and arrives through pull in seq order', async () => {
  const results = await push(alice, 'SAVED_RECIPE', [
    change(alice, 'preset-1', { name: 'Um' }),
    change(alice, 'preset-2', { name: 'Dois' }),
  ])
  assert.deepEqual(results.map((r) => [r.status, r.key, r.revision]), [
    ['applied', 'preset-1', 1],
    ['applied', 'preset-2', 1],
  ])
  assert.ok(results[1].seq > results[0].seq)

  const page = await pull(alice, 'SAVED_RECIPE', 0)
  assert.deepEqual(page.rows.map((r) => r.key), ['preset-1', 'preset-2'])
  assert.equal(page.cursor, results[1].seq)
  assert.equal(page.rows[0].user_id, undefined)
  assert.deepEqual((await pull(alice, 'SAVED_RECIPE', page.cursor)).rows, [])
  assert.equal((await pull(alice, 'SAVED_RECIPE', page.cursor)).cursor, page.cursor)
  assert.deepEqual((await pull(alice, 'SAVED_RECIPE', 0, 1)).rows.map((r) => r.key), ['preset-1'])
})

test('an outdated revision gives a conflict without saving; with the remote revision it applies and keeps the previous one', async () => {
  await push(alice, 'PROJECT', [change(alice, 'p', { name: 'Telefone' })])
  await push(alice, 'PROJECT', [change(alice, 'p', { name: 'Tablet' }, { baseRevision: 1 })])

  const [conflict] = await push(alice, 'PROJECT', [change(alice, 'p', { name: 'Atrasado' }, { baseRevision: 1 })])
  assert.equal(conflict.status, 'conflict')
  assert.equal(conflict.remote.revision, 2)
  assert.deepEqual(conflict.remote.payload, { name: 'Tablet' })

  const [fromScratch] = await push(alice, 'PROJECT', [change(alice, 'p', { name: 'Sem base' })])
  assert.equal(fromScratch.status, 'conflict', 'no baseRevision over an existing record is a conflict too')

  const [applied] = await push(alice, 'PROJECT', [change(alice, 'p', { name: 'Vencedor' }, { baseRevision: 2 })])
  assert.equal(applied.status, 'applied')
  assert.equal(applied.revision, 3)

  const history = await alice.as(`select revision, payload->>'name' as name from public.revisions where key = 'p' order by id`)
  assert.deepEqual(history, [
    { revision: 1, name: 'Telefone' },
    { revision: 2, name: 'Tablet' },
  ])
})

test('the history keeps at most 20 versions per record', async () => {
  await push(alice, 'TEMPLATE', [change(alice, 't', { v: 0 })])
  for (let revision = 1; revision <= 25; revision++) {
    await push(alice, 'TEMPLATE', [change(alice, 't', { v: revision }, { baseRevision: revision })])
  }
  const history = await alice.as(`select revision from public.revisions where key = 't' order by revision`)
  assert.equal(history.length, 20)
  assert.equal(history[0].revision, 6)
  assert.equal(history.at(-1).revision, 25)
})

test('a clock in the future does not win: editedAt becomes now()', async () => {
  await push(alice, 'SAVED_RECIPE', [
    change(alice, 'future', {}, { editedAt: '2099-01-01T00:00:00Z' }),
    change(alice, 'near', {}, { editedAt: new Date(Date.now() + 60_000).toISOString() }),
  ])
  const rows = await alice.as(
    `select key, edited_at <= now() + interval '1 second' as clamped from public.saved_recipes order by key`,
  )
  assert.deepEqual(rows, [
    { key: 'future', clamped: true },
    { key: 'near', clamped: false },
  ])
})

test('invalid changes are refused one by one without stopping the others in the batch', async () => {
  const huge = { blob: 'x'.repeat(1024 * 1024 + 10) }
  const results = await push(alice, 'RECIPE', [
    change(alice, 'not-a-sha1', { schemaVersion: 1 }),
    change(alice, SHA_A, huge),
    change(alice, SHA_B, { schemaVersion: 1 }, { deviceId: bob.device }),
    change(alice, SHA_B, { schemaVersion: 1 }, { deviceId: 'nope' }),
    change(alice, SHA_B, { schemaVersion: 'um' }),
    change(alice, SHA_A, { schemaVersion: 1, processVersion: 8 }, { hints: { fileName: 'PXL.jpg', size: 3 } }),
  ])
  assert.deepEqual(results.map((r) => [r.status, r.reason]), [
    ['rejected', 'invalid_change'],
    ['rejected', 'payload_too_large'],
    ['rejected', 'unknown_device'],
    ['rejected', 'invalid_change'],
    ['rejected', 'invalid_change'],
    ['applied', undefined],
  ])
  const [recipe] = await alice.as('select schema_version, process_version, hints from public.recipes')
  assert.deepEqual(recipe, { schema_version: 1, process_version: 8, hints: { fileName: 'PXL.jpg', size: 3 } })
  assert.equal((await alice.as('select count(*)::int as n from public.revisions'))[0].n, 0,
    'saving the hints must not create a ghost version in the history')

  await assert.rejects(push(alice, 'NOPE', []), /Entidade desconhecida/)
  await assert.rejects(push(alice, 'RECIPE', Array.from({ length: 51 }, () => ({}))), /até 50/)
})

test('deleting leaves a mark that arrives through pull, and saving again brings the record back', async () => {
  await push(alice, 'SAVED_RECIPE', [change(alice, 'x', { name: 'x' })])
  const [deleted] = await push(alice, 'SAVED_RECIPE', [change(alice, 'x', { name: 'x' }, { baseRevision: 1, deleted: true })])
  assert.equal(deleted.status, 'applied')
  const page = await pull(alice, 'SAVED_RECIPE', 0)
  assert.equal(page.rows.length, 1)
  assert.ok(page.rows[0].deleted_at)

  await push(alice, 'SAVED_RECIPE', [change(alice, 'x', { name: 'x' }, { baseRevision: 2 })])
  assert.equal((await pull(alice, 'SAVED_RECIPE', page.cursor)).rows[0].deleted_at, null)
})

test('the brand kit is a single record per account', async () => {
  const results = await push(alice, 'BRAND_KIT', [
    change(alice, 'brand-kit', { primaryFont: 'Sans' }),
    change(alice, 'outro', { primaryFont: 'Serif' }),
  ])
  assert.deepEqual(results.map((r) => r.status), ['applied', 'rejected'])
})

test('the marks on the app presets sync like any record, and each account only sees its own', async () => {
  const [first] = await push(alice, 'BUILT_IN_RECIPE_MARK', [change(alice, 'built-in-pastel', { isFavorite: true })])
  assert.deepEqual([first.status, first.revision], ['applied', 1])
  const [stale] = await push(alice, 'BUILT_IN_RECIPE_MARK', [change(alice, 'built-in-pastel', { isFavorite: false })])
  assert.equal(stale.status, 'conflict')
  const [used] = await push(alice, 'BUILT_IN_RECIPE_MARK', [
    change(alice, 'built-in-pastel', { isFavorite: true, usedAt: '2026-10-09T10:00:00Z' }, { baseRevision: 1 }),
  ])
  assert.deepEqual([used.status, used.revision], ['applied', 2])

  const page = await pull(alice, 'BUILT_IN_RECIPE_MARK', 0)
  assert.deepEqual(page.rows.map((r) => [r.key, r.payload.usedAt]), [['built-in-pastel', '2026-10-09T10:00:00Z']])
  assert.deepEqual(await alice.as('select entity from public.sync_signals'), [{ entity: 'BUILT_IN_RECIPE_MARK' }])
  assert.deepEqual((await pull(bob, 'BUILT_IN_RECIPE_MARK', 0)).rows, [])
  assert.deepEqual(await bob.as('select * from public.built_in_recipe_marks'), [])
  await assert.rejects(bob.as(`delete from public.built_in_recipe_marks`), /permission denied/)
})

test('derived assets only accept libraries of the same account and a consistent key', async () => {
  const library = (await alice.as(`select public.sync_link_library('immich', 'fp-1', 'Casa', null) as id`))[0].id
  const again = (await alice.as(`select public.sync_link_library('immich', 'fp-1', 'Casa nova', $1) as id`, [alice.device]))[0].id
  assert.equal(again, library, 'the same photo account is the same library')
  const payload = { libraryId: library, derivedAssetId: 'export-1', originalAssetId: 'photo-1' }

  const results = await push(alice, 'DERIVED_ASSET', [
    change(alice, `${library}/export-1`, payload),
    change(alice, `${library}/other`, payload),
  ])
  assert.deepEqual(results.map((r) => r.status), ['applied', 'rejected'])
  const [row] = await alice.as('select library_id, derived_asset_id, original_asset_id from public.derived_assets')
  assert.deepEqual(row, { library_id: library, derived_asset_id: 'export-1', original_asset_id: 'photo-1' })

  const [stolen] = await push(bob, 'DERIVED_ASSET', [change(bob, `${library}/export-2`, { ...payload, derivedAssetId: 'export-2' })])
  assert.deepEqual([stolen.status, stolen.reason], ['rejected', 'unknown_library'])
})

test('RLS: a user never reads or writes another user\'s data', async () => {
  await push(alice, 'RECIPE', [change(alice, SHA_A, { schemaVersion: 1 })])
  await alice.as(`select public.sync_link_library('immich', 'fp', 'Casa', null)`)

  for (const table of ['recipes', 'devices', 'libraries', 'revisions', 'sync_signals']) {
    assert.deepEqual(await bob.as(`select * from public.${table} where user_id = $1`, [alice.id]), [], table)
  }
  assert.deepEqual((await pull(bob, 'RECIPE', 0)).rows, [])

  // The same content in Bob's account is another record, not a conflict with Alice's.
  const [own] = await push(bob, 'RECIPE', [change(bob, SHA_A, { schemaVersion: 2 })])
  assert.deepEqual([own.status, own.revision], ['applied', 1])
  assert.deepEqual((await pull(alice, 'RECIPE', 0)).rows.map((r) => r.payload.schemaVersion), [1])

  const denied = /permission denied/
  await assert.rejects(bob.as(`insert into public.recipes (user_id, key, payload, edited_at) values ($1, $2, '{}', now())`, [bob.id, SHA_B]), denied)
  await assert.rejects(bob.as(`update public.recipes set payload = '{}'`), denied)
  await assert.rejects(bob.as(`delete from public.recipes`), denied)
  await assert.rejects(bob.as(`insert into public.revisions (user_id, entity, key, revision, payload, edited_at, reason) values ($1, 'RECIPE', 'k', 1, '{}', now(), 'conflict')`, [bob.id]), denied)
  await assert.rejects(bob.as(`select private.sync_table('RECIPE')`), denied)
  await assert.rejects(bob.as(`select nextval('public.sync_seq')`), denied)

  // Deleting devices is allowed, but only one's own.
  assert.deepEqual(await bob.as(`delete from public.devices where id = $1 returning id`, [alice.device]), [])
  assert.equal((await alice.as('select count(*)::int as n from public.devices'))[0].n, 1)
  await assert.rejects(bob.as(`select public.sync_touch_device($1)`, [alice.device]), /Aparelho desconhecido/)
})

test('without a session nothing is called and nothing is read', async () => {
  await assert.rejects(anon(`select public.sync_push('RECIPE', '[]')`), /permission denied/)
  await assert.rejects(anon(`select public.sync_register_device('x')`), /permission denied/)
  await assert.rejects(anon(`select * from public.recipes`), /permission denied/)
  const noClaims = () => db.transaction(async (tx) => {
    await tx.exec('set local role authenticated')
    return (await tx.query(`select public.sync_pull('RECIPE', 0)`)).rows
  })
  await assert.rejects(noClaims(), /Sessão em falta/)
})

test('the lost version of a recipe conflict reaches the other devices through pull', async () => {
  const id = (await alice.as(
    `select public.sync_record_conflict('RECIPE', $1, 3, '{"tone":{"exposure":1}}', '2026-09-12T18:40:00Z', $2) as id`,
    [SHA_A, alice.device],
  ))[0].id
  assert.ok(id)
  const page = await pull(alice, 'RECIPE_CONFLICT', 0)
  assert.deepEqual(page.rows.map((r) => [r.key, r.reason, r.edited_by_device]), [[SHA_A, 'conflict', alice.device]])
  assert.deepEqual((await pull(bob, 'RECIPE_CONFLICT', 0)).rows, [])
})

test('every write wakes the other devices through the entity signal', async () => {
  const [first] = await push(alice, 'PROJECT', [change(alice, 'p', {})])
  const [second] = await push(alice, 'PROJECT', [change(alice, 'p', {}, { baseRevision: 1 })])
  const signals = await alice.as('select entity, seq from public.sync_signals')
  assert.deepEqual(signals, [{ entity: 'PROJECT', seq: second.seq }])
  assert.ok(second.seq > first.seq)
})

test('removing a device keeps the libraries; deleting the account deletes everything', async () => {
  await alice.as(`select public.sync_link_library('device', $1, 'Pixel 8', $2)`, ['device|' + alice.device, alice.device])
  await push(alice, 'RECIPE', [change(alice, SHA_A, {})])
  await push(alice, 'PROJECT', [change(alice, 'p', {})])
  await push(alice, 'BUILT_IN_RECIPE_MARK', [change(alice, 'built-in-pastel', { isFavorite: true })])
  await alice.as(`select public.sync_record_conflict('RECIPE', $1, 1, '{}', now(), null)`, [SHA_A])

  await alice.as('delete from public.devices where id = $1', [alice.device])
  assert.deepEqual(await alice.as('select device_id from public.libraries'), [{ device_id: null }])

  await db.query('delete from auth.users where id = $1', [alice.id])
  for (const table of ['devices', 'libraries', 'recipes', 'derived_assets', 'saved_recipes',
    'composition_templates', 'composition_projects', 'brand_kits', 'built_in_recipe_marks', 'revisions', 'sync_signals']) {
    const left = (await db.query(`select count(*)::int as n from public.${table} where user_id = $1`, [alice.id])).rows[0].n
    assert.equal(left, 0, table)
  }
  assert.equal((await db.query('select count(*)::int as n from public.devices where user_id = $1', [bob.id])).rows[0].n, 1)
})
