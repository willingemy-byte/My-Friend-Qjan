-- To apply ONLY to a separately selected 3AI project. Not applied by the APK.
-- No personal data, service_role credentials or provider keys belong in this file.
begin;
create table public.threeai_memory (
    owner_id uuid primary key references auth.users(id) on delete cascade,
    body text not null check (octet_length(body) <= 2097152),
    revision bigint not null default 1 check (revision > 0),
    updated_at timestamptz not null default now()
);
create table public.threeai_messages (
    id uuid primary key,
    owner_id uuid not null references auth.users(id) on delete cascade,
    role text not null check (role in ('user','assistant')),
    content text not null check (octet_length(content) <= 1048576),
    created_ms bigint not null
);
create index threeai_messages_owner_created on public.threeai_messages(owner_id,created_ms);
alter table public.threeai_memory enable row level security;
alter table public.threeai_messages enable row level security;
revoke all on public.threeai_memory, public.threeai_messages from anon;
grant select,insert,update,delete on public.threeai_memory to authenticated;
grant select,insert,delete on public.threeai_messages to authenticated;
create policy own_memory_select on public.threeai_memory for select to authenticated using ((select auth.uid()) = owner_id);
create policy own_memory_insert on public.threeai_memory for insert to authenticated with check ((select auth.uid()) = owner_id);
create policy own_memory_update on public.threeai_memory for update to authenticated using ((select auth.uid()) = owner_id) with check ((select auth.uid()) = owner_id);
create policy own_memory_delete on public.threeai_memory for delete to authenticated using ((select auth.uid()) = owner_id);
create policy own_messages_select on public.threeai_messages for select to authenticated using ((select auth.uid()) = owner_id);
create policy own_messages_insert on public.threeai_messages for insert to authenticated with check ((select auth.uid()) = owner_id);
create policy own_messages_delete on public.threeai_messages for delete to authenticated using ((select auth.uid()) = owner_id);
create function public.threeai_save_memory(p_body text, p_expected_revision bigint)
returns bigint language plpgsql security invoker set search_path = '' as $$
declare v_revision bigint;
begin
    if auth.uid() is null then raise exception 'Sign in required' using errcode = '42501'; end if;
    if p_expected_revision < 0 then raise exception 'Invalid revision' using errcode = '22023'; end if;
    insert into public.threeai_memory(owner_id,body,revision)
    select auth.uid(),p_body,1 where p_expected_revision = 0
    on conflict(owner_id) do nothing
    returning revision into v_revision;
    if v_revision is null then
        update public.threeai_memory set body=p_body,revision=revision+1,updated_at=now()
        where owner_id=auth.uid() and revision=p_expected_revision
        returning revision into v_revision;
    end if;
    if v_revision is null then raise exception 'Memory changed remotely' using errcode = 'PT409'; end if;
    return v_revision;
end $$;
revoke all on function public.threeai_save_memory(text,bigint) from public,anon;
grant execute on function public.threeai_save_memory(text,bigint) to authenticated;
commit;
