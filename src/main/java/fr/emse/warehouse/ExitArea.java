package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an exit area (zone Zy) in the warehouse where pallets are delivered.
 * 
 * Each exit area has:
 * - A unique ID (e.g., "Z1", "Z2")
 * - A position on the grid
 * - Statistics about delivered pallets
 */
public class ExitArea {
    
    private final String id;
    private final int[] position;
    
    // Statistics
    private final List<Pallet> deliveredPallets;
    private int totalDeliveryTime;
    
    /**
     * Creates a new exit area.
     * 
     * @param id       Unique identifier (e.g., "Z1")
     * @param position Position [x, y] on the grid
     */
    public ExitArea(String id, int[] position) {
        this.id = id;
        this.position = position.clone();
        this.deliveredPallets = new ArrayList<>();
        this.totalDeliveryTime = 0;
    }
    
    /**
     * Receive a delivered pallet.
     * 
     * @param pallet      The pallet being delivered
     * @param currentTick The current simulation tick
     * @return The delivery time for this pallet
     */
    public int receivePallet(Pallet pallet, int currentTick) {
        // Mark pallet as delivered
        pallet.markDelivered(currentTick);
        
        // Calculate delivery time
        int deliveryTime = pallet.getDeliveryTime();
        totalDeliveryTime += deliveryTime;
        
        // Store for statistics
        deliveredPallets.add(pallet);
        
        return deliveryTime;
    }
    
    /**
     * Check if a position is at this exit area.
     */
    public boolean isAtExit(int x, int y) {
        return position[0] == x && position[1] == y;
    }
    
    public boolean isAtExit(int[] pos) {
        return isAtExit(pos[0], pos[1]);
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
    
    public int getDeliveredCount() {
        return deliveredPallets.size();
    }
    
    public int getTotalDeliveryTime() {
        return totalDeliveryTime;
    }
    
    public double getAverageDeliveryTime() {
        if (deliveredPallets.isEmpty()) return 0;
        return (double) totalDeliveryTime / deliveredPallets.size();
    }
    
    public List<Pallet> getDeliveredPallets() {
        return new ArrayList<>(deliveredPallets);
    }
    
    @Override
    public String toString() {
        return String.format("ExitArea[%s at (%d,%d), delivered=%d pallets, avgTime=%.2f]",
                id, position[0], position[1], deliveredPallets.size(), getAverageDeliveryTime());
    }
}

