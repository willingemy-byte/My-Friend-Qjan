const assert=require('node:assert/strict'),M=require('../tools/penalty-model');
const app=(permissions)=>({audit_schema:'aiv-audit/22',package_name:'test.app',uid:10123,version_code:7,permissions,components_collected:true});
const p=(name,extra={})=>({name:'android.permission.'+name,granted:true,...extra});
let a=app([p('INTERNET')]);assert.equal(M.exposure(a).level,0);
// A estimated reference or matching count is not a Play/manifest equivalence.
a.local_reference={estimated:true,version_code:7,visible_permission_count:1,hidden_candidates:[]};assert.equal(M.evaluate({apps:[a]}).apps[0].color,'unknown');
const visibility={count:1,kind:'permissions',source:'Synthetic Play snapshot',same_version:true,version_code:7,surface:'google_play'};
let ref={penalty_evidence:{visibility:{principal:visibility}}};assert.equal(M.exposure(a,ref).level,0);
visibility.permission_names=['android.permission.INTERNET'];assert.equal(M.exposure(a,ref).level,1);
visibility.version_code=6;assert.equal(M.exposure(a,ref).level,0);visibility.version_code=7;
visibility.surface='android_settings';assert.equal(M.exposure(a,ref).level,0);visibility.surface='google_play';
a.permissions.push(p('ACCESS_NETWORK_STATE'));ref.penalty_evidence.visibility.toutes={...visibility,count:2,permission_names:a.permissions.map(p=>p.name)};assert.equal(M.exposure(a,ref).level,2);
ref.penalty_evidence.visibility.toutes.permission_names=['android.permission.ACCESS_NETWORK_STATE','android.permission.FAKE'];assert.equal(M.exposure(a,ref).visibility_status,'CONTRADICTOIRE');
for(const [name,extra,level]of [
 ['EXAMPLE',{description:'Allows the app to act without your confirmation.'},3],
 ['EXAMPLE',{description:'Description non fournie',bayton:{description:'Malicious apps may misuse this permission.',reference_api_level:37}},4],
 ['WRITE_SETTINGS',{},5],['GRANT_RUNTIME_PERMISSIONS',{},5],
 ['REQUEST_INSTALL_PACKAGES',{},0],['QUERY_ALL_PACKAGES',{},0],['CAMERA',{protection_level:1},0]]){
 const e=M.exposure(app([p(name,extra)]));assert.equal(e.level,level,name);assert.equal(e.color,({0:'unknown',3:'orange',4:'red',5:'gray'})[level]);
}
a=app([p('WRITE_SETTINGS',{granted:false})]);let e=M.exposure(a);assert.equal(e.level,5);assert.equal(e.findings[0].effective_status,'NON_ACCORDEE');assert.equal(e.findings[0].operation_observed,false);
let r=M.evaluate({apps:[a]});assert.equal(r.apps[0].color,'gray');assert.equal(r.summary.color,'gray');assert.equal(r.summary.exposure_level,5);assert.equal(r.summary.exposure_levels[5],1);
// No fabricated activity from a warning, and an unknown companion prevents a green summary.
a=app([p('INTERNET')]);const unknown={...app([p('OTHER')]),package_name:'test.other',uid:10200};r=M.evaluate({apps:[a,unknown],references:{'test.app':{penalty_evidence:{visibility:{principal:visibility}}}}});assert.equal(r.apps.find(a=>a.package_name==='test.app').color,'green');assert.equal(r.summary.color,'unknown');
assert.throws(()=>M.validateEvidence({visibility:{principal:{...visibility,count:2}}}));
console.log('PASS V22 disclosure identity/version, L1–L5 colors, unknown coverage, grant state and historical formula separation');
