// Tests of the recipe links (RECEITAS-09) on an in-memory Postgres: only the owner creates, updates and
// revokes, and anon only reads links that are still live.

import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PGlite } from '@electric-sql/pglite'

const here = dirname(fileURLToPath(import.meta.url))
const migrations = join(here, '..', 'supabase', 'migrations')
const recipe = (name = 'Warm') => ({ format: 'imago', version: 1, kind: 'recipes', recipes: [{ name, processVersion: 24, recipe: {} }] })

let db
let alice
let bob
let anon

beforeEach(async () => {
  db = new PGlite()
  await db.exec(readFileSync(join(here, 'supabase-stub.sql'), 'utf8'))
  for (const file of readdirSync(migrations).filter((name) => name.endsWith('.sql')).sort()) {
    await db.exec(readFileSync(join(migrations, file), 'utf8'))
  }
  alice = await user('alice@example.test')
  bob = await user('bob@example.test')
  anon = session(null, 'anon')
})

function session(id, role) {
  return async (sql, params = []) =>
    db.transaction(async (tx) => {
      await tx.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify({ sub: id ?? '', role })])
      await tx.exec(`set local role ${role}`)
      return (await tx.query(sql, params)).rows
    })
}

async function user(email) {
  const id = (await db.query('insert into auth.users (email) values ($1) returning id', [email])).rows[0].id
  return Object.assign(session(id, 'authenticated'), { id })
}

const create = async (who, recipeId = 'r1', content = recipe()) =>
  (await who(`select public.shared_recipe_create($1, $2::jsonb) as id`, [recipeId, JSON.stringify(content)]))[0].id
const get = async (who, id) => (await who(`select public.shared_recipe_get($1) as content`, [id]))[0].content
const update = (who, id, content) => who(`select public.shared_recipe_update($1, $2::jsonb)`, [id, JSON.stringify(content)])
const revoke = (who, id) => who(`select public.shared_recipe_revoke($1)`, [id])
const fails = (promise, hint) => assert.rejects(promise, (error) => {
  assert.equal(error.hint, hint)
  return true
})

test('a link can be read without an account', async () => {
  const id = await create(alice)
  assert.equal((await get(anon, id)).recipes[0].name, 'Warm')
  assert.equal((await get(bob, id)).recipes[0].name, 'Warm')
})

test('creating needs an account', async () => {
  await assert.rejects(create(anon))
})

test('anon cannot call anything but the read', async () => {
  const id = await create(alice)
  await assert.rejects(update(anon, id, recipe('Stolen')))
  await assert.rejects(revoke(anon, id))
  await assert.rejects(anon('select * from public.shared_recipe_mine()'))
})

test('nobody reads or writes the table directly', async () => {
  const id = await create(alice)
  for (const who of [anon, alice, bob]) {
    await assert.rejects(who('select * from public.shared_recipes'))
    await assert.rejects(who(`update public.shared_recipes set revoked_at = now() where id = $1`, [id]))
    await assert.rejects(who(`delete from public.shared_recipes where id = $1`, [id]))
    await assert.rejects(who(`insert into public.shared_recipes (id, owner_id, recipe_id, content) values ('x', $1, 'r', '{}')`, [alice.id]))
  }
})

test('only the owner updates', async () => {
  const id = await create(alice)
  await fails(update(bob, id, recipe('Stolen')), 'shared_recipe_not_found')
  assert.equal((await get(anon, id)).recipes[0].name, 'Warm')

  await update(alice, id, recipe('Warmer'))
  assert.equal((await get(anon, id)).recipes[0].name, 'Warmer')
})

test('only the owner revokes', async () => {
  const id = await create(alice)
  await fails(revoke(bob, id), 'shared_recipe_not_found')
  assert.ok(await get(anon, id))
})

test('a revoked link reads as nothing, and its content is gone', async () => {
  const id = await create(alice)
  await revoke(alice, id)
  assert.equal(await get(anon, id), null)
  assert.equal(await get(alice, id), null)
  assert.deepEqual((await db.query('select content from public.shared_recipes where id = $1', [id])).rows[0].content, {})
  await fails(update(alice, id, recipe()), 'shared_recipe_not_found')
})

test('a link that never existed reads the same as a revoked one', async () => {
  assert.equal(await get(anon, 'nothing-here'), null)
})

test('sharing the same recipe again is the same link and keeps what it holds', async () => {
  const first = await create(alice, 'r1', recipe('First'))
  assert.equal(await create(alice, 'r1', recipe('Second')), first)
  assert.equal((await get(anon, first)).recipes[0].name, 'First')
})

test('after revoking, sharing the recipe again makes another link', async () => {
  const first = await create(alice)
  await revoke(alice, first)
  const second = await create(alice)
  assert.notEqual(second, first)
  assert.equal(await get(anon, first), null)
  assert.ok(await get(anon, second))
})

test('two people can share a recipe with the same id', async () => {
  assert.notEqual(await create(alice, 'same'), await create(bob, 'same'))
})

test('an account has twenty live links, and a revoked one frees a place', async () => {
  const ids = []
  for (let n = 0; n < 20; n++) ids.push(await create(alice, `r${n}`))
  await fails(create(alice, 'one-more'), 'shared_recipe_limit')
  assert.ok(await create(bob, 'not-her-limit'))

  // A recipe that already has its link is not a new one, at the limit or not.
  assert.equal(await create(alice, 'r3'), ids[3])

  await revoke(alice, ids[0])
  assert.ok(await create(alice, 'one-more'))
})

test('the list has only the live links of whoever asks, without the content', async () => {
  const kept = await create(alice, 'kept')
  await revoke(alice, await create(alice, 'gone'))
  await create(bob, 'hers')
  const mine = await alice('select * from public.shared_recipe_mine()')
  assert.deepEqual(mine.map((row) => [row.id, row.recipe_id]), [[kept, 'kept']])
  assert.deepEqual(Object.keys(mine[0]).sort(), ['id', 'recipe_id', 'updated_at'])
})

test('deleting the account deletes its links', async () => {
  const id = await create(alice)
  await db.query('delete from auth.users where id = $1', [alice.id])
  assert.equal(await get(anon, id), null)
  assert.equal((await db.query('select count(*)::int as n from public.shared_recipes')).rows[0].n, 0)
})

test('only one recipe in the IMAGO format is accepted', async () => {
  const invalid = [
    {},
    { format: 'other', kind: 'recipes', recipes: [{}] },
    { format: 'imago', kind: 'other', recipes: [{}] },
    { format: 'imago', kind: 'recipes', recipes: 'x' },
    { format: 'imago', kind: 'recipes', recipes: [] },
    { format: 'imago', kind: 'recipes', recipes: [{}, {}] },
    [1, 2],
  ]
  for (const content of invalid) await fails(create(alice, 'r', content), 'shared_recipe_invalid')
})

test('an updated link has to be valid too', async () => {
  const id = await create(alice)
  await fails(update(alice, id, {}), 'shared_recipe_invalid')
  assert.ok(await get(anon, id))
})

test('a huge recipe is refused', async () => {
  const big = recipe('x'.repeat(1_100_000))
  await fails(create(alice, 'big', big), 'shared_recipe_too_large')
})

test('identifiers are long, URL safe and different', async () => {
  const ids = []
  for (let n = 0; n < 15; n++) ids.push(await create(alice, `r${n}`))
  assert.equal(new Set(ids).size, ids.length)
  for (const id of ids) assert.match(id, /^[A-Za-z0-9_-]{27}$/)
})
