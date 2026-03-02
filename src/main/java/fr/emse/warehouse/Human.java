package fr.emse.warehouse;

import java.awt.Color;
import java.util.Random;

import fr.emse.fayol.maqit.simulator.components.ColorRobot;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

/**
 * Human worker in the warehouse - moves randomly and acts as a dynamic obstacle.
 * 
 * Humans are represented with a distinct YELLOW/ORANGE color to differentiate
 * from AMR robots (BLUE) and static obstacles (GRAY).
 * 
 * Visual: YELLOW square (moving)
 */
public class Human extends ColorRobot<ColorSimpleCell> {
    
    private final int rows;
    private final int columns;
    private final Random random;
    private int moveCounter;
    private final int moveInterval;  // Move every N ticks
    
    // Human-specific color
    public static final Color HUMAN_COLOR = new Color(255, 200, 0);  // Yellow/Orange
    
    /**
     * Creates a human worker.
     * 
     * @param name   Human identifier (e.g., "Human_1")
     * @param field  Perception field (usually 1-2)
     * @param pos    Starting position [row, column]
     * @param rows   Grid rows
     * @param columns Grid columns
     */
    public Human(String name, int field, int[] pos, int rows, int columns) {
        super(name, field, pos, new int[]{HUMAN_COLOR.getRed(), HUMAN_COLOR.getGreen(), HUMAN_COLOR.getBlue()});
        this.rows = rows;
        this.columns = columns;
        this.random = new Random();
        this.moveCounter = 0;
        this.moveInterval = 2 + random.nextInt(3);  // Move every 2-4 ticks (slower than robots)
    }
    
    @Override
    public void move(int nb) {
        moveCounter++;
        
        // Humans move slower than robots
        if (moveCounter >= moveInterval) {
            moveCounter = 0;
            moveRandomly();
        }
    }
    
    /**
     * Move in a random direction.
     */
    private void moveRandomly() {
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        
        // Shuffle directions
        for (int i = directions.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] temp = directions[i];
            directions[i] = directions[j];
            directions[j] = temp;
        }
        
        // Try each direction
        for (int[] dir : directions) {
            int nx = getX() + dir[0];
            int ny = getY() + dir[1];
            
            // Check bounds
            if (nx >= 0 && nx < rows && ny >= 0 && ny < columns) {
                // Check if cell is free (use perception grid)
                if (isCellFree(nx, ny)) {
                    setLocation(new int[]{nx, ny});
                    return;
                }
            }
        }
        // If no valid move, stay in place
    }
    
    /**
     * Check if a cell is free using perception grid.
     */
    private boolean isCellFree(int x, int y) {
        if (grid == null) return true;
        
        int relX = x - getX() + field;
        int relY = y - getY() + field;
        
        if (relX < 0 || relX >= grid.length || relY < 0 || relY >= grid[0].length) {
            return true;  // Outside perception, assume free
        }
        
        ColorSimpleCell cell = grid[relX][relY];
        return cell != null && cell.getContent() == null;
    }
    
    /**
     * Get the human's current position.
     */
    public int[] getPosition() {
        return getLocation();
    }
}

