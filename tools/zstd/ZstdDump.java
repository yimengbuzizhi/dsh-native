import com.github.luben.zstd.ZstdInputStream;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

/**
 * 把多 zstd 帧拼接的会话文件（session.v4.jsonl.zstd / 旧版备份里的 v0/v3）解成文本。
 * 用法：java -cp zstd-jni.jar ZstdDump <in.zst> <out.jsonl>
 */
public final class ZstdDump {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ZstdDump <in.zst> <out.jsonl>");
            System.exit(2);
        }
        try (InputStream in = new FileInputStream(args[0]);
             ZstdInputStream zstd = new ZstdInputStream(in);
             OutputStream out = new FileOutputStream(args[1])) {
            zstd.setContinuous(true);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = zstd.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }
}
