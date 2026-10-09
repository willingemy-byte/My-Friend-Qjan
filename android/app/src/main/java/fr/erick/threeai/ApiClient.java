package fr.erick.threeai;

import org.json.JSONObject;
import org.json.JSONArray;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Collections;

final class ApiClient {
    interface Connections { HttpsURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    ApiClient() { this(url -> (HttpsURLConnection) url.openConnection()); }
    ApiClient(Connections connections) { this.connections = connections; }
    static String origin(String endpoint) {
        try { URI u = new URI(base(endpoint)); return u.getScheme() + "://" + u.getHost() + (u.getPort() < 0 ? "" : ":" + u.getPort()); }
        catch (Exception e) { return "adresse HTTPS invalide"; }
    }
    static class HttpFailure extends IOException {
        final int code;
        HttpFailure(int code, String endpoint, boolean html) {
            super(origin(endpoint) + " · HTTP " + code + "\n" +
                (code == 401 ? "Authentification refusée. Vérifier la clé enregistrée pour ce serveur." :
                 code == 403 ? "Serveur joignable, accès refusé. La clé, les droits du compte ou un filtre réseau peuvent être en cause." :
                 code == 402 ? "Accès au modèle refusé : vérifier les crédits et le modèle." :
                 code == 404 ? "Adresse API ou modèle introuvable." :
                 code == 429 ? "Limite de requêtes atteinte. Réessayer plus tard." :
                 code == 409 ? "Conflit de mémoire : relire la version distante." : "Le serveur a refusé la requête.") +
                (html ? "\nRéponse HTML reçue; le refus peut venir d’un intermédiaire réseau." : ""));
            this.code = code;
        }
    }
    private volatile HttpsURLConnection active;
    static String base(String value) throws Exception {
        String s = value.trim().replaceAll("/+$", ""); URI uri = new URI(s);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IOException("Entrer une vraie adresse API HTTPS, sans clé dans l'adresse.");
        return s;
    }
    void cancel() { HttpsURLConnection c = active; if (c != null) c.disconnect(); }
    interface Response<T> { T read(InputStream in,String contentType) throws Exception; }
    String request(String base, String path, String method, Object body, Map<String,String> headers) throws Exception {
        return response(base,path,method,body,headers,(in,type)->LocalStore.readBounded(in,2*1024*1024));
    }
    private <T> T response(String base,String path,String method,Object body,Map<String,String> headers,Response<T> reader)throws Exception {
        HttpsURLConnection c = connections.open(new URL(base(base) + path)); active = c;
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(90000); c.setRequestMethod(method);
            c.setRequestProperty("Content-Type", "application/json");
            for (Map.Entry<String,String> entry : headers.entrySet()) {
                if (entry.getValue().contains("\n") || entry.getValue().contains("\r")) throw new IOException("Clé invalide.");
                c.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                if (data.length > 4 * 1024 * 1024) throw new IOException("Requête trop volumineuse.");
                c.setDoOutput(true); c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int code = c.getResponseCode(); if (code < 200 || code >= 300) throw new HttpFailure(code, base, c.getContentType() != null && c.getContentType().toLowerCase(java.util.Locale.ROOT).contains("text/html"));
            try (InputStream in = c.getInputStream()) { return reader.read(in,c.getContentType()); }
        } catch (UnknownHostException e) { throw new IOException(origin(base) + " · nom du serveur introuvable. Vérifier l’adresse et le réseau.");
        } catch (SocketTimeoutException e) { throw new IOException(origin(base) + " · délai réseau dépassé. Aucun refus HTTP identifié.");
        } catch (SSLException e) { throw new IOException(origin(base) + " · connexion HTTPS non établie. Vérifier l’heure Android et le réseau.");
        } finally { c.disconnect(); if (active == c) active = null; }
    }
    String chat(String base, String model, String key, JSONArray messages, double temperature, int tokens) throws Exception {
        if (key.isEmpty()) throw new IOException("Clé absente pour ce serveur. Ajouter la clé dans Réglages puis enregistrer. La clé GitHub reste dans GitHub.");
        if (key.matches("(?s).*\\s.*")) throw new IOException("Clé mal collée : saisir uniquement sa valeur, sans Bearer, guillemets ni commande.");
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("stream", false)
            .put("temperature", temperature).put("max_tokens", tokens);
        JSONObject result = new JSONObject(request(base, "/chat/completions", "POST", body, Collections.singletonMap("Authorization", "Bearer " + key)));
        JSONObject choice = result.getJSONArray("choices").getJSONObject(0);
        String content = choice.getJSONObject("message").optString("content", "");
        if (content.trim().isEmpty())
            throw new IOException("Réponse interrompue ou vide. Augmenter la limite de réponse si nécessaire.");
        return content;
    }
    ChatStream.Result stream(String base,String model,String key,JSONArray messages,double temperature,int tokens,ChatStream.Listener listener)throws Exception {
        if(key.isEmpty())throw new IOException("Clé absente pour ce serveur. L’ajouter dans Réglages.");
        if(key.matches("(?s).*\\s.*"))throw new IOException("Clé mal collée.");
        JSONObject body=new JSONObject().put("model",model).put("messages",messages).put("stream",true).put("temperature",temperature).put("max_tokens",tokens);
        return response(base,"/chat/completions","POST",body,Collections.singletonMap("Authorization","Bearer "+key),(in,type)->{
            if(type!=null&&type.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream"))return ChatStream.read(new InputStreamReader(in,StandardCharsets.UTF_8),listener);
            JSONObject choice=new JSONObject(LocalStore.readBounded(in,2*1024*1024)).getJSONArray("choices").getJSONObject(0);
            String text=choice.getJSONObject("message").optString("content","");if(text.trim().isEmpty())throw new IOException("Aucune réponse textuelle reçue.");listener.update(text,false);return new ChatStream.Result(text,choice.optString("finish_reason","interrupted"));
        });
    }
}
