CREATE OR REPLACE FUNCTION public.aiv_finalize_journal_segment(p_install_id uuid, p_segment_no bigint)
RETURNS TABLE(state text, received_count integer, server_segment_sha256 text, first_event_id bigint, last_event_id bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
  s public.aiv_journal_segments%rowtype;
  v_count bigint;
  v_first bigint;
  v_last bigint;
  v_sha text;
  v_valid boolean;
BEGIN
  SELECT * INTO s FROM public.aiv_journal_segments
  WHERE install_id=p_install_id AND segment_no=p_segment_no FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'segment_not_found'; END IF;

  SELECT count(*), min(e.event_id), max(e.event_id),
    encode(extensions.digest(coalesce(string_agg(e.event_id::text || ':' || e.event_sha256,
      chr(10) ORDER BY e.event_id), ''), 'sha256'), 'hex')
    INTO v_count,v_first,v_last,v_sha
  FROM public.aiv_journal_events e WHERE e.install_id=p_install_id AND e.segment_no=p_segment_no;
  v_valid := v_count=s.expected_count AND v_first=s.first_event_id
    AND v_last=s.last_event_id AND v_sha=s.client_segment_sha256;
  UPDATE public.aiv_journal_segments
  SET received_count=v_count::integer, server_segment_sha256=v_sha,
    state=CASE WHEN v_valid THEN 'VERIFIED' ELSE 'FAILED' END,
    verified_at=CASE WHEN v_valid THEN now() ELSE NULL END,
    error=CASE WHEN v_valid THEN NULL ELSE format('count/hash/range mismatch: got count=%s first=%s last=%s sha=%s',v_count,v_first,v_last,v_sha) END,
    updated_at=now()
  WHERE install_id=p_install_id AND segment_no=p_segment_no;
  RETURN QUERY SELECT x.state,x.received_count,x.server_segment_sha256,x.first_event_id,x.last_event_id
    FROM public.aiv_journal_segments x WHERE x.install_id=p_install_id AND x.segment_no=p_segment_no;
END;
$function$;
REVOKE ALL ON FUNCTION public.aiv_finalize_journal_segment(uuid,bigint) FROM public,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.aiv_finalize_journal_segment(uuid,bigint) TO service_role;
COMMENT ON FUNCTION public.aiv_finalize_journal_segment(uuid,bigint) IS
'Service-role segment verification. SHA-256 uses extensions.pgcrypto and LF matches the Android manifest.';
