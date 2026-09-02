import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the MultiVPN icon candidates. Brand identity: deep navy base
 * (#05070E), electric blue -> violet gradient (#4F8CFF -> #8B5CF6), cyan
 * accent (#22D3EE). All geometry is hand-drawn (no fonts) so the result is
 * deterministic on every machine.
 *
 * Usage: java IconGen <outputDir>   ->  icon-1..5 at 1024px (+256px previews)
 */
public final class IconGen {

    static final Color BG_TOP = new Color(0x0A0F1E);
    static final Color BG = new Color(0x05070E);
    static final Color BLUE = new Color(0x4F8CFF);
    static final Color VIOLET = new Color(0x8B5CF6);
    static final Color CYAN = new Color(0x22D3EE);
    static final float[] GRAD_STOPS = {0f, 1f};

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "icon");
        Files.createDirectories(out);
        List<Runnable> designs = List.of(
            () -> draw(out, 1, "m-monogram", IconGen::mMonogram),
            () -> draw(out, 2, "shield-m", IconGen::shieldM),
            () -> draw(out, 3, "portal-tunnel", IconGen::portalTunnel),
            () -> draw(out, 4, "m-node", IconGen::mNode),
            () -> draw(out, 5, "shield-bolt", IconGen::shieldBolt)
        );
        designs.forEach(Runnable::run);
        System.out.println("done: " + out.toAbsolutePath());
    }

    interface Painter { void paint(Graphics2D g, int s); }

    static void draw(Path dir, int n, String name, Painter p) {
        for (int size : new int[]{1024, 256}) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            canvas(g, size);
            p.paint(g, size);
            g.dispose();
            try {
                File f = dir.resolve(String.format("icon-%d-%s-%d.png", n, name, size)).toFile();
                ImageIO.write(img, "png", f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Rounded-square canvas: navy vertical gradient + hairline inner border. */
    static void canvas(Graphics2D g, int s) {
        float r = s * 0.225f;
        g.setPaint(new LinearGradientPaint(0, 0, 0, s,
            new float[]{0f, 1f}, new Color[]{BG_TOP, BG}));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, r * 2, r * 2));
        g.setStroke(new BasicStroke(s * 0.012f));
        g.setColor(new Color(0x33, 0x46, 0x66, 140));
        g.draw(new RoundRectangle2D.Float(s * 0.006f, s * 0.006f,
            s * 0.988f, s * 0.988f, r * 2 - s * 0.012f, r * 2 - s * 0.012f));
    }

    static LinearGradientPaint brand(int s) {
        return new LinearGradientPaint(0, s * 0.18f, s * 0.9f, s * 0.85f,
            GRAD_STOPS, new Color[]{BLUE, VIOLET});
    }

    static LinearGradientPaint brandX(int s, float x0, float y0, float x1, float y1) {
        return new LinearGradientPaint(s * x0, s * y0, s * x1, s * y1,
            GRAD_STOPS, new Color[]{BLUE, VIOLET});
    }

    static void glowStroke(Graphics2D g, int s, java.awt.Shape sh) {
        // fake outer glow: wide low-alpha strokes under the crisp stroke
        Color glow = new Color(0x4F8CFF);
        for (int i = 5; i >= 1; i--) {
            g.setStroke(new BasicStroke(s * 0.02f * i, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 14));
            g.draw(sh);
        }
    }

    // 1 - bold gradient M with glow
    static void mMonogram(Graphics2D g, int s) {
        java.awt.geom.Path2D.Float m = new java.awt.geom.Path2D.Float();
        float w = s * 0.52f, h = s * 0.40f;
        float x0 = (s - w) / 2, y0 = s * 0.30f;
        m.moveTo(x0, y0 + h);
        m.lineTo(x0, y0);
        m.lineTo(x0 + w / 2, y0 + h * 0.62f);
        m.lineTo(x0 + w, y0);
        m.lineTo(x0 + w, y0 + h);
        glowStroke(g, s, m);
        g.setStroke(new BasicStroke(s * 0.075f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(brand(s));
        g.draw(m);
    }

    // 2 - shield + gradient M
    static void shieldM(Graphics2D g, int s) {
        java.awt.geom.Path2D.Float sh = shieldPath(s);
        glowStroke(g, s, sh);
        g.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(brand(s));
        g.draw(sh);

        java.awt.geom.Path2D.Float m = new java.awt.geom.Path2D.Float();
        float w = s * 0.28f, h = s * 0.22f;
        float x0 = (s - w) / 2f, y0 = s * 0.40f;
        m.moveTo(x0, y0 + h);
        m.lineTo(x0, y0);
        m.lineTo(x0 + w / 2f, y0 + h * 0.62f);
        m.lineTo(x0 + w, y0);
        m.lineTo(x0 + w, y0 + h);
        glowStroke(g, s, m);
        g.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(brandX(s, 0.32f, 0.35f, 0.68f, 0.62f));
        g.draw(m);
    }

    // 2 legacy preview kept as a separate candidate implementation.
    static void shieldLock(Graphics2D g, int s) {
        java.awt.geom.Path2D.Float sh = shieldPath(s);
        glowStroke(g, s, sh);
        g.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(brand(s));
        g.draw(sh);

        // padlock inside
        float bw = s * 0.20f, bh = s * 0.16f;
        float bx = s / 2f - bw / 2, by = s * 0.50f;
        float arc = s * 0.06f;
        java.awt.geom.RoundRectangle2D body =
            new java.awt.geom.RoundRectangle2D.Float(bx, by, bw, bh, arc, arc);
        g.setColor(new Color(0xE7ECF6));
        g.fill(body);
        g.setStroke(new BasicStroke(s * 0.045f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xE7ECF6));
        // shackle
        float sw = s * 0.12f;
        float sx = s / 2f - sw / 2, sy = by - s * 0.055f;
        java.awt.geom.Arc2D.Float shackle = new java.awt.geom.Arc2D.Float(
            sx, sy, sw, s * 0.12f, 0, 180, java.awt.geom.Arc2D.OPEN);
        g.draw(shackle);
    }

    static java.awt.geom.Path2D.Float shieldPath(int s) {
        java.awt.geom.Path2D.Float sh = new java.awt.geom.Path2D.Float();
        float top = s * 0.18f, mid = s * 0.78f;
        float left = s * 0.22f, right = s * 0.78f, cx = s / 2f;
        sh.moveTo(cx, top);
        sh.lineTo(right, top + s * 0.10f);
        sh.lineTo(right, s * 0.46f);
        sh.quadTo(right, mid + s * 0.08f, cx, mid + s * 0.14f);
        sh.quadTo(left, mid + s * 0.08f, left, s * 0.46f);
        sh.lineTo(left, top + s * 0.10f);
        sh.closePath();
        return sh;
    }

    // 3 - concentric portal rings + core dot
    static void portalTunnel(Graphics2D g, int s) {
        float cx = s / 2f, cy = s / 2f;
        Color[] ringColors = {BLUE, VIOLET, CYAN};
        for (int i = 0; i < 3; i++) {
            float rad = s * (0.34f - i * 0.085f);
            java.awt.geom.Ellipse2D ring = new java.awt.geom.Ellipse2D.Float(
                cx - rad, cy - rad, rad * 2, rad * 2);
            glowStroke(g, s, ring);
            g.setStroke(new BasicStroke(s * 0.05f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(ringColors[i]);
            g.draw(ring);
        }
        float core = s * 0.085f;
        g.setPaint(brandX(s, 0.3f, 0.3f, 0.7f, 0.7f));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - core, cy - core, core * 2, core * 2));
    }

    // 4 - M whose last stroke rises into a glowing node
    static void mNode(Graphics2D g, int s) {
        java.awt.geom.Path2D.Float m = new java.awt.geom.Path2D.Float();
        float w = s * 0.52f, h = s * 0.34f;
        float x0 = s * 0.20f, y0 = s * 0.34f;
        m.moveTo(x0, y0 + h);
        m.lineTo(x0, y0);
        m.lineTo(x0 + w / 2, y0 + h * 0.66f);
        m.lineTo(x0 + w, y0);
        m.lineTo(x0 + w, y0 + h);
        glowStroke(g, s, m);
        g.setStroke(new BasicStroke(s * 0.075f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(brand(s));
        g.draw(m);
        // node at the apex + two thin circuit lines to the corners
        float nx = x0 + w / 2, ny = y0 + h * 0.66f;
        float dot = s * 0.035f;
        g.setPaint(new LinearGradientPaint(nx - dot, ny - dot, nx + dot, ny + dot,
            GRAD_STOPS, new Color[]{CYAN, BLUE}));
        g.fill(new java.awt.geom.Ellipse2D.Float(nx - dot * 1.6f, ny - dot * 1.6f, dot * 3.2f, dot * 3.2f));
        g.setStroke(new BasicStroke(s * 0.014f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x22D3EE, true));
        g.draw(new java.awt.geom.Line2D.Float(nx, ny, s * 0.80f, s * 0.20f));
        g.draw(new java.awt.geom.Line2D.Float(nx, ny, s * 0.80f, s * 0.80f));
        for (float[] p : new float[][]{{s * 0.80f, s * 0.20f}, {s * 0.80f, s * 0.80f}}) {
            g.fill(new java.awt.geom.Ellipse2D.Float(p[0] - dot, p[1] - dot, dot * 2, dot * 2));
        }
    }

    // 5 - shield + lightning bolt (security + speed)
    static void shieldBolt(Graphics2D g, int s) {
        java.awt.geom.Path2D.Float sh = shieldPath(s);
        glowStroke(g, s, sh);
        g.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(brand(s));
        g.draw(sh);

        java.awt.geom.Path2D.Float bolt = new java.awt.geom.Path2D.Float();
        float cx = s / 2f;
        bolt.moveTo(cx + s * 0.045f, s * 0.28f);
        bolt.lineTo(cx - s * 0.10f, s * 0.50f);
        bolt.lineTo(cx - s * 0.005f, s * 0.50f);
        bolt.lineTo(cx - s * 0.065f, s * 0.66f);
        bolt.lineTo(cx + s * 0.10f, s * 0.43f);
        bolt.lineTo(cx + s * 0.005f, s * 0.43f);
        bolt.lineTo(cx + s * 0.045f, s * 0.28f);
        bolt.closePath();
        g.setPaint(brandX(s, 0.3f, 0.25f, 0.75f, 0.7f));
        g.fill(bolt);
    }
}
