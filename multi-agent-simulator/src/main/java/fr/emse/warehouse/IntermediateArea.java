package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an intermediate storage area in the warehouse.
 * Pallets can be temporarily stored here before being picked up
 * by another AMR for final delivery.
 * 
 * Used in ENHANCED MODEL for:
 * - Battery conservation (AMR drops pallet here before running out)
 * - Load balancing (relay station strategy)
 */
public class IntermediateArea {
    
    private final String id;
    private final int[] position;
    private final int capacity;  // Maximum pallets this area can hold
    private final List<Pallet> storedPallets;
    
    // Statistics
    private int totalPalletsReceived;
    private int totalPalletsPickedUp;
    
    /**
     * Creates a new intermediate area.
     * 
     * @param id       Unique identifier (e.g., "I1", "I2")
     * @param position Position [x, y] on the grid
     * @param capacity Maximum number of pallets this area can store
     */
    public IntermediateArea(String id, int[] position, int capacity) {
        this.id = id;
        this.position = position.clone();
        this.capacity = capacity;
        this.storedPallets = new ArrayList<>();
        this.totalPalletsReceived = 0;
        this.totalPalletsPickedUp = 0;
    }
    
    /**
     * Check if this area can accept another pallet.
     */
    public boolean canAccept() {
        return storedPallets.size() < capacity;
    }
    
    /**
     * Check if this area has pallets waiting for pickup.
     */
    public boolean hasPallets() {
        return !storedPallets.isEmpty();
    }
    
    /**
     * Store a pallet in this area.
     * 
     * @param pallet The pallet to store
     * @return true if stored successfully, false if area is full
     */
    public boolean storePallet(Pallet pallet) {
        if (!canAccept()) {
            return false;
        }
        
        storedPallets.add(pallet);
        totalPalletsReceived++;
        return true;
    }
    
    /**
     * Pick up the first pallet (FIFO) from this area.
     * 
     * @return The pallet, or null if area is empty
     */
    public Pallet pickupPallet() {
        if (storedPallets.isEmpty()) {
            return null;
        }
        
        Pallet pallet = storedPallets.remove(0);
        totalPalletsPickedUp++;
        return pallet;
    }
    
    /**
     * Peek at the first pallet without removing it.
     */
    public Pallet peekPallet() {
        if (storedPallets.isEmpty()) {
            return null;
        }
        return storedPallets.get(0);
    }
    
    /**
     * Get all stored pallets (read-only copy).
     */
    public List<Pallet> getStoredPallets() {
        return new ArrayList<>(storedPallets);
    }
    
    /**
     * Get urgency level based on how full this area is.
     * 
     * @return "CRITICAL" if full, "HIGH" if >80% full, "NORMAL" otherwise
     */
    public String getUrgencyLevel() {
        double fillRatio = (double) storedPallets.size() / capacity;
        
        if (fillRatio >= 1.0) {
            return "CRITICAL";
        } else if (fillRatio >= 0.8) {
            return "HIGH";
        } else {
            return "NORMAL";
        }
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
    
    public int getCapacity() {
        return capacity;
    }
    
    public int getCurrentCount() {
        return storedPallets.size();
    }
    
    public int getAvailableSpace() {
        return capacity - storedPallets.size();
    }
    
    public int getTotalPalletsReceived() {
        return totalPalletsReceived;
    }
    
    public int getTotalPalletsPickedUp() {
        return totalPalletsPickedUp;
    }
    
    public double getUtilization() {
        return (double) storedPallets.size() / capacity * 100;
    }
    
    @Override
    public String toString() {
        return String.format("IntermediateArea[%s at (%d,%d), %d/%d pallets, urgency=%s]",
                id, position[0], position[1], storedPallets.size(), capacity, getUrgencyLevel());
    }
}

