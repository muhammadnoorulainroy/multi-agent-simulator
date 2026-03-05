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
    private int numHumans = 2;  // Number of human workers in warehouse (less clutter)
    
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
        
        // ===== INTERMEDIATE AREAS - CENTER of grid =====
        // Two relay stations for pallet handoff
        IntermediateArea i1 = new IntermediateArea("I1", 
            new int[]{3, sp.columns / 2}, 5);      // Upper-middle
        IntermediateArea i2 = new IntermediateArea("I2", 
            new int[]{sp.rows - 4, sp.columns / 2}, 5);  // Lower-middle
        warehouse.addIntermediateArea(i1);
        warehouse.addIntermediateArea(i2);
        
        // ===== CHARGING STATIONS — 3 strategic positions =====
        //
        // Strategy rationale:
        //   C1 (near Z1, top-left)    — robots recharge immediately after delivering to Z1
        //   C2 (near Z2, bottom-left) — robots recharge immediately after delivering to Z2
        //   C3 (center warehouse)     — emergency mid-route station; closest when battery
        //                               runs low before the robot reaches the left side
        //
        // Battery prediction (enforced in AMRobot.canCompleteTask):
        //   required = (dist_to_pickup + dist_to_delivery + dist_to_nearest_recharge) × 1.2
        //   If battery < required → go to C3 first (center), then accept the task
        warehouse.addRechargeStation(new int[]{2,             3});               // C1 near Z1
        warehouse.addRechargeStation(new int[]{sp.rows - 3,   3});               // C2 near Z2
        warehouse.addRechargeStation(new int[]{sp.rows / 2,   sp.columns / 2}); // C3 center
    }
    
    @Override
    public void createObstacle() {
        // Create fixed obstacles
        for (int i = 0; i < this.sp.nbobstacle; i++) {
            int[] pos = this.environment.getPlace();
            
            // Make sure obstacle is not on any reserved area (entry, exit, intermediate, recharge)
            while (warehouse.isEntryArea(pos)
                    || warehouse.isExitArea(pos)
                    || warehouse.isIntermediateArea(pos)
                    || warehouse.isRechargeStation(pos)) {
                pos = this.environment.getPlace();
            }
            
            int[] rgb = {
                this.sp.colorobstacle.getRed(),
                this.sp.colorobstacle.getGreen(),
                this.sp.colorobstacle.getBlue()
            };
            ColorObstacle obstacle = new ColorObstacle(pos, rgb);
            addNewComponent(obstacle);
            warehouse.addObstacle(pos);
        }
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
     */
    private void createHumans() {
        for (int i = 0; i < numHumans; i++) {
            // Find a free position for the human
            int[] pos = this.environment.getPlace();
            
            Human human = new Human(
                "Human_" + (i + 1),
                this.sp.field,
                pos,
                this.sp.rows,
                this.sp.columns
            );
            
            humanList.add(human);
            addNewComponent(human);
        }
        
        System.out.println("Created " + numHumans + " human workers (YELLOW squares - they move!)");
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
            
            // 3b. Move humans (dynamic obstacles)
            moveHumans();
            
            // 4. Handle message distribution (enhanced mode)
            if (mode == SimulationMode.ENHANCED) {
                distributeMessages();
            }
            
            // 5. Check for completed deliveries
            checkDeliveries(tick);
            
            // 6. Remove completed AMRs (reference model)
            removeCompletedAMRs();
            
            // 7. Print status
            if (this.sp.debug == 1) {
                printStatus(tick);
            }
            
            // 8. Refresh display (icon window only — default color window removed)
            refreshCustomWindow();
            
            // 9. Check if simulation is complete
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
    }
    
    /**
     * Generate new pallets at entry areas.
     */
    private void generatePallets(int tick) {
        // Always advance the warehouse tick so internal timing (e.g., delivery timestamps
        // via currentTick in WarehouseEnvironment) stays accurate even after the pallet
        // generation cap is reached. Stopping tick() would leave deliveries timestamped
        // with a stale tick and produce incorrect delivery-time statistics.
        List<Pallet> newPallets = warehouse.tick(tick);

        if (palletsGenerated >= totalPalletsToGenerate) {
            return;  // Cap reached — do not enqueue any more pallets
        }

        for (Pallet pallet : newPallets) {
            if (palletsGenerated >= totalPalletsToGenerate) {
                break;  // Respect the total pallet cap mid-batch
            }
            palletsGenerated++;
            
            if (this.sp.debug == 1) {
                System.out.println("New pallet: " + pallet);
            }
            
            // For reference model: create an AMR for each new pallet
            if (mode == SimulationMode.REFERENCE) {
                createAMRForPallet(pallet);
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
     * Assign tasks to available AMRs (Enhanced Model).
     * Each entry gets at most one AMR assignment per tick to avoid redundant work
     * and congestion caused by multiple AMRs racing toward the same pallet.
     */
    private void assignTasks(int tick) {
        if (mode != SimulationMode.ENHANCED) {
            return;  // Reference model assigns immediately in createAMRForPallet
        }

        // Build a set of entry positions already targeted by a busy AMR so we
        // don't dispatch a second robot to the same spot this tick.
        java.util.Set<String> claimedEntries = new java.util.HashSet<>();
        for (AMRobot amr : amrList) {
            if (!amr.isAvailable()) {
                int[] target = amr.getTargetPosition();
                if (target != null) {
                    claimedEntries.add(target[0] + "," + target[1]);
                }
            }
        }

        // Get entry areas with waiting pallets
        List<EntryArea> entriesWithPallets = warehouse.getEntriesWithPallets();
        
        for (EntryArea entry : entriesWithPallets) {
            String key = entry.getPosition()[0] + "," + entry.getPosition()[1];
            if (claimedEntries.contains(key)) {
                continue;  // An AMR is already heading here — skip to avoid duplicates
            }

            // Find an available AMR
            AMRobot availableAMR = findBestAvailableAMR(entry.getPosition());
            
            if (availableAMR != null) {
                // Assign pickup task and mark entry as claimed for this tick
                availableAMR.assignPickupTask(entry.getPosition(), 
                    entry.peekPallet().getDestination());
                claimedEntries.add(key);
                
                if (this.sp.debug == 1) {
                    System.out.println(availableAMR.getName() + " assigned to " + entry.getId());
                }
            }
        }
        
        // Check for AMRs that need to recharge
        for (AMRobot amr : amrList) {
            if (amr.isIdle() && amr.shouldRecharge()) {
                int[] rechargePos = warehouse.getNearestRechargeStation(amr.getLocation());
                if (rechargePos != null) {
                    amr.assignRechargeTask(rechargePos);
                }
            }
        }
    }
    
    /**
     * Find the best available AMR for a task at given position.
     * Uses simple distance-based selection (can be enhanced with auction).
     */
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
     */
    private void moveAMRs(int tick) {
        for (AMRobot amr : amrList) {
            int[] oldPos = amr.getLocation();
            
            // Update perception
            ColorSimpleCell[][] per = this.environment.getNeighbor(
                amr.getX(), amr.getY(), amr.getField());
            amr.updatePerception(per);
            
            // Move
            amr.move(1);
            
            // Update environment if moved
            int[] newPos = amr.getLocation();
            if (oldPos[0] != newPos[0] || oldPos[1] != newPos[1]) {
                updateEnvironment(oldPos, newPos, amr.getId());
            }
        }
    }
    
    /**
     * Move human workers (dynamic obstacles).
     * Humans move randomly and act as moving obstacles for AMRs.
     */
    private void moveHumans() {
        for (Human human : humanList) {
            int[] oldPos = human.getPosition();
            
            // Update perception
            ColorSimpleCell[][] per = this.environment.getNeighbor(
                human.getX(), human.getY(), human.getField()
            );
            human.updatePerception(per);
            
            // Move the human
            human.move(1);
            
            // Update environment if position changed
            int[] newPos = human.getPosition();
            if (oldPos[0] != newPos[0] || oldPos[1] != newPos[1]) {
                // Use the correct moveComponent API: (oldX, oldY, newX, newY)
                this.environment.moveComponent(oldPos[0], oldPos[1], newPos[0], newPos[1]);
            }
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
     */
    private void checkDeliveries(int tick) {
        for (AMRobot amr : amrList) {
            // Check if AMR is in PICKING_UP state at entry area
            if (amr.getState() == AMRobot.State.PICKING_UP) {
                handlePickup(amr);
            }
            
            // Check if AMR is in DELIVERED state at exit area
            if (amr.getState() == AMRobot.State.DELIVERED) {
                handleDelivery(amr, tick);
            }
        }
    }
    
    /**
     * Handle pallet pickup at entry area.
     */
    private void handlePickup(AMRobot amr) {
        Pallet pallet = warehouse.pickupPalletAtPosition(amr.getLocation());
        
        if (pallet != null) {
            int[] exitPos = warehouse.getExitPosition(pallet.getDestination());
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
     */
    private void handleDelivery(AMRobot amr, int tick) {
        Pallet delivered = amr.deliverPallet();
        
        if (delivered != null) {
            int deliveryTime = warehouse.deliverPallet(delivered);
            
            if (this.sp.debug == 1) {
                System.out.println(amr.getName() + " delivered " + delivered + 
                    " (delivery time: " + deliveryTime + " ticks)");
            }
            
            // Reference model: mark AMR for removal
            if (mode == SimulationMode.REFERENCE) {
                amrsToRemove.add(amr);
            }
        }
    }
    
    /**
     * Remove AMRs that have completed delivery (Reference Model).
     */
    private void removeCompletedAMRs() {
        for (AMRobot amr : amrsToRemove) {
            // Remove from grid
            environment.removeCellContent(amr.getX(), amr.getY());
            
            // Remove from list
            amrList.remove(amr);
            
            if (this.sp.debug == 1) {
                System.out.println(amr.getName() + " removed (delivery complete)");
            }
        }
        amrsToRemove.clear();
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
        String separator = "==================================================";
        System.out.println("\n" + separator);
        System.out.println("       SIMULATION COMPLETE - " + mode + " MODEL");
        System.out.println(separator);
        
        System.out.println(warehouse.getStatisticsSummary());
        
        System.out.println("\n--- Simulation Statistics ---");
        System.out.println("Total Ticks: " + simulationTicks);
        System.out.println("Runtime: " + (endTime - startTime) + " ms");
        System.out.println("Total Pallets: " + warehouse.getTotalPalletCount());
        System.out.println("Delivered Pallets: " + warehouse.getDeliveredPalletCount());
        System.out.println("Total Delivery Time: " + warehouse.getTotalDeliveryTime());
        System.out.println("Average Delivery Time: " + 
            String.format("%.2f", warehouse.getAverageDeliveryTime()) + " ticks");
        
        if (mode == SimulationMode.ENHANCED) {
            System.out.println("\n--- AMR Statistics ---");
            for (AMRobot amr : amrList) {
                System.out.println(amr.getName() + 
                    ": delivered=" + amr.getPalletsDelivered() +
                    ", distance=" + amr.getTotalDistanceTraveled() +
                    ", utilization=" + String.format("%.1f", amr.getUtilizationRate()) + "%");
            }
        }
        
        System.out.println(separator);
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
        double arrivalProbability = 0.2;
        int numAMRs = 5;
        int maxBattery = 100;
        int rechargeRate = 5;
        try { totalPallets       = ifile.getIntValue("warehouse", "total_pallets"); }         catch (Exception e) { /* use default */ }
        try { arrivalProbability = ifile.getDoubleValue("warehouse", "arrival_probability"); } catch (Exception e) { /* use default */ }
        try { numAMRs            = ifile.getIntValue("warehouse", "num_amrs"); }              catch (Exception e) { /* use default */ }
        try { maxBattery         = ifile.getIntValue("warehouse", "max_battery"); }           catch (Exception e) { /* use default */ }
        try { rechargeRate       = ifile.getIntValue("warehouse", "recharge_rate"); }         catch (Exception e) { /* use default */ }

        simulator.setTotalPallets(totalPallets);
        simulator.setPalletArrivalProbability(arrivalProbability);
        
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

