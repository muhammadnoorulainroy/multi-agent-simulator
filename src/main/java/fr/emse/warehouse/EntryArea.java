package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Represents an entry area (zone Ax) in the warehouse where pallets arrive.
 * 
 * Each entry area has:
 * - A unique ID (e.g., "A1", "A2", "A3")
 * - A position on the grid
 * - A queue of pallets waiting to be picked up
 * - A pallet arrival distribution
 */
public class EntryArea {
    
    private final String id;
    private final int[] position;
    private final List<Pallet> palletQueue;
    private final Random random;
    
    // Arrival distribution parameters
    private final double arrivalProbability;  // Probability of pallet arriving each tick
    private final String[] possibleDestinations;  // Exit areas this entry can send to
    
    // Statistics
    private int totalPalletsGenerated;
    
    /**
     * Creates a new entry area.
     * 
     * @param id                   Unique identifier (e.g., "A1")
     * @param position             Position [x, y] on the grid
     * @param arrivalProbability   Probability (0-1) of a pallet arriving each tick
     * @param possibleDestinations Array of exit area IDs pallets can be sent to
     * @param seed                 Random seed for reproducibility
     */
    public EntryArea(String id, int[] position, double arrivalProbability, 
                     String[] possibleDestinations, long seed) {
        this.id = id;
        this.position = position.clone();
        this.palletQueue = new ArrayList<>();
        this.arrivalProbability = arrivalProbability;
        this.possibleDestinations = possibleDestinations.clone();
        this.random = new Random(seed);
        this.totalPalletsGenerated = 0;
    }
    
    /**
     * Called each tick to potentially generate new pallets.
     * Uses Poisson-like probability distribution.
     * 
     * @param currentTick Current simulation tick
     * @return The newly created pallet, or null if none arrived
     */
    public Pallet tick(int currentTick) {
        // Check if a pallet arrives this tick (based on probability)
        if (random.nextDouble() < arrivalProbability) {
            return generatePallet(currentTick);
        }
        return null;
    }
    
    /**
     * Generate a new pallet with random destination.
     */
    private Pallet generatePallet(int arrivalTick) {
        // Choose random destination from possible destinations
        String destination = possibleDestinations[random.nextInt(possibleDestinations.length)];
        
        // Create pallet at this entry area's position
        Pallet pallet = new Pallet(arrivalTick, destination, position);
        palletQueue.add(pallet);
        totalPalletsGenerated++;
        
        return pallet;
    }
    
    /**
     * Add a predefined pallet to the queue (useful for testing).
     */
    public void addPallet(Pallet pallet) {
        palletQueue.add(pallet);
        totalPalletsGenerated++;
    }
    
    /**
     * Remove and return the first pallet in the queue (FIFO).
     * 
     * @return The pallet, or null if queue is empty
     */
    public Pallet pickupPallet() {
        if (palletQueue.isEmpty()) {
            return null;
        }
        return palletQueue.remove(0);
    }
    
    /**
     * Peek at the first pallet without removing it.
     */
    public Pallet peekPallet() {
        if (palletQueue.isEmpty()) {
            return null;
        }
        return palletQueue.get(0);
    }
    
    /**
     * Check if there are pallets waiting.
     */
    public boolean hasPallets() {
        return !palletQueue.isEmpty();
    }
    
    /**
     * Get the number of pallets currently waiting.
     */
    public int getQueueSize() {
        return palletQueue.size();
    }
    
    /**
     * Get all waiting pallets (read-only).
     */
    public List<Pallet> getWaitingPallets() {
        return new ArrayList<>(palletQueue);
    }
    
    // ==================== Getters ====================
    
    public String getId() {
        return id;
    }
    
    public int[] getPosition() {
        return position.clone();
    }
    
    public int getX() {
        return position[0];
    }
    
    public int getY() {
        return position[1];
    }
    
    public int getTotalPalletsGenerated() {
        return totalPalletsGenerated;
    }
    
    @Override
    public String toString() {
        return String.format("EntryArea[%s at (%d,%d), queue=%d pallets]",
                id, position[0], position[1], palletQueue.size());
    }
}

