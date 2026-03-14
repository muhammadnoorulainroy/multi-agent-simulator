package fr.emse.warehouse;

import fr.emse.fayol.maqit.simulator.ColorSimFactory;
import fr.emse.fayol.maqit.simulator.configuration.IniFile;
import fr.emse.fayol.maqit.simulator.configuration.SimProperties;
import fr.emse.fayol.maqit.simulator.components.ColorObstacle;
import fr.emse.fayol.maqit.simulator.components.ColorRobot;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.components.SituatedComponent;
import fr.emse.fayol.maqit.simulator.components.ComponentType;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

// COLORS for different components - makes visualization clearer
// Entry Areas: GREEN (where pallets arrive)
// Exit Areas: RED/ORANGE (destinations)
// Intermediate Areas: CYAN (relay stations)
// Recharge Stations: YELLOW
// AMRs: BLUE (idle) or MAGENTA (carrying pallet)
// Obstacles: DARK GRAY

/**
 * Main warehouse simulation for AMR coordination.
 * 
 * Supports two modes:
 * 1. REFERENCE MODEL: Simple, naive approach (baseline for comparison)
 *    - 1 AMR per pallet
 *    - No communication
 *    - No battery management
 *    - AMR disappears after delivery
 * 
 * 2. ENHANCED MODEL: Decentralized coordination
 *    - Fewer AMRs (reusable)
 *    - Communication between AMRs (auction-based task allocation)
 *    - Battery management with recharging
 *    - Intermediate areas for relay
 */
public class WarehouseSimulator extends ColorSimFactory {
    
    // ==================== Simulation Mode ====================
    public enum SimulationMode {
        REFERENCE,  // Naive baseline model
        ENHANCED    // Decentralized coordination model
    }
    
    private SimulationMode mode;
    
    // ==================== Warehouse Components ====================
    private WarehouseEnvironment warehouse;
    private List<AMRobot> amrList;
    private List<AMRobot> amrsToRemove;  // AMRs to remove (reference model)
    private List<Human> humanList;       // Human workers (dynamic obstacles)
    
    // ==================== Custom Graphics ====================
    private WarehouseGraphicalWindow customWindow;  // Custom icon-based window
    
    // ==================== Visual Configuration ====================
    private int numHumans = 2;  // Default; overridden from warehouse_config.ini [warehouse] num_humans
    
    // ==================== Configuration ====================
    private int totalPalletsToGenerate;
    private int palletsGenerated;
    private double palletArrivalProbability;
    
    // Enhanced mode config
    private int maxBattery;
    private int rechargeRate;
    private int numAMRs;
    
    // ==================== Statistics ====================
    private int simulationTicks;
    private long startTime;
    private long endTime;

    /**
     * Creates a warehouse simulator.
     */
    public WarehouseSimulator(SimProperties sp, SimulationMode mode) {
        super(sp);
        this.mode = mode;
        this.amrList = new ArrayList<>();
        this.amrsToRemove = new ArrayList<>();
        this.humanList = new ArrayList<>();
        this.palletsGenerated = 0;
        this.simulationTicks = 0;
        
        // Default configurations (can be overridden)
        this.totalPalletsToGenerate = 20;
        this.palletArrivalProbability = 0.15;  // 15% chance per tick
        this.maxBattery = 100;
        this.rechargeRate = 5;
        this.numAMRs = 5;  // For enhanced mode
    }
    
    // ==================== Configuration Setters ====================
    
    public void setTotalPallets(int total) {
        this.totalPalletsToGenerate = total;
    }
    
    public void setPalletArrivalProbability(double prob) {
        this.palletArrivalProbability = prob;
    }
    
    public void setMaxBattery(int battery) {
        this.maxBattery = battery;
    }
    
    public void setRechargeRate(int rate) {
        this.rechargeRate = rate;
    }
    
    public void setNumAMRs(int num) {
        this.numAMRs = num;
    }

    public void setNumHumans(int num) {
        this.numHumans = num;
    }
    
    // ==================== Environment Setup ====================
    
    @Override
    public void createEnvironment() {
        this.environment = new ColorGridEnvironment(this.sp.seed);
        
        // Create warehouse environment
        this.warehouse = new WarehouseEnvironment(this.sp.rows, this.sp.columns);
        
        // Setup default warehouse layout
        setupWarehouseLayout();
    }
    
    /**
     * Setup the warehouse layout with entry areas, exit areas, and obstacles.
     * 
     * COORDINATE SYSTEM: [row, column] where:
     * - row 0 is TOP, row increases going DOWN
     * - column 0 is LEFT, column increases going RIGHT
     * 
     * Layout matches reference image:
     * - Exit areas (Z1, Z2) on LEFT side (low column numbers)  
     * - Entry areas (A1, A2, A3) on RIGHT side (high column numbers)
     * - Intermediate areas in CENTER
     */
    private void setupWarehouseLayout() {
        // ===== EXIT AREAS (Destinations) - LEFT side =====
        // Z1 at top-left, Z2 at bottom-left
        String[] exitIds = {"Z1", "Z2"};
        int[][] exitPositions = {
            {2, 1},                  // Z1: row 2, column 1 (top-left)
            {sp.rows - 3, 1}         // Z2: row 9, column 1 (bottom-left)
        };
        
        for (int i = 0; i < exitIds.length; i++) {
            ExitArea exit = new ExitArea(exitIds[i], exitPositions[i]);
            warehouse.addExitArea(exit);
        }
        
        // ===== ENTRY AREAS (Pallet Spawn) - RIGHT side =====
        // A1, A2, A3 distributed on right edge
        String[] entryIds = {"A1", "A2", "A3"};
        int[][] entryPositions = {
            {2, sp.columns - 2},              // A1: row 2, column 14 (top-right)
            {sp.rows / 2, sp.columns - 2},    // A2: row 6, column 14 (middle-right)
            {sp.rows - 3, sp.columns - 2}     // A3: row 9, column 14 (bottom-right)
        };
        
        for (int i = 0; i < entryIds.length; i++) {
            EntryArea entry = new EntryArea(
                entryIds[i],
                entryPositions[i],
                palletArrivalProbability / entryIds.length,
                exitIds,
                sp.seed + i
            );
            warehouse.addEntryArea(entry);
        }
        
        // ===== INTERMEDIATE AREAS & CHARGING STATIONS — Enhanced Model ONLY =====
        // These features do not exist in the Reference Model:
        //   - Intermediate areas are relay stations used by reusable AMRs
        //   - Charging stations are only needed when battery management is active
        if (mode == SimulationMode.ENHANCED) {
            // --- Intermediate Areas - CENTER of grid ---
            IntermediateArea i1 = new IntermediateArea("I1",
                new int[]{3, sp.columns / 2}, 5);             // Upper-middle
            IntermediateArea i2 = new IntermediateArea("I2",
                new int[]{sp.rows - 4, sp.columns / 2}, 5);  // Lower-middle
            warehouse.addIntermediateArea(i1);
            warehouse.addIntermediateArea(i2);

            // --- Charging Stations — 2 strategic positions ---
            //
            // With max 2 AMRs charging simultaneously, 2 stations is sufficient.
            // Strategy: one on each side so AMRs always have a nearby station.
            //   C1 (center-left, near exits)   — recharge after delivery to Z1/Z2
            //   C2 (center-right, near entries) — recharge near pickup areas A1-A3
            warehouse.addRechargeStation(new int[]{sp.rows / 2,   3});                // C1 center-left
            warehouse.addRechargeStation(new int[]{sp.rows / 2,   sp.columns - 4});  // C2 center-right
        }
    }
    
    @Override
    public void createObstacle() {
        // Create fixed obstacles with smart placement:
        // - Spread apart (minimum 3 cells between obstacles)
        // - Away from entry areas (right side, columns >= columns-4)
        // - Away from exit areas (left side, columns <= 3)
        
        final int MIN_OBSTACLE_SPACING = 3;  // Minimum cells between obstacles
        final int ENTRY_BUFFER = 4;          // Keep away from right edge (entry areas)
        final int EXIT_BUFFER = 4;           // Keep away from left edge (exit areas)
        final int MAX_ATTEMPTS = 100;        // Prevent infinite loop
        
        List<int[]> placedObstacles = new ArrayList<>();
        
        for (int i = 0; i < this.sp.nbobstacle; i++) {
            int attempts = 0;
            boolean placed = false;
            
            while (!placed && attempts < MAX_ATTEMPTS) {
                attempts++;
                int[] pos = this.environment.getPlace();
                
                // Skip if in reserved areas
                if (warehouse.isEntryArea(pos) || warehouse.isExitArea(pos)
                        || warehouse.isIntermediateArea(pos) || warehouse.isRechargeStation(pos)) {
                    continue;
                }
                
                // Skip if too close to left edge (exit areas)
                if (pos[1] < EXIT_BUFFER) {
                    continue;
                }
                
                // Skip if too close to right edge (entry areas)
                if (pos[1] > sp.columns - ENTRY_BUFFER - 1) {
                    continue;
                }
                
                // Skip if too close to another obstacle
                boolean tooClose = false;
                for (int[] existing : placedObstacles) {
                    int dist = Math.abs(pos[0] - existing[0]) + Math.abs(pos[1] - existing[1]);
                    if (dist < MIN_OBSTACLE_SPACING) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) {
                    continue;
                }
                
                // Valid position - place obstacle
                int[] rgb = {
                    this.sp.colorobstacle.getRed(),
                    this.sp.colorobstacle.getGreen(),
                    this.sp.colorobstacle.getBlue()
                };
                ColorObstacle obstacle = new ColorObstacle(pos, rgb);
                addNewComponent(obstacle);
                warehouse.addObstacle(pos);
                placedObstacles.add(pos);
                placed = true;
            }
            
            if (!placed) {
                System.out.println("Warning: Could not place obstacle " + i + " with proper spacing");
            }
        }
        
        System.out.println("Placed " + placedObstacles.size() + " obstacles (spread apart, away from entry/exit)");
    }
    
    @Override
    public void createRobot() {
        // For reference model, robots are created dynamically when pallets arrive
        // For enhanced model, create initial pool of AMRs
        
        if (mode == SimulationMode.ENHANCED) {
            for (int i = 0; i < numAMRs; i++) {
                int[] pos = this.environment.getPlace();
                
                AMRobot amr = new AMRobot(
                    "AMR" + i,
                    this.sp.field,
                    pos,
                    this.sp.colorrobot,
                    this.sp.rows,
                    this.sp.columns,
                    maxBattery,
                    rechargeRate
                );
                amr.setWarehouseEnvironment(warehouse);
                
                // Register robot position for collision tracking
                warehouse.updateRobotPosition(amr.getId(), pos);
                
                amrList.add(amr);
                addNewComponent(amr);
            }
        }
    }
    
    @Override
    public void createGoal() {
        // Area icons are now rendered directly by WarehouseGraphicalWindow from
        // WarehouseEnvironment — no ColorObstacle markers needed on the grid.

        // Create human workers (dynamic obstacles)
        createHumans();
    }
    
    /**
     * Create human workers that move randomly in the warehouse.
     * Humans are shown in YELLOW/ORANGE to distinguish from robots (BLUE).
     * 
     * Smart placement ensures humans:
     * - Start away from entry areas (right side)
     * - Start away from exit areas (left side)
     * - Start spread apart from each other
     */
    private void createHumans() {
        // Simple human creation - let framework place them
        for (int i = 0; i < numHumans; i++) {
            int[] pos = this.environment.getPlace();
            
            Human human = new Human(
                "Human_" + (i + 1),
                this.sp.field,
                pos,
                this.sp.rows,
                this.sp.columns
            );
            human.setWarehouseEnvironment(warehouse);
            
            humanList.add(human);
            addNewComponent(human);
            // Register initial human position for AMR collision detection
            warehouse.updateHumanPosition(human.getName(), pos);
        }

        System.out.println("Created " + numHumans + " human workers (dynamic obstacles)");
    }
    
    /**
     * Find where a component was placed on the grid by the framework.
     */
    private int[] findComponentOnGrid(Object component, ColorSimpleCell[][] grid) {
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[0].length; c++) {
                ColorSimpleCell cell = grid[r][c];
                if (cell != null && cell.getContent() == component) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }
    
    /**
     * Create colored markers for entry, exit, and intermediate areas.
     * Creates LARGER markers (2-3 cells) to match reference warehouse image.
     * 
     * COLOR SCHEME (matching reference image):
     * - Exit Areas: DARK RED (hatched ovals in reference) - delivery destinations
     * - Entry Areas: BRIGHT GREEN - pallet spawn points  
     * - Intermediate Areas: BLUE with stripes - relay stations
     * - Recharge Stations: PURPLE
     * - Static Obstacles: DARK GRAY (set in config)
     * - Humans: YELLOW/ORANGE
     * - AMRs: BLUE (empty) / MAGENTA (carrying)
     */
    // createAreaMarkers() and createMarkerBlock() removed:
    // Area visuals are now rendered as a background layer directly from WarehouseEnvironment
    // by WarehouseGraphicalWindow — no ColorObstacle markers are placed on the grid.
    
    /**
     * Print the warehouse layout showing positions of all areas.
     */
    private void printWarehouseLayout() {
        System.out.println("\n--- WAREHOUSE POSITIONS ---");
        System.out.println("Entry Areas (GREEN - pallets spawn here):");
        for (EntryArea entry : warehouse.getEntryAreas()) {
            System.out.println("  " + entry.getId() + " at position (" + 
                entry.getX() + ", " + entry.getY() + ")");
        }
        
        System.out.println("Exit Areas (ORANGE - delivery destinations):");
        for (ExitArea exit : warehouse.getExitAreas()) {
            System.out.println("  " + exit.getId() + " at position (" + 
                exit.getX() + ", " + exit.getY() + ")");
        }
        
        if (mode == SimulationMode.ENHANCED) {
            System.out.println("Intermediate Areas (CYAN - relay stations):");
            for (IntermediateArea inter : warehouse.getIntermediateAreas()) {
                System.out.println("  " + inter.getId() + " at position (" + 
                    inter.getX() + ", " + inter.getY() + ") capacity=" + inter.getCapacity());
            }
            
            System.out.println("Recharge Stations (YELLOW):");
            for (int[] pos : warehouse.getRechargeStations()) {
                System.out.println("  at position (" + pos[0] + ", " + pos[1] + ")");
            }
        }
        
        System.out.println("Obstacles: " + warehouse.getObstacles().size() + " placed randomly");
    }
    
    // ==================== Main Simulation Loop ====================
    
    @Override
    public void schedule() {
        startTime = System.currentTimeMillis();
        
        // Print warehouse layout positions
        printWarehouseLayout();
        
        System.out.println("\nStarting " + mode + " simulation...");
        System.out.println("Total pallets to deliver: " + totalPalletsToGenerate);
        System.out.println("Watch the GUI window for visual simulation!");
        
        for (int tick = 0; tick < this.sp.step; tick++) {
            simulationTicks = tick;
            
            if (this.sp.debug == 1) {
                System.out.println("\n=== Tick " + tick + " ===");
            }
            
            // 1. Generate new pallets at entry areas
            generatePallets(tick);

            // 2. Assign tasks to available AMRs
            assignTasks(tick);

            // 3. Move all AMRs
            moveAMRs(tick);

            // 4. Move humans (dynamic obstacles)
            moveHumans();

            // 5. Handle message distribution (enhanced mode)
            if (mode == SimulationMode.ENHANCED) {
                distributeMessages();
            }

            // 6. Check for completed deliveries
            checkDeliveries(tick);

            // 7. Print status
            if (this.sp.debug == 1) {
                printStatus(tick);
            }

            // 8. Refresh GUI BEFORE removing AMRs so the user sees the robot
            //    at the exit area for one frame before it vanishes
            refreshCustomWindow();

            // 9. Remove completed AMRs (reference model — vanish after being shown at exit)
            removeCompletedAMRs();

            // 10. Check if simulation is complete
            if (isSimulationComplete()) {
                System.out.println("\nSimulation complete at tick " + tick);
                break;
            }
            
            // Wait between ticks
            try {
                Thread.sleep(this.sp.waittime);
            } catch (InterruptedException ex) {
                System.out.println(ex);
            }
        }
        
        endTime = System.currentTimeMillis();
        printFinalStatistics();

        // Close GUI and exit cleanly once all work is done
        if (customWindow != null) {
            customWindow.dispose();
        }
        System.exit(0);
    }
    
    /**
     * Generate new pallets at entry areas.
     */
    private void generatePallets(int tick) {
        // warehouse.tick() advances the internal clock AND generates pallets.
        // Once the cap is reached we call stopGeneration() so entry areas stop
        // queuing new pallets — warehouse.tick() then only advances the clock,
        // keeping delivery timestamps accurate without bloating entry queues.
        List<Pallet> newPallets = warehouse.tick(tick);

        for (Pallet pallet : newPallets) {
            if (palletsGenerated >= totalPalletsToGenerate) {
                // Reached cap mid-batch — disable further generation and stop
                warehouse.stopGeneration();
                break;
            }
            palletsGenerated++;

            if (this.sp.debug == 1) {
                System.out.println("New pallet: " + pallet);
            }

            // For reference model: create an AMR for each new pallet
            if (mode == SimulationMode.REFERENCE) {
                createAMRForPallet(pallet);
            }

            // Check again after incrementing (handles exactly-cap case)
            if (palletsGenerated >= totalPalletsToGenerate) {
                warehouse.stopGeneration();
            }
        }
    }
    
    /**
     * Create an AMR for a specific pallet (Reference Model).
     */
    private void createAMRForPallet(Pallet pallet) {
        int[] pos = pallet.getPosition();
        
        AMRobot amr = new AMRobot(
            "AMR_P" + pallet.getId(),
            this.sp.field,
            pos,
            this.sp.colorrobot,
            this.sp.rows,
            this.sp.columns
        );
        amr.setWarehouseEnvironment(warehouse);
        
        // Register robot position for collision tracking
        warehouse.updateRobotPosition(amr.getId(), pos);
        
        // Directly assign the pallet to this AMR
        int[] exitPos = warehouse.getExitPosition(pallet.getDestination());
        
        // Pick up pallet immediately (AMR starts at entry area)
        Pallet pickup = warehouse.pickupPalletAtPosition(pos);
        if (pickup != null) {
            amr.pickupPallet(pickup, exitPos);
        }
        
        amrList.add(amr);
        addNewComponent(amr);
    }
    
    /**
     * Assign tasks to available AMRs.
     *
     * ENHANCED MODEL — Contract Net Protocol:
     *   1. For each entry area with waiting pallets, broadcast a task announcement
     *   2. Each idle AMR computes a bid score (distance, battery, availability)
     *   3. Highest scoring AMR wins the task
     *   4. If winner can't do full delivery, assign relay to intermediate area
     *
     * Also handles:
     *   - Intermediate area pickups (relay completion)
     *   - Recharge queue management (max 2 charging simultaneously)
     */
    private void assignTasks(int tick) {
        if (mode != SimulationMode.ENHANCED) {
            return;  // Reference model assigns immediately in createAMRForPallet
        }

        // --- Phase 1: Intermediate area pickups (relay completion) ---
        // Pallets sitting in intermediate areas need to be picked up and delivered to exits
        for (IntermediateArea area : warehouse.getIntermediateAreas()) {
            if (!area.hasPallets()) continue;

            Pallet waitingPallet = area.peekPallet();
            int[] exitPos = warehouse.getExitPosition(waitingPallet.getDestination());
            if (exitPos == null) continue;

            // Contract Net: collect bids from idle AMRs for this relay task
            AMRobot bestBidder = null;
            double bestScore = -1;

            for (AMRobot amr : amrList) {
                double score = amr.computeBidScore(area.getPosition(), exitPos);
                if (score > bestScore) {
                    bestScore = score;
                    bestBidder = amr;
                }
            }

            if (bestBidder != null && bestBidder.canCompleteFullDelivery(area.getPosition(), exitPos)) {
                // Award relay pickup task — pallet stays in area until AMR physically arrives
                bestBidder.assignPickupTask(area.getPosition(), waitingPallet.getDestination());

                if (this.sp.debug == 1) {
                    System.out.println("[CONTRACT NET] " + bestBidder.getName() +
                        " won relay bid for pallet #" + waitingPallet.getId() +
                        " from " + area.getId() + " (score: " + String.format("%.3f", bestScore) + ")");
                }
            }
        }

        // --- Phase 2: Entry area pickups (Contract Net bidding) ---
        // Build set of already-claimed entry positions
        java.util.Set<String> claimedEntries = new java.util.HashSet<>();
        for (AMRobot amr : amrList) {
            if (!amr.isAvailable()) {
                int[] target = amr.getTargetPosition();
                if (target != null) {
                    claimedEntries.add(target[0] + "," + target[1]);
                }
            }
        }

        List<EntryArea> entriesWithPallets = warehouse.getEntriesWithPallets();

        for (EntryArea entry : entriesWithPallets) {
            String key = entry.getPosition()[0] + "," + entry.getPosition()[1];
            if (claimedEntries.contains(key)) continue;

            Pallet nextPallet = entry.peekPallet();
            if (nextPallet == null) continue;

            int[] exitPos = warehouse.getExitPosition(nextPallet.getDestination());
            if (exitPos == null) continue;

            // Contract Net: collect bids from all idle AMRs
            AMRobot bestBidder = null;
            double bestScore = -1;

            for (AMRobot amr : amrList) {
                double score = amr.computeBidScore(entry.getPosition(), exitPos);
                if (score > bestScore) {
                    bestScore = score;
                    bestBidder = amr;
                }
            }

            if (bestBidder != null) {
                bestBidder.assignPickupTask(entry.getPosition(), nextPallet.getDestination());
                claimedEntries.add(key);

                if (this.sp.debug == 1) {
                    boolean fullDelivery = bestBidder.canCompleteFullDelivery(entry.getPosition(), exitPos);
                    System.out.println("[CONTRACT NET] " + bestBidder.getName() +
                        " won bid for " + entry.getId() + " pallet #" + nextPallet.getId() +
                        " → " + nextPallet.getDestination() +
                        " (score: " + String.format("%.3f", bestScore) +
                        ", mode: " + (fullDelivery ? "FULL" : "RELAY") + ")");
                }
            }
        }

        // --- Phase 3: Recharge management (AFTER task assignment) ---
        // Only idle AMRs that didn't win any bid get sent to recharge.
        // This ensures no robot sits idle when it could handle a task.
        for (AMRobot amr : amrList) {
            if (amr.isIdle() && amr.shouldRecharge()) {
                if (warehouse.isRechargeSlotAvailable()) {
                    int[] rechargePos = warehouse.getNearestRechargeStation(amr.getLocation());
                    if (rechargePos != null) {
                        amr.assignRechargeTask(rechargePos);
                        if (this.sp.debug == 1) {
                            System.out.println(amr.getName() + " heading to recharge (battery: " +
                                (int) amr.getBatteryPercentage() + "%)");
                        }
                    }
                }
            }
        }
    }

    /** Kept for compatibility but no longer used — bidding is via computeBidScore. */
    private AMRobot findBestAvailableAMR(int[] taskPosition) {
        AMRobot best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (AMRobot amr : amrList) {
            if (amr.isAvailable()) {
                int distance = warehouse.manhattanDistance(amr.getLocation(), taskPosition);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = amr;
                }
            }
        }
        
        return best;
    }
    
    /**
     * Move all AMRs one step.
     *
     * The grid position is determined by searching for the component on the grid
     * (not from getLocation()) because escape moves inside move() can call
     * setLocation() multiple times, causing the logical position to diverge
     * from the actual grid cell.
     */
    private void moveAMRs(int tick) {
        ColorSimpleCell[][] grid = this.environment.getGrid();

        for (AMRobot amr : amrList) {
            // Find actual grid position (ground truth)
            int[] gridPos = findComponentOnGrid(amr, grid);

            // Get FRESH perception before moving
            ColorSimpleCell[][] per = this.environment.getNeighbor(
                amr.getX(), amr.getY(), amr.getField());
            amr.updatePerception(per);

            // Move (may call setLocation one or more times internally)
            amr.move(1);

            // Sync grid with AMR's new logical position
            int[] newPos = amr.getLocation();
            if (gridPos != null &&
                (gridPos[0] != newPos[0] || gridPos[1] != newPos[1])) {
                this.environment.moveComponent(gridPos[0], gridPos[1], newPos[0], newPos[1]);
            }
            // Update position tracker for collision detection
            warehouse.updateRobotPosition(amr.getId(), newPos);
        }
    }
    
    /**
     * Move human workers (dynamic obstacles).
     * Humans move randomly and act as moving obstacles for AMRs.
     */
    private void moveHumans() {
        ColorSimpleCell[][] grid = this.environment.getGrid();

        for (Human human : humanList) {
            // Find actual grid position (ground truth)
            int[] gridPos = findComponentOnGrid(human, grid);

            // Update perception
            ColorSimpleCell[][] per = this.environment.getNeighbor(
                human.getX(), human.getY(), human.getField()
            );
            human.updatePerception(per);

            // Move the human
            human.move(1);

            // Sync grid with human's new logical position
            int[] newPos = human.getPosition();
            if (gridPos != null &&
                (gridPos[0] != newPos[0] || gridPos[1] != newPos[1])) {
                this.environment.moveComponent(gridPos[0], gridPos[1], newPos[0], newPos[1]);
            }
            // Update human position tracker so AMRs can detect humans
            warehouse.updateHumanPosition(human.getName(), newPos);
        }
    }
    
    /**
     * Distribute messages between AMRs (Enhanced Model).
     */
    private void distributeMessages() {
        for (AMRobot sender : amrList) {
            // Collect all outgoing messages ONCE per sender (popSentMessages clears the outbox)
            List<Message> outgoing = sender.popSentMessages();
            if (outgoing.isEmpty()) continue;

            // Fan out to all other AMRs
            for (AMRobot receiver : amrList) {
                if (sender.getId() != receiver.getId()) {
                    for (Message msg : outgoing) {
                        receiver.receiveMessage(msg);
                    }
                }
            }
        }
        
        // Have each AMR process received messages
        for (AMRobot amr : amrList) {
            amr.readMessages();
        }
    }
    
    /**
     * Check for AMRs that have completed deliveries.
     * Delivery is only valid when the AMR is physically inside an exit area cell.
     * After delivery the AMR vanishes immediately (reference model).
     */
    private void checkDeliveries(int tick) {
        for (AMRobot amr : amrList) {
            // Check if AMR is in PICKING_UP state at entry area
            if (amr.getState() == AMRobot.State.PICKING_UP) {
                handlePickup(amr);
                continue;
            }

            // Delivery check: AMR must be at an exit area cell
            if (amr.getState() == AMRobot.State.DELIVERING || amr.getState() == AMRobot.State.DELIVERED) {
                if (amr.getCarriedPallet() != null && warehouse.isExitArea(amr.getLocation())) {
                    handleDelivery(amr, tick);
                }
            }

            // Enhanced: intermediate area drop-off
            if (mode == SimulationMode.ENHANCED &&
                amr.getState() == AMRobot.State.MOVING_TO_INTERMEDIATE) {
                if (amr.getCarriedPallet() != null && warehouse.isIntermediateArea(amr.getLocation())) {
                    handleIntermediateDrop(amr, tick);
                }
            }

            // Enhanced: recharge queue management
            if (mode == SimulationMode.ENHANCED && amr.getState() == AMRobot.State.RECHARGING) {
                // Register in recharge queue if not already
                warehouse.tryStartCharging(amr.getId());
            }
            // When AMR finishes recharging (transitions to IDLE), free the slot
            if (mode == SimulationMode.ENHANCED && amr.getState() == AMRobot.State.IDLE) {
                warehouse.stopCharging(amr.getId());
            }

            // Enhanced: if battery getting low while active, go recharge
            // Carrying pallet: recharge WITH pallet (keeps it, resumes delivery after)
            // Moving to pickup: abort and go recharge (will re-bid after charge)
            if (mode == SimulationMode.ENHANCED && amr.shouldRecharge() &&
                (amr.getState() == AMRobot.State.DELIVERING ||
                 amr.getState() == AMRobot.State.MOVING_TO_PICKUP)) {
                int[] rechargePos = warehouse.getNearestRechargeStation(amr.getLocation());
                if (rechargePos != null) {
                    if (amr.isCarryingPallet()) {
                        amr.assignRechargeTask(rechargePos);
                        if (this.sp.debug == 1) {
                            System.out.println("[RECHARGE-WITH-PALLET] " + amr.getName() +
                                " going to recharge while carrying pallet #" + amr.getCarriedPallet().getId() +
                                " (battery: " + (int) amr.getBatteryPercentage() + "%)");
                        }
                    } else {
                        amr.assignRechargeTask(rechargePos);
                        if (this.sp.debug == 1) {
                            System.out.println("[RECHARGE-ABORT] " + amr.getName() +
                                " aborting pickup to recharge (battery: " +
                                (int) amr.getBatteryPercentage() + "%)");
                        }
                    }
                }
            }

            // Enhanced: recover pallets from dead AMRs
            if (mode == SimulationMode.ENHANCED && amr.isDead() && amr.isCarryingPallet()) {
                handleDeadAMRRecovery(amr, tick);
            }
        }
    }

    /**
     * Recover pallet from a dead AMR by dropping it at nearest intermediate area.
     * If no intermediate area is available, the pallet is returned to the nearest entry area.
     */
    private void handleDeadAMRRecovery(AMRobot amr, int tick) {
        Pallet pallet = amr.getCarriedPallet();
        if (pallet == null) return;

        // Try to drop at nearest intermediate area
        IntermediateArea nearest = warehouse.getNearestIntermediateArea(amr.getLocation());
        if (nearest != null && nearest.canAccept()) {
            // Force-remove pallet from dead AMR
            Pallet dropped = amr.dropPalletAtIntermediate();
            if (dropped != null) {
                nearest.storePallet(dropped);
                if (this.sp.debug == 1) {
                    System.out.println("[RECOVERY] Dead " + amr.getName() +
                        " — pallet #" + dropped.getId() + " recovered to " + nearest.getId());
                }
            }
        }
        // If no intermediate area available, pallet is lost (counted in stats)
    }

    /**
     * Handle pallet drop at intermediate area (relay delivery).
     */
    private void handleIntermediateDrop(AMRobot amr, int tick) {
        IntermediateArea targetArea = null;
        for (IntermediateArea area : warehouse.getIntermediateAreas()) {
            int ax = area.getX(), ay = area.getY();
            int rx = amr.getX(), ry = amr.getY();
            if (rx >= ax && rx < ax + 2 && ry >= ay && ry < ay + 2) {
                targetArea = area;
                break;
            }
        }

        if (targetArea != null && targetArea.canAccept()) {
            Pallet dropped = amr.dropPalletAtIntermediate();
            if (dropped != null) {
                targetArea.storePallet(dropped);
                if (this.sp.debug == 1) {
                    System.out.println(amr.getName() + " dropped pallet #" + dropped.getId() +
                        " at " + targetArea.getId() + " for relay (battery: " +
                        (int) amr.getBatteryPercentage() + "%)");
                }
            }
        }
    }

    /**
     * Emergency pallet drop when battery is critically low while delivering.
     * Drops at nearest intermediate area if adjacent, otherwise continues.
     */
    private void handleEmergencyDrop(AMRobot amr, int tick) {
        // Only drop if we're actually at or adjacent to an intermediate area
        for (IntermediateArea area : warehouse.getIntermediateAreas()) {
            if (warehouse.manhattanDistance(amr.getLocation(), area.getPosition()) <= 2
                && area.canAccept()) {
                Pallet dropped = amr.dropPalletAtIntermediate();
                if (dropped != null) {
                    area.storePallet(dropped);
                    if (this.sp.debug == 1) {
                        System.out.println("[EMERGENCY] " + amr.getName() +
                            " emergency-dropped pallet #" + dropped.getId() +
                            " at " + area.getId() + " (battery: " +
                            (int) amr.getBatteryPercentage() + "%)");
                    }
                    // Send AMR to recharge immediately
                    int[] rechargePos = warehouse.getNearestRechargeStation(amr.getLocation());
                    if (rechargePos != null) {
                        amr.assignRechargeTask(rechargePos);
                    }
                }
                return;
            }
        }
    }
    
    /**
     * Handle pallet pickup at entry area.
     * Accepts pickup from the exact entry cell OR any adjacent cell (distance ≤ 1).
     *
     * Enhanced model: If AMR doesn't have enough battery for full delivery,
     * routes to the nearest intermediate area for relay instead.
     */
    private void handlePickup(AMRobot amr) {
        // Try exact position first
        Pallet pallet = warehouse.pickupPalletAtPosition(amr.getLocation());

        // If no pallet at exact position, try adjacent entry areas
        if (pallet == null) {
            for (EntryArea entry : warehouse.getEntryAreas()) {
                if (warehouse.manhattanDistance(amr.getLocation(), entry.getPosition()) <= 1
                        && entry.hasPallets()) {
                    pallet = entry.pickupPallet();
                    if (pallet != null) break;
                }
            }
        }

        // Enhanced: also try intermediate areas for relay pickups
        // Check if AMR is within the 2x2 intermediate area block (not just manhattan distance)
        if (pallet == null && mode == SimulationMode.ENHANCED) {
            for (IntermediateArea area : warehouse.getIntermediateAreas()) {
                int ax = area.getX(), ay = area.getY();
                int rx = amr.getX(), ry = amr.getY();
                boolean withinArea = (rx >= ax && rx < ax + 2 && ry >= ay && ry < ay + 2)
                    || warehouse.manhattanDistance(amr.getLocation(), area.getPosition()) <= 1;
                if (withinArea && area.hasPallets()) {
                    pallet = area.pickupPallet();
                    if (pallet != null) {
                        if (this.sp.debug == 1) {
                            System.out.println(amr.getName() + " picked up relay pallet #" +
                                pallet.getId() + " from " + area.getId());
                        }
                        break;
                    }
                }
            }
        }

        if (pallet != null) {
            // Use best free cell within the 2x2 exit block (spreads AMRs across all 4 cells)
            int[] exitPos = (mode == SimulationMode.ENHANCED)
                ? warehouse.getBestExitCell(pallet.getDestination(), amr.getLocation())
                : warehouse.getExitPosition(pallet.getDestination());

            if (mode == SimulationMode.ENHANCED && !amr.canCompleteFullDelivery(amr.getLocation(), exitPos)) {
                // Not enough battery for full delivery — relay via intermediate area
                IntermediateArea nearest = warehouse.getNearestIntermediateArea(amr.getLocation());
                if (nearest != null && nearest.canAccept()) {
                    amr.pickupPalletForRelay(pallet, nearest.getPosition());
                    if (this.sp.debug == 1) {
                        System.out.println(amr.getName() + " picked up " + pallet +
                            " → RELAY via " + nearest.getId() + " (low battery: " +
                            (int) amr.getBatteryPercentage() + "%)");
                    }
                    return;
                }
                // No intermediate available — try full delivery anyway
            }

            amr.pickupPallet(pallet, exitPos);

            if (this.sp.debug == 1) {
                System.out.println(amr.getName() + " picked up " + pallet);
            }
        } else {
            // No pallet available, return to idle
            amr.setState(AMRobot.State.IDLE);
        }
    }
    
    /**
     * Handle pallet delivery at exit area.
     * Robot delivers pallet and is immediately marked for removal.
     */
    private void handleDelivery(AMRobot amr, int tick) {
        Pallet delivered = amr.deliverPallet();

        if (delivered != null) {
            int deliveryTime = warehouse.deliverPallet(delivered);

            // Identify which exit area the delivery happened at
            String exitId = "?";
            for (ExitArea exit : warehouse.getExitAreas()) {
                int ex = exit.getX(), ey = exit.getY();
                int ax = amr.getX(), ay = amr.getY();
                if (ax >= ex && ax < ex + 2 && ay >= ey && ay < ey + 2) {
                    exitId = exit.getId();
                    break;
                }
            }

            if (this.sp.debug == 1) {
                String suffix = (mode == SimulationMode.REFERENCE) ? " - vanishing" :
                    " (battery: " + (int) amr.getBatteryPercentage() + "%)";
                System.out.println(amr.getName() + " DELIVERED pallet #" + delivered.getId() +
                    " at " + exitId + " (" + amr.getX() + "," + amr.getY() + ")" +
                    " | delivery time: " + deliveryTime + " ticks" + suffix);
            }
        }

        if (mode == SimulationMode.REFERENCE) {
            // Reference model: remove AMR after delivery
            amrsToRemove.add(amr);
        } else {
            // Enhanced model: AMR returns to idle (handled by deliverPallet())
            // If low battery, immediately head to recharge
            if (amr.shouldRecharge() && warehouse.isRechargeSlotAvailable()) {
                int[] rechargePos = warehouse.getNearestRechargeStation(amr.getLocation());
                if (rechargePos != null) {
                    amr.assignRechargeTask(rechargePos);
                }
            }
        }
    }
    
    /**
     * Remove AMRs that have completed delivery (Reference Model).
     * Uses reflection to clear the grid cell since setContent is protected.
     */
    private void removeCompletedAMRs() {
        ColorSimpleCell[][] grid = this.environment.getGrid();
        
        for (AMRobot amr : amrsToRemove) {
            // Find where this AMR is actually displayed on the grid
            int[] actualPos = findComponentOnGrid(amr, grid);
            
            if (actualPos != null) {
                // Use reflection to clear the cell content
                ColorSimpleCell cell = grid[actualPos[0]][actualPos[1]];
                if (cell != null) {
                    clearCellContent(cell);
                }
                
                if (this.sp.debug == 1) {
                    System.out.println(amr.getName() + " VANISHED from (" + actualPos[0] + "," + actualPos[1] + ")");
                }
            }
            
            // Remove from position tracker
            warehouse.removeRobot(amr.getId());
            
            // Remove from list
            amrList.remove(amr);
        }
        amrsToRemove.clear();
    }
    
    /**
     * Clear cell content using reflection (setContent is protected).
     */
    private void clearCellContent(ColorSimpleCell cell) {
        try {
            // Try to find and invoke the setContent method using reflection
            java.lang.reflect.Method setContentMethod = null;
            
            // Search through the class hierarchy for setContent method
            Class<?> clazz = cell.getClass();
            while (clazz != null && setContentMethod == null) {
                try {
                    setContentMethod = clazz.getDeclaredMethod("setContent", 
                        fr.emse.fayol.maqit.simulator.components.ColorSituatedComponent.class);
                } catch (NoSuchMethodException e) {
                    // Try parent class
                    clazz = clazz.getSuperclass();
                }
            }
            
            if (setContentMethod != null) {
                setContentMethod.setAccessible(true);
                setContentMethod.invoke(cell, (Object) null);
            }
        } catch (Exception e) {
            // Reflection failed - try alternative: SituatedComponent parameter type
            try {
                java.lang.reflect.Method setContentMethod = null;
                Class<?> clazz = cell.getClass();
                while (clazz != null && setContentMethod == null) {
                    try {
                        setContentMethod = clazz.getDeclaredMethod("setContent",
                            fr.emse.fayol.maqit.simulator.components.SituatedComponent.class);
                    } catch (NoSuchMethodException ex) {
                        clazz = clazz.getSuperclass();
                    }
                }
                if (setContentMethod != null) {
                    setContentMethod.setAccessible(true);
                    setContentMethod.invoke(cell, (Object) null);
                }
            } catch (Exception ex) {
                System.err.println("Failed to clear cell: " + ex.getMessage());
            }
        }
    }
    
    /**
     * Check if simulation is complete.
     */
    private boolean isSimulationComplete() {
        // Complete when all pallets are generated AND delivered
        return palletsGenerated >= totalPalletsToGenerate &&
               warehouse.allPalletsDelivered();
    }

    // ==================== Status and Statistics ====================
    
    private void printStatus(int tick) {
        System.out.println("+--------------------------------------------------+");
        System.out.println("| PALLETS: Generated " + palletsGenerated + "/" + totalPalletsToGenerate +
            " | Pending: " + warehouse.getPendingPalletCount() +
            " | Delivered: " + warehouse.getDeliveredPalletCount());
        System.out.println("| AMRs ACTIVE: " + amrList.size());
        
        // Show each AMR status
        for (AMRobot amr : amrList) {
            String carryingInfo = "";
            if (amr.isCarryingPallet()) {
                Pallet p = amr.getCarriedPallet();
                carryingInfo = " -> delivering to " + p.getDestination();
            }
            System.out.printf("|   %s at (%d,%d) [%s]%s%n", 
                amr.getName(), amr.getX(), amr.getY(), amr.getState(), carryingInfo);
        }
        
        // Show waiting pallets at entry areas
        for (EntryArea entry : warehouse.getEntryAreas()) {
            if (entry.hasPallets()) {
                System.out.println("|   " + entry.getId() + " has " + entry.getQueueSize() + " pallets waiting");
            }
        }
        System.out.println("+--------------------------------------------------+");
        
        // Print text-based grid map every 10 ticks for clarity
        if (tick % 10 == 0) {
            printTextGrid();
        }
    }
    
    /**
     * Print a text-based grid showing the warehouse state.
     * Symbols:
     *   A1,A2,A3 = Entry Areas (pallets arrive)
     *   Z1,Z2    = Exit Areas (destinations)  
     *   R        = AMR Robot (empty)
     *   R*       = AMR Robot carrying pallet
     *   H        = Human worker (dynamic obstacle)
     *   X        = Static Obstacle
     *   I        = Intermediate Area
     *   C        = Charging Station
     *   .        = Empty cell
     */
    private void printTextGrid() {
        System.out.println("\n=== WAREHOUSE MAP ===");
        System.out.println("Legend: A=Entry, Z=Exit, R/R*=Robot, H=Human, X=Obstacle, I=Intermediate, C=Charge");
        
        // Create grid representation
        String[][] grid = new String[sp.rows][sp.columns];
        
        // Initialize with empty cells
        for (int r = 0; r < sp.rows; r++) {
            for (int c = 0; c < sp.columns; c++) {
                grid[r][c] = ". ";
            }
        }
        
        // Mark obstacles (lowest priority - gets overwritten by other elements)
        for (int[] pos : warehouse.getObstacles()) {
            if (isValidPosition(pos)) {
                grid[pos[0]][pos[1]] = "X ";
            }
        }
        
        // Mark intermediate areas
        for (IntermediateArea inter : warehouse.getIntermediateAreas()) {
            int[] pos = inter.getPosition();
            if (isValidPosition(pos)) {
                grid[pos[0]][pos[1]] = "I ";
            }
        }
        
        // Mark recharge stations
        for (int[] pos : warehouse.getRechargeStations()) {
            if (isValidPosition(pos)) {
                grid[pos[0]][pos[1]] = "C ";
            }
        }
        
        // Mark entry areas
        for (EntryArea entry : warehouse.getEntryAreas()) {
            int[] pos = entry.getPosition();
            if (isValidPosition(pos)) {
                String label = entry.getId().substring(0, 2);  // "A1", "A2", etc.
                grid[pos[0]][pos[1]] = label;
            }
        }
        
        // Mark exit areas
        for (ExitArea exit : warehouse.getExitAreas()) {
            int[] pos = exit.getPosition();
            if (isValidPosition(pos)) {
                String label = exit.getId().substring(0, 2);  // "Z1", "Z2", etc.
                grid[pos[0]][pos[1]] = label;
            }
        }
        
        // Mark humans (dynamic obstacles) - they move!
        for (Human human : humanList) {
            int[] pos = human.getPosition();
            if (isValidPosition(pos)) {
                grid[pos[0]][pos[1]] = "H ";  // H = Human
            }
        }
        
        // Mark AMRs (highest priority - overwrite other markers)
        for (AMRobot amr : amrList) {
            int[] pos = amr.getLocation();
            if (isValidPosition(pos)) {
                if (amr.isCarryingPallet()) {
                    grid[pos[0]][pos[1]] = "R*";  // R* = carrying pallet
                } else {
                    grid[pos[0]][pos[1]] = "R ";  // R = idle/moving
                }
            }
        }
        
        // Print column headers
        System.out.print("   ");
        for (int c = 0; c < sp.columns; c++) {
            System.out.printf("%2d ", c);
        }
        System.out.println();
        
        // Print grid
        for (int r = 0; r < sp.rows; r++) {
            System.out.printf("%2d ", r);
            for (int c = 0; c < sp.columns; c++) {
                System.out.print(grid[r][c] + " ");
            }
            System.out.println();
        }
        System.out.println();
    }
    
    private boolean isValidPosition(int[] pos) {
        return pos[0] >= 0 && pos[0] < sp.rows && pos[1] >= 0 && pos[1] < sp.columns;
    }
    
    private void printFinalStatistics() {
        String sep  = "============================================================";
        String sep2 = "------------------------------------------------------------";
        System.out.println("\n" + sep);
        System.out.println("         SIMULATION RESULTS - " + mode + " MODEL");
        System.out.println(sep);

        // --- Core metrics (per project requirements) ---
        int totalPallets     = warehouse.getTotalPalletCount();
        int delivered        = warehouse.getDeliveredPalletCount();
        int pending          = warehouse.getPendingPalletCount();
        int totalDeliveryTd  = warehouse.getTotalDeliveryTime();   // td = sum of (tc - ts)
        double avgDelivery   = warehouse.getAverageDeliveryTime();
        int makespan         = simulationTicks;
        double throughput    = makespan > 0 ? (double) delivered / makespan : 0;

        System.out.println("\n  DELIVERY METRICS");
        System.out.println(sep2);
        System.out.println("  Total pallets generated     : " + totalPallets);
        System.out.println("  Total pallets delivered      : " + delivered);
        System.out.println("  Pallets still pending        : " + pending);
        System.out.println("  Total delivery time (td)     : " + totalDeliveryTd +
            " ticks   [td = sum of (tc - ts) for each pallet]");
        System.out.println("  Average delivery time        : " +
            String.format("%.2f", avgDelivery) + " ticks per pallet");
        System.out.println("  Makespan                     : " + makespan + " ticks");
        System.out.println("  Throughput                   : " +
            String.format("%.4f", throughput) + " pallets/tick");

        // --- Per-pallet delivery log ---
        System.out.println("\n  PER-PALLET DELIVERY LOG");
        System.out.println(sep2);
        System.out.println("  Pallet | Dest | Arrived (ts) | Delivered (tc) | Time (tp = tc - ts)");
        System.out.println("  -------+------+--------------+----------------+--------------------");
        for (Pallet p : warehouse.getDeliveredPallets()) {
            System.out.printf("  %-6d | %-4s | %-12d | %-14d | %d ticks%n",
                p.getId(), p.getDestination(), p.getArrivalTick(),
                p.getDeliveryTick(), p.getDeliveryTime());
        }

        // --- Per entry area ---
        System.out.println("\n  ENTRY AREAS");
        System.out.println(sep2);
        for (EntryArea entry : warehouse.getEntryAreas()) {
            System.out.println("  " + entry.getId() +
                " (" + entry.getX() + "," + entry.getY() + ")" +
                ": generated " + entry.getTotalPalletsGenerated() +
                ", remaining " + entry.getQueueSize());
        }

        // --- Per exit area ---
        System.out.println("\n  EXIT AREAS");
        System.out.println(sep2);
        for (ExitArea exit : warehouse.getExitAreas()) {
            System.out.println("  " + exit.getId() +
                " (" + exit.getX() + "," + exit.getY() + ")" +
                ": " + exit.getDeliveredCount() + " delivered" +
                ", avg time: " + String.format("%.2f", exit.getAverageDeliveryTime()) + " ticks");
        }

        // --- Enhanced Model: Per-AMR stats ---
        if (mode == SimulationMode.ENHANCED) {
            System.out.println("\n  AMR STATISTICS");
            System.out.println(sep2);
            System.out.println("  AMR     | Delivered | Distance | Util%  | Battery | Recharges | State");
            System.out.println("  --------+-----------+----------+--------+---------+-----------+------");
            int totalBatteryDeaths = 0;
            for (AMRobot amr : amrList) {
                if (amr.isDead()) totalBatteryDeaths++;
                System.out.printf("  %-7s | %-9d | %-8d | %-5.1f%% | %-6d%% | %-9d | %s%n",
                    amr.getName(),
                    amr.getPalletsDelivered(),
                    amr.getTotalDistanceTraveled(),
                    amr.getUtilizationRate(),
                    (int) amr.getBatteryPercentage(),
                    amr.getRechargeCount(),
                    amr.getState());
            }
            System.out.println("  Battery deaths: " + totalBatteryDeaths);

            // Intermediate area usage
            System.out.println("\n  INTERMEDIATE AREAS");
            System.out.println(sep2);
            for (IntermediateArea area : warehouse.getIntermediateAreas()) {
                System.out.println("  " + area.getId() +
                    " (" + area.getX() + "," + area.getY() + ")" +
                    ": received=" + area.getTotalPalletsReceived() +
                    ", picked up=" + area.getTotalPalletsPickedUp() +
                    ", remaining=" + area.getCurrentCount() +
                    "/" + area.getCapacity());
            }

            // Recharge stations
            System.out.println("\n  RECHARGE STATIONS");
            System.out.println(sep2);
            System.out.println("  Max simultaneous charging: 2 | Currently charging: " + warehouse.getChargingCount());
            for (int[] station : warehouse.getRechargeStations()) {
                System.out.println("  Station at (" + station[0] + "," + station[1] + ")");
            }
        }

        // --- Environment summary ---
        System.out.println("\n  ENVIRONMENT");
        System.out.println(sep2);
        System.out.println("  Grid size       : " + sp.rows + " x " + sp.columns);
        System.out.println("  Fixed obstacles  : " + warehouse.getObstacles().size());
        System.out.println("  Human workers    : " + numHumans);
        System.out.println("  Arrival prob     : " + palletArrivalProbability +
            " (split across " + warehouse.getEntryAreas().size() + " entries)");
        System.out.println("  Simulation time  : " + (endTime - startTime) + " ms");

        System.out.println("\n" + sep);
    }
    
    // ==================== Getters ====================
    
    public WarehouseEnvironment getWarehouse() {
        return warehouse;
    }
    
    public List<AMRobot> getAMRList() {
        return new ArrayList<>(amrList);
    }
    
    public SimulationMode getMode() {
        return mode;
    }
    
    // ==================== Custom Window Methods ====================
    
    /**
     * Initialize the icon-based graphical window.
     * This is now the ONLY display window — the default color window is removed.
     * The window reads area positions directly from WarehouseEnvironment so icons
     * are always in the correct fixed positions regardless of robot movements.
     */
    public void initializeCustomWindow() {
        customWindow = new WarehouseGraphicalWindow(
            this.environment.getGrid(),
            warehouse,                       // Pass environment for direct area rendering
            this.sp.display_x,
            this.sp.display_y,
            this.sp.display_width,
            this.sp.display_height,
            "Warehouse AMR Simulation"
        );
        customWindow.init();
    }
    
    /**
     * Refresh the custom graphical window.
     */
    public void refreshCustomWindow() {
        if (customWindow != null) {
            customWindow.setGrid(this.environment.getGrid());
            customWindow.refresh();
        }
    }
    
    // ==================== Main Entry Point ====================
    
    /**
     * Print a clear, prominent startup banner with legend.
     */
    private static void printStartupBanner(SimulationMode mode, SimProperties sp) {
        String line = "============================================================";
        System.out.println();
        System.out.println(line);
        System.out.println("          WAREHOUSE AMR SIMULATION");
        System.out.println(line);
        System.out.println();
        System.out.println("  Mode: " + mode);
        System.out.println("  Grid: " + sp.rows + " rows x " + sp.columns + " columns");
        System.out.println();
        System.out.println(line);
        System.out.println("                 COLOR LEGEND (GUI Window)");
        System.out.println(line);
        System.out.println();
        System.out.println("  +------------------+------------------------------------------+");
        System.out.println("  | COLOR            | MEANING                                  |");
        System.out.println("  +------------------+------------------------------------------+");
        System.out.println("  | LIME GREEN       | Entry Areas (A1, A2, A3) - pallets spawn |");
        System.out.println("  | BRIGHT RED       | Exit Areas (Z1, Z2) - destinations       |");
        System.out.println("  | BLUE             | AMR Robot (EMPTY - no pallet)            |");
        System.out.println("  | MAGENTA/PINK     | AMR Robot CARRYING a pallet              |");
        System.out.println("  | YELLOW/ORANGE    | HUMAN worker (moves randomly!)           |");
        System.out.println("  | DARK GRAY        | Static Obstacles/Walls                   |");
        System.out.println("  | CYAN             | Intermediate Areas (relay stations)      |");
        System.out.println("  | PURPLE           | Charging/Recharge Stations               |");
        System.out.println("  +------------------+------------------------------------------+");
        System.out.println();
        System.out.println(line);
        System.out.println("            TEXT MAP SYMBOLS (Console output)");
        System.out.println(line);
        System.out.println();
        System.out.println("  +--------+------------------------------------------------+");
        System.out.println("  | SYMBOL | MEANING                                        |");
        System.out.println("  +--------+------------------------------------------------+");
        System.out.println("  | A1-A3  | Entry Areas (pallets arrive here)              |");
        System.out.println("  | Z1, Z2 | Exit Areas (delivery destinations)             |");
        System.out.println("  | R      | Robot (empty/idle)                             |");
        System.out.println("  | R*     | Robot CARRYING a pallet                        |");
        System.out.println("  | H      | Human worker (dynamic obstacle)                |");
        System.out.println("  | X      | Static Obstacle                                |");
        System.out.println("  | I      | Intermediate Area (relay station)              |");
        System.out.println("  | C      | Charging Station                               |");
        System.out.println("  | .      | Empty cell                                     |");
        System.out.println("  +--------+------------------------------------------------+");
        System.out.println();
        System.out.println(line);
        System.out.println("                   WAREHOUSE LAYOUT");
        System.out.println(line);
        System.out.println();
        System.out.println("       LEFT SIDE           |         RIGHT SIDE");
        System.out.println("   ----------------------- | ------------------------");
        System.out.println("   Exit Areas (Z1, Z2)     |   Entry Areas (A1-A3)");
        System.out.println("   Delivery Destinations   |   Pallets Spawn Here");
        System.out.println();
        System.out.println("   Robots move from RIGHT --> to --> LEFT to deliver");
        System.out.println("   Humans move RANDOMLY - they are dynamic obstacles!");
        System.out.println();
        System.out.println(line);
        System.out.println();
    }
    
    public static void main(String[] args) throws Exception {
        // Load configuration — allow override via -Dwarehouse.config=path/to/file.ini
        String configFile = System.getProperty("warehouse.config", "warehouse_config.ini");
        IniFile ifile = new IniFile(configFile);
        SimProperties sp = new SimProperties(ifile);
        sp.simulationParams();
        sp.displayParams();
        
        // Determine simulation mode
        SimulationMode mode = SimulationMode.REFERENCE;
        if (args.length > 0 && args[0].equalsIgnoreCase("enhanced")) {
            mode = SimulationMode.ENHANCED;
        }
        
        // Print a clear, prominent legend
        printStartupBanner(mode, sp);
        
        // Create and run simulator
        WarehouseSimulator simulator = new WarehouseSimulator(sp, mode);
        
        // Read simulation parameters from [warehouse] section of config file
        // Defaults are used as fallback in case values are missing
        int totalPallets = 20;
        double arrivalProbability = 0.15;
        int numObstacles = sp.nbobstacle;
        int numHumans = 2;
        int numAMRs = 5;
        int maxBattery = 100;
        int rechargeRate = 5;
        try { totalPallets       = ifile.getIntValue("warehouse", "total_pallets"); }         catch (Exception e) { /* use default */ }
        try { arrivalProbability = ifile.getDoubleValue("warehouse", "arrival_probability"); } catch (Exception e) { /* use default */ }
        try { numObstacles       = ifile.getIntValue("warehouse", "num_obstacles"); }         catch (Exception e) { /* use default */ }
        try { numHumans          = ifile.getIntValue("warehouse", "num_humans"); }            catch (Exception e) { /* use default */ }
        try { numAMRs            = ifile.getIntValue("warehouse", "num_amrs"); }              catch (Exception e) { /* use default */ }
        try { maxBattery         = ifile.getIntValue("warehouse", "max_battery"); }           catch (Exception e) { /* use default */ }
        try { rechargeRate       = ifile.getIntValue("warehouse", "recharge_rate"); }         catch (Exception e) { /* use default */ }

        // Override framework obstacle count with warehouse-specific config
        sp.nbobstacle = numObstacles;

        simulator.setTotalPallets(totalPallets);
        simulator.setPalletArrivalProbability(arrivalProbability);
        simulator.setNumHumans(numHumans);

        if (mode == SimulationMode.ENHANCED) {
            simulator.setNumAMRs(numAMRs);
            simulator.setMaxBattery(maxBattery);
            simulator.setRechargeRate(rechargeRate);
        }
        
        // Initialize
        simulator.createEnvironment();
        simulator.createObstacle();
        simulator.createRobot();
        simulator.createGoal();
        simulator.initializeCustomWindow(); // Icon window (only window — default color window removed)
        simulator.refreshCustomWindow();
        
        // Run simulation
        simulator.schedule();
    }
}

