import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class IcoWriter {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input.png output.ico");
        BufferedImage source = ImageIO.read(new File(args[0]));
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        byte[][] payloads = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            BufferedImage image = new BufferedImage(sizes[i], sizes[i], BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, sizes[i], sizes[i], null);
            g.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            payloads[i] = png.toByteArray();
        }
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            // ICONDIR followed by one PNG-backed ICONDIRENTRY per standard size.
            writeLE16(out, 0); writeLE16(out, 1); writeLE16(out, sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                int size = sizes[i];
                out.writeByte(size == 256 ? 0 : size); // 0 represents 256
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(0); out.writeByte(0); // palette/reserved
                writeLE16(out, 1); writeLE16(out, 32);
                writeLE32(out, payloads[i].length); writeLE32(out, offset);
                offset += payloads[i].length;
            }
            for (byte[] payload : payloads) out.write(payload);
        }
    }
    static void writeLE16(java.io.DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 255); o.writeByte((v >>> 8) & 255);
    }
    static void writeLE32(java.io.DataOutputStream o, int v) throws IOException {
        o.writeByte(v & 255); o.writeByte((v >>> 8) & 255); o.writeByte((v >>> 16) & 255); o.writeByte((v >>> 24) & 255);
    }
}
