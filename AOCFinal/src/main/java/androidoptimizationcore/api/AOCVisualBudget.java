package androidoptimizationcore.api;

/**
 * Small, thread-safe visual-work budget.
 *
 * A budget limits optional client-side work. It never controls game logic,
 * entity lifetime, networking, or server state.
 */
public final class AOCVisualBudget {
    private int capacity;
    private int remaining;

    public AOCVisualBudget(int capacity) {
        setCapacity(capacity);
    }

    public synchronized void setCapacity(int capacity) {
        this.capacity = Math.max(0, capacity);
        this.remaining = this.capacity;
    }

    public synchronized void reset() {
        remaining = capacity;
    }

    public synchronized boolean tryConsume() {
        if (remaining <= 0) return false;
        remaining--;
        return true;
    }

    public synchronized int getCapacity() {
        return capacity;
    }

    public synchronized int getRemaining() {
        return remaining;
    }
}
