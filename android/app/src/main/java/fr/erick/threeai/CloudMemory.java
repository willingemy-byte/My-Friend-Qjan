package fr.erick.threeai;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class CloudMemory {
    private final SharedPreferences prefs;
    private final SecretStore secrets;
    private final ApiClient api = new ApiClient();
    CloudMemory(Context context, SecretStore secrets) { prefs = context.getSharedPreferences("settings", 0); this.secrets = secrets; }
    String url() throws Exception {
        String value = prefs.getString("supabase_url", "");
        if (value.isEmpty()) throw new IOException("Supabase séparé à configurer. Ta mémoire reste enregistrée sur ce téléphone.");
        return ApiClient.base(value);
    }
    private Map<String,String> headers(JSONObject session) throws Exception {
        Map<String,String> headers = new HashMap<>(); String key = prefs.getString("supabase_key", "").trim();
        if (key.isEmpty()) throw new IOException("Clé publique Supabase absente.");
        if (key.startsWith("sb_secret_")) throw new IOException("Utiliser une clé publique Supabase, jamais une clé secrète serveur.");
        if (key.startsWith("ey")) {
            try {
                String claims = new String(android.util.Base64.decode(key.split("\\.")[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP), "UTF-8");
                if ("service_role".equals(new JSONObject(claims).optString("role"))) throw new IOException("Une clé service_role ne doit pas être utilisée dans l'APK.");
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException("Clé publique Supabase invalide."); }
        }
        headers.put("apikey", key);
        if (session != null) headers.put("Authorization", "Bearer " + session.getString("access_token"));
        return headers;
    }
    private void storeSession(JSONObject result) throws Exception {
        UUID.fromString(result.getJSONObject("user").getString("id"));
        result.put("local_expiry_ms", System.currentTimeMillis() + result.getLong("expires_in") * 1000L);
        secrets.set("session:" + url(), result.toString());
    }
    void login(String email, String password) throws Exception {
        JSONObject body = new JSONObject().put("email", email).put("password", password);
        JSONObject result = new JSONObject(api.request(url(), "/auth/v1/token?grant_type=password", "POST", body, headers(null)));
        storeSession(result);
    }
    private synchronized JSONObject session() throws Exception {
        String stored = secrets.get("session:" + url());
        if (stored.isEmpty()) throw new IOException("Se connecter à Supabase dans Réglages.");
        JSONObject result = new JSONObject(stored);
        if (result.getLong("local_expiry_ms") < System.currentTimeMillis() + 60000) {
            JSONObject body = new JSONObject().put("refresh_token", result.getString("refresh_token"));
            result = new JSONObject(api.request(url(), "/auth/v1/token?grant_type=refresh_token", "POST", body, headers(null)));
            storeSession(result);
        }
        return result;
    }
    String owner() throws Exception { return session().getJSONObject("user").getString("id"); }
    private String revisionKey(JSONObject session) throws Exception { return "cloud_revision:" + url() + ":" + session.getJSONObject("user").getString("id"); }
    void logout() throws Exception {
        JSONObject s = session();
        try { api.request(url(), "/auth/v1/logout?scope=local", "POST", null, headers(s)); }
        finally { secrets.set("session:" + url(), ""); }
    }
    long saveMemory(String text) throws Exception {
        JSONObject s = session(); String revisionKey = revisionKey(s);
        JSONObject body = new JSONObject().put("p_body", text).put("p_expected_revision", prefs.getLong(revisionKey, 0));
        String result = api.request(url(), "/rest/v1/rpc/threeai_save_memory", "POST", body, headers(s));
        long revision = Long.parseLong(result.trim()); prefs.edit().putLong(revisionKey, revision).commit(); return revision;
    }
    JSONObject readMemory() throws Exception {
        JSONObject s = session();
        JSONArray data = new JSONArray(api.request(url(), "/rest/v1/threeai_memory?select=body,revision&owner_id=eq." + s.getJSONObject("user").getString("id"), "GET", null, headers(s)));
        if (data.length() == 0) throw new IOException("Aucune mémoire distante enregistrée.");
        return data.getJSONObject(0);
    }
    void acceptRevision(long revision) throws Exception { JSONObject s = session(); prefs.edit().putLong(revisionKey(s), revision).commit(); }
    void saveMessages(JSONArray local) throws Exception {
        JSONObject s = session(); String uid = s.getJSONObject("user").getString("id"); JSONArray batch = new JSONArray();
        for (int i = 0; i < local.length(); i++) {
            JSONObject item = local.getJSONObject(i);
            batch.put(new JSONObject().put("id", item.getString("id")).put("owner_id", uid)
                .put("role", item.getString("role")).put("content", item.getString("content")).put("created_ms", item.getLong("created_ms")));
            if (batch.length() == 50 || i == local.length() - 1) {
                Map<String,String> headers = headers(s); headers.put("Prefer", "resolution=ignore-duplicates,return=minimal");
                api.request(url(), "/rest/v1/threeai_messages?on_conflict=id", "POST", batch, headers); batch = new JSONArray();
            }
        }
    }
}
