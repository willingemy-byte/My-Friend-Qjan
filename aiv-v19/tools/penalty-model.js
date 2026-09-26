// Tests exercise the exact model embedded in the shipped reader.
const fs=require('node:fs'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/journal.html'),'utf8');
const start=html.indexOf('const PenaltyModel ='),end=html.indexOf("if(typeof module!=='undefined')module.exports=PenaltyModel;",start);
if(start<0||end<0)throw Error('Embedded PenaltyModel missing');
new Function('module',html.slice(start,end)+"\nmodule.exports=PenaltyModel;")(module);
