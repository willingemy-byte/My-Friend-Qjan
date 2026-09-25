const assert=require('node:assert/strict');
const rules=require('../tools/audit-rules.js');
const ref={play_count:12,count_kind:'permissions',same_version:true,play_source:'Capture locale',reference_origin:'Saisie utilisateur',installed_version_code:5};
assert.equal(rules.compare(72,ref,5).level,'red');
assert.equal(rules.compare(72,ref,5).provisional,false);
for(const [n,level]of [[0,'green'],[23,'green'],[24,'yellow'],[35,'yellow'],[36,'orange'],[47,'orange'],[48,'red']])assert.equal(rules.compare(n,ref,5).level,level);
for(const count of [null,undefined,0,-1,NaN,Infinity,'12'])assert.equal(rules.compare(72,{...ref,play_count:count},5).level,'gray');
for(const patch of [{count_kind:'groups'},{same_version:false},{play_source:''},{reference_origin:'Valeur importée'},{installed_version_code:4}])assert.equal(rules.compare(72,{...ref,...patch},5).provisional,true);
assert.equal(rules.packageFor({details:{uid:10123,packages:['test.app']}}),'test.app');
for(const d of [{uid:1000,packages:['test.app']},{uid:101000,packages:['test.app']},{uid:10123,packages:['test.a','test.b']},{uid:-1,packages:['test.app']},{uid:10123,packages:[]}])assert.equal(rules.packageFor({details:d}),null);
assert.equal(rules.max('yellow','green'),'yellow');
assert.equal(rules.max('yellow','red'),'red');
console.log('PASS: 29 audit checks — thresholds, absent counts, provenance, versions and shared UIDs.');

assert.equal(rules.eventGroup({original:{details:{uid:10271,packages:['com.google.android.apps.photos'],system_app:true,journal_group:'system'}}}),'system');
assert.equal(rules.packageFor({original:{details:{uid:10271,packages:['com.google.android.apps.photos'],system_app:true}}}),'com.google.android.apps.photos');
console.log('PASS normalized Photos event retains system group and unique package');