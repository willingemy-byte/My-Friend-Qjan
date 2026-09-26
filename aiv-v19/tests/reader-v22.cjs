// Browser smoke test with synthetic Android bridge responses; not a device test.
const assert=require('node:assert/strict'),path=require('node:path');
let chromium;try{({chromium}=require('playwright'));}catch(e){({chromium}=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright'));}
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.AIV_CHROMIUM?{executablePath:process.env.AIV_CHROMIUM}:{})});
 try{
  const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript({path:path.join(__dirname,'v22-bridge.js')});
  await page.goto('file://'+path.resolve(__dirname,'../app/src/main/assets/journal.html'));
  await page.waitForFunction(()=>!document.querySelector('#jc-startup')&&!document.querySelector('#jc-reader').inert);
  console.log('Startup',await page.locator('#jc-mode-banner').innerText());
  await page.getByRole('button',{name:'Applications',exact:true}).click();
  await page.getByRole('button',{name:'Application exemple',exact:true}).first().click();
  assert.match(await page.locator('#ja-detail').innerText(),/L5/);
  const pedigree=page.locator('#ja-detail details').filter({has:page.locator('summary', {hasText:'Pédigrée APK'})});await pedigree.first().locator('summary').click();
  assert.match(await pedigree.first().innerText(),/synthetic-certificate/);
  await page.getByRole('button',{name:'Paramètres',exact:true}).click();
  await page.locator('#jc-pane-menu summary').filter({hasText:/^Référentiels$/}).click();
  await page.waitForFunction(()=>document.querySelector('#v22-reference-status').textContent.includes('1138'));
  assert.match(await page.locator('#v22-reference-status').innerText(),/1138/);
  await page.locator('#v22-tracker-query').fill('tracker');await page.locator('#v22-tracker-search').click();
  assert.match(await page.locator('#v22-tracker-results').innerText(),/SDK exemple/);
  await page.locator('#v22-tracker-export').click();
  assert.ok(await page.evaluate(()=>window.__aivCalls.some(c=>c[0]==='command'&&c[1]==='export-trackers')));
  await page.getByRole('button',{name:'Voir ce flux dans le journal',exact:true}).click();
  assert.ok(await page.evaluate(()=>window.__aivCalls.some(c=>c[0]==='flowPage'&&c[1]==='synthetic-flow')));
  await page.locator('#jf-list summary').first().click();
  assert.match(await page.locator('#jf-list').innerText(),/SDK exemple/);
  assert.deepEqual(errors,[]);
  console.log('PASS reader startup, application L5, pedigree, catalogue status, tracker search, export command and flow drilldown');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
