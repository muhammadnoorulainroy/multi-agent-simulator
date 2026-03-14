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
 * Two modes:
 * 1. REFERENCE MODEL: 1 AMR per pallet, no communication, no battery, vanishes after delivery
 * 2. ENHANCED MODEL: Reusable AMRs with Contract Net coordination, battery, intermediate relay
 *
 * Enhanced Model Communication (Contract Net Protocol):
 * - Simulator announces tasks → AMRs compute bids → best bidder wins
 * - AMRs broadcast status (position, battery, carrying state)
 * - Relay requests when battery too low for full delivery
 */
public class AMRobot extends ColorInteractionRobot<ColorSimpleCell> {

    // ==================== Visual Colors ====================
    public static final Color COLOR_IDLE = new Color(0, 150, 255);        // Blue
    public static final Color COLOR_CARRYING = new Color(255, 0, 255);    // Magenta
    public static final Color COLOR_LOW_BATTERY = new Color(255, 50, 50); // Red

    // ==================== Robot State ====================
    public enum State {
        IDLE,
        MOVING_TO_PICKUP,
        PICKING_UP,
        DELIVERING,
        DELIVERED,
        MOVING_TO_INTERMEDIATE,   // Enhanced: delivering to intermediate relay
        MOVING_TO_RECHARGE,
        RECHARGING,
        DEAD
    }

    // ==================== Configuration ====================
    private final int rows;
    private final int columns;
    private final boolean enhancedMode;

    // ==================== State Variables ====================
    private State state;
    private Pallet carriedPallet;
    private int[] targetPosition;
    private List<int[]> currentPath;
    private int pathIndex;

    // ==================== Battery (Enhanced Mode Only) ====================
    private int battery;
    private int maxBattery;
    private int rechargeRate;

    // ==================== Statistics ====================
    private int palletsDelivered;
    private int totalDistanceTraveled;
    private int ticksIdle;
    private int ticksCarrying;
    private int ticksRecharging;
    private int rechargeCount;

    // ==================== Pathfinding ====================
    private WarehouseEnvironment warehouseEnv;

    // ==================== Recharge-with-pallet ====================
    private int[] savedDeliveryTarget;  // Remembers exit target while recharging with pallet

    // ==================== Blocked Handling ====================
    private int waitCounter;
    private int[] lastPosition;
    private int totalStuckTicks;
    private java.util.Random random;

    // Reference model: simple wait+repath
    private static final int REPATH_AFTER_TICKS = 3;
    // Enhanced model: escalating escape
    private static final int PERPENDICULAR_ESCAPE_TICKS = 8;
    private static final int RANDOM_ESCAPE_TICKS = 15;

    /**
     * Creates an AMR for the REFERENCE MODEL (no battery, no communication).
     */
    public AMRobot(String name, int field, int[] pos, Color color, int rows, int columns) {
        super(name, field, pos, new int[]{color.getRed(), color.getGreen(), color.getBlue()});
        this.rows = rows;
        this.columns = columns;
        this.enhancedMode = false;
        initCommon(pos);
        this.battery = -1;
        this.maxBattery = -1;
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
        initCommon(pos);
        this.battery = maxBattery;
        this.maxBattery = maxBattery;
        this.rechargeRate = rechargeRate;
    }

    private void initCommon(int[] pos) {
        this.state = State.IDLE;
        this.carriedPallet = null;
        this.currentPath = new ArrayList<>();
        this.pathIndex = 0;
        this.palletsDelivered = 0;
        this.totalDistanceTraveled = 0;
        this.ticksIdle = 0;
        this.ticksCarrying = 0;
        this.ticksRecharging = 0;
        this.rechargeCount = 0;
        this.waitCounter = 0;
        this.lastPosition = pos.clone();
        this.totalStuckTicks = 0;
        this.random = new java.util.Random(System.nanoTime() + (long) pos[0] * 31 + pos[1]);
    }

    public void setWarehouseEnvironment(WarehouseEnvironment env) {
        this.warehouseEnv = env;
    }

    // ==================== Contract Net: Bidding ====================

    /**
     * Compute a bid score for picking up a pallet at the given entry area.
     * Higher score = better candidate. Returns -1 if this AMR cannot bid.
     *
     * Factors:
     * - Distance to pickup (closer = better)
     * - Battery sufficiency (must have enough for full trip or relay)
     * - Idle status (only idle AMRs bid)
     */
    public double computeBidScore(int[] pickupPos, int[] exitPos) {
        if (!enhancedMode) return -1;
        if (state != State.IDLE) return -1;
        if (isBatteryCritical()) return -1;

        int[] myPos = getLocation();
        int distToPickup = manhattanDist(myPos, pickupPos);
        int distPickupToExit = manhattanDist(pickupPos, exitPos);

        // Estimate battery needed: pickup trip + delivery trip + return to nearest recharge
        int[] nearestRecharge = (warehouseEnv != null) ?
            warehouseEnv.getNearestRechargeStation(exitPos) : null;
        int distToRecharge = (nearestRecharge != null) ?
            manhattanDist(exitPos, nearestRecharge) : 10;

        int totalCost = distToPickup + distPickupToExit + distToRecharge;
        int safetyMargin = (int) (totalCost * 0.2);

        if (battery < totalCost + safetyMargin) {
            // Not enough for full delivery — check if enough for relay to intermediate
            if (warehouseEnv != null) {
                IntermediateArea nearest = warehouseEnv.getNearestIntermediateArea(pickupPos);
                if (nearest != null && nearest.canAccept()) {
                    int distToIntermediate = manhattanDist(pickupPos, nearest.getPosition());
                    int rechargeFromIntermediate = (warehouseEnv.getNearestRechargeStation(nearest.getPosition()) != null) ?
                        manhattanDist(nearest.getPosition(), warehouseEnv.getNearestRechargeStation(nearest.getPosition())) : 10;
                    int relayCost = distToPickup + distToIntermediate + rechargeFromIntermediate;
                    if (battery < relayCost + (int)(relayCost * 0.2)) {
                        return -1;  // Can't even do relay
                    }
                    // Can do relay — bid lower (0.5x penalty)
                    double proximityScore = 1.0 / (1.0 + distToPickup);
                    double batteryScore = (double) battery / maxBattery;
                    return (proximityScore * 0.6 + batteryScore * 0.4) * 0.5;  // Relay penalty
                }
                return -1;
            }
            return -1;
        }

        // Full delivery possible — compute score
        double proximityScore = 1.0 / (1.0 + distToPickup);
        double batteryScore = (double) battery / maxBattery;
        return proximityScore * 0.6 + batteryScore * 0.4;
    }

    /**
     * Check if this AMR can complete a full delivery (pickup → exit → recharge).
     */
    public boolean canCompleteFullDelivery(int[] pickupPos, int[] exitPos) {
        if (!enhancedMode) return true;
        int[] myPos = getLocation();
        int totalTrip = manhattanDist(myPos, pickupPos) + manhattanDist(pickupPos, exitPos);
        int[] nearestRecharge = (warehouseEnv != null) ?
            warehouseEnv.getNearestRechargeStation(exitPos) : null;
        int rechargeTrip = (nearestRecharge != null) ? manhattanDist(exitPos, nearestRecharge) : 10;
        int required = (int) ((totalTrip + rechargeTrip) * 1.2);
        return battery >= required;
    }

    private int manhattanDist(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }

    // ==================== Main Movement Logic ====================

    @Override
    public void move(int nb) {
        for (int i = 0; i < nb; i++) {
            executeOneStep();
        }
    }

    private void executeOneStep() {
        // Update statistics
        if (state == State.IDLE) {
            ticksIdle++;
        } else if (carriedPallet != null) {
            ticksCarrying++;
        }
        if (state == State.RECHARGING) {
            ticksRecharging++;
        }

        // Battery check (drain happens on actual movement, not every tick)
        if (enhancedMode && battery <= 0 && state != State.RECHARGING && state != State.DEAD) {
            state = State.DEAD;
            return;
        }

        switch (state) {
            case IDLE:
                break;
            case MOVING_TO_PICKUP:
            case DELIVERING:
            case MOVING_TO_INTERMEDIATE:
            case MOVING_TO_RECHARGE:
                moveAlongPath();
                break;
            case RECHARGING:
                recharge();
                break;
            case DEAD:
                break;
            default:
                break;
        }
    }

    /**
     * Move one step along the current path.
     * Reference model: simple wait + repath.
     * Enhanced model: escalating escape (wait → repath → perpendicular → random).
     */
    private void moveAlongPath() {
        if (currentPath == null || pathIndex >= currentPath.size()) {
            onPathCompleted();
            return;
        }

        int[] nextPos = currentPath.get(pathIndex);
        int[] currentPos = getLocation();

        // Track stuck state
        boolean sameAsLast = lastPosition != null &&
            lastPosition[0] == currentPos[0] && lastPosition[1] == currentPos[1];
        if (sameAsLast) {
            totalStuckTicks++;
        } else {
            totalStuckTicks = 0;
            waitCounter = 0;
        }
        lastPosition = currentPos.clone();

        if (isCellFree(nextPos)) {
            setLocation(nextPos);
            pathIndex++;
            totalDistanceTraveled++;
            waitCounter = 0;
            totalStuckTicks = 0;

            // Battery drain on movement (Enhanced mode)
            if (enhancedMode && battery > 0) {
                battery--;
            }

            if (pathIndex >= currentPath.size()) {
                onPathCompleted();
            }
        } else {
            waitCounter++;

            if (!enhancedMode) {
                // Reference model: simple wait + repath
                if (waitCounter >= REPATH_AFTER_TICKS) {
                    recalculatePath();
                    waitCounter = 0;
                }
            } else {
                // Enhanced model: escalating escape for persistent AMRs
                if (waitCounter >= REPATH_AFTER_TICKS) {
                    recalculatePath();
                    waitCounter = 0;
                }
                if (totalStuckTicks >= PERPENDICULAR_ESCAPE_TICKS) {
                    if (tryPerpendicularEscape(currentPos, nextPos)) {
                        totalStuckTicks = 0;
                    }
                }
                if (totalStuckTicks >= RANDOM_ESCAPE_TICKS) {
                    tryRandomEscapeMove(currentPos);
                    totalStuckTicks = 0;
                }
            }
        }
    }

    // ==================== Enhanced Escape Moves ====================

    private boolean tryPerpendicularEscape(int[] currentPos, int[] blockedPos) {
        int dx = blockedPos[0] - currentPos[0];
        int dy = blockedPos[1] - currentPos[1];

        int[][] perpDirs;
        if (dx != 0) {
            perpDirs = new int[][]{{0, -1}, {0, 1}};
        } else {
            perpDirs = new int[][]{{-1, 0}, {1, 0}};
        }

        if (random.nextBoolean()) {
            int[] temp = perpDirs[0]; perpDirs[0] = perpDirs[1]; perpDirs[1] = temp;
        }

        for (int[] dir : perpDirs) {
            int nx = currentPos[0] + dir[0];
            int ny = currentPos[1] + dir[1];
            if (nx >= 0 && nx < rows && ny >= 0 && ny < columns) {
                int[] newPos = new int[]{nx, ny};
                if (warehouseEnv != null && !warehouseEnv.isObstacle(nx, ny) && isCellFree(newPos)) {
                    setLocation(newPos);
                    totalDistanceTraveled++;
                    if (enhancedMode && battery > 0) battery--;
                    recalculatePath();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryRandomEscapeMove(int[] currentPos) {
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        // Shuffle
        for (int i = directions.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] temp = directions[i]; directions[i] = directions[j]; directions[j] = temp;
        }

        for (int[] dir : directions) {
            int nx = currentPos[0] + dir[0];
            int ny = currentPos[1] + dir[1];
            if (nx >= 0 && nx < rows && ny >= 0 && ny < columns) {
                int[] newPos = new int[]{nx, ny};
                if (warehouseEnv != null && !warehouseEnv.isObstacle(nx, ny) && isCellFree(newPos)) {
                    setLocation(newPos);
                    totalDistanceTraveled++;
                    if (enhancedMode && battery > 0) battery--;
                    recalculatePath();
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== Path Completion ====================

    private void recalculatePath() {
        if (targetPosition != null) {
            currentPath = findPathAStar(getLocation(), targetPosition);
            pathIndex = 0;
        }
    }

    private void onPathCompleted() {
        int[] currentPos = getLocation();

        switch (state) {
            case MOVING_TO_PICKUP:
                state = State.PICKING_UP;
                break;

            case DELIVERING:
                if (warehouseEnv != null && warehouseEnv.isExitArea(currentPos)) {
                    state = State.DELIVERED;
                    palletsDelivered++;
                } else if (targetPosition != null) {
                    calculatePath(targetPosition);
                }
                break;

            case MOVING_TO_INTERMEDIATE:
                // Stay in this state — simulator's checkDeliveries handles the drop
                break;

            case MOVING_TO_RECHARGE:
                state = State.RECHARGING;
                rechargeCount++;
                break;

            default:
                break;
        }
    }

    private void recharge() {
        if (!enhancedMode) return;
        battery = Math.min(maxBattery, battery + rechargeRate);
        if (battery >= maxBattery) {
            if (carriedPallet != null && savedDeliveryTarget != null) {
                // Resume delivery after recharging with pallet
                this.targetPosition = savedDeliveryTarget.clone();
                this.state = State.DELIVERING;
                this.savedDeliveryTarget = null;
                calculatePath(targetPosition);
            } else {
                state = State.IDLE;
            }
            updateColor();
        }
    }

    // ==================== Task Assignment ====================

    public void assignPickupTask(int[] pickupPosition, String destination) {
        this.targetPosition = pickupPosition.clone();
        this.state = State.MOVING_TO_PICKUP;
        calculatePath(pickupPosition);
    }

    public void pickupPallet(Pallet pallet, int[] deliveryPosition) {
        this.carriedPallet = pallet;
        this.targetPosition = deliveryPosition.clone();
        this.state = State.DELIVERING;
        updateColor();
        calculatePath(deliveryPosition);
    }

    /**
     * Assign relay delivery: carry pallet to intermediate area instead of exit.
     */
    public void pickupPalletForRelay(Pallet pallet, int[] intermediatePosition) {
        this.carriedPallet = pallet;
        this.targetPosition = intermediatePosition.clone();
        this.state = State.MOVING_TO_INTERMEDIATE;
        updateColor();
        calculatePath(intermediatePosition);
    }

    public Pallet deliverPallet() {
        Pallet delivered = this.carriedPallet;
        this.carriedPallet = null;

        if (enhancedMode) {
            this.state = State.IDLE;
            updateColor();
        } else {
            this.state = State.DELIVERED;
        }

        return delivered;
    }

    /**
     * Drop pallet at intermediate area (relay or emergency).
     * Returns the dropped pallet so simulator can store it.
     */
    public Pallet dropPalletAtIntermediate() {
        Pallet dropped = this.carriedPallet;
        this.carriedPallet = null;
        if (state != State.DEAD) {
            this.state = State.IDLE;
        }
        updateColor();
        return dropped;
    }

    public void updateColor() {
        Color newColor;
        if (carriedPallet != null) {
            newColor = COLOR_CARRYING;
        } else if (enhancedMode && battery < maxBattery * 0.2) {
            newColor = COLOR_LOW_BATTERY;
        } else {
            newColor = COLOR_IDLE;
        }
        setColor(new int[]{newColor.getRed(), newColor.getGreen(), newColor.getBlue()});
    }

    public void assignRechargeTask(int[] rechargePosition) {
        if (!enhancedMode) return;
        // If carrying a pallet, save the delivery target so we resume after recharging
        if (carriedPallet != null && targetPosition != null) {
            this.savedDeliveryTarget = targetPosition.clone();
        }
        this.targetPosition = rechargePosition.clone();
        this.state = State.MOVING_TO_RECHARGE;
        calculatePath(rechargePosition);
    }

    // ==================== Pathfinding ====================

    private void calculatePath(int[] target) {
        currentPath = findPathAStar(getLocation(), target);
        pathIndex = 0;
    }

    private List<int[]> findPathAStar(int[] start, int[] goal) {
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

            if (current.x == goal[0] && current.y == goal[1]) {
                return reconstructPath(current);
            }

            String currentKey = posKey(current.x, current.y);
            if (closedSet.contains(currentKey)) continue;
            closedSet.add(currentKey);

            int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] dir : directions) {
                int nx = current.x + dir[0];
                int ny = current.y + dir[1];

                if (nx < 0 || nx >= rows || ny < 0 || ny >= columns) continue;
                if (warehouseEnv != null && warehouseEnv.isObstacle(nx, ny)) continue;

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

        return new ArrayList<>();
    }

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

    private String posKey(int x, int y) { return x + "," + y; }

    private double heuristic(int[] a, int[] b) {
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
     * Check if a cell is free (no static obstacle, no other AMR, no human).
     */
    private boolean isCellFree(int[] pos) {
        if (warehouseEnv != null && warehouseEnv.isObstacle(pos[0], pos[1])) {
            return false;
        }
        if (warehouseEnv != null && warehouseEnv.isOccupiedByRobot(pos, getId())) {
            return false;
        }
        if (warehouseEnv != null && warehouseEnv.isOccupiedByHuman(pos)) {
            return false;
        }

        if (grid != null) {
            int relX = pos[0] - getX() + field;
            int relY = pos[1] - getY() + field;
            if (relX >= 0 && relX < grid.length && relY >= 0 && relY < grid[0].length) {
                ColorSimpleCell cell = grid[relX][relY];
                if (cell != null && cell.getContent() != null) {
                    if (cell.getContent() instanceof ColorRobot) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    // ==================== Communication (Enhanced Mode) ====================

    @Override
    public void handleMessage(Message msg) {
        if (!enhancedMode) return;

        String content = msg.getContent();
        if (content == null) return;

        // Log received messages for debug purposes
        // Messages are processed by the simulator's Contract Net mediator,
        // not individually by each AMR. This handler is for future direct
        // AMR-to-AMR communication (e.g., relay requests, position updates).
    }

    /**
     * Broadcast current status to other AMRs.
     */
    public void broadcastStatus() {
        if (!enhancedMode) return;
        String content = String.format("STATUS:%d:%d,%d:%d:%s",
            getId(), getX(), getY(), battery, state.name());
        Message msg = new Message(getId(), content);
        sendMessage(msg);
    }

    // ==================== Battery Management ====================

    public boolean canCompleteTask(int taskDistance, int rechargeDistance) {
        if (!enhancedMode) return true;
        int requiredBattery = (int) ((taskDistance + rechargeDistance) * 1.2);
        return battery >= requiredBattery;
    }

    public boolean shouldRecharge() {
        if (!enhancedMode) return false;
        return battery < maxBattery * 0.4;  // 40% — proactive recharge
    }

    public boolean isBatteryCritical() {
        if (!enhancedMode) return false;
        return battery < maxBattery * 0.2;  // 20% — emergency threshold
    }

    // ==================== Getters ====================

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public boolean isEnhancedMode() { return enhancedMode; }
    public Pallet getCarriedPallet() { return carriedPallet; }
    public boolean isCarryingPallet() { return carriedPallet != null; }

    public int[] getTargetPosition() {
        return targetPosition != null ? targetPosition.clone() : null;
    }

    public int getBattery() { return battery; }
    public int getMaxBattery() { return maxBattery; }

    public double getBatteryPercentage() {
        if (!enhancedMode) return 100.0;
        return (double) battery / maxBattery * 100;
    }

    public int getPalletsDelivered() { return palletsDelivered; }
    public int getTotalDistanceTraveled() { return totalDistanceTraveled; }
    public int getTicksIdle() { return ticksIdle; }
    public int getTicksCarrying() { return ticksCarrying; }
    public int getTicksRecharging() { return ticksRecharging; }
    public int getRechargeCount() { return rechargeCount; }

    public double getUtilizationRate() {
        int totalTicks = ticksIdle + ticksCarrying;
        if (totalTicks == 0) return 0;
        return (double) ticksCarrying / totalTicks * 100;
    }

    public boolean isIdle() { return state == State.IDLE; }
    public boolean isAvailable() { return state == State.IDLE && !isBatteryCritical(); }
    public boolean isDead() { return state == State.DEAD; }

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
