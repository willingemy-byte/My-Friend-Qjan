(()=>{
 const json=JSON.stringify;
 const app={audit_schema:'aiv-audit/22',package_name:'example.audit',label:'Application exemple',uid:10123,profile_id:0,uid_packages:['example.audit'],system_app:false,enabled:true,attribution:'Un paquet',version_name:'1.0',version_code:7,components:[],components_collected:true,permissions:[{name:'android.permission.WRITE_SETTINGS',label:'Modifier les paramètres système',description:'Allows changing system settings.',protection_level:18,protection:'Signature',granted:true,defined_by:'android',bayton:{description:'Allows an app to modify system settings.',reference_api_level:37}}],count:1,reference:{},certificates:{current_sha256:['synthetic-certificate'],history_sha256:[]},apk_evidence:{status:'COMPLETE_WITHIN_SCOPE',parts:[{name:'base.apk',sha256:'synthetic-apk'}],trackers:[{id:1,name:'SDK exemple'}]},install_source:{source_available:true,installing_package:'example.store'}};
 const input={scan_id:1,apps:[app],references:{}};
 const match={tracker_id:1,name:'SDK exemple',host:'tracker.example',evidence:'TLS_SNI',domain_boundary_match:true};
 const flow={app:app.label,actor:app.label,uid:app.uid,packages:[app.package_name],attribution_unique:true,attribution_status:'ATTRIBUTION_UNIQUE',journal_group:'user',flow_correlation_id:'synthetic-flow',remote_ip:'192.0.2.1',remote_port:443,protocol:'TCP',tls_sni:'tracker.example',tracker_matches:[match],tracker_dns_candidates:[],tx_bytes:1234,rx_bytes:567,first_observed_ms:1000,first_outbound_ms:1100,first_inbound_ms:1200,last_packet_ms:1500};
 let settings={schema:'aiv-penalty-settings/1',policy:{},references:{}};
 const status={catalog:{bayton_permissions:1138,exodus_trackers:432,fetched_utc:'2026-09-26',device_api_level:35,bayton_api_level:37,notice:'Fixture synthétique'},apk:{cached_packages:1,busy:false,scope:'Fixture'},index:{enabled:true,checkpoint:100,latest_event:100,candidate_flows:1,notice:'Fixture'}};
 window.__aivCalls=[];
 window.JournalAndroid={
 startupStatus:()=>json({ready:true,state:'Prêt'}),startupData:()=>json(input),status:()=>json({capture:false,running:false,total:0}),
 penaltyData:()=>json(input),penaltyConfig:()=>json(settings),penaltySave:value=>{settings=JSON.parse(value);return json({ok:true})},calculationLoad:()=>json({cached:false}),calculationSave:()=>json({ok:true}),
 auditSummary:()=>json({scan_id:1,total:1,system:0,coverage:'Fixture'}),auditPage:()=>json({rows:[app],total:1}),auditDetail:()=>json({app,reference:settings.references[app.package_name]?{penalty_evidence:settings.references[app.package_name]}:{},history:[],imported_findings:[]}),
 aivSummary:()=>json({sources:[],apps:[],rules:[],findings:0,verification:'FIXTURE',checkpoint:100,latest_event_id:100}),aivPage:()=>json({rows:[]}),
 trackerStatus:()=>json(status),trackerPage:(q,b)=>{window.__aivCalls.push(['trackerPage',q,b]);return json({rows:q&&!['SDK','exemple','tracker','example'].some(x=>q.includes(x))?[]:[flow],next_before:1,status:status.index})},trackerResume:()=>window.__aivCalls.push(['resume']),
 flowPage:q=>{window.__aivCalls.push(['flowPage',q]);return json({flows:[flow],scanned_events:1,notice:'Fixture'})},
 analysisSummary:()=>json({config:{quiet:true},total:0,unread:0,running:false}),analysisPage:()=>json({rows:[],total:0}),coherenceDetail:()=>json({available:false}),coherenceSummary:()=>json({available:false}),
 command:c=>window.__aivCalls.push(['command',c]),openAuditedApp:()=>{}, apps:()=>json([]),permissions:()=>json([]),transparencySummary:()=>json({}),
 };
})();
