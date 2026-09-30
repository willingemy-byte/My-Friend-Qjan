// Review priority, not a security verdict. Counts with different units remain provisional.
const AuditRules=(()=>{
  const levels={gray:0,green:1,blue:1,yellow:2,orange:3,red:4};
  function compare(manifest,reference={},version){
    const count=reference.play_count;
    if(!Number.isFinite(manifest)||manifest<0||typeof count!=='number'||!Number.isFinite(count)||count<=0)return {level:'gray',ratio:null,label:'Comparaison à compléter',provisional:true};
    const ratio=manifest/count,level=ratio>=4?'red':ratio>=3?'orange':ratio>=2?'yellow':'green';
    const provisional=reference.count_kind!=='permissions'||reference.same_version!==true||!reference.play_source||reference.reference_origin!=='Saisie utilisateur'||reference.installed_version_code!==version;
    return {level,ratio,label:(provisional?'Priorité provisoire':'Priorité de revue')+' · '+ratio.toFixed(1)+'×',provisional};
  }
  function eventGroup(event){const d=event&&(event.details||(event.original&&event.original.details))||{};if(typeof d.journal_group==='string')return d.journal_group;const uid=Number(d.uid);if(!Number.isInteger(uid)||uid<0||uid%100000<10000)return 'android';if(!Array.isArray(d.packages)||d.packages.length!==1)return 'android';return d.system_app===true?'system':'user';}
  function packageFor(event){const d=event.details||(event.original&&event.original.details)||{};return eventGroup(event)==='android'?null:Number.isInteger(d.uid)&&d.uid>=0&&Array.isArray(d.packages)&&d.packages.length===1&&typeof d.packages[0]==='string'?d.packages[0]:null;}
  function max(a,b){return levels[a]>=levels[b]?a:b;}
  return {compare,eventGroup,packageFor,max};
})();
if(typeof module!=='undefined')module.exports=AuditRules;
