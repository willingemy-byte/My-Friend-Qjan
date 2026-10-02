// Synthetic WebView bridge only: does not connect to or modify a phone.
const assert=require('node:assert/strict'),path=require('node:path'),{chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.AIV_TEST_CHROMIUM||undefined});
 try {
  const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript({path:path.join(__dirname,'v22-bridge.js')});
  await page.addInitScript(()=>{
   const b=window.JournalAndroid;b.startupStatus=()=>JSON.stringify({ready:false,state:'Calcul en cours'});
   b.controlAccess=()=>JSON.stringify({allowed:true,tier:3});b.accessPolicy=()=>JSON.stringify({tiers:{free:1,paid:2,it:3}});
   b.developerControlState=()=>JSON.stringify({status:'Fixture',busy:false,watch_enabled:false,watch_count:0,watch:{},shizuku:true,collector:true});
   b.developerControlTargets=()=>JSON.stringify({applications:[{package:'example.optional',label:'Facultative',system:true,enabled:0,stopped:false,reserved_reason:''},{package:'com.android.systemui',label:'Interface système',system:true,enabled:0,reserved_reason:'Essentielle'}]});
   b.developerControlPreview=(packages,action)=>{window.__aivCalls.push(['preview',packages,action]);return JSON.stringify({stamp:'synthetic',action,before:[{package:'example.optional',label:'Facultative',user:0,enabled:0}],excluded:[]});};
   b.developerControlApply=(stamp,watch)=>{window.__aivCalls.push(['control',stamp,watch]);return JSON.stringify({status:'Fixture lancée'});};
   b.developerControlReport=()=>JSON.stringify({id:'fixture',phase:'finished',entries:[{package:'example.optional',action:'disable',outcome:'no_effect',exit:0,stderr:'Fixture : état inchangé'}]});
   b.developerControlMonitoring=enabled=>{window.__aivCalls.push(['monitor',enabled]);return JSON.stringify({});};
   b.developerControlRestore=()=>{window.__aivCalls.push(['restore']);return JSON.stringify({status:'Fixture restauration'});};
  });
  await page.goto('file://'+path.resolve(__dirname,'../app/src/main/assets/journal.html'));
  await page.getByRole('button',{name:'Ouvrir le journal maintenant',exact:true}).click();
  await page.getByRole('button',{name:'Shizuku',exact:true}).click();
  await page.locator('#aiv-control-load').click();
  assert.equal(await page.locator('#aiv-control-targets input:disabled').count(),1);
  await page.locator('#aiv-control-targets input:enabled').check();await page.locator('#aiv-control-action').selectOption('disable');
  await page.locator('#aiv-control-preview').click();
  assert.match(await page.locator('#aiv-control-preview-result').innerText(),/example.optional/);
  assert.equal(await page.evaluate(()=>window.__aivCalls.some(c=>c[0]==='control')),false);
  await page.locator('#aiv-control-action').selectOption('stop');assert.equal(await page.locator('#aiv-control-apply').isDisabled(),true);
  await page.locator('#aiv-control-preview').click();await page.locator('#aiv-control-watch').check();await page.locator('#aiv-control-apply').click();
  assert.deepEqual(await page.evaluate(()=>window.__aivCalls.find(c=>c[0]==='control')),['control','synthetic',true]);
  await page.locator('#aiv-control-report').click();assert.match(await page.locator('#aiv-control-results').innerText(),/Commande sans effet/);
  await page.locator('#aiv-control-pause').click();await page.locator('#aiv-control-restore').click();
  assert.deepEqual(await page.evaluate(()=>window.__aivCalls.find(c=>c[0]==='monitor')),['monitor',false]);
  assert.equal(await page.evaluate(()=>window.__aivCalls.some(c=>c[0]==='restore')),true);
  await page.screenshot({path:'/tmp/aiv-developer-control.png'});assert.deepEqual(errors,[]);
  console.log('PASS selected targets, essential exclusion, pre-state, invalidated preview, explicit action, honest result, pause/restore');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
