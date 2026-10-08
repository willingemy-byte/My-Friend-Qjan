package fr.erick.threeai;

import org.json.JSONObject;
import org.json.JSONArray;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Collections;

final class ApiClient {
    static class HttpFailure extends IOException {
        final int code;
        HttpFailure(int code) { super(code == 402 ? "Accès au modèle refusé (402) : vérifier les crédits et le modèle." :
            code == 401 || code == 403 ? "Accès refusé : vérifier la clé ou la connexion." :
            code == 409 ? "Conflit de mémoire : relire la version distante avant d'enregistrer." : "Erreur du service HTTP " + code + "."); this.code = code; }
    }
    private volatile HttpsURLConnection active;
    static String base(String value) throws Exception {
        String s = value.trim().replaceAll("/+$", ""); URI uri = new URI(s);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IOException("Entrer une vraie adresse API HTTPS, sans clé dans l'adresse.");
        return s;
    }
    void cancel() { HttpsURLConnection c = active; if (c != null) c.disconnect(); }
    String request(String base, String path, String method, Object body, Map<String,String> headers) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(base(base) + path).openConnection(); active = c;
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
            int code = c.getResponseCode(); if (code < 200 || code >= 300) throw new HttpFailure(code);
            try (InputStream in = c.getInputStream()) { return LocalStore.readBounded(in, 2 * 1024 * 1024); }
        } finally { c.disconnect(); if (active == c) active = null; }
    }
    String chat(String base, String model, String key, JSONArray messages, double temperature, int tokens) throws Exception {
        if (key.isEmpty()) throw new IOException("Ajouter ta clé Ollama dans Réglages. La clé GitHub reste dans GitHub.");
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("stream", false)
            .put("temperature", temperature).put("max_tokens", tokens);
        JSONObject result = new JSONObject(request(base, "/chat/completions", "POST", body, Collections.singletonMap("Authorization", "Bearer " + key)));
        JSONObject choice = result.getJSONArray("choices").getJSONObject(0);
        String content = choice.getJSONObject("message").optString("content", "");
        if (!"stop".equals(choice.optString("finish_reason")) || content.trim().isEmpty())
            throw new IOException("Réponse interrompue ou vide. Augmenter la limite de réponse si nécessaire.");
        return content;
    }
}
