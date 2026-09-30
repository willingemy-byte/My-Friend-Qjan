const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const model=require('../tools/penalty-model');
const java=fs.readFileSync(path.join(__dirname,'../app/src/main/java/fr/erick/journallocal/DefenseRules.java'),'utf8');
const nativeNames=[...java.split('private static final Set<String> SYSTEM')[1].split('private static final Pattern')[0].matchAll(/"([A-Z_]+)"/g)].map(m=>'android.permission.'+m[1]);
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/journal.html'),'utf8');
const jsNames=[...html.split('const system=new Set(')[1].split('const autonomous=')[0].matchAll(/'([A-Z_]+)'/g)].map(m=>'android.permission.'+m[1]);
assert.deepEqual(new Set(nativeNames),new Set(jsNames),'The queue must match the actual displayed L5 rules');
for(const name of nativeNames)assert.equal(model.exposure({permissions:[{name,granted:false}]}).level,5);
console.log('PASS native review threshold matches all displayed L5 capability rules, independent of grants and multipliers');
