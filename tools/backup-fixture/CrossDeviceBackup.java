import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 生成「跨设备恢复」fixture 备份（M36，docs/60）：
 * 拿一条真实 v4 会话，把 header 换成新的 session id + 目标 cwd，重新压成逐事件帧，
 * 再打包成 DSH 原生备份 v1（manifest.json + settings.json + 一个会话）。
 *
 * 用法：
 *   java -cp "zstd-jni.jar;classes" CrossDeviceBackup &lt;source.zst&gt; &lt;out.zip&gt; &lt;new-id&gt; &lt;new-cwd&gt;
 *
 * 用途：设备上恢复这份备份，验证「原 cwd 不存在 → 映射到当前工作区」。
 */
public final class CrossDeviceBackup {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: CrossDeviceBackup <source.zst> <out.zip> <new-id> <new-cwd>");
            System.exit(2);
        }
        File source = new File(args[0]);
        File out = new File(args[1]);
        String newId = args[2];
        String newCwd = args[3];

        String text;
        try (InputStream in = new FileInputStream(source);
             ZstdInputStream zstd = new ZstdInputStream(in)) {
            zstd.setContinuous(true);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = zstd.read(chunk)) > 0) buffer.write(chunk, 0, n);
            text = buffer.toString(StandardCharsets.UTF_8.name());
        }

        String[] lines = text.split("\n", -1);
        ByteArrayOutputStream events = new ByteArrayOutputStream();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            events.write(Zstd.compress((lines[i] + "\n").getBytes(StandardCharsets.UTF_8), 3));
        }

        String header = "{\"type\":\"session\",\"version\":4,\"id\":\"" + jsonEscape(newId)
            + "\",\"createdAt\":" + System.currentTimeMillis()
            + ",\"cwd\":\"" + jsonEscape(newCwd) + "\",\"isSeeded\":false,\"delegationDepth\":0}";
        ByteArrayOutputStream session = new ByteArrayOutputStream();
        session.write(Zstd.compress((header + "\n").getBytes(StandardCharsets.UTF_8), 3));
        session.write(events.toByteArray());
        byte[] sessionBytes = session.toByteArray();

        String sha = sha256(sessionBytes);
        String slug = workspaceSlug(newCwd);
        String manifest = "{"
            + "\"type\":\"dsh.native.backup\",\"version\":1,"
            + "\"createdAt\":" + System.currentTimeMillis() + ","
            + "\"appVersion\":\"fixture-m36\","
            + "\"includes\":{\"settings\":true,\"plugins\":false,\"attachments\":false,\"credentials\":false},"
            + "\"sessions\":[{\"id\":\"" + jsonEscape(newId) + "\",\"workspace\":\"" + jsonEscape(slug)
            + "\",\"legacy\":false,\"bytes\":" + sessionBytes.length + ",\"sha256\":\"" + sha
            + "\",\"cwd\":\"" + jsonEscape(newCwd) + "\"}],"
            + "\"plugins\":[],"
            + "\"counts\":{\"sessions\":1,\"plugins\":0,\"settingsKeys\":0,\"attachmentsFiles\":0,"
            + "\"attachmentsBytes\":0,\"totalBytes\":" + sessionBytes.length + "},"
            + "\"warnings\":[]}";
        String settings = "{\"strings\":{},\"booleans\":{},\"ints\":{},\"longs\":{},\"floats\":{},\"stringSets\":{}}";

        out.getParentFile().mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            put(zos, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(zos, "settings.json", settings.getBytes(StandardCharsets.UTF_8));
            put(zos, "sessions/" + slug + "/" + newId + "/session.v4.jsonl.zstd", sessionBytes);
        }
        System.out.println("ok zip=" + out.getAbsolutePath()
            + " sessionBytes=" + sessionBytes.length
            + " entry=sessions/" + slug + "/" + newId + "/session.v4.jsonl.zstd");
    }

    private static void put(ZipOutputStream zos, String name, byte[] bytes) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    /** 与 Kotlin WorkspaceSlug.of 一致：非字母数字压成 '-'，首尾去 '-'，包 `--`。 */
    private static String workspaceSlug(String cwd) {
        String body = cwd.replaceAll("[^A-Za-z0-9]+", "-");
        int start = 0;
        int end = body.length();
        while (start < end && body.charAt(start) == '-') start++;
        while (end > start && body.charAt(end - 1) == '-') end--;
        return "--" + body.substring(start, end) + "--";
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }
}
