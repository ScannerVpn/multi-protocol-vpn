import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public final class BannerGen {
    static final Color NAVY = new Color(0x05070E);
    static final Color SURFACE = new Color(0x0B101E);
    static final Color BLUE = new Color(0x4F8CFF);
    static final Color VIOLET = new Color(0x8B5CF6);
    static final Color CYAN = new Color(0x22D3EE);
    static final Color TEXT = new Color(0xE7ECF6);
    static final Color MUTED = new Color(0x9BA9C3);

    public static void main(String[] args) throws Exception {
        File out = new File(args.length == 0 ? "../desktop/banners" : args[0]);
        if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);
        banner(out, 1, 1200, 630, "اتصال امن، ساده و سریع", "MultiVPN | چند پروتکل در یک برنامه", "IKEv2  •  WireGuard  •  AmneziaWG  •  OpenVPN");
        banner(out, 2, 1200, 630, "حریم خصوصی، همیشه همراه شما", "MultiVPN | کنترل کامل اتصال شما", "اتصال با یک کلیک  •  پشتیبانی از کانفیگ‌های متنوع");
        banner(out, 3, 1200, 630, "یک برنامه برای همه مسیرها", "MultiVPN | سریع، امن، قابل اعتماد", "کانفیگ‌هایت را مدیریت کن و بهترین سرور را انتخاب کن");
        banner(out, 4, 1080, 1080, "امن وصل شو", "MultiVPN", "پشتیبانی از WireGuard، IKEv2، OpenVPN و بیشتر", "برای ویندوز");
        System.out.println("done: " + out.getAbsolutePath());
    }

    static void banner(File out, int n, int w, int h, String headline, String title, String feature) throws Exception {
        banner(out, n, w, h, headline, title, feature, "MultiVPN");
    }

    static void banner(File out, int n, int w, int h, String headline, String title, String feature, String footer) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, NAVY, w, h, new Color(0x0D1833)));
        g.fillRect(0, 0, w, h);
        // soft brand orbs
        g.setColor(new Color(0x4F8CFF)); g.fillOval(w - 380, -180, 600, 600);
        g.setColor(new Color(0x8B5CF6)); g.fillOval(-260, h - 240, 520, 520);
        g.setColor(new Color(0x05070E, true)); g.fillRect(0, 0, w, h);
        // main glass panel
        int pad = w / 14;
        g.setColor(new Color(0x0B101E));
        g.fill(new RoundRectangle2D.Float(pad, pad, w - 2f * pad, h - 2f * pad, 36, 36));
        g.setColor(new Color(0x263251));
        g.draw(new RoundRectangle2D.Float(pad, pad, w - 2f * pad, h - 2f * pad, 36, 36));
        // icon, drawn from shield geometry
        int iconSize = Math.min(w, h) / 4;
        shieldM(g, w - pad - iconSize - 58, pad + 55, iconSize);
        // text, right aligned for Persian Telegram banner layout
        int right = w - pad - 58;
        Font bold = new Font("Segoe UI", Font.BOLD, Math.max(28, w / 27));
        Font normal = new Font("Segoe UI", Font.PLAIN, Math.max(18, w / 48));
        g.setFont(new Font("Segoe UI", Font.BOLD, Math.max(18, w / 45)));
        g.setColor(CYAN); g.drawString(title, right - g.getFontMetrics().stringWidth(title), h / 2 - 80);
        g.setFont(bold); g.setColor(TEXT);
        g.drawString(headline, right - g.getFontMetrics().stringWidth(headline), h / 2);
        g.setFont(normal); g.setColor(MUTED);
        g.drawString(feature, right - g.getFontMetrics().stringWidth(feature), h / 2 + 62);
        g.setFont(new Font("Segoe UI", Font.BOLD, Math.max(18, w / 50)));
        g.setColor(new Color(0x6F83A6)); g.drawString(footer, pad + 58, h - pad - 55);
        ImageIO.write(im, "png", new File(out, "telegram-banner-" + n + ".png"));
        g.dispose();
    }

    static void shieldM(Graphics2D g, int x, int y, int s) {
        java.awt.geom.Path2D.Float sh = new java.awt.geom.Path2D.Float();
        float cx = x + s / 2f, top = y + s * .04f, left = x + s * .10f, right = x + s * .90f;
        sh.moveTo(cx, top); sh.lineTo(right, y + s * .18f); sh.lineTo(right, y + s * .52f);
        sh.quadTo(right, y + s * .88f, cx, y + s * .99f);
        sh.quadTo(left, y + s * .88f, left, y + s * .52f);
        sh.lineTo(left, y + s * .18f); sh.closePath();
        g.setPaint(new GradientPaint(x, y, BLUE, x + s, y + s, VIOLET));
        g.setStroke(new java.awt.BasicStroke(s * .075f, 1, 1)); g.draw(sh);
        java.awt.geom.Path2D.Float m = new java.awt.geom.Path2D.Float();
        float mw = s * .40f, mh = s * .28f, mx = cx - mw / 2, my = y + s * .38f;
        m.moveTo(mx, my + mh); m.lineTo(mx, my); m.lineTo(cx, my + mh * .62f);
        m.lineTo(mx + mw, my); m.lineTo(mx + mw, my + mh);
        g.setStroke(new java.awt.BasicStroke(s * .065f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.setColor(TEXT); g.draw(m);
    }
}
