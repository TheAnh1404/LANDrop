package com.landrop.server.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.BooleanSupplier;

/** Shared native desktop styling and human-readable values; model values remain unchanged. */
final class DesktopUi {
    static final Font FONT = new Font("Segoe UI", Font.PLAIN, 14);
    static final Color BLUE = new Color(0x3166D9);
    static final Color GREEN = new Color(0x147D5A);
    static final Color AMBER = new Color(0x9A6515);
    static final Color RED = new Color(0xBD3645);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm")
            .withZone(ZoneId.systemDefault());

    private DesktopUi() { }

    static Color text(boolean dark) { return new Color(dark ? 0xE6EDF7 : 0x172B4D); }
    static Color muted(boolean dark) { return new Color(dark ? 0xA7B8D0 : 0x596B84); }
    static Color canvas(boolean dark) { return new Color(dark ? 0x101826 : 0xF3F5F9); }
    static Color surface(boolean dark) { return new Color(dark ? 0x192538 : 0xFFFFFF); }
    static Color border(boolean dark) { return new Color(dark ? 0x31415A : 0xDEE5EF); }

    static JPanel card(LayoutManager layout, BooleanSupplier dark) {
        JPanel panel = new JPanel(layout) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(surface(dark.getAsBoolean()));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g.setColor(border(dark.getAsBoolean()));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(20, 22, 20, 22));
        return panel;
    }

    static JTextArea paragraph(String text) {
        JTextArea area = new JTextArea(text);
        area.setFont(FONT);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        return area;
    }

    static String display(Object value) {
        if (value == null) return "—";
        if (value instanceof Instant time) return DATE.format(time);
        if (value instanceof Boolean flag) return flag ? "Có" : "Không";
        return switch (value.toString()) {
            case "ACTIVE" -> "Đang hoạt động";
            case "DISABLED" -> "Đã khóa";
            case "SYSTEM_ADMIN" -> "Quản trị viên";
            case "USER" -> "Người dùng";
            case "OWNER" -> "Chủ nhóm";
            case "ADMIN" -> "Quản lý nhóm";
            case "MEMBER" -> "Thành viên";
            case "INFO" -> "Thông tin";
            case "WARN" -> "Cần chú ý";
            case "ERROR" -> "Lỗi";
            default -> value.toString();
        };
    }

    static ListCellRenderer<Object> choiceRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, display(value), index, selected, focus);
                putClientProperty("html.disable", true);
                setBorder(new EmptyBorder(6, 10, 6, 10));
                return this;
            }
        };
    }
}
