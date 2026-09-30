const fs=require('node:fs'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/journal.html'),'utf8');
const start=html.indexOf('const AuditRules='),end=html.indexOf("if(typeof module!=='undefined')module.exports=AuditRules;",start);
if(start<0||end<0)throw Error('Embedded AuditRules missing');
new Function('module',html.slice(start,end)+"\nmodule.exports=AuditRules;")(module);
