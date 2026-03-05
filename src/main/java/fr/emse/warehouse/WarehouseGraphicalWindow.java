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
    private int width;
    private int height;
    private int cellWidth;
    private int cellHeight;
    private JPanel displayPanel;
    
    // Component identification based on color — must match values in configuration.ini and AMRobot
    private static final int[] COLOR_ROBOT_BLUE    = {0, 150, 255};  // Idle robot (matches config robot=0,150,255)
    private static final int[] COLOR_ROBOT_MAGENTA = {255, 0, 255};  // Robot carrying pallet
    private static final int[] COLOR_HUMAN         = {255, 200, 0};  // Human worker
    private static final int[] COLOR_OBSTACLE      = {80, 80, 80};   // Static obstacle
    private static final int[] COLOR_ENTRY         = {50, 200, 50};  // Entry area
    private static final int[] COLOR_EXIT          = {180, 50, 50};  // Exit area
    private static final int[] COLOR_INTERMEDIATE  = {100, 150, 255}; // Intermediate
    private static final int[] COLOR_RECHARGE      = {200, 100, 255}; // Recharge
    
    // Cache for generated icons
    private Map<String, BufferedImage> iconCache = new HashMap<>();
    
    public WarehouseGraphicalWindow(ColorSimpleCell[][] grid, int x, int y, 
                                     int width, int height, String title) {
        this.grid = grid;
        this.width = width;
        this.height = height;
        
        // Calculate cell dimensions
        int rows = grid.length;
        int cols = grid[0].length;
        this.cellWidth = width / cols;
        this.cellHeight = height / rows;
        
        // Setup window
        setTitle(title);
        setSize(width + 20, height + 45);
        setLocation(x, y);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Create display panel with custom painting
        displayPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                renderWarehouse((Graphics2D) g);
            }
        };
        displayPanel.setPreferredSize(new Dimension(width, height));
        displayPanel.setBackground(new Color(240, 240, 240));
        
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
     * Create a recharge station icon (battery/charging symbol).
     */
    private BufferedImage createRechargeIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Purple background
        g.setColor(new Color(180, 100, 220));
        g.fillRect(0, 0, size, size);
        
        // Battery shape
        g.setColor(Color.WHITE);
        int battW = size * 2/3;
        int battH = size / 2;
        int battX = (size - battW) / 2;
        int battY = (size - battH) / 2;
        g.fillRoundRect(battX, battY, battW, battH, 4, 4);
        
        // Battery terminal
        int termW = size / 8;
        int termH = battH / 2;
        g.fillRect(battX + battW, battY + (battH - termH)/2, termW, termH);
        
        // Lightning bolt
        g.setColor(new Color(255, 220, 0));  // Yellow
        int boltX = size / 2;
        int[] xPts = {boltX, boltX - 3, boltX, boltX + 3};
        int[] yPts = {battY + 2, battY + battH/2, battY + battH/2 - 2, battY + battH - 2};
        g.fillPolygon(xPts, yPts, 4);
        
        // Border
        g.setColor(new Color(120, 60, 150));
        g.setStroke(new BasicStroke(2));
        g.drawRect(1, 1, size - 2, size - 2);
        
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
     * Render the entire warehouse grid.
     */
    private void renderWarehouse(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int rows = grid.length;
        int cols = grid[0].length;
        
        // Draw grid lines first (light gray)
        g.setColor(new Color(220, 220, 220));
        for (int r = 0; r <= rows; r++) {
            g.drawLine(0, r * cellHeight, cols * cellWidth, r * cellHeight);
        }
        for (int c = 0; c <= cols; c++) {
            g.drawLine(c * cellWidth, 0, c * cellWidth, rows * cellHeight);
        }
        
        // Draw each cell
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ColorSimpleCell cell = grid[r][c];
                int x = c * cellWidth;
                int y = r * cellHeight;
                
                if (cell != null) {
                    ColorSituatedComponent content = cell.getContent();
                    
                    if (content != null) {
                        int[] color = content.getColor();
                        String iconKey = identifyComponent(color);
                        
                        if (iconKey != null && iconCache.containsKey(iconKey)) {
                            // Draw icon
                            BufferedImage icon = iconCache.get(iconKey);
                            int iconX = x + (cellWidth - icon.getWidth()) / 2;
                            int iconY = y + (cellHeight - icon.getHeight()) / 2;
                            g.drawImage(icon, iconX, iconY, null);
                        } else {
                            // Fallback to color fill
                            g.setColor(new Color(color[0], color[1], color[2]));
                            g.fillRect(x + 1, y + 1, cellWidth - 2, cellHeight - 2);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Identify component type based on its color.
     */
    private String identifyComponent(int[] color) {
        if (color == null) return null;
        
        // Check robots FIRST (before area checks) to avoid misclassifying robot colors as areas

        // Robot idle (blue ~0,150,255)
        if (isColorMatch(color, COLOR_ROBOT_BLUE, 50)) {
            return "robot_empty";
        }
        // Robot carrying pallet (magenta ~255,0,255)
        if (isColorMatch(color, COLOR_ROBOT_MAGENTA, 50)) {
            return "robot_carrying";
        }
        // Human worker (yellow/orange ~255,200,0)
        if (isColorMatch(color, COLOR_HUMAN, 50) ||
            (color[0] > 200 && color[1] > 150 && color[2] < 100)) {
            return "human";
        }

        // Area checks — only after ruling out all robot states

        // Entry area (green ~50,200,50)
        if (isColorMatch(color, COLOR_ENTRY, 50) ||
            (color[0] < 100 && color[1] > 150 && color[2] < 100)) {
            return "entry";
        }
        // Exit area (dark red ~180,50,50) — exclude pure-red robot colors (r>220, g<60, b<60)
        if (isColorMatch(color, COLOR_EXIT, 40) ||
            (color[0] > 150 && color[0] <= 210 && color[1] < 80 && color[2] < 80)) {
            return "exit";
        }
        // Intermediate area (light blue ~100,150,255)
        if (isColorMatch(color, COLOR_INTERMEDIATE, 50)) {
            return "intermediate";
        }
        // Recharge station (purple ~200,100,255)
        if (isColorMatch(color, COLOR_RECHARGE, 50) ||
            (color[0] > 150 && color[1] < 150 && color[2] > 200)) {
            return "recharge";
        }
        // Obstacle (dark gray ~80,80,80)
        if (isColorMatch(color, COLOR_OBSTACLE, 30) ||
            (color[0] < 120 && color[1] < 120 && color[2] < 120 &&
             Math.abs(color[0] - color[1]) < 20 && Math.abs(color[1] - color[2]) < 20)) {
            return "obstacle";
        }
        
        return null;  // Unknown - will use color fallback
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
     * Update the grid reference.
     */
    public void setGrid(ColorSimpleCell[][] newGrid) {
        this.grid = newGrid;
    }
}

