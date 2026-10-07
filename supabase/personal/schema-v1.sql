-- AIV BYO Supabase schema v1. Execute only in the user's personal project.
-- Anonymous sign-ins must be enabled in Supabase Auth. Publishable key alone has no access.
begin;
create extension if not exists pgcrypto with schema extensions;
create table public.aiv_personal_segments (
 user_id uuid not null default auth.uid() references auth.users(id),
 segment_no bigint not null check(segment_no>0), expected_count integer not null check(expected_count between 1 and 50000),
 first_id bigint not null, last_id bigint not null, client_sha256 text not null check(client_sha256 ~ '^[0-9a-f]{64}$'),
 state text not null default 'UPLOADING' check(state in ('UPLOADING','VERIFIED')),
 primary key(user_id,segment_no),check(first_id>0 and last_id>=first_id)
);
create table public.aiv_personal_events (
 user_id uuid not null default auth.uid(),segment_no bigint not null,event_id bigint not null,
 event_json text not null,event_sha256 text not null check(event_sha256 ~ '^[0-9a-f]{64}$'),
 primary key(user_id,segment_no,event_id),
 foreign key(user_id,segment_no) references public.aiv_personal_segments(user_id,segment_no)
);
alter table public.aiv_personal_segments enable row level security;
alter table public.aiv_personal_events enable row level security;
create policy personal_segment_read on public.aiv_personal_segments for select to authenticated using((select auth.uid())=user_id);
create policy personal_segment_insert on public.aiv_personal_segments for insert to authenticated with check((select auth.uid())=user_id);
create policy personal_segment_update on public.aiv_personal_segments for update to authenticated using((select auth.uid())=user_id) with check((select auth.uid())=user_id);
create policy personal_event_read on public.aiv_personal_events for select to authenticated using((select auth.uid())=user_id);
create policy personal_event_insert on public.aiv_personal_events for insert to authenticated with check((select auth.uid())=user_id);
revoke all on public.aiv_personal_segments,public.aiv_personal_events from anon;
grant select,insert,update on public.aiv_personal_segments to authenticated;
grant select,insert on public.aiv_personal_events to authenticated;
-- Enforce immutability even for direct Data API writes.
create function public.aiv_personal_immutable() returns trigger language plpgsql security invoker set search_path='' as $$
begin
 if TG_TABLE_NAME='aiv_personal_events' then
  if NEW.event_sha256<>encode(extensions.digest(convert_to(NEW.event_json,'UTF8'),'sha256'),'hex') then raise exception 'event hash mismatch';end if;
  perform 1 from public.aiv_personal_segments where user_id=NEW.user_id and segment_no=NEW.segment_no and state='UPLOADING' for update;
  if not found then raise exception 'segment sealed or absent';end if;
 else
  if OLD.state='VERIFIED' or NEW.user_id<>OLD.user_id or NEW.segment_no<>OLD.segment_no or NEW.expected_count<>OLD.expected_count or NEW.first_id<>OLD.first_id or NEW.last_id<>OLD.last_id or NEW.client_sha256<>OLD.client_sha256 then raise exception 'immutable manifest';end if;
 end if;return NEW;
end $$;
revoke all on function public.aiv_personal_immutable() from public;
create trigger personal_event_guard before insert on public.aiv_personal_events for each row execute function public.aiv_personal_immutable();
create trigger personal_manifest_guard before update on public.aiv_personal_segments for each row execute function public.aiv_personal_immutable();
create function public.aiv_personal_archive(body jsonb) returns jsonb language plpgsql security invoker set search_path='' as $$
declare
 owner_id uuid:=auth.uid(); action text:=body->>'action'; segment bigint:=(body->>'segment_no')::bigint;
 m jsonb; e jsonb; s public.aiv_personal_segments%rowtype; n integer; digest_hex text; write_ok boolean:=false; last_segment bigint;
begin
 if owner_id is null then raise exception 'authenticated session required';end if;
 if action='probe' then
  select max(segment_no) into last_segment from public.aiv_personal_segments where user_id=owner_id and state='VERIFIED';
  -- Subtransaction rollback: verifies actual insert/update/select without keeping a probe row.
  begin
   insert into public.aiv_personal_segments(user_id,segment_no,expected_count,first_id,last_id,client_sha256) values(owner_id,9223372036854775806,1,1,1,repeat('0',64));
   insert into public.aiv_personal_events(user_id,segment_no,event_id,event_json,event_sha256) values(owner_id,9223372036854775806,1,'{}',encode(extensions.digest(convert_to('{}','UTF8'),'sha256'),'hex'));
   perform 1 from public.aiv_personal_events where user_id=owner_id and segment_no=9223372036854775806;
   if not found then raise exception 'probe read failed';end if;
   update public.aiv_personal_segments set state='VERIFIED' where user_id=owner_id and segment_no=9223372036854775806;
   raise sqlstate 'AIV01';
  exception when sqlstate 'AIV01' then write_ok:=true;when others then write_ok:=false;end;
  return jsonb_build_object('schema_version',1,'read_ok',true,'write_ok',write_ok,'last_verified_segment',last_segment);
 end if;
 if action='begin' then
  m:=body->'segment';
  insert into public.aiv_personal_segments(user_id,segment_no,expected_count,first_id,last_id,client_sha256)
   values(owner_id,segment,(m->>'expected_count')::integer,(m->>'first_event_id')::bigint,(m->>'last_event_id')::bigint,lower(m->>'client_segment_sha256')) on conflict do nothing;
 end if;
 select * into s from public.aiv_personal_segments where user_id=owner_id and segment_no=segment for update;
 if not found then raise exception 'segment absent';end if;
 if action='begin' and (s.expected_count<>(m->>'expected_count')::integer or s.first_id<>(m->>'first_event_id')::bigint or s.last_id<>(m->>'last_event_id')::bigint or s.client_sha256<>lower(m->>'client_segment_sha256')) then raise exception 'manifest differs';end if;
 if action='batch' then
  if jsonb_typeof(body->'events')<>'array' or jsonb_array_length(body->'events')>100 then raise exception 'invalid batch';end if;
  for e in select value from jsonb_array_elements(body->'events') loop
   if (e->>'event_id')::bigint not between s.first_id and s.last_id then raise exception 'event out of range';end if;
   if exists(select 1 from public.aiv_personal_events where user_id=owner_id and segment_no=segment and event_id=(e->>'event_id')::bigint) then
    if not exists(select 1 from public.aiv_personal_events where user_id=owner_id and segment_no=segment and event_id=(e->>'event_id')::bigint and event_json=e->>'event_json' and event_sha256=e->>'event_sha256') then raise exception 'duplicate differs';end if;
   else
    insert into public.aiv_personal_events(user_id,segment_no,event_id,event_json,event_sha256) values(owner_id,segment,(e->>'event_id')::bigint,e->>'event_json',e->>'event_sha256');
   end if;
  end loop;
  return jsonb_build_object('ok',true);
 end if;
 select count(*),encode(extensions.digest(convert_to(string_agg(event_id::text||':'||encode(extensions.digest(convert_to(event_json,'UTF8'),'sha256'),'hex'),E'\n' order by event_id),'UTF8'),'sha256'),'hex') into n,digest_hex from public.aiv_personal_events where user_id=owner_id and segment_no=segment;
 if action='finalize' then
  if n<>s.expected_count or digest_hex<>s.client_sha256 then raise exception 'remote count/hash mismatch';end if;
  if s.state<>'VERIFIED' then update public.aiv_personal_segments set state='VERIFIED' where user_id=owner_id and segment_no=segment;end if;
  return jsonb_build_object('state','VERIFIED','received_count',n,'server_segment_sha256',digest_hex);
 end if;
 if action='begin' then return jsonb_build_object('state',s.state,'received_count',n,'server_segment_sha256',digest_hex);end if;
 raise exception 'unsupported action';
end $$;
revoke all on function public.aiv_personal_archive(jsonb) from public,anon;
grant execute on function public.aiv_personal_archive(jsonb) to authenticated;
commit;
