package fr.erick.threeai;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class AssistantShell extends IAssistantShell.Stub {
    public AssistantShell() {}
    private String read(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new Exception(); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[1024]; int count;
            while ((count = in.read(buf)) != -1) { if (out.size() + count > 8192) break; out.write(buf, 0, count); }
        }
        return out.toString("UTF-8").trim();
    }
    @Override public String diagnostic() {
        try { return read("id") + "\nModèle : " + read("getprop", "ro.product.model") + "\nAndroid : " + read("getprop", "ro.build.version.release"); }
        catch (Exception e) { return "Diagnostic shell indisponible."; }
    }
    @Override public void destroy() { System.exit(0); }
}
