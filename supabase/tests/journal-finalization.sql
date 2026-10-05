BEGIN;
DO $test$
DECLARE
  device uuid := gen_random_uuid();
  hash text := encode(extensions.digest('{"fixture":true}','sha256'),'hex');
  manifest text;
  result record;
BEGIN
  manifest := encode(extensions.digest('11:'||hash||chr(10)||'31:'||hash,'sha256'),'hex');
  INSERT INTO public.aiv_devices(install_id,secret_hash) VALUES(device,repeat('f',64));
  INSERT INTO public.aiv_journal_segments(install_id,segment_no,first_event_id,last_event_id,expected_count,client_segment_sha256)
    VALUES(device,1,11,31,2,manifest);
  INSERT INTO public.aiv_journal_events(install_id,segment_no,event_id,event_sha256,timestamp_ms,event_json,payload)
    VALUES(device,1,11,hash,1000,'{"fixture":true}','{"fixture":true}'::jsonb),
      (device,1,31,hash,1001,'{"fixture":true}','{"fixture":true}'::jsonb);
  SELECT * INTO result FROM public.aiv_finalize_journal_segment(device,1);
  IF result.state<>'VERIFIED' OR result.received_count<>2 OR result.server_segment_sha256<>manifest THEN RAISE EXCEPTION 'valid LF manifest rejected'; END IF;
  SELECT * INTO result FROM public.aiv_finalize_journal_segment(device,1);
  IF result.state<>'VERIFIED' OR result.server_segment_sha256<>manifest THEN RAISE EXCEPTION 'repeat finalize changed receipt'; END IF;
  UPDATE public.aiv_journal_segments SET client_segment_sha256=repeat('0',64) WHERE install_id=device;
  SELECT * INTO result FROM public.aiv_finalize_journal_segment(device,1);
  IF result.state<>'FAILED' THEN RAISE EXCEPTION 'bad hash accepted'; END IF;
  UPDATE public.aiv_journal_segments SET client_segment_sha256=manifest,expected_count=3 WHERE install_id=device;
  SELECT * INTO result FROM public.aiv_finalize_journal_segment(device,1);
  IF result.state<>'FAILED' THEN RAISE EXCEPTION 'bad count accepted'; END IF;
  UPDATE public.aiv_journal_segments SET expected_count=2,last_event_id=99 WHERE install_id=device;
  SELECT * INTO result FROM public.aiv_finalize_journal_segment(device,1);
  IF result.state<>'FAILED' THEN RAISE EXCEPTION 'bad range accepted'; END IF;
  IF has_function_privilege('anon','public.aiv_finalize_journal_segment(uuid,bigint)','EXECUTE')
    OR has_function_privilege('authenticated','public.aiv_finalize_journal_segment(uuid,bigint)','EXECUTE')
    OR NOT has_function_privilege('service_role','public.aiv_finalize_journal_segment(uuid,bigint)','EXECUTE')
    THEN RAISE EXCEPTION 'verifier privileges changed'; END IF;
END;
$test$;
ROLLBACK;
