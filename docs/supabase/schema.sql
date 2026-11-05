-- jkanime TV — esquema de sincronización multi-dispositivo
--
-- Diseño: no se usa Supabase Auth. La identidad es un "sync space"
-- identificado por un código de 8 dígitos + un token secreto. Ambos se
-- generan una sola vez (sync_create) y se comparten entre dispositivos
-- introduciendo el código. El token nunca sale del dispositivo salvo hacia
-- estas funciones.
--
-- Por qué no Auth: la app corre en Android TV, donde teclear email+password
-- con el D-pad es inviable y Play Services no está garantizado. Un código
-- numérico de 8 dígitos sí es usable con el mando.
--
-- Ejecutar en el SQL Editor de Supabase.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Tablas
-- ---------------------------------------------------------------------------

create table if not exists sync_spaces (
    id         uuid primary key default gen_random_uuid(),
    code       text unique not null,
    token      text not null,
    created_at timestamptz not null default now()
);

create table if not exists favorites (
    space_id             uuid not null references sync_spaces(id) on delete cascade,
    slug                 text not null,
    anime_id             int,
    title                text,
    poster_url           text,
    last_watched_episode int,
    last_aired_episode   int,
    total_episodes       int,
    status_label         text,
    -- Epoch millis (no timestamptz): el cliente Kotlin trabaja en millis y
    -- así no hay conversiones de zona horaria ni de formato ISO.
    updated_at           bigint not null default 0,
    -- Borrado lógico: sin tombstone, un dispositivo que no vio el borrado
    -- resucitaría el favorito en el siguiente push.
    deleted_at           bigint,
    primary key (space_id, slug)
);

create table if not exists progress (
    space_id    uuid not null references sync_spaces(id) on delete cascade,
    slug        text not null,
    episode     int not null,
    position_ms bigint not null default 0,
    duration_ms bigint not null default 0,
    completed   boolean not null default false,
    updated_at  bigint not null default 0,
    primary key (space_id, slug, episode)
);

create index if not exists favorites_space_idx on favorites (space_id);
create index if not exists progress_space_idx on progress (space_id);

-- ---------------------------------------------------------------------------
-- RLS: nadie entra directamente a las tablas. Solo las funciones
-- SECURITY DEFINER de abajo, que validan código+token.
-- ---------------------------------------------------------------------------

alter table sync_spaces enable row level security;
alter table favorites  enable row level security;
alter table progress   enable row level security;

-- ---------------------------------------------------------------------------
-- RPC: crear un espacio nuevo
-- ---------------------------------------------------------------------------

create or replace function sync_create()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_code  text;
    v_token text;
    v_id    uuid;
begin
    loop
        v_code := lpad((floor(random() * 100000000))::bigint::text, 8, '0');
        exit when not exists (select 1 from sync_spaces where code = v_code);
    end loop;

    v_token := encode(gen_random_bytes(24), 'hex');

    insert into sync_spaces (code, token) values (v_code, v_token)
    returning id into v_id;

    return jsonb_build_object('code', v_code, 'token', v_token);
end;
$$;

-- ---------------------------------------------------------------------------
-- RPC: leer todo el estado del espacio
-- ---------------------------------------------------------------------------

create or replace function sync_pull(p_code text, p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_space uuid;
begin
    select id into v_space
    from sync_spaces
    where code = p_code and token = p_token;

    if v_space is null then
        raise exception 'invalid_credentials';
    end if;

    return jsonb_build_object(
        'favorites', coalesce(
            (select jsonb_agg(to_jsonb(f)) from favorites f where f.space_id = v_space),
            '[]'::jsonb
        ),
        'progress', coalesce(
            (select jsonb_agg(to_jsonb(p)) from progress p where p.space_id = v_space),
            '[]'::jsonb
        )
    );
end;
$$;

-- ---------------------------------------------------------------------------
-- RPC: subir cambios locales
--
-- El guard `where excluded.updated_at >= tabla.updated_at` hace que un push
-- con datos viejos (un dispositivo que estuvo offline) nunca pise una
-- escritura más reciente hecha desde otro dispositivo.
-- ---------------------------------------------------------------------------

create or replace function sync_push(
    p_code      text,
    p_token     text,
    p_favorites jsonb default '[]'::jsonb,
    p_progress  jsonb default '[]'::jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_space  uuid;
    v_favs   int := 0;
    v_progs  int := 0;
begin
    select id into v_space
    from sync_spaces
    where code = p_code and token = p_token;

    if v_space is null then
        raise exception 'invalid_credentials';
    end if;

    if jsonb_typeof(p_favorites) = 'array' and jsonb_array_length(p_favorites) > 0 then
        insert into favorites (
            space_id, slug, anime_id, title, poster_url, last_watched_episode,
            last_aired_episode, total_episodes, status_label, updated_at, deleted_at
        )
        select
            v_space,
            x->>'slug',
            nullif(x->>'anime_id', '')::int,
            x->>'title',
            x->>'poster_url',
            nullif(x->>'last_watched_episode', '')::int,
            nullif(x->>'last_aired_episode', '')::int,
            nullif(x->>'total_episodes', '')::int,
            x->>'status_label',
            coalesce((x->>'updated_at')::bigint, 0),
            nullif(x->>'deleted_at', '')::bigint
        from jsonb_array_elements(p_favorites) x
        where x->>'slug' is not null
        on conflict (space_id, slug) do update set
            anime_id             = excluded.anime_id,
            title                = excluded.title,
            poster_url           = excluded.poster_url,
            last_watched_episode = excluded.last_watched_episode,
            last_aired_episode   = excluded.last_aired_episode,
            total_episodes       = excluded.total_episodes,
            status_label         = excluded.status_label,
            updated_at           = excluded.updated_at,
            deleted_at           = excluded.deleted_at
        where excluded.updated_at >= favorites.updated_at;

        get diagnostics v_favs = row_count;
    end if;

    if jsonb_typeof(p_progress) = 'array' and jsonb_array_length(p_progress) > 0 then
        insert into progress (
            space_id, slug, episode, position_ms, duration_ms, completed, updated_at
        )
        select
            v_space,
            x->>'slug',
            (x->>'episode')::int,
            coalesce((x->>'position_ms')::bigint, 0),
            coalesce((x->>'duration_ms')::bigint, 0),
            coalesce((x->>'completed')::boolean, false),
            coalesce((x->>'updated_at')::bigint, 0)
        from jsonb_array_elements(p_progress) x
        where x->>'slug' is not null and x->>'episode' is not null
        on conflict (space_id, slug, episode) do update set
            position_ms = excluded.position_ms,
            duration_ms = excluded.duration_ms,
            completed   = excluded.completed,
            updated_at  = excluded.updated_at
        where excluded.updated_at >= progress.updated_at;

        get diagnostics v_progs = row_count;
    end if;

    return jsonb_build_object('favorites', v_favs, 'progress', v_progs);
end;
$$;

-- Solo los roles de PostgREST (anon/authenticated) pueden invocar las RPC.
-- Se comprueba que existan para que el script también corra en un Postgres
-- pelado (tests locales), donde esos roles no están definidos.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        grant execute on function sync_create() to anon;
        grant execute on function sync_pull(text, text) to anon;
        grant execute on function sync_push(text, text, jsonb, jsonb) to anon;
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        grant execute on function sync_create() to authenticated;
        grant execute on function sync_pull(text, text) to authenticated;
        grant execute on function sync_push(text, text, jsonb, jsonb) to authenticated;
    end if;
end $$;
