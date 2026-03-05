package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages all warehouse components: entry areas, exit areas, intermediate areas,
 * recharge stations, obstacles, and keeps track of all pallets.
 * 
 * This class is used by the simulator to:
 * - Generate pallets at entry areas
 * - Track pallet deliveries at exit areas
 * - Manage intermediate storage (enhanced model)
 * - Manage recharge stations (enhanced model)
 */
public class WarehouseEnvironment {
    
    // ==================== Warehouse Layout ====================
    private final int rows;
    private final int columns;
    
    private final List<EntryArea> entryAreas;
    private final List<ExitArea> exitAreas;
    private final List<IntermediateArea> intermediateAreas;
    private final List<int[]> rechargeStations;
    private final List<int[]> obstacles;
    
    // ==================== Pallet Management ====================
    private final List<Pallet> allPallets;
    private final List<Pallet> pendingPallets;  // Pallets waiting to be picked up
    private final List<Pallet> deliveredPallets;
    
    // ==================== Exit Area Lookup ====================
    private final Map<String, ExitArea> exitAreaMap;  // ID -> ExitArea
    
    // ==================== Statistics ====================
    private int totalDeliveryTime;
    private int currentTick;

    // ==================== Generation Control ====================
    /** When false, tick() only advances the clock — no new pallets are generated. */
    private boolean generationEnabled = true;
    
    /**
     * Creates a new warehouse environment.
     */
    public WarehouseEnvironment(int rows, int columns) {
        this.rows = rows;
        this.columns = columns;
        
        this.entryAreas = new ArrayList<>();
        this.exitAreas = new ArrayList<>();
        this.intermediateAreas = new ArrayList<>();
        this.rechargeStations = new ArrayList<>();
        this.obstacles = new ArrayList<>();
        
        this.allPallets = new ArrayList<>();
        this.pendingPallets = new ArrayList<>();
        this.deliveredPallets = new ArrayList<>();
        
        this.exitAreaMap = new HashMap<>();
        
        this.totalDeliveryTime = 0;
        this.currentTick = 0;
    }
    
    // ==================== Setup Methods ====================
    
    /**
     * Add an entry area to the warehouse.
     */
    public void addEntryArea(EntryArea entryArea) {
        entryAreas.add(entryArea);
    }
    
    /**
     * Add an exit area to the warehouse.
     */
    public void addExitArea(ExitArea exitArea) {
        exitAreas.add(exitArea);
        exitAreaMap.put(exitArea.getId(), exitArea);
    }
    
    /**
     * Add an intermediate area (enhanced model).
     */
    public void addIntermediateArea(IntermediateArea intermediateArea) {
        intermediateAreas.add(intermediateArea);
    }
    
    /**
     * Add a recharge station (enhanced model).
     */
    public void addRechargeStation(int[] position) {
        rechargeStations.add(position.clone());
    }
    
    /**
     * Add an obstacle position.
     */
    public void addObstacle(int[] position) {
        obstacles.add(position.clone());
    }
    
    // ==================== Simulation Tick ====================
    
    /**
     * Process one simulation tick.
     * When generationEnabled is true, generates new pallets at entry areas.
     * When false (cap reached), only advances the internal clock so delivery
     * timestamps remain accurate.
     *
     * @param tick Current simulation tick
     * @return List of newly arrived pallets (empty when generation is disabled)
     */
    public List<Pallet> tick(int tick) {
        this.currentTick = tick;
        List<Pallet> newPallets = new ArrayList<>();

        if (!generationEnabled) {
            return newPallets;  // Clock advanced; no pallet generation
        }

        // Check each entry area for new pallet arrivals
        for (EntryArea entry : entryAreas) {
            Pallet newPallet = entry.tick(tick);
            if (newPallet != null) {
                allPallets.add(newPallet);
                pendingPallets.add(newPallet);
                newPallets.add(newPallet);
            }
        }

        return newPallets;
    }

    /** Stop all entry areas from generating new pallets (call when pallet cap is reached). */
    public void stopGeneration() {
        this.generationEnabled = false;
    }

    public boolean isGenerationEnabled() {
        return generationEnabled;
    }
    
    // ==================== Pallet Operations ====================
    
    /**
     * Pick up a pallet from an entry area.
     * 
     * @param entryAreaId ID of the entry area
     * @return The picked up pallet, or null if none available
     */
    public Pallet pickupPalletFromEntry(String entryAreaId) {
        for (EntryArea entry : entryAreas) {
            if (entry.getId().equals(entryAreaId)) {
                Pallet pallet = entry.pickupPallet();
                if (pallet != null) {
                    pendingPallets.remove(pallet);
                }
                return pallet;
            }
        }
        return null;
    }
    
    /**
     * Pick up a pallet from a specific position.
     */
    public Pallet pickupPalletAtPosition(int[] position) {
        for (EntryArea entry : entryAreas) {
            if (entry.getX() == position[0] && entry.getY() == position[1]) {
                Pallet pallet = entry.pickupPallet();
                if (pallet != null) {
                    pendingPallets.remove(pallet);
                }
                return pallet;
            }
        }
        return null;
    }
    
    /**
     * Deliver a pallet to its destination exit area.
     * 
     * @param pallet The pallet being delivered
     * @return The delivery time for this pallet
     */
    public int deliverPallet(Pallet pallet) {
        String destinationId = pallet.getDestination();
        ExitArea exitArea = exitAreaMap.get(destinationId);
        
        if (exitArea != null) {
            int deliveryTime = exitArea.receivePallet(pallet, currentTick);
            deliveredPallets.add(pallet);
            totalDeliveryTime += deliveryTime;
            return deliveryTime;
        }
        
        return -1;  // Invalid destination
    }
    
    /**
     * Get the position of an exit area by ID.
     */
    public int[] getExitPosition(String exitId) {
        ExitArea exitArea = exitAreaMap.get(exitId);
        return exitArea != null ? exitArea.getPosition() : null;
    }
    
    /**
     * Get the exit area for a given position.
     */
    public ExitArea getExitAreaAtPosition(int[] position) {
        for (ExitArea exit : exitAreas) {
            if (exit.getX() == position[0] && exit.getY() == position[1]) {
                return exit;
            }
        }
        return null;
    }
    
    /**
     * Check if a position is an entry area.
     */
    public boolean isEntryArea(int[] position) {
        for (EntryArea entry : entryAreas) {
            if (entry.getX() == position[0] && entry.getY() == position[1]) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a position is an exit area.
     */
    public boolean isExitArea(int[] position) {
        for (ExitArea exit : exitAreas) {
            if (exit.getX() == position[0] && exit.getY() == position[1]) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a position is an intermediate area.
     */
    public boolean isIntermediateArea(int[] position) {
        for (IntermediateArea area : intermediateAreas) {
            if (area.getX() == position[0] && area.getY() == position[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a position is a recharge station.
     */
    public boolean isRechargeStation(int[] position) {
        for (int[] station : rechargeStations) {
            if (station[0] == position[0] && station[1] == position[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a position is blocked by an obstacle.
     * Entry and exit areas are NOT considered obstacles for pathfinding.
     */
    public boolean isObstacle(int[] position) {
        return isObstacle(position[0], position[1]);
    }
    
    /**
     * Check if a position (x, y) is blocked by an obstacle.
     * Entry and exit areas are NOT considered obstacles for pathfinding.
     */
    public boolean isObstacle(int x, int y) {
        // Check if it's an entry or exit area (these are passable)
        for (EntryArea entry : entryAreas) {
            int[] pos = entry.getPosition();
            if (pos[0] == x && pos[1] == y) {
                return false;  // Entry areas are passable
            }
        }
        for (ExitArea exit : exitAreas) {
            int[] pos = exit.getPosition();
            if (pos[0] == x && pos[1] == y) {
                return false;  // Exit areas are passable
            }
        }
        for (IntermediateArea inter : intermediateAreas) {
            int[] pos = inter.getPosition();
            if (pos[0] == x && pos[1] == y) {
                return false;  // Intermediate areas are passable
            }
        }
        for (int[] station : rechargeStations) {
            if (station[0] == x && station[1] == y) {
                return false;  // Recharge stations are passable
            }
        }
        
        // Check actual obstacles
        for (int[] obs : obstacles) {
            if (obs[0] == x && obs[1] == y) {
                return true;
            }
        }
        return false;
    }
    
    // ==================== Intermediate Areas (Enhanced Model) ====================
    
    /**
     * Find the nearest intermediate area to a position.
     */
    public IntermediateArea getNearestIntermediateArea(int[] position) {
        if (intermediateAreas.isEmpty()) return null;
        
        IntermediateArea nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (IntermediateArea area : intermediateAreas) {
            if (area.canAccept()) {
                int distance = manhattanDistance(position, area.getPosition());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = area;
                }
            }
        }
        
        return nearest;
    }
    
    // ==================== Recharge Stations (Enhanced Model) ====================
    
    /**
     * Find the nearest recharge station to a position.
     */
    public int[] getNearestRechargeStation(int[] position) {
        if (rechargeStations.isEmpty()) return null;
        
        int[] nearest = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (int[] station : rechargeStations) {
            int distance = manhattanDistance(position, station);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = station;
            }
        }
        
        return nearest;
    }
    
    // ==================== Utility ====================
    
    /**
     * Calculate Manhattan distance between two positions.
     */
    public int manhattanDistance(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }
    
    /**
     * Check if all pallets have been delivered.
     * Note: when totalPallets is 0 (no pallets configured), treat as complete.
     */
    public boolean allPalletsDelivered() {
        return pendingPallets.isEmpty() &&
               deliveredPallets.size() == allPallets.size();
    }
    
    /**
     * Get entry area that has waiting pallets.
     */
    public EntryArea getEntryWithPallets() {
        for (EntryArea entry : entryAreas) {
            if (entry.hasPallets()) {
                return entry;
            }
        }
        return null;
    }
    
    /**
     * Get all entry areas with waiting pallets.
     */
    public List<EntryArea> getEntriesWithPallets() {
        List<EntryArea> result = new ArrayList<>();
        for (EntryArea entry : entryAreas) {
            if (entry.hasPallets()) {
                result.add(entry);
            }
        }
        return result;
    }
    
    // ==================== Getters ====================
    
    public int getRows() {
        return rows;
    }
    
    public int getColumns() {
        return columns;
    }
    
    public List<EntryArea> getEntryAreas() {
        return new ArrayList<>(entryAreas);
    }
    
    public List<ExitArea> getExitAreas() {
        return new ArrayList<>(exitAreas);
    }
    
    public List<IntermediateArea> getIntermediateAreas() {
        return new ArrayList<>(intermediateAreas);
    }
    
    public List<int[]> getRechargeStations() {
        // Deep copy: callers must not be able to mutate internal position arrays
        List<int[]> copies = new ArrayList<>(rechargeStations.size());
        for (int[] station : rechargeStations) {
            copies.add(station.clone());
        }
        return copies;
    }
    
    public List<int[]> getObstacles() {
        // Deep copy: callers must not be able to mutate internal position arrays
        List<int[]> copies = new ArrayList<>(obstacles.size());
        for (int[] obstacle : obstacles) {
            copies.add(obstacle.clone());
        }
        return copies;
    }
    
    public List<Pallet> getAllPallets() {
        return new ArrayList<>(allPallets);
    }
    
    public List<Pallet> getPendingPallets() {
        return new ArrayList<>(pendingPallets);
    }
    
    public List<Pallet> getDeliveredPallets() {
        return new ArrayList<>(deliveredPallets);
    }
    
    public int getTotalPalletCount() {
        return allPallets.size();
    }
    
    public int getPendingPalletCount() {
        return pendingPallets.size();
    }
    
    public int getDeliveredPalletCount() {
        return deliveredPallets.size();
    }
    
    public int getTotalDeliveryTime() {
        return totalDeliveryTime;
    }
    
    public double getAverageDeliveryTime() {
        if (deliveredPallets.isEmpty()) return 0;
        return (double) totalDeliveryTime / deliveredPallets.size();
    }
    
    public int getCurrentTick() {
        return currentTick;
    }
    
    // ==================== Statistics Summary ====================
    
    public String getStatisticsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Warehouse Statistics ===\n");
        sb.append(String.format("Total Pallets: %d\n", allPallets.size()));
        sb.append(String.format("Delivered: %d\n", deliveredPallets.size()));
        sb.append(String.format("Pending: %d\n", pendingPallets.size()));
        sb.append(String.format("Total Delivery Time: %d\n", totalDeliveryTime));
        sb.append(String.format("Average Delivery Time: %.2f\n", getAverageDeliveryTime()));
        
        sb.append("\n--- Exit Areas ---\n");
        for (ExitArea exit : exitAreas) {
            sb.append(String.format("%s: %d delivered, avg time: %.2f\n",
                    exit.getId(), exit.getDeliveredCount(), exit.getAverageDeliveryTime()));
        }
        
        return sb.toString();
    }
}

