package fr.erick.threeai;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class LocalStore {
    static final int MAX_FILE = 5 * 1024 * 1024;
    private final Context context;
    LocalStore(Context context) { this.context = context; }
    synchronized String read(String name, String fallback) throws IOException {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), name));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return fallback;
        try (InputStream in = file.openRead()) { return readBounded(in, MAX_FILE); }
    }
    static String readBounded(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > maximum) throw new IOException("Fichier trop volumineux.");
            out.write(b, 0, n);
        }
        return out.toString("UTF-8");
    }
    static String readImportText(InputStream in,int maximum)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1){if(out.size()+count>maximum)throw new IOException("Fichier trop volumineux.");out.write(buffer,0,count);}
        String value;
        try{value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();}
        catch(java.nio.charset.CharacterCodingException e){throw new IOException("Ce fichier contient des données binaires. Importer un rapport texte/JSON; joindre une image avec le bouton Image.");}
        validateImportText(value);return value;
    }
    static void validateImportText(String value)throws IOException {
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if((c<32&&c!='\n'&&c!='\r'&&c!='\t')||c==127)throw new IOException("Ce fichier contient des données binaires, pas un rapport texte/JSON.");}
        if(value.startsWith("%PDF-")||value.startsWith("data:image/")||value.startsWith("data:application/"))throw new IOException("Contenu de fichier encodé reçu. Choisir un rapport texte/JSON ou joindre l’image séparément.");
    }
    synchronized void write(String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE) throw new IOException("Stockage local plein : exporter avant de continuer.");
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), name)); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(bytes); file.finishWrite(out); }
        catch (IOException e) { if (out != null) file.failWrite(out); throw e; }
    }
    static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("role", role)
            .put("content", content).put("created_ms", System.currentTimeMillis()).put("synced", false);
    }
    static JSONArray payload(JSONArray history, String prompt, String memory, boolean useMemory,
                             String image, int budget, int responseTokens) throws Exception {
        JSONArray result = new JSONArray();
        String system = prompt + (useMemory && !memory.isEmpty() ? "\n\nMémoire personnelle fournie par l'utilisateur :\n" + memory : "");
        result.put(new JSONObject().put("role", "system").put("content", system));
        int first = Math.max(0, history.length() - 40);
        if (first < history.length() && "assistant".equals(history.getJSONObject(first).optString("role"))) first++;
        long chars = system.length();
        for (int i = first; i < history.length(); i++) {
            JSONObject item = history.getJSONObject(i); String content = item.getString("content"); chars += content.length();
            JSONObject entry = new JSONObject().put("role", item.getString("role")).put("content", content);
            if (i == history.length() - 1 && image != null) {
                JSONArray parts = new JSONArray().put(new JSONObject().put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", image)))
                    .put(new JSONObject().put("type", "text").put("text", content));
                entry.put("content", parts); chars += 4096;
            }
            result.put(entry);
        }
        // Conservative estimate, not a tokenizer. Never truncate the user's memory silently.
        if (chars / 2 + responseTokens > budget) throw new IOException("Mémoire/conversation trop longue pour le budget choisi. Augmenter le contexte ou ouvrir une nouvelle conversation; la mémoire reste intacte.");
        return result;
    }
}
