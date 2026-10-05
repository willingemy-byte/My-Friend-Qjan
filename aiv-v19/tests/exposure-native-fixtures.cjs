const fs=require('node:fs'),M=require('../tools/penalty-model');
const clone=x=>JSON.parse(JSON.stringify(x));
const app=permissions=>({package_name:'com.example.synthetic',version_code:7,permissions});
const p=(name,extra={})=>({name:'android.permission.'+name,...extra});
const list=(names,extra={})=>({count:names.length,kind:'permissions',source:'Synthetic version 7 list',same_version:true,version_code:7,surface:'google_play',permission_names:names,...extra});
const evidence=(principal,toutes)=>({penalty_evidence:{visibility:{principal,toutes}}});
const project=e=>({level:e.level,disclosure_verified:e.disclosure_verified,visibility_status:e.visibility_status,
  findings:e.findings.map(f=>({permission:f.permission,level:f.level,reason:f.reason,source:f.source,granted:f.granted,effective_status:f.effective_status,operation_observed:f.operation_observed})),
  counts:e.counts});
const cases=[];
function add(a,ref={},label='synthetic'){cases.push({label,app:a,reference:ref,expected:project(M.exposure(a,ref))});}
const ordinary=app([p('INTERNET',{granted:true}),p('ACCESS_NETWORK_STATE')]);
add(ordinary,{},'missing visibility');
const full=list(ordinary.permissions.map(p=>p.name));add(ordinary,evidence(full),'verified A1');
add(ordinary,evidence(list([ordinary.permissions[0].name]),full),'verified A2');
for(const extra of [{version_code:6},{version_code:'7'},{same_version:false},{same_version:'true'},{kind:'groups'},{source:'  '},{surface:'android_settings'},{count:1},{count:'2'}])add(ordinary,evidence({...full,...extra}),'invalid principal '+JSON.stringify(extra));
add(ordinary,evidence(list([ordinary.permissions[0].name]),list(['android.permission.FAKE'])),'contradictory lists');
add(ordinary,evidence(list([ordinary.permissions[0].name])),'partial list');
for(const name of ['RECEIVE_BOOT_COMPLETED','READ_LOGS','READ_PHONE_STATE','PACKAGE_USAGE_STATS','WRITE_SETTINGS','GRANT_RUNTIME_PERMISSIONS','INTERACT_ACROSS_USERS','INSTALL_PACKAGES','QUERY_ALL_PACKAGES','REQUEST_INSTALL_PACKAGES','CAMERA']){
 for(const granted of [true,false,null])add(app([p(name,{granted})]),{},name+' grant '+granted);
}
for(const description of ['Allows the app to act without your confirmation.','Permet de modifier les données à votre insu.','Ne permet pas de modifier les données à votre insu.','Does not allow acting without asking.','Malicious apps may misuse this permission.','Une application malveillante peut détourner ce droit.','Sans votre intervention immédiate.','Without user interaction.','Description non fournie']){
 add(app([p('EXAMPLE',{description})]),{},'phone description '+description);
 add(app([p('EXAMPLE',{bayton:{description,reference_api_level:37}})]),{},'catalog description '+description);
}
add(app(['android.permission.RECEIVE_BOOT_COMPLETED']),{},'string permission');
add(app([p('WRITE_SETTINGS'),p('WRITE_SETTINGS',{granted:false})]),{},'duplicate identity latest state');
add(app([]),evidence(list([])),'empty verified inventory');
const permissionPool=[p('INTERNET'),p('RECEIVE_BOOT_COMPLETED',{granted:false}),p('WRITE_SETTINGS',{granted:true}),p('CUSTOM',{description:'Malicious apps may misuse this permission.'}),p('OTHER',{description:'Allows changes without asking.'}),p('CAMERA')];
for(let i=0;i<256;i++){
 const permissions=permissionPool.filter((_,n)=>(i>>n)&1).map(clone),a=app(permissions),names=permissions.map(p=>p.name),principal=names.filter((_,n)=>(i>>(n+2))&1);
 const ref=i%4===0?{}:i%4===1?evidence(list(names)):i%4===2?evidence(list(principal),list(names)):evidence(list(principal,{version_code:6}),list(names));
 add(a,ref,'combined '+i);
}
fs.writeFileSync(process.argv[2],JSON.stringify(cases));
console.log('Generated '+cases.length+' synthetic parity cases from the existing shipped JS model');
