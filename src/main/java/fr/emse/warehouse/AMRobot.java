package fr.emse.warehouse;

import java.awt.Color;
import java.util.List;
import java.util.ArrayList;

import fr.emse.fayol.maqit.simulator.components.ColorInteractionRobot;
import fr.emse.fayol.maqit.simulator.components.ColorRobot;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

/**
 * Autonomous Mobile Robot (AMR) for warehouse pallet transportation.
 * 
 * This robot can operate in two modes:
 * 1. REFERENCE MODEL: Simple delivery, no communication, no battery, disappears after delivery
 * 2. ENHANCED MODEL: Communication, battery management, reusable
 * 
 * States:
 * - IDLE: Waiting for a task
 * - MOVING_TO_PICKUP: Moving toward a pallet to pick up
 * - PICKING_UP: At entry area, picking up pallet
 * - DELIVERING: Carrying pallet to destination
 * - DELIVERED: Pallet delivered (Reference: will be removed; Enhanced: returns to IDLE)
 * - MOVING_TO_RECHARGE: Going to recharge station (Enhanced only)
 * - RECHARGING: At recharge station (Enhanced only)
 * - DEAD: Out of battery (Enhanced only)
 * 
 * Visual Colors:
 * - BLUE: Robot idle/moving without pallet
 * - MAGENTA: Robot CARRYING a pallet
 * - RED: Robot with low battery (Enhanced)
 */
public class AMRobot extends ColorInteractionRobot<ColorSimpleCell> {
    
    // ==================== Visual Colors ====================
    public static final Color COLOR_IDLE = new Color(0, 150, 255);      // Blue - empty robot
    public static final Color COLOR_CARRYING = new Color(255, 0, 255);  // Magenta - carrying pallet
    public static final Color COLOR_LOW_BATTERY = new Color(255, 50, 50); // Red - low battery
    
    // ==================== Robot State ====================
    public enum State {
        IDLE,
        MOVING_TO_PICKUP,
        PICKING_UP,
        DELIVERING,
        DELIVERED,
        MOVING_TO_RECHARGE,
        RECHARGING,
        DEAD
    }
    
    // ==================== Configuration ====================
    private final int rows;
    private final int columns;
    private final boolean enhancedMode;  // true = enhanced model with battery, false = reference model
    
    // ==================== State Variables ====================
    private State state;
    private Pallet carriedPallet;
    private int[] targetPosition;
    private List<int[]> currentPath;
    private int pathIndex;
    
    // ==================== Battery (Enhanced Mode Only) ====================
    private int battery;
    private int maxBattery;
    private int rechargeRate;  // Battery units recovered per tick while recharging
    
    // ==================== Statistics ====================
    private int palletsDelivered;
    private int totalDistanceTraveled;
    private int ticksIdle;
    private int ticksCarrying;
    
    // ==================== Pathfinding Helpers ====================
    private WarehouseEnvironment warehouseEnv;  // Reference to warehouse for pathfinding
    
    // ==================== Gridlock Prevention ====================
    private int waitCounter;           // Ticks spent waiting at current position
    private int[] lastPosition;        // Previous position (to detect stuck)
    private int stuckCounter;          // Times we've been at the same position
    private java.util.Random random;   // For random jitter
    private int robotPriority;         // Priority based on robot ID (lower = higher priority)
    private static final int MAX_WAIT_TICKS = 2;    // Max ticks to wait before trying alternative
    private static final int MAX_STUCK_COUNT = 3;   // Max times to be stuck before full replan
    
    /**
     * Creates an AMR for the REFERENCE MODEL (no battery, no communication).
     */
    public AMRobot(String name, int field, int[] pos, Color color, int rows, int columns) {
        super(name, field, pos, new int[]{color.getRed(), color.getGreen(), color.getBlue()});
        this.rows = rows;
        this.columns = columns;
        this.enhancedMode = false;
        this.state = State.IDLE;
        this.carriedPallet = null;
        this.currentPath = new ArrayList<>();
        this.pathIndex = 0;
        this.palletsDelivered = 0;
        this.totalDistanceTraveled = 0;
        this.ticksIdle = 0;
        this.ticksCarrying = 0;
        
        // Gridlock prevention
        this.waitCounter = 0;
        this.lastPosition = pos.clone();
        this.stuckCounter = 0;
        this.random = new java.util.Random(System.nanoTime() + name.hashCode());  // More randomness
        this.robotPriority = extractPriority(name);  // Extract number from name for priority
        
        // No battery in reference model
        this.battery = -1;
        this.maxBattery = -1;
    }
    
    /**
     * Extract priority number from robot name (e.g., "AMR_P5" -> 5).
     */
    private int extractPriority(String name) {
        try {
            String numStr = name.replaceAll("[^0-9]", "");
            return Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;  // Low priority if no number
        }
    }
    
    /**
     * Creates an AMR for the ENHANCED MODEL (with battery and communication).
     */
    public AMRobot(String name, int field, int[] pos, Color color, int rows, int columns,
                   int maxBattery, int rechargeRate) {
        super(name, field, pos, new int[]{color.getRed(), color.getGreen(), color.getBlue()});
        this.rows = rows;
        this.columns = columns;
        this.enhancedMode = true;
        this.state = State.IDLE;
        this.carriedPallet = null;
        this.currentPath = new ArrayList<>();
        this.pathIndex = 0;
        this.palletsDelivered = 0;
        this.totalDistanceTraveled = 0;
        this.ticksIdle = 0;
        this.ticksCarrying = 0;
        
        // Gridlock prevention
        this.waitCounter = 0;
        this.lastPosition = pos.clone();
        this.stuckCounter = 0;
        this.random = new java.util.Random(System.nanoTime() + name.hashCode());
        this.robotPriority = extractPriority(name);
        
        // Battery configuration
        this.battery = maxBattery;
        this.maxBattery = maxBattery;
        this.rechargeRate = rechargeRate;
    }
    
    /**
     * Set the warehouse environment reference (needed for pathfinding).
     */
    public void setWarehouseEnvironment(WarehouseEnvironment env) {
        this.warehouseEnv = env;
    }
    
    // ==================== Main Movement Logic ====================
    
    @Override
    public void move(int nb) {
        for (int i = 0; i < nb; i++) {
            executeOneStep();
        }
    }
    
    /**
     * Execute one step of the robot's behavior based on current state.
     */
    private void executeOneStep() {
        // Update statistics
        if (state == State.IDLE) {
            ticksIdle++;
        } else if (carriedPallet != null) {
            ticksCarrying++;
        }
        
        // Handle battery drain (Enhanced mode only)
        if (enhancedMode && state != State.RECHARGING && state != State.DEAD) {
            if (battery > 0) {
                battery--;
            }
            if (battery <= 0) {
                state = State.DEAD;
                return;
            }
        }
        
        // Execute based on state
        switch (state) {
            case IDLE:
                // Do nothing, waiting for assignment
                break;
                
            case MOVING_TO_PICKUP:
            case DELIVERING:
            case MOVING_TO_RECHARGE:
                moveAlongPath();
                break;
                
            case RECHARGING:
                recharge();
                break;
                
            case DEAD:
                // Can't move
                break;
                
            default:
                break;
        }
    }
    
    /**
     * Move one step along the current path.
     * Includes gridlock prevention: if blocked for too long, try alternative moves.
     * Uses priority-based waiting: higher-numbered robots wait longer.
     */
    private void moveAlongPath() {
        if (currentPath == null || pathIndex >= currentPath.size()) {
            // Path completed or no path
            onPathCompleted();
            return;
        }
        
        int[] nextPos = currentPath.get(pathIndex);
        int[] currentPos = getLocation();
        
        // Check if we're stuck at the same position
        if (lastPosition != null && lastPosition[0] == currentPos[0] && lastPosition[1] == currentPos[1]) {
            stuckCounter++;
        } else {
            stuckCounter = 0;
            waitCounter = 0;
        }
        lastPosition = currentPos.clone();
        
        // Priority-based wait: higher ID robots wait proportionally longer
        int priorityWaitTicks = MAX_WAIT_TICKS + (robotPriority % 4);  // 2-5 ticks based on priority
        
        // Check if next cell is free
        if (isCellFree(nextPos)) {
            // Move to next position
            setLocation(nextPos);
            pathIndex++;
            totalDistanceTraveled++;
            waitCounter = 0;  // Reset wait counter on successful move
            
            // Check if path is complete
            if (pathIndex >= currentPath.size()) {
                onPathCompleted();
            }
        } else {
            // Cell is blocked - increment wait counter
            waitCounter++;
            
            // Higher priority robots (lower ID) try alternatives sooner
            // Lower priority robots wait longer before trying alternatives
            if (waitCounter >= priorityWaitTicks) {
                boolean moved = tryAlternativeMove(currentPos, nextPos);
                if (moved) {
                    waitCounter = 0;
                } else if (stuckCounter >= MAX_STUCK_COUNT) {
                    // Completely stuck - recalculate entire path with randomization
                    recalculatePathWithJitter();
                    stuckCounter = 0;
                }
            }
        }
    }
    
    /**
     * Try to move in an alternative direction when the preferred path is blocked.
     * Prefers moves that take us AWAY from the target (true detour) based on priority.
     * Returns true if an alternative move was made.
     */
    private boolean tryAlternativeMove(int[] currentPos, int[] blockedPos) {
        // Get all possible moves (4 directions + 4 diagonals for more options)
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        
        // Shuffle and prioritize based on robot priority
        shuffleArray(directions);
        
        // Sort by distance to target - higher priority robots prefer closer moves,
        // lower priority robots prefer moves that take them away (yielding)
        if (targetPosition != null && robotPriority > 3) {
            // Lower priority robots: sort moves so they prefer going AWAY from target
            sortByDistanceFromTarget(directions, currentPos, true);  // descending = away
        }
        
        for (int[] dir : directions) {
            int nx = currentPos[0] + dir[0];
            int ny = currentPos[1] + dir[1];
            
            // Skip the blocked position
            if (nx == blockedPos[0] && ny == blockedPos[1]) continue;
            
            // Check bounds
            if (nx < 0 || nx >= rows || ny < 0 || ny >= columns) continue;
            
            // Check if this cell is free and not an obstacle
            int[] newPos = new int[]{nx, ny};
            if (!warehouseEnv.isObstacle(nx, ny) && isCellFree(newPos)) {
                // Move to alternative position
                setLocation(newPos);
                totalDistanceTraveled++;
                
                // Recalculate path from new position
                recalculatePath();
                return true;
            }
        }
        return false;
    }
    
    /**
     * Sort directions by distance to target.
     * @param away If true, prefer moves that increase distance (yielding behavior)
     */
    private void sortByDistanceFromTarget(int[][] directions, int[] currentPos, boolean away) {
        java.util.Arrays.sort(directions, (a, b) -> {
            int distA = Math.abs((currentPos[0] + a[0]) - targetPosition[0]) + 
                       Math.abs((currentPos[1] + a[1]) - targetPosition[1]);
            int distB = Math.abs((currentPos[0] + b[0]) - targetPosition[0]) + 
                       Math.abs((currentPos[1] + b[1]) - targetPosition[1]);
            return away ? (distB - distA) : (distA - distB);
        });
    }
    
    /**
     * Shuffle array using Fisher-Yates algorithm.
     */
    private void shuffleArray(int[][] array) {
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }
    }
    
    /**
     * Recalculate path from current position to target.
     */
    private void recalculatePath() {
        if (targetPosition != null) {
            currentPath = findPathAStar(getLocation(), targetPosition);
            pathIndex = 0;
        }
    }
    
    /**
     * Recalculate path with random jitter to avoid same-path convergence.
     * Uses priority to add different detour distances for different robots.
     */
    private void recalculatePathWithJitter() {
        if (targetPosition == null) return;
        
        int[] currentPos = getLocation();
        
        // Higher priority robots (lower number) take shorter detours
        // Lower priority robots take longer detours to let others pass
        int detourDistance = 1 + (robotPriority % 5);  // 1-5 cells detour based on priority
        
        // Try to find a valid waypoint in a random direction
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
        shuffleArray(directions);
        
        for (int[] dir : directions) {
            int waypointX = currentPos[0] + dir[0] * detourDistance;
            int waypointY = currentPos[1] + dir[1] * detourDistance;
            
            // Clamp to grid bounds
            waypointX = Math.max(0, Math.min(rows - 1, waypointX));
            waypointY = Math.max(0, Math.min(columns - 1, waypointY));
            
            // Check if waypoint is valid
            if (!warehouseEnv.isObstacle(waypointX, waypointY) && 
                (waypointX != currentPos[0] || waypointY != currentPos[1])) {
                
                int[] waypoint = new int[]{waypointX, waypointY};
                
                // Path: current -> waypoint -> target
                List<int[]> pathToWaypoint = findPathAStar(currentPos, waypoint);
                List<int[]> pathToTarget = findPathAStar(waypoint, targetPosition);
                
                if (!pathToWaypoint.isEmpty() && !pathToTarget.isEmpty()) {
                    // Combine paths
                    currentPath = new ArrayList<>();
                    currentPath.addAll(pathToWaypoint);
                    currentPath.addAll(pathToTarget);
                    pathIndex = 0;
                    return;
                }
            }
        }
        
        // Fallback: just recalculate normally
        recalculatePath();
    }
    
    /**
     * Called when the robot reaches the end of its path.
     */
    private void onPathCompleted() {
        switch (state) {
            case MOVING_TO_PICKUP:
                // Arrived at entry area - pick up is handled by simulator
                state = State.PICKING_UP;
                break;
                
            case DELIVERING:
                // Arrived at exit area - delivery is handled by simulator
                state = State.DELIVERED;
                palletsDelivered++;
                break;
                
            case MOVING_TO_RECHARGE:
                // Arrived at recharge station
                state = State.RECHARGING;
                break;
                
            default:
                break;
        }
    }
    
    /**
     * Recharge battery while at recharge station.
     */
    private void recharge() {
        if (!enhancedMode) return;
        
        battery = Math.min(maxBattery, battery + rechargeRate);
        
        // Fully charged?
        if (battery >= maxBattery) {
            state = State.IDLE;
        }
    }
    
    // ==================== Task Assignment ====================
    
    /**
     * Assign a task to pick up a pallet.
     * 
     * @param pickupPosition Position of the entry area
     * @param destination    Exit area ID where pallet should go
     */
    public void assignPickupTask(int[] pickupPosition, String destination) {
        this.targetPosition = pickupPosition.clone();
        this.state = State.MOVING_TO_PICKUP;
        
        // Calculate path to pickup location
        calculatePath(pickupPosition);
    }
    
    /**
     * Called when robot picks up a pallet at entry area.
     */
    public void pickupPallet(Pallet pallet, int[] deliveryPosition) {
        this.carriedPallet = pallet;
        this.targetPosition = deliveryPosition.clone();
        this.state = State.DELIVERING;
        
        // Change color to show carrying pallet
        updateColor();
        
        // Calculate path to delivery location
        calculatePath(deliveryPosition);
    }
    
    /**
     * Called when robot delivers pallet at exit area.
     * Returns the delivered pallet.
     */
    public Pallet deliverPallet() {
        Pallet delivered = this.carriedPallet;
        this.carriedPallet = null;
        
        if (enhancedMode) {
            // Enhanced mode: return to idle for next task
            this.state = State.IDLE;
        } else {
            // Reference mode: stay in DELIVERED state (will be removed)
            this.state = State.DELIVERED;
        }
        
        // Change color back to idle
        updateColor();
        
        return delivered;
    }
    
    /**
     * Update robot color based on current state.
     * - MAGENTA when carrying pallet
     * - RED when low battery
     * - BLUE when idle/empty
     */
    public void updateColor() {
        Color newColor;
        
        if (carriedPallet != null) {
            newColor = COLOR_CARRYING;  // Magenta - carrying pallet
        } else if (enhancedMode && battery < maxBattery * 0.2) {
            newColor = COLOR_LOW_BATTERY;  // Red - low battery
        } else {
            newColor = COLOR_IDLE;  // Blue - normal
        }
        
        // Update the robot's color
        setColor(new int[]{newColor.getRed(), newColor.getGreen(), newColor.getBlue()});
    }
    
    /**
     * Assign robot to go to recharge station (Enhanced mode only).
     */
    public void assignRechargeTask(int[] rechargePosition) {
        if (!enhancedMode) return;
        
        this.targetPosition = rechargePosition.clone();
        this.state = State.MOVING_TO_RECHARGE;
        
        // Calculate path to recharge station
        calculatePath(rechargePosition);
    }
    
    // ==================== Pathfinding ====================
    
    /**
     * Calculate path from current position to target using A*.
     */
    private void calculatePath(int[] target) {
        currentPath = findPathAStar(getLocation(), target);
        pathIndex = 0;
    }
    
    /**
     * A* pathfinding algorithm that avoids obstacles.
     * Returns list of positions from start to goal (excluding start).
     */
    private List<int[]> findPathAStar(int[] start, int[] goal) {
        // Use A* algorithm with obstacle avoidance
        java.util.PriorityQueue<PathNode> openSet = new java.util.PriorityQueue<>(
            (a, b) -> Double.compare(a.fScore, b.fScore)
        );
        java.util.Set<String> closedSet = new java.util.HashSet<>();
        java.util.Map<String, PathNode> nodeMap = new java.util.HashMap<>();
        
        PathNode startNode = new PathNode(start[0], start[1], null);
        startNode.gScore = 0;
        startNode.fScore = heuristic(start, goal);
        openSet.add(startNode);
        nodeMap.put(posKey(start[0], start[1]), startNode);
        
        while (!openSet.isEmpty()) {
            PathNode current = openSet.poll();
            
            // Check if we reached the goal
            if (current.x == goal[0] && current.y == goal[1]) {
                return reconstructPath(current);
            }
            
            String currentKey = posKey(current.x, current.y);
            if (closedSet.contains(currentKey)) continue;
            closedSet.add(currentKey);
            
            // Explore neighbors (4 directions: up, down, left, right)
            int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] dir : directions) {
                int nx = current.x + dir[0];
                int ny = current.y + dir[1];
                
                // Check bounds
                if (nx < 0 || nx >= rows || ny < 0 || ny >= columns) continue;
                
                // Check if obstacle (skip if blocked)
                if (isObstacle(nx, ny)) continue;
                
                String neighborKey = posKey(nx, ny);
                if (closedSet.contains(neighborKey)) continue;
                
                double tentativeG = current.gScore + 1;
                
                PathNode neighbor = nodeMap.get(neighborKey);
                if (neighbor == null) {
                    neighbor = new PathNode(nx, ny, current);
                    neighbor.gScore = tentativeG;
                    neighbor.fScore = tentativeG + heuristic(new int[]{nx, ny}, goal);
                    nodeMap.put(neighborKey, neighbor);
                    openSet.add(neighbor);
                } else if (tentativeG < neighbor.gScore) {
                    neighbor.parent = current;
                    neighbor.gScore = tentativeG;
                    neighbor.fScore = tentativeG + heuristic(new int[]{nx, ny}, goal);
                    openSet.remove(neighbor);
                    openSet.add(neighbor);
                }
            }
        }
        
        // No path found - return simple direct path as fallback
        return findSimplePath(start, goal);
    }
    
    /**
     * Helper class for A* pathfinding.
     */
    private static class PathNode {
        int x, y;
        PathNode parent;
        double gScore = Double.MAX_VALUE;
        double fScore = Double.MAX_VALUE;
        
        PathNode(int x, int y, PathNode parent) {
            this.x = x;
            this.y = y;
            this.parent = parent;
        }
    }
    
    private String posKey(int x, int y) {
        return x + "," + y;
    }
    
    private double heuristic(int[] a, int[] b) {
        // Manhattan distance
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }
    
    private List<int[]> reconstructPath(PathNode node) {
        List<int[]> path = new ArrayList<>();
        PathNode current = node;
        while (current.parent != null) {
            path.add(0, new int[]{current.x, current.y});
            current = current.parent;
        }
        return path;
    }
    
    /**
     * Check if a position is an obstacle.
     */
    private boolean isObstacle(int x, int y) {
        if (warehouseEnv != null) {
            return warehouseEnv.isObstacle(x, y);
        }
        return false;
    }
    
    /**
     * Simple fallback pathfinding (greedy, no obstacle avoidance).
     */
    private List<int[]> findSimplePath(int[] start, int[] goal) {
        List<int[]> path = new ArrayList<>();
        int currentX = start[0];
        int currentY = start[1];
        int goalX = goal[0];
        int goalY = goal[1];
        
        while (currentX != goalX || currentY != goalY) {
            if (currentX < goalX) currentX++;
            else if (currentX > goalX) currentX--;
            else if (currentY < goalY) currentY++;
            else if (currentY > goalY) currentY--;
            
            path.add(new int[]{currentX, currentY});
            if (path.size() > rows * columns) break;
        }
        return path;
    }
    
    /**
     * Check if a cell is free (no obstacle or other robot).
     * Uses warehouse environment to properly check for real obstacles
     * (as opposed to visual markers for entry/exit areas).
     */
    private boolean isCellFree(int[] pos) {
        // First check if it's a real obstacle via warehouse environment
        if (warehouseEnv != null && warehouseEnv.isObstacle(pos[0], pos[1])) {
            return false;  // Real obstacle - not free
        }
        
        // Check perception grid for other robots (if available)
        if (grid != null) {
            int relX = pos[0] - getX() + field;
            int relY = pos[1] - getY() + field;
            
            // Check bounds of perception grid
            if (relX >= 0 && relX < grid.length && relY >= 0 && relY < grid[0].length) {
                ColorSimpleCell cell = grid[relX][relY];
                if (cell != null && cell.getContent() != null) {
                    // Check if it's another robot (not a visual marker)
                    Object content = cell.getContent();
                    if (content instanceof ColorRobot) {
                        return false;  // Another robot is there
                    }
                    // Visual markers (ColorObstacle) at entry/exit are OK
                }
            }
        }
        
        return true;  // Cell is free
    }
    
    // ==================== Communication (Enhanced Mode) ====================
    
    @Override
    public void handleMessage(Message msg) {
        if (!enhancedMode) return;  // No communication in reference model
        
        // Parse message content and handle accordingly
        String content = msg.getContent();
        
        // TODO: Implement message handling for auction-based task allocation
        // Message types:
        // - NEW_TASK: pallet_id, location, destination
        // - BID: pallet_id, amr_id, score
        // - TASK_CLAIMED: pallet_id, winner_amr_id
        // - POSITION_UPDATE: amr_id, x, y
        // - HELP_NEEDED: pallet at intermediate
    }
    
    /**
     * Broadcast current position (Enhanced mode).
     */
    public void broadcastPosition() {
        if (!enhancedMode) return;
        
        String content = String.format("POSITION:%d,%d,%d", getId(), getX(), getY());
        Message msg = new Message(getId(), content);
        sendMessage(msg);
    }
    
    // ==================== Battery Management (Enhanced Mode) ====================
    
    /**
     * Check if robot has enough battery to complete a task.
     * 
     * @param taskDistance Distance of the task (pickup + delivery)
     * @param rechargeDistance Distance to nearest recharge station from end point
     * @return true if battery is sufficient
     */
    public boolean canCompleteTask(int taskDistance, int rechargeDistance) {
        if (!enhancedMode) return true;  // No battery in reference model
        
        // Need enough battery for task + return to recharge + safety margin
        int requiredBattery = (int) ((taskDistance + rechargeDistance) * 1.2);
        return battery >= requiredBattery;
    }
    
    /**
     * Check if robot should go recharge.
     */
    public boolean shouldRecharge() {
        if (!enhancedMode) return false;
        return battery < maxBattery * 0.3;  // Below 30%
    }
    
    /**
     * Check if robot is critically low on battery.
     */
    public boolean isBatteryCritical() {
        if (!enhancedMode) return false;
        return battery < maxBattery * 0.15;  // Below 15%
    }
    
    // ==================== Getters ====================
    
    public State getState() {
        return state;
    }
    
    public void setState(State state) {
        this.state = state;
    }
    
    public boolean isEnhancedMode() {
        return enhancedMode;
    }
    
    public Pallet getCarriedPallet() {
        return carriedPallet;
    }
    
    public boolean isCarryingPallet() {
        return carriedPallet != null;
    }
    
    public int[] getTargetPosition() {
        return targetPosition != null ? targetPosition.clone() : null;
    }
    
    public int getBattery() {
        return battery;
    }
    
    public int getMaxBattery() {
        return maxBattery;
    }
    
    public double getBatteryPercentage() {
        if (!enhancedMode) return 100.0;
        return (double) battery / maxBattery * 100;
    }
    
    public int getPalletsDelivered() {
        return palletsDelivered;
    }
    
    public int getTotalDistanceTraveled() {
        return totalDistanceTraveled;
    }
    
    public int getTicksIdle() {
        return ticksIdle;
    }
    
    public int getTicksCarrying() {
        return ticksCarrying;
    }
    
    public double getUtilizationRate() {
        int totalTicks = ticksIdle + ticksCarrying;
        if (totalTicks == 0) return 0;
        return (double) ticksCarrying / totalTicks * 100;
    }
    
    public boolean isIdle() {
        return state == State.IDLE;
    }
    
    public boolean isAvailable() {
        return state == State.IDLE && !isBatteryCritical();
    }
    
    public boolean isDead() {
        return state == State.DEAD;
    }
    
    @Override
    public String toString() {
        if (enhancedMode) {
            return String.format("AMR[%s, state=%s, pos=(%d,%d), battery=%d%%, carrying=%b]",
                    getName(), state, getX(), getY(), (int) getBatteryPercentage(), isCarryingPallet());
        } else {
            return String.format("AMR[%s, state=%s, pos=(%d,%d), carrying=%b]",
                    getName(), state, getX(), getY(), isCarryingPallet());
        }
    }
}

