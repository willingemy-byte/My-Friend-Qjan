// Synthetic UI verification only. No package is changed on a phone by this test.
const assert=require('node:assert/strict'),path=require('node:path');
let chromium;try{({chromium}=require('playwright'));}catch(e){({chromium}=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright'));}
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.AIV_CHROMIUM?{executablePath:process.env.AIV_CHROMIUM}:{})});
 try{
  const page=await browser.newPage({viewport:{width:412,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript({path:path.join(__dirname,'v22-bridge.js')});
  await page.addInitScript(()=>{
   const rows=[{package_name:'example.user',label:'Application à retirer',level:5,state:'TO_REVIEW',stamp:'v1',version_code:1,assessment:{can_request_uninstall:true,granted_high:1,findings:[{permission:'android.permission.WRITE_SETTINGS',level:5,granted:true,reason:'Capacité système'}],special_actions:['unknown_sources']}},{package_name:'example.system',label:'Composant préinstallé',level:5,state:'TO_REVIEW',stamp:'system',version_code:1,assessment:{can_request_uninstall:false,protected_reasons:['Application préinstallée'],special_actions:[]}}];
   const fixture=window.__defense={rows,pending:'',revision:1,history:[{id:1,kind:'INITIAL_REVIEW',at_ms:1000,data:{before:null,after:{package_name:'example.user'}}}]};
   const json=JSON.stringify,b=window.JournalAndroid;
   b.defenseStatus=()=>json({scan_id:1,revision:String(fixture.revision),watching:true,pending_action:fixture.pending,counts:{TO_REVIEW:rows.filter(r=>r.state==='TO_REVIEW').length},coverage:'Profil courant; surveillance locale.'});
   b.defenseOpenRequested=()=>false;
   b.defensePage=(scope,offset)=>{let rs=rows.filter(r=>scope==='ALL'||(scope==='HISTORY'?r.state==='REMOVED':r.state===scope));return json({rows:rs.slice(offset,offset+25),total:rs.length});};
   b.defenseDecide=(pkg,stamp,keep)=>{const row=rows.find(r=>r.package_name===pkg);if(row.stamp!==stamp)return json({error:'Dossier modifié'});row.state=keep?'KEPT':'TO_REVIEW';fixture.revision++;window.__aivCalls.push(['defenseDecide',pkg,keep]);return json({ok:true});};
   b.defenseAction=(pkg,action,stamp)=>{window.__aivCalls.push(['defenseAction',pkg,action,stamp]);fixture.pending=pkg;fixture.revision++;};
   b.defenseDetail=pkg=>json({app:{...rows.find(r=>r.package_name===pkg),snapshot:{package_name:pkg,version_name:'1.0',version_code:1,count:1,install_source:{installing_package:'example.store'}}},history:fixture.history,scope:'Dossier conservé après retrait.'});
   b.defenseRecover=()=>{fixture.pending='';fixture.revision++;return json({ok:true});};
  });
  await page.goto('file://'+path.resolve(__dirname,'../app/src/main/assets/journal.html'));
  await page.waitForFunction(()=>!document.querySelector('#jc-startup')&&!document.querySelector('#jc-reader').inert);
  assert.equal(await page.evaluate(()=>window.__aivCalls.filter(c=>c[0]==='defenseAction').length),0,'Startup never launches a destructive action');
  await page.locator('#v23-defense-home').click();
  await page.waitForFunction(()=>document.querySelector('#v23-defense').open);
  assert.equal(await page.locator('#v23-defense-list').getByRole('button',{name:'Désinstaller…',exact:true}).count(),1,'System app has no direct uninstall');
  await page.locator('#v23-defense-list article').first().getByRole('button',{name:'Conserver cette application',exact:true}).click();
  await page.locator('#v23-defense-filter').selectOption('KEPT');
  assert.match(await page.locator('#v23-defense-list').innerText(),/Application à retirer/);
  await page.locator('#v23-defense-list').getByRole('button',{name:'Remettre à examiner',exact:true}).click();
  await page.locator('#v23-defense-filter').selectOption('TO_REVIEW');
  await page.locator('#v23-defense-list').getByRole('button',{name:'Installation de sources inconnues',exact:true}).click();
  assert.ok(await page.evaluate(()=>window.__aivCalls.some(c=>c[0]==='defenseAction'&&c[2]==='unknown_sources')));
  await page.waitForFunction(()=>!document.querySelector('#v23-defense-pending').hidden);
  await page.getByRole('button',{name:'Vérifier après mon retour d’Android',exact:true}).click();
  await page.locator('#v23-defense-list').getByRole('button',{name:'Désinstaller…',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('#v23-defense-pending').hidden);
  assert.equal(await page.evaluate(()=>window.__defense.rows[0].state),'TO_REVIEW','Opening Android is not removal');
  assert.match(await page.locator('#v23-defense-pending').innerText(),/en attente/);
  await page.evaluate(()=>{window.__defense.pending='';window.__defense.revision++;window.__defense.history.push({id:2,at_ms:2000,kind:'UNINSTALL_CANCELLED',data:{change_confirmed:false}});window.AivDefenseRefresh();});
  assert.equal(await page.locator('#v23-defense-list').getByRole('button',{name:'Désinstaller…',exact:true}).count(),1,'Cancel keeps review available');
  await page.locator('#v23-defense-list article').first().getByRole('button',{name:'Dossier et enquête locale',exact:true}).click();
  assert.match(await page.locator('#v23-defense-detail').innerText(),/désinstallation annulée/);
  await page.evaluate(()=>{window.__defense.rows[0].state='REMOVED';window.__defense.revision++;window.AivDefenseRefresh();});
  await page.locator('#v23-defense-filter').selectOption('HISTORY');
  assert.equal(await page.locator('#v23-defense-list').getByRole('button',{name:'Désinstaller…',exact:true}).count(),0);
  await page.locator('#v23-defense-list').getByRole('button',{name:'Dossier et enquête locale',exact:true}).click();
  assert.match(await page.locator('#v23-defense-detail').innerText(),/example.store/,'Installer evidence remains accessible after removal');
  await page.getByRole('button',{name:'Exporter le journal de ménage',exact:true}).click();
  assert.ok(await page.evaluate(()=>window.__aivCalls.some(c=>c[0]==='command'&&c[1]==='export-defense')));
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2);assert.equal(overflow,false,'No page-wide horizontal overflow at phone width');
  if(process.env.AIV_SCREENSHOT)await page.screenshot({path:process.env.AIV_SCREENSHOT,fullPage:false});
  assert.deepEqual(errors,[]);
  console.log('PASS V23 mobile UI: startup, protected app, keep/review, special settings, pending/cancelled/removed states, dossier retention and export');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
