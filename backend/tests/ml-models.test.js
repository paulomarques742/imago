// Tests of the mask models' bucket (20261116120000_ml_models.sql): it is public, and no client, with an
// account or without, can put, change or delete anything in it.

import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PGlite } from '@electric-sql/pglite'

const here = dirname(fileURLToPath(import.meta.url))
const migrations = join(here, '..', 'supabase', 'migrations')

let db
let ana
let anon

beforeEach(async () => {
  db = new PGlite()
  await db.exec(readFileSync(join(here, 'supabase-stub.sql'), 'utf8'))
  for (const file of readdirSync(migrations).filter((name) => name.endsWith('.sql')).sort()) {
    await db.exec(readFileSync(join(migrations, file), 'utf8'))
  }
  ana = await user('ana@example.test')
  anon = session(null, 'anon')
  // A supporter can write to the gallery's bucket; that must not reach this one.
  await db.query(`select public.supporter_record_payment('manual', 'p1', 'one_off', 'ana@example.test', 1, 'EUR', now())`)
  await db.query(`insert into storage.objects (bucket_id, name) values ('ml-models', 'u2netp/1/u2netp.onnx')`)
})

function session(id, role) {
  return async (sql, params = []) =>
    db.transaction(async (tx) => {
      if (id) await tx.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify({ sub: id, role })])
      await tx.exec(`set local role ${role}`)
      return (await tx.query(sql, params)).rows
    })
}

async function user(email) {
  const id = (await db.query('insert into auth.users (email) values ($1) returning id', [email])).rows[0].id
  return Object.assign(session(id, 'authenticated'), { id })
}

test('the bucket is public and takes the models and the manifest', async () => {
  const [bucket] = (await db.query(`select public, file_size_limit, allowed_mime_types from storage.buckets where id = 'ml-models'`)).rows
  assert.equal(bucket.public, true)
  assert.ok(bucket.file_size_limit >= 25 * 1024 * 1024, 'the sky model is 24 MB')
  assert.deepEqual(bucket.allowed_mime_types, ['application/octet-stream', 'application/json'])
})

test('no client uploads to the bucket', async () => {
  for (const who of [anon, ana]) {
    await assert.rejects(who(`insert into storage.objects (bucket_id, name) values ('ml-models', 'manifest.json')`))
    await assert.rejects(who(`insert into storage.objects (bucket_id, name) values ('ml-models', $1)`, [`${ana.id}/x.onnx`]))
  }
})

test('no client changes or deletes what is there', async () => {
  for (const who of [anon, ana]) {
    assert.equal((await who(`update storage.objects set name = 'other.onnx' where bucket_id = 'ml-models' returning name`)).length, 0)
    assert.equal((await who(`delete from storage.objects where bucket_id = 'ml-models' returning name`)).length, 0)
  }
  assert.equal((await db.query(`select name from storage.objects where bucket_id = 'ml-models'`)).rows[0].name, 'u2netp/1/u2netp.onnx')
})
