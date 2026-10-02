// Synthetic browser test: never executes commands on a real Android device.
const assert=require('node:assert/strict'),path=require('node:path'),{chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.AIV_TEST_CHROMIUM||undefined});
 try {
  const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript({path:path.join(__dirname,'v22-bridge.js')});
  await page.addInitScript(()=>{
   const b=window.JournalAndroid;b.startupStatus=()=>JSON.stringify({ready:false,state:'Calcul en cours'});
   b.controlAccess=()=>JSON.stringify({allowed:true,tier:3});
   b.accessPolicy=()=>JSON.stringify({tiers:{free:1,paid:2,it:3}});
   b.shizukuCleanupState=()=>JSON.stringify({status:'Fixture Shizuku disponible',running:false,authorized:true,binder:true,candidates:0});
   b.normalizationPreview=(pkg,profile)=>JSON.stringify({package:pkg,profile,stamp:'synthetic',changes:[{permission:'android.permission.CAMERA',proposed:'revoke'}]});
   b.normalizationApply=(...args)=>{window.__aivCalls.push(['normalize',...args]);return JSON.stringify({status:'Fixture : aucun téléphone modifié'});};
  });
  await page.goto('file://'+path.resolve(__dirname,'../app/src/main/assets/journal.html'));
  await page.locator('#jc-startup .aiv-startup-logo').waitFor();
  assert.match(await page.locator('.v32-logo').getAttribute('src'),/^data:image\/jpeg;base64,/);
  const c=await page.locator('#jc-startup').evaluate(el=>getComputedStyle(el).backgroundColor);
  assert.equal(c,'rgb(4, 16, 47)');
  await page.screenshot({path:'/tmp/aiv-startup.png'});
  await page.getByRole('button',{name:'Ouvrir le journal maintenant',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('#jc-startup')&&!document.querySelector('#jc-reader').inert);
  assert.equal(await page.locator('#jc-pane-journal').evaluate(el=>el.hidden),false);
  await page.getByRole('button',{name:'Mon parc · TI',exact:true}).first().click();
  assert.match(await page.locator('#jc-pane-fleet').innerText(),/Aucun parc connecté/);
  await page.getByRole('button',{name:'Mon appareil',exact:true}).first().click();
  await page.getByRole('button',{name:'Shizuku',exact:true}).click();
  assert.equal(await page.locator('#jc-pane-shizuku').evaluate(el=>el.hidden),false);
  await page.locator('#aiv-normal-package').fill('example.weather');
  await page.locator('#aiv-normal-preview').click();
  assert.match(await page.locator('#aiv-normal-result').innerText(),/android.permission.CAMERA/);
  assert.equal(await page.locator('#aiv-normal-apply').isEnabled(),true);
  assert.equal(await page.evaluate(()=>window.__aivCalls.some(c=>c[0]==='normalize')),false);
  await page.screenshot({path:'/tmp/aiv-shizuku.png'});
  page.once('dialog',dialog=>dialog.accept());await page.locator('#aiv-normal-apply').click();
  assert.deepEqual(await page.evaluate(()=>window.__aivCalls.find(c=>c[0]==='normalize')),['normalize','example.weather','weather','synthetic']);
  await page.evaluate(()=>window.JournalAndroid.controlAccess=()=>JSON.stringify({allowed:false,tier:1}));
  await page.getByRole('button',{name:'Journal',exact:true}).click();
  await page.getByRole('button',{name:'Shizuku',exact:true}).click();
  assert.equal(await page.locator('#jc-shizuku-run').isEnabled(),false);
  await page.locator('#aiv-normal-preview').click();
  assert.equal(await page.locator('#aiv-normal-apply').isEnabled(),false);
  assert.deepEqual(errors,[]);
  console.log('PASS logo, startup bypass, dedicated Shizuku tab, preview before action, free control unavailable');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
