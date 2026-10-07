package fr.erick.journallocal;
import android.app.*;
import android.widget.*;
import org.json.*;
import java.net.URI;
/** Read-only sales console. Server authorizes the existing device signing key. */
final class OwnerConsole {
 static String url(String value)throws Exception{
  URI uri=new URI(value.trim());
  if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))throw new SecurityException("URL HTTPS du serveur, sans clé ni identifiant dans l’adresse");
  return value.trim().replaceAll("/+$","");
 }
 static JSONObject fetch(Activity c,String base)throws Exception{
  JSONObject identity=DeviceIdentity.describe();
  JSONObject challenge=LicenseClient.post(base+"/challenge",new JSONObject().put("public_key",identity.getString("public_key_spki_b64")),null,null);
  String nonce=challenge.getString("nonce");JSONObject signature=DeviceIdentity.sign(nonce+"\n/owner/licenses\n{}");
  return LicenseClient.post(base+"/owner/licenses",new JSONObject(),nonce,signature.getString("signature_b64"));
 }
 static void open(Activity c){
  LinearLayout layout=new LinearLayout(c);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,12,24,12);
  TextView info=new TextView(c);info.setText("Suivi réservé au propriétaire. Aucun secret PayPal à entrer dans AIV.\nIdentité à autoriser côté serveur :\n"+DeviceIdentity.describe().optString("key_id","Identité indisponible"));layout.addView(info);
  EditText server=new EditText(c);server.setSingleLine(true);server.setHint("https://licence.votre-domaine");
  try{server.setText(ProtectedSettings.read(c,"owner_console").optString("base_url",EditionConfig.LICENSE_URL));}catch(Exception ignored){}layout.addView(server);
  new AlertDialog.Builder(c).setTitle("Console propriétaire").setView(layout).setNegativeButton("Fermer",null).setPositiveButton("Vérifier et consulter",(d,w)->{
   String value=server.getText().toString();
   new Thread(()->{try{
    String base=url(value);JSONObject result=fetch(c,base);
    if(result.getInt("schema_version")!=1||!result.getBoolean("owner_verified"))throw new SecurityException("Accès propriétaire non vérifié");
    ProtectedSettings.save(c,"owner_console",new JSONObject().put("base_url",base));
    StringBuilder text=new StringBuilder("Ventes confirmées : "+result.getInt("sold_confirmed")+" / 1000\nActives : "+result.getInt("active")+"\nRévoquées : "+result.getInt("revoked")+"\nRéservées, non payées : "+result.getInt("reserved")+"\nMode PayPal : "+result.getString("mode")+"\n\n");
    JSONArray rows=result.getJSONArray("licenses");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);text.append("Founder ").append(r.getInt("number")).append(" · ").append(r.getString("state")).append("\nLicence : ").append(r.getString("licence_id")).append("\nIdentité : ").append(r.getString("key_id")).append("\nPaiement : ").append(r.optString("payment_status")).append("\n\n");}
    c.runOnUiThread(()->show(c,"Liste serveur vérifiée",text.toString()));
   }catch(Exception e){c.runOnUiThread(()->show(c,"Console non accessible",e.getMessage()));}},"aiv-owner-sales").start();
  }).show();
 }
 static void show(Activity c,String title,String value){TextView t=new TextView(c);t.setText(value);t.setTextIsSelectable(true);t.setPadding(24,16,24,16);ScrollView scroll=new ScrollView(c);scroll.addView(t);new AlertDialog.Builder(c).setTitle(title).setView(scroll).setPositiveButton("Fermer",null).show();}
}
