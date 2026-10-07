import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const dependency=process.env.AIV_PGLITE_ROOT;
const {PGlite}=await import(dependency?pathToFileURL(dependency+'/dist/index.js').href:'@electric-sql/pglite');
const {pgcrypto}=await import(dependency?pathToFileURL(dependency+'/dist/contrib/pgcrypto.js').href:'@electric-sql/pglite/contrib/pgcrypto');
const db=new PGlite({extensions:{pgcrypto}});
await db.exec(`create role anon;create role authenticated;create schema auth;create schema extensions;
create table auth.users(id uuid primary key);create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema public,auth,extensions to authenticated,anon;
insert into auth.users values ('11111111-1111-4111-8111-111111111111'),('22222222-2222-4222-8222-222222222222');`);
await db.exec(fs.readFileSync(new URL('./schema-v1.sql',import.meta.url),'utf8'));
async function user(id){await db.exec(`set role authenticated;set request.jwt.claim.sub='${id}';`);}
const A='11111111-1111-4111-8111-111111111111',B='22222222-2222-4222-8222-222222222222';
async function rpc(body){return (await db.query('select public.aiv_personal_archive($1::jsonb) as receipt',[JSON.stringify(body)])).rows[0].receipt;}
async function rejects(fn){let refused=false;try{await fn();}catch{refused=true;}assert(refused);}
await user(A);
const probe=await rpc({action:'probe'});assert.equal(probe.schema_version,1);assert(probe.read_ok&&probe.write_ok);
assert.equal((await db.query('select count(*)::int n from public.aiv_personal_segments')).rows[0].n,0);
console.log('PASS real PostgreSQL schema/probe; zero probe data retained');
const crypto=await import('node:crypto');const sha=x=>crypto.createHash('sha256').update(x).digest('hex');
const events=[{event_id:1,event_json:'{"é":"a\\nb"}'},{event_id:2,event_json:'{"id":2}'}].map(e=>({...e,event_sha256:sha(e.event_json)}));
const manifest=sha(events.map(e=>`${e.event_id}:${e.event_sha256}`).join('\n'));
await rpc({action:'begin',segment_no:1,segment:{expected_count:2,first_event_id:1,last_event_id:2,client_segment_sha256:manifest}});
await rejects(()=>rpc({action:'finalize',segment_no:1}));
await rpc({action:'batch',segment_no:1,events});await rpc({action:'batch',segment_no:1,events});
const receipt=await rpc({action:'finalize',segment_no:1});assert.equal(receipt.server_segment_sha256,manifest);assert.equal(receipt.received_count,2);assert.equal(receipt.state,'VERIFIED');
await rpc({action:'finalize',segment_no:1});
await rejects(()=>rpc({action:'batch',segment_no:1,events:[{...events[0],event_json:'changed'}]}));
console.log('PASS actual RPC: Unicode/raw hash, idempotence, remote hash/count verification, immutable events');
await user(B);assert.equal((await db.query('select count(*)::int n from public.aiv_personal_events')).rows[0].n,0);
await rejects(()=>rpc({action:'finalize',segment_no:1}));
await rejects(()=>db.query('insert into public.aiv_personal_segments(user_id,segment_no,expected_count,first_id,last_id,client_sha256) values($1,2,1,1,1,$2)',[A,sha('test')]));
await db.exec('set role anon');await rejects(()=>rpc({action:'probe'}));await rejects(()=>db.query('select * from public.aiv_personal_events'));
console.log('PASS RLS: owner isolation; anon/publishable key alone cannot read or write');
await user(A);
const full=Array.from({length:50000},(_,i)=>({event_id:i+1,event_json:JSON.stringify({id:i+1})}));const fullManifest=sha(full.map(e=>`${e.event_id}:${sha(e.event_json)}`).join('\n'));
await rpc({action:'begin',segment_no:2,segment:{expected_count:50000,first_event_id:1,last_event_id:50000,client_segment_sha256:fullManifest}});
// Exercise the same RPC upload in 100-event batches, not an admin seed.
for(let i=0;i<full.length;i+=100)await rpc({action:'batch',segment_no:2,events:full.slice(i,i+100).map(e=>({...e,event_sha256:sha(e.event_json)}))});
const sealed=await rpc({action:'finalize',segment_no:2});assert.equal(sealed.received_count,50000);assert.equal(sealed.server_segment_sha256,fullManifest);
console.log('PASS 50,000 events / 500 RPC batches; independently computed SHA-256 matches remote receipt');
await db.close();
