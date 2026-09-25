const assert=require('node:assert/strict'),M=require('../tools/penalty-model');
const perm='android.permission.WRITE_CALL_LOG';
const app=(name,uid,ps,hidden=ps.map(p=>p.name))=>({package_name:name,label:name,uid,profile_id:0,version_code:1,enabled:true,components_collected:true,permissions:ps,local_reference:{version_code:1,estimated:true,visible_permission_count:ps.length-hidden.length,hidden_candidates:hidden,reference_note:'test'}});
const visibility={count:0,kind:'permissions',source:'test',same_version:true,version_code:1};
// Manual evidence: 25 distinct capabilities, each weighted at L2/L3 and reaching 25 apps.
let a=app('owner',10001,Array.from({length:60},(_,i)=>({name:'p.'+i,granted:true})));
let input={apps:[a],references:{owner:{penalty_evidence:{visibility:{principal:visibility},findings:Array.from({length:25},(_,i)=>({id:'cap'+i,levels:[2,3,4],hidden:true,share_count:25,version_code:1,source:'test',reason:'test'}))}}}};
let r=M.evaluate(input);assert.deepEqual(r.apps[0].factors,['50','75','625','1']);assert.equal(r.apps[0].multiplier,'2343750');assert.equal(r.summary.base_mean,'60.00');
// Use derived visibility of exact permission identities.
a=app('owner',10001,[{name:perm,granted:true,warning:'Les applications malveillantes peuvent modifier vos journaux.'}]);
a.components=[{name:'CallLog',type:'provider',enabled:true,exported:true,write_permission:perm}];
const clients=Array.from({length:25},(_,i)=>app('client'+i,11000+i,[{name:perm,granted:true}],[]));
input={apps:[a,...clients],references:{}};r=M.evaluate(input);let row=r.apps.find(x=>x.package_name==='owner');
assert.equal(row.H,1);assert.deepEqual(row.factors,['2','3','25','1']);assert.equal(row.multiplier,'150');assert.equal(row.routes[0].clients.length,25);
// 25 recipients don't become 25 separate abilities. Duplicate components and scan rows are deduplicated.
a.components.push({...a.components[0],name:'OtherCallLog'});input.apps.push(clients[0]);row=M.evaluate(input).apps.find(x=>x.package_name==='owner');assert.equal(row.factors[2],'25');
// No inferred delegation from a common permission alone; no blocked/disabled/cross-profile recipients.
a.components=[];row=M.evaluate(input).apps.find(x=>x.package_name==='owner');assert.equal(row.factors[2],'1');
a.components=[{name:'CallLog',type:'provider',enabled:true,exported:false,write_permission:perm}];assert.equal(M.evaluate(input).apps.find(x=>x.package_name==='owner').routes.length,0);
a.components[0].exported=true;clients[0].permissions[0].granted=false;clients[1].profile_id=1;clients[2].uid=a.uid;clients[3].enabled=false;row=M.evaluate(input).apps.find(x=>x.package_name==='owner');assert.equal(row.factors[2],'21');
// Declared but denied is not autonomous capability; Android class dangerous alone is not an observed warning.
a.permissions[0].granted=false;row=M.evaluate(input).apps.find(x=>x.package_name==='owner');assert.equal(row.multiplier,'1');
a.permissions[0].granted=true;delete a.permissions[0].warning;row=M.evaluate(input).apps.find(x=>x.package_name==='owner');assert.equal(row.factors[1],'1');
// Unknown permission names don't receive fabricated capabilities.
input={apps:[app('mystery',10044,[{name:'vendor.permission.UNKNOWN',granted:true}])],references:{}};row=M.evaluate(input).apps[0];assert.equal(row.multiplier,'1');assert.equal(row.color,'unknown');assert.equal(row.coverage_partial,true);
// L5 needs confirmed special access, then distinct Android installation relations.
a=app('installer',10001,[{name:'p',granted:true}]);const child=app('child',10002,[]);child.install_source={source_available:true,source:'PackageManager.getInstallSourceInfo',installing_package:'installer'};
const finding={id:'special',levels:[5],hidden:true,active:true,outside_permissions:true,version_code:1,source:'capture',reason:'test'};
input={apps:[a,child],references:{installer:{penalty_evidence:{visibility:{principal:visibility},findings:[finding]}}}};
row=M.evaluate(input).apps.find(x=>x.package_name==='installer');assert.equal(row.counts[5],1);assert.equal(row.K,1);finding.active=null;assert.equal(M.evaluate(input).apps.find(x=>x.package_name==='installer').counts[5],0);
// Exact Android description markers add L2; the warning adds L3 only on an eligible autonomous capability.
const custom=app('described',10008,[{name:'android.permission.CUSTOM',granted:true,autonomy_description:'Permet de modifier les données à votre insu.',warning:'Les applications malveillantes peuvent modifier les données.'}]);
row=M.evaluate({apps:[custom],references:{}}).apps[0];assert.deepEqual(row.factors,['2','3','1','1']);
custom.permissions[0].autonomy_description='Ne permet pas de modifier les données à votre insu.';row=M.evaluate({apps:[custom],references:{}}).apps[0];assert.equal(row.multiplier,'1');
const old=M.validatePolicy({schema:'aiv-penalty-policy/2',rules:[]});assert.equal(old.schema,'aiv-penalty-policy/3');assert.equal(old.automatic.enabled,true);
console.log('PASS automatic capability provenance, granted states, warnings, 25 recipients, 25 capabilities ×25, deduplication, profiles, unknown coverage, L5 confirmation and migration');