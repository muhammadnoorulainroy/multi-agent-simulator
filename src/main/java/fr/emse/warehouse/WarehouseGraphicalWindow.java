package fr.emse.warehouse;

import fr.emse.fayol.maqit.simulator.components.ColorSituatedComponent;
import fr.emse.fayol.maqit.simulator.components.ComponentType;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom graphical window for warehouse simulation with icon-based rendering.
 * 
 * Renders different components with distinct visual icons:
 * - AMR Robots: Forklift-style icon (blue=empty, magenta=carrying)
 * - Humans: Person icon (orange/yellow)
 * - Obstacles: Wall/crate icon (dark gray)
 * - Entry/Exit areas: Gate icons with distinct colors
 * 
 * This replaces the default color-only rendering with more realistic icons.
 */
public class WarehouseGraphicalWindow extends JFrame {
    
    private ColorSimpleCell[][] grid;
    private WarehouseEnvironment warehouseEnv;  // Source of truth for fixed area positions
    private int width;
    private int height;
    private int cellWidth;
    private int cellHeight;
    private JPanel displayPanel;

    // Legend height strip at bottom
    private static final int LEGEND_HEIGHT = 60;

    // Colors for the background area layer (drawn directly from env, no color guessing)
    private static final Color COLOR_FLOOR       = new Color(245, 245, 240); // Warm off-white floor
    private static final Color COLOR_ENTRY_BG    = new Color(50,  200, 50);  // Bright green
    private static final Color COLOR_EXIT_BG     = new Color(180, 50,  50);  // Dark red
    private static final Color COLOR_INTER_BG    = new Color(100, 150, 220); // Steel blue
    private static final Color COLOR_RECHARGE_BG = new Color(180, 100, 220); // Purple

    // Moving-agent identification — only robots/humans/obstacles need color matching now
    private static final int[] COLOR_ROBOT_BLUE    = {0, 150, 255};  // Idle robot
    private static final int[] COLOR_ROBOT_MAGENTA = {255, 0, 255};  // Robot carrying pallet
    private static final int[] COLOR_HUMAN         = {255, 200, 0};  // Human worker
    private static final int[] COLOR_OBSTACLE      = {80, 80, 80};   // Static obstacle

    // Cache for generated icons
    private Map<String, BufferedImage> iconCache = new HashMap<>();

    public WarehouseGraphicalWindow(ColorSimpleCell[][] grid, WarehouseEnvironment env,
                                     int x, int y, int width, int height, String title) {
        this.grid = grid;
        this.warehouseEnv = env;
        this.width = width;
        this.height = height;

        // Calculate cell dimensions (reserve bottom strip for legend)
        int rows = grid.length;
        int cols = grid[0].length;
        this.cellWidth  = width / cols;
        this.cellHeight = (height - LEGEND_HEIGHT) / rows;

        // Setup window
        setTitle(title);
        setSize(width + 20, height + LEGEND_HEIGHT + 45);
        setLocation(x, y);
        // DISPOSE_ON_CLOSE: closing this icon window does not kill the entire application
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // Create display panel with two-layer custom painting
        displayPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                renderWarehouse(g2);
                renderLegend(g2);
            }
        };
        displayPanel.setPreferredSize(new Dimension(width, height + LEGEND_HEIGHT));
        displayPanel.setBackground(COLOR_FLOOR);

        add(displayPanel);

        // Pre-generate icons
        generateIcons();
    }
    
    /**
     * Pre-generate all icon images for faster rendering.
     */
    private void generateIcons() {
        int size = Math.min(cellWidth, cellHeight) - 2;
        if (size < 8) size = 8;
        
        // Robot icons
        iconCache.put("robot_empty", createRobotIcon(size, new Color(0, 100, 200), false));
        iconCache.put("robot_carrying", createRobotIcon(size, new Color(200, 50, 150), true));
        
        // Human icon
        iconCache.put("human", createHumanIcon(size));
        
        // Area icons
        iconCache.put("entry", createEntryIcon(size));
        iconCache.put("exit", createExitIcon(size));
        iconCache.put("intermediate", createIntermediateIcon(size));
        iconCache.put("recharge", createRechargeIcon(size));
        
        // Obstacle icon
        iconCache.put("obstacle", createObstacleIcon(size));
    }
    
    /**
     * Create a robot/AMR icon that looks like a forklift.
     */
    private BufferedImage createRobotIcon(int size, Color bodyColor, boolean carryingPallet) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int padding = 2;
        int bodyW = size - 2 * padding;
        int bodyH = (int)(size * 0.6);
        int bodyY = size - bodyH - padding;
        
        // Body (rectangular robot base)
        g.setColor(bodyColor);
        g.fillRoundRect(padding, bodyY, bodyW, bodyH, 4, 4);
        
        // Wheels
        g.setColor(Color.DARK_GRAY);
        int wheelSize = size / 5;
        g.fillOval(padding + 2, size - wheelSize - 1, wheelSize, wheelSize);
        g.fillOval(size - padding - wheelSize - 2, size - wheelSize - 1, wheelSize, wheelSize);
        
        // Fork/arms at front
        g.setColor(new Color(100, 100, 100));
        int forkW = size / 6;
        int forkH = size / 3;
        g.fillRect(padding + bodyW/4, padding, forkW, forkH);
        g.fillRect(size - padding - bodyW/4 - forkW, padding, forkW, forkH);
        
        // Pallet if carrying
        if (carryingPallet) {
            g.setColor(new Color(210, 180, 140)); // Tan/wood color
            int palletW = bodyW - 8;
            int palletH = size / 4;
            g.fillRect(padding + 4, padding, palletW, palletH);
            
            // Pallet lines
            g.setColor(new Color(160, 130, 90));
            g.drawLine(padding + 4 + palletW/3, padding, padding + 4 + palletW/3, padding + palletH);
            g.drawLine(padding + 4 + 2*palletW/3, padding, padding + 4 + 2*palletW/3, padding + palletH);
        }
        
        // Outline
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(padding, bodyY, bodyW, bodyH, 4, 4);
        
        g.dispose();
        return img;
    }
    
    /**
     * Create a human worker icon.
     */
    private BufferedImage createHumanIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        Color skinColor = new Color(255, 200, 150);
        Color shirtColor = new Color(255, 150, 50);  // Orange safety vest
        
        // Head
        int headSize = size / 3;
        int headX = (size - headSize) / 2;
        int headY = 2;
        g.setColor(skinColor);
        g.fillOval(headX, headY, headSize, headSize);
        g.setColor(Color.BLACK);
        g.drawOval(headX, headY, headSize, headSize);
        
        // Body (triangular torso for safety vest look)
        int bodyTop = headY + headSize;
        int bodyBottom = size - size/4;
        int bodyWidth = size * 2/3;
        g.setColor(shirtColor);
        int[] xPoints = {size/2, (size - bodyWidth)/2, (size + bodyWidth)/2};
        int[] yPoints = {bodyTop, bodyBottom, bodyBottom};
        g.fillPolygon(xPoints, yPoints, 3);
        
        // Vest stripes
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.drawLine(size/2 - 3, bodyTop + 4, size/2 - 6, bodyBottom - 2);
        g.drawLine(size/2 + 3, bodyTop + 4, size/2 + 6, bodyBottom - 2);
        
        // Legs
        g.setColor(new Color(50, 50, 150)); // Blue pants
        int legWidth = size / 6;
        g.fillRect(size/2 - legWidth - 2, bodyBottom, legWidth, size - bodyBottom - 1);
        g.fillRect(size/2 + 2, bodyBottom, legWidth, size - bodyBottom - 1);
        
        g.dispose();
        return img;
    }
    
    /**
     * Create an entry area icon (gate/door).
     */
    private BufferedImage createEntryIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Green background
        g.setColor(new Color(50, 180, 50));
        g.fillRect(0, 0, size, size);
        
        // Gate posts
        g.setColor(new Color(80, 80, 80));
        int postW = size / 6;
        g.fillRect(2, 2, postW, size - 4);
        g.fillRect(size - postW - 2, 2, postW, size - 4);
        
        // Gate top bar
        g.fillRect(2, 2, size - 4, size / 6);
        
        // Arrow pointing in (incoming pallets)
        g.setColor(Color.WHITE);
        int arrowCenterX = size / 2;
        int arrowCenterY = size / 2 + 2;
        int arrowSize = size / 3;
        
        // Arrow body
        g.fillRect(arrowCenterX - 2, arrowCenterY - arrowSize/2, 4, arrowSize);
        // Arrow head pointing down (into warehouse)
        int[] xPts = {arrowCenterX, arrowCenterX - arrowSize/3, arrowCenterX + arrowSize/3};
        int[] yPts = {arrowCenterY + arrowSize/2, arrowCenterY + 2, arrowCenterY + 2};
        g.fillPolygon(xPts, yPts, 3);
        
        // Border
        g.setColor(new Color(30, 100, 30));
        g.setStroke(new BasicStroke(2));
        g.drawRect(1, 1, size - 2, size - 2);
        
        g.dispose();
        return img;
    }
    
    /**
     * Create an exit area icon (gate/door with X pattern).
     */
    private BufferedImage createExitIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Red/brown background
        g.setColor(new Color(180, 50, 50));
        g.fillRect(0, 0, size, size);
        
        // Diagonal lines (X pattern like reference image)
        g.setColor(new Color(120, 30, 30));
        g.setStroke(new BasicStroke(3));
        g.drawLine(4, 4, size - 4, size - 4);
        g.drawLine(size - 4, 4, 4, size - 4);
        
        // "Z" label
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, size / 2));
        FontMetrics fm = g.getFontMetrics();
        String label = "Z";
        int textX = (size - fm.stringWidth(label)) / 2;
        int textY = (size + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(label, textX, textY);
        
        // Border
        g.setColor(new Color(100, 20, 20));
        g.setStroke(new BasicStroke(2));
        g.drawRect(1, 1, size - 2, size - 2);
        
        g.dispose();
        return img;
    }
    
    /**
     * Create an intermediate area icon (relay station).
     */
    private BufferedImage createIntermediateIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Blue striped background (like reference image)
        g.setColor(new Color(100, 150, 220));
        g.fillRect(0, 0, size, size);
        
        // Horizontal stripes
        g.setColor(new Color(50, 100, 180));
        for (int y = 3; y < size; y += 6) {
            g.fillRect(0, y, size, 2);
        }
        
        // "I" label in center
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, size / 2));
        FontMetrics fm = g.getFontMetrics();
        String label = "I";
        int textX = (size - fm.stringWidth(label)) / 2;
        int textY = (size + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(label, textX, textY);
        
        // Border
        g.setColor(new Color(30, 60, 120));
        g.setStroke(new BasicStroke(2));
        g.drawRect(1, 1, size - 2, size - 2);
        
        g.dispose();
        return img;
    }
    
    /**
     * Create a recharge station icon — a horizontal battery outline filled green
     * with a yellow lightning-bolt overlay.
     */
    private BufferedImage createRechargeIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int pad   = size / 8;
        int termW = Math.max(3, size / 8);
        int termH = Math.max(4, size / 4);

        // Battery outline (leaves room on right for terminal)
        int battX = pad;
        int battY = size / 4;
        int battW = size - pad * 2 - termW - 1;
        int battH = size / 2;

        // Outer shell
        g.setColor(new Color(220, 220, 220));
        g.fillRoundRect(battX, battY, battW, battH, 5, 5);
        g.setColor(new Color(80, 80, 80));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(battX, battY, battW, battH, 5, 5);

        // Terminal nub
        g.setColor(new Color(80, 80, 80));
        g.fillRoundRect(battX + battW, battY + (battH - termH) / 2, termW, termH, 2, 2);

        // Fill level (~80% green)
        int fillW = (int)(battW * 0.80) - 4;
        g.setColor(new Color(40, 200, 80));
        g.fillRoundRect(battX + 2, battY + 2, fillW, battH - 4, 4, 4);

        // Lightning bolt (yellow) centred in battery
        g.setColor(new Color(255, 230, 0));
        int bx = battX + battW / 2;
        int by = battY + 2;
        int bh = battH - 4;
        int bw = Math.max(4, battW / 4);
        // Top-right to mid-left to bottom-right zig-zag
        int[] xb = { bx + bw/2,  bx - bw/4,  bx + bw/4,  bx - bw/2 };
        int[] yb = { by,          by + bh/2,   by + bh/2,  by + bh   };
        g.fillPolygon(xb, yb, 4);
        // Outline the bolt
        g.setColor(new Color(200, 150, 0));
        g.setStroke(new BasicStroke(0.8f));
        g.drawPolygon(xb, yb, 4);

        g.dispose();
        return img;
    }
    
    /**
     * Create an obstacle icon (crate/wall).
     */
    private BufferedImage createObstacleIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Dark gray crate
        g.setColor(new Color(80, 80, 80));
        g.fillRect(2, 2, size - 4, size - 4);
        
        // Crate planks (horizontal lines)
        g.setColor(new Color(60, 60, 60));
        g.setStroke(new BasicStroke(1));
        for (int y = size/4; y < size; y += size/4) {
            g.drawLine(2, y, size - 2, y);
        }
        
        // Crate edges (3D effect)
        g.setColor(new Color(100, 100, 100));
        g.drawLine(2, 2, size - 2, 2);  // Top
        g.drawLine(2, 2, 2, size - 2);  // Left
        
        g.setColor(new Color(40, 40, 40));
        g.drawLine(size - 2, 2, size - 2, size - 2);  // Right
        g.drawLine(2, size - 2, size - 2, size - 2);  // Bottom
        
        g.dispose();
        return img;
    }
    
    /**
     * Two-layer renderer:
     *   Layer 1 — Floor + fixed areas (from WarehouseEnvironment — never moves, never flickers)
     *   Layer 2 — Moving agents (robots, humans, obstacles) from the live grid
     */
    private void renderWarehouse(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int rows = grid.length;
        int cols = grid[0].length;

        // ── LAYER 1: floor tiles ────────────────────────────────────────────
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                g.setColor(COLOR_FLOOR);
                g.fillRect(c * cellWidth, r * cellHeight, cellWidth, cellHeight);
            }
        }

        // ── LAYER 1b: fixed area backgrounds (from WarehouseEnvironment) ───
        if (warehouseEnv != null) {
            Font labelFont = new Font("SansSerif", Font.BOLD, Math.max(7, Math.min(cellHeight / 2, 12)));
            g.setFont(labelFont);

            // Entry areas — green (2 rows × 1 col)
            for (EntryArea entry : warehouseEnv.getEntryAreas()) {
                int r = entry.getX(), c = entry.getY();
                drawAreaCells(g, r, c, 2, 1, COLOR_ENTRY_BG);
                drawIconInArea(g, "entry", r, c, 2, 1);
                drawAreaLabel(g, r, c, entry.getId(), Color.WHITE);
            }
            // Exit areas — dark red (2×2)
            for (ExitArea exit : warehouseEnv.getExitAreas()) {
                int r = exit.getX(), c = exit.getY();
                drawAreaCells(g, r, c, 2, 2, COLOR_EXIT_BG);
                drawIconInArea(g, "exit", r, c, 2, 2);
                drawAreaLabel(g, r, c, exit.getId(), Color.WHITE);
            }
            // Intermediate areas — steel blue (2×2)
            for (IntermediateArea inter : warehouseEnv.getIntermediateAreas()) {
                int r = inter.getX(), c = inter.getY();
                drawAreaCells(g, r, c, 2, 2, COLOR_INTER_BG);
                drawIconInArea(g, "intermediate", r, c, 2, 2);
                drawAreaLabel(g, r, c, inter.getId(), Color.WHITE);
            }
            // Recharge stations — purple (1×1) with battery icon
            int idx = 1;
            for (int[] pos : warehouseEnv.getRechargeStations()) {
                drawAreaCells(g, pos[0], pos[1], 1, 1, COLOR_RECHARGE_BG);
                drawIconInArea(g, "recharge", pos[0], pos[1], 1, 1);
                drawAreaLabel(g, pos[0], pos[1], "C" + idx, Color.WHITE);
                idx++;
            }
        }

        // ── LAYER 2: grid lines ─────────────────────────────────────────────
        g.setColor(new Color(200, 200, 200));
        for (int r = 0; r <= rows; r++) {
            g.drawLine(0, r * cellHeight, cols * cellWidth, r * cellHeight);
        }
        for (int c = 0; c <= cols; c++) {
            g.drawLine(c * cellWidth, 0, c * cellWidth, rows * cellHeight);
        }

        // ── LAYER 3: moving agents from live grid ────────────────────────────
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ColorSimpleCell cell = grid[r][c];
                int px = c * cellWidth;
                int py = r * cellHeight;

                if (cell == null) continue;
                ColorSituatedComponent content = cell.getContent();
                if (content == null) continue;

                int[] color = content.getColor();
                String iconKey = identifyAgent(color);

                if (iconKey != null && iconCache.containsKey(iconKey)) {
                    BufferedImage icon = iconCache.get(iconKey);
                    int iconX = px + (cellWidth  - icon.getWidth())  / 2;
                    int iconY = py + (cellHeight - icon.getHeight()) / 2;
                    g.drawImage(icon, iconX, iconY, null);
                }
                // Note: area marker ColorObstacles are no longer placed on the grid,
                // so any unknown color here is a real obstacle
                else if (color != null) {
                    if (isColorMatch(color, COLOR_OBSTACLE, 40)) {
                        BufferedImage icon = iconCache.get("obstacle");
                        if (icon != null) {
                            int iconX = px + (cellWidth  - icon.getWidth())  / 2;
                            int iconY = py + (cellHeight - icon.getHeight()) / 2;
                            g.drawImage(icon, iconX, iconY, null);
                        }
                    }
                }
            }
        }
    }

    /**
     * Fill a rectangular area (heightCells × widthCells) with a solid background.
     * No diagonal hatch — clean flat fill with a subtle inner border.
     */
    private void drawAreaCells(Graphics2D g, int row, int col,
                                int heightCells, int widthCells, Color bg) {
        int totalRows = grid.length;
        int totalCols = grid[0].length;
        // Full bounding box in pixels
        int px = col * cellWidth;
        int py = row * cellHeight;
        int pw = widthCells  * cellWidth;
        int ph = heightCells * cellHeight;
        // Clip to grid
        int maxW = (totalCols - col) * cellWidth;
        int maxH = (totalRows - row) * cellHeight;
        pw = Math.min(pw, maxW);
        ph = Math.min(ph, maxH);
        if (pw <= 0 || ph <= 0) return;

        // Solid fill
        g.setColor(bg);
        g.fillRect(px + 1, py + 1, pw - 2, ph - 2);

        // Subtle darker border around the whole block
        g.setColor(bg.darker());
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(px + 1, py + 1, pw - 3, ph - 3);
        g.setStroke(new BasicStroke(1));
    }

    /**
     * Draw a cached icon centred inside a multi-cell area block.
     */
    private void drawIconInArea(Graphics2D g, String iconKey,
                                 int row, int col, int heightCells, int widthCells) {
        BufferedImage icon = iconCache.get(iconKey);
        if (icon == null) return;
        int areaPixW = widthCells  * cellWidth;
        int areaPixH = heightCells * cellHeight;
        // Scale icon to fit nicely (80 % of the smaller dimension)
        int maxSize = (int)(Math.min(areaPixW, areaPixH) * 0.80);
        if (maxSize < 6) return;
        int ix = col * cellWidth  + (areaPixW - maxSize) / 2;
        int iy = row * cellHeight + (areaPixH - maxSize) / 2;
        g.drawImage(icon, ix, iy, maxSize, maxSize, null);
    }

    /** Draw a white label in the top-left of the area cell. */
    private void drawAreaLabel(Graphics2D g, int row, int col, String label, Color color) {
        g.setColor(color);
        int px = col * cellWidth  + 2;
        int py = row * cellHeight + g.getFontMetrics().getAscent() + 1;
        // Small shadow for readability
        g.setColor(new Color(0, 0, 0, 140));
        g.drawString(label, px + 1, py + 1);
        g.setColor(color);
        g.drawString(label, px, py);
    }

    /**
     * Draw a legend strip below the grid explaining every icon.
     */
    private void renderLegend(Graphics2D g) {
        int rows = grid.length;
        int legendY = rows * cellHeight;
        int panelW  = grid[0].length * cellWidth;

        // Background
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, legendY, panelW, LEGEND_HEIGHT);

        // Items: [icon-cache key, label]  — all entries now use cached icons
        Object[][] items = {
            {"robot_empty",    "AMR (idle)"},
            {"robot_carrying", "AMR (pallet)"},
            {"human",          "Human"},
            {"obstacle",       "Obstacle"},
            {"entry",          "Entry (A1-A3)"},
            {"exit",           "Exit (Z1-Z2)"},
            {"intermediate",   "Intermediate"},
            {"recharge",       "Recharge"},
        };

        int iconSize = 20;
        int spacing  = 6;
        int x = 6;
        int y = legendY + 8;
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        FontMetrics fm = g.getFontMetrics();

        for (Object[] item : items) {
            String label = (String) item[1];
            if (x + iconSize + fm.stringWidth(label) + spacing * 2 > panelW) {
                x = 6;
                y += iconSize + 4;
            }
            // Draw icon from cache
            BufferedImage icon = iconCache.get((String) item[0]);
            if (icon != null) {
                g.drawImage(icon, x, y, iconSize, iconSize, null);
            } else {
                // Fallback swatch (should not happen)
                g.setColor(Color.GRAY);
                g.fillRect(x, y, iconSize, iconSize);
            }
            g.setColor(Color.WHITE);
            g.drawString(label, x + iconSize + 2, y + fm.getAscent() + (iconSize - fm.getHeight()) / 2);
            x += iconSize + fm.stringWidth(label) + spacing + 10;
        }
    }
    
    /**
     * Identify only moving agents (robots / humans) by color.
     * Areas are no longer detected here — they are drawn directly from WarehouseEnvironment.
     */
    private String identifyAgent(int[] color) {
        if (color == null) return null;

        if (isColorMatch(color, COLOR_ROBOT_BLUE, 50))    return "robot_empty";
        if (isColorMatch(color, COLOR_ROBOT_MAGENTA, 50)) return "robot_carrying";
        if (isColorMatch(color, COLOR_HUMAN, 50) ||
            (color[0] > 200 && color[1] > 150 && color[2] < 100)) return "human";

        return null;
    }
    
    /**
     * Check if two colors match within a tolerance.
     */
    private boolean isColorMatch(int[] c1, int[] c2, int tolerance) {
        return Math.abs(c1[0] - c2[0]) <= tolerance &&
               Math.abs(c1[1] - c2[1]) <= tolerance &&
               Math.abs(c1[2] - c2[2]) <= tolerance;
    }
    
    /**
     * Initialize and show the window.
     */
    public void init() {
        setVisible(true);
    }
    
    /**
     * Refresh the display.
     */
    public void refresh() {
        // Regenerate icons if cell size changed
        if (grid.length > 0 && grid[0].length > 0) {
            int newCellWidth = width / grid[0].length;
            int newCellHeight = height / grid.length;
            if (newCellWidth != cellWidth || newCellHeight != cellHeight) {
                cellWidth = newCellWidth;
                cellHeight = newCellHeight;
                generateIcons();
            }
        }
        displayPanel.repaint();
    }
    
    /**
     * Update the grid reference (called every tick).
     */
    public void setGrid(ColorSimpleCell[][] newGrid) {
        this.grid = newGrid;
    }

    /**
     * Update the warehouse environment reference (optional, set once after construction).
     */
    public void setWarehouseEnvironment(WarehouseEnvironment env) {
        this.warehouseEnv = env;
    }
}

