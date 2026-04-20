package mini.projet_dac;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JPanel;
import javax.swing.Timer;
import static mini.projet_dac.MiniProjet_DAC.*;

public class carrefourManager {

    static Timer mytimer = new Timer(1000, new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
            if (!stopButtonIsActive.get()) {
                seconds.decrementAndGet();
                if (seconds.get() == 0) {
                    seconds.set(duree_de_feu.get() / 1000);
                }
                lightTimer.setText(String.valueOf(seconds.get()));
            }
        }
    });

    // -------------------------------------------------------------------------
    // [CONCURRENCY FIX: ReentrantLock – Fair Mutex]
    // ReentrantLock(true) enforces FIFO lock acquisition,
    // preventing starvation where some car threads wait indefinitely.
    // -------------------------------------------------------------------------
    static Lock verro = new ReentrantLock(true); 
    // fair = true prevents starvation

    Condition feuVertVoie1               = verro.newCondition();
    Condition feuVertVoie2               = verro.newCondition();
    Condition voie2_Cars_In_Intersection = verro.newCondition();
    Condition voie1_Cars_In_Intersection = verro.newCondition();
    static Condition mainRestartTimer    = verro.newCondition();

    // Light state and intersection counter – always accessed under `verro`.
    boolean feuVert1 = true;
    boolean feuVert2 = false;
    int nmbrVoitureIntersection = 0;

    static AtomicBoolean mainStopedTheTimer = new AtomicBoolean(false);

    static Semaphore restart = new Semaphore(0, true); // fair=true, starts closed

    // -------------------------------------------------------------------------
    // Lane sub-lane pixel positions
    // -------------------------------------------------------------------------
    int[] voie1PositionPossible = {420, 470, 530, 580};
    int[] voie2PositionPossible = {327, 369, 457, 500};

    // -------------------------------------------------------------------------
    // [CONCURRENCY Fix: AtomicInteger array – Per-Sub-Lane Queue Tail]
    // AtomicInteger per sub-lane tracks queue tail position.
    // Each arriving car claims a unique stop position (decrement 80px),
    // preventing cars from overlapping at red lights.
    // -------------------------------------------------------------------------
    private static final int CAR_GAP         = 80;
    private static final int VOIE1_STOP_BASE = 225;
    private static final int VOIE2_STOP_BASE = 310;
    private static final int NUM_SUB_LANES   = 4;

    private final AtomicInteger[] voie1QueueTail = new AtomicInteger[NUM_SUB_LANES];
    private final AtomicInteger[] voie2QueueTail = new AtomicInteger[NUM_SUB_LANES];

    public carrefourManager() {
        for (int i = 0; i < NUM_SUB_LANES; i++) {
            voie1QueueTail[i] = new AtomicInteger(VOIE1_STOP_BASE);
            voie2QueueTail[i] = new AtomicInteger(VOIE2_STOP_BASE);
        }
    }

    public void Intersection() {

        try {
            if (stopButtonIsActive.get()) {
                restart.acquire();
            }
        } catch (InterruptedException ex) {
            System.out.println(ex.getMessage());
        }

        verro.lock();
        try {
            if (mytimer.isRunning()) {
                mytimer.stop();
                feuVoie1Orange.setEnabled(true);
                feuVoie2Orange.setEnabled(true);
                feuVoie1Green.setEnabled(false);
                feuVoie2Red.setEnabled(false);
                feuVoie1Red.setEnabled(false);
                feuVoie2Green.setEnabled(false);
            }

            if (feuVert1) {
                feuVert1 = false;

                if (nmbrVoitureIntersection != 0) {
                    voie1_Cars_In_Intersection.await();
                }

                feuVert2 = true;
                feuVoie1Orange.setEnabled(false);
                feuVoie2Orange.setEnabled(false);
                feuVoie1Green.setEnabled(false);
                feuVoie2Red.setEnabled(false);
                feuVoie1Red.setEnabled(true);
                feuVoie2Green.setEnabled(true);

                feuVertVoie2.signalAll();

            } else {
                feuVert2 = false;

                if (nmbrVoitureIntersection != 0) {
                    voie2_Cars_In_Intersection.await();
                }

                feuVert1 = true;
                feuVoie1Orange.setEnabled(false);
                feuVoie2Orange.setEnabled(false);
                feuVoie1Green.setEnabled(true);
                feuVoie2Red.setEnabled(true);
                feuVoie1Red.setEnabled(false);
                feuVoie2Green.setEnabled(false);

                feuVertVoie1.signalAll();
            }

            if (mainStopedTheTimer.get()) {
                mainRestartTimer.await();
            }

            seconds.set(duree_de_feu.get() / 1000);
            lightTimer.setText(String.valueOf(seconds.get()));
            mytimer.start();

        } catch (InterruptedException ex) {
            System.out.println(ex.getMessage());
        } finally {
            verro.unlock();
        }
    }

    public void traversee1(JPanel C, int p, int vitess) {

        final int laneIndex = p - 1;
        final int myStopPosition;

        // -------------------------------------------------------------------------
        // [CONCURRENCY FIX: ReentrantLock lock()]
        // verro.lock() ensures only one car at a time claims a stop position,
        // preventing two cars from reading the same queue tail simultaneously.
        // -------------------------------------------------------------------------
        verro.lock();
        try {
            myStopPosition = voie1QueueTail[laneIndex].get();
            voie1QueueTail[laneIndex].addAndGet(-CAR_GAP);
        } finally {
            verro.unlock();
        }

        // slotReleased tracks whether this car has already given back its queue
        // slot (on green light). Used in the finally block to avoid a double
        // release if the car exits the screen without ever crossing.
        boolean slotReleased = false;

        try {
            for (int j = -60; j < 830; j++) {

                // -------------------------------------------------------------------------
                // [CONCURRENCY FIX: Semaphore release()]
                // restart.release() chain-wakes waiting car threads in order,
                // allowing all cars to resume when START is pressed.           
                // -------------------------------------------------------------------------
                try {
                    if (stopButtonIsActive.get()) {
                        restart.acquire();
                        restart.release();
                    }
                } catch (InterruptedException ex) {
                    System.out.println(ex.getMessage());
                }

                // Has this car reached its personal stop-line?
                if (!slotReleased && C.getBounds().y >= myStopPosition && myStopPosition > -500) {

                    // Snap exactly to the stop-line for uniform visual spacing.
                    C.setBounds(voie1PositionPossible[laneIndex], myStopPosition, 30, 60);
                    
                    // -------------------------------------------------------------------------
                    // [CONCURRENCY FIX: Condition]
                    // feuVertVoie2.await() sleeps the thread at red light,
                    // avoiding busy-waiting. while loop guards against spurious wake-ups.
                    // -------------------------------------------------------------------------
                    verro.lock();
                    try {
                        while (!feuVert1) {
                            feuVertVoie1.await();
                        }
                        // -------------------------------------------------------------------------
                        // [CONCURRENCY FIX: AtomicInteger]
                        // AtomicInteger ensures queue tail writes are immediately visible
                        // across threads, preventing cars from reading stale stop positions.
                        // -------------------------------------------------------------------------
                        voie1QueueTail[laneIndex].addAndGet(CAR_GAP);
                        slotReleased = true;

                        // Increment intersection counter BEFORE releasing the lock.
                        // This prevents the light controller from seeing
                        // nmbrVoitureIntersection == 0 and switching the light
                        // while this car is still entering the intersection.
                        nmbrVoitureIntersection++;
                    } finally {
                        verro.unlock();
                    }

                    // Move through the intersection body.
                    circuler("Voie 1", C, p, myStopPosition, vitess);

                    verro.lock();
                    try {
                        j = 555;
                        nmbrVoitureIntersection--;
                        if (nmbrVoitureIntersection == 0 && !feuVert1) {
                            voie1_Cars_In_Intersection.signal();
                        }
                    } finally {
                        verro.unlock();
                    }
                }

                C.setBounds(voie1PositionPossible[laneIndex], j, 30, 60);
                Thread.sleep(vitess);
            }

        } catch (InterruptedException ex) {
            Logger.getLogger(carrefourManager.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            // -------------------------------------------------------------------------
            // [CONCURRENCY FIX: ReentrantLock lock() Queue Tail Safety Net]
            // finally block returns the slot under verro lock if a car exits
            // without crossing the intersection, preventing the tail from drifting negative.
            // -------------------------------------------------------------------------
            if (!slotReleased) {
                verro.lock();
                try {
                    voie1QueueTail[laneIndex].addAndGet(CAR_GAP);
                } finally {
                    verro.unlock();
                }
            }
        }
    }

    public void traversee2(JPanel C, int p, int vitess) {

        final int laneIndex = p - 1;
        final int myStopPosition;

        // -------------------------------------------------------------------------
        // [CONCURRENCY FIX: ReentrantLock lock()]
        // verro.lock() ensures only one car at a time claims a stop position,
        // preventing two cars from reading the same queue tail simultaneously.
        // -------------------------------------------------------------------------
        verro.lock();
        try {
            myStopPosition = voie2QueueTail[laneIndex].get();
            voie2QueueTail[laneIndex].addAndGet(-CAR_GAP);
        } finally {
            verro.unlock();
        }

        // slotReleased tracks whether this car has already given back its queue
        // slot (on green light). Used in the finally block to avoid a double
        // release if the car exits the screen without ever crossing.
        boolean slotReleased = false;

        try {
            for (int j = -60; j < 1035; j++) {

                // -------------------------------------------------------------------------
                // [CONCURRENCY FIX: Semaphore release()]
                // restart.release() chain-wakes waiting threads in order,
                // allowing all cars to resume when START is pressed.
                // -------------------------------------------------------------------------
                try {
                    if (stopButtonIsActive.get()) {
                        restart.acquire();
                        restart.release();
                    }
                } catch (InterruptedException ex) {
                    System.out.println(ex.getMessage());
                }

                if (!slotReleased && C.getBounds().x >= myStopPosition && myStopPosition > -500) {

                    C.setBounds(myStopPosition, voie2PositionPossible[laneIndex], 60, 30);

                    // -------------------------------------------------------------------------
                    // [CONCURRENCY FIX: Condition]
                    // feuVertVoie2.await() sleeps the thread at red light,
                    // avoiding busy-waiting. while loop guards against spurious wake-ups.
                    // -------------------------------------------------------------------------
                    verro.lock();
                    try {
                        while (!feuVert2) {
                            feuVertVoie2.await();
                        }
                        // -------------------------------------------------------------------------
                        // [CONCURRENCY FIX: AtomicInteger]
                        // AtomicInteger ensures queue tail writes are immediately visible
                        // across threads, preventing cars from reading stale stop positions.
                        // -------------------------------------------------------------------------
                        voie2QueueTail[laneIndex].addAndGet(CAR_GAP);
                        slotReleased = true;
                        nmbrVoitureIntersection++;
                    } finally {
                        verro.unlock();
                    }

                    circuler("Voie 2", C, p, myStopPosition, vitess);
                    // -------------------------------------------------------------------------
                    // [CONCURRENCY FIX: ReentrantLock + Condition]
                    // Decrement intersection counter and signal the light controller
                    // if this was the last car inside (allows it to switch the light).
                    // -------------------------------------------------------------------------
                    verro.lock();
                    try {
                        j = 640;
                        nmbrVoitureIntersection--;
                        if (nmbrVoitureIntersection == 0 && !feuVert2) {
                            voie2_Cars_In_Intersection.signal();
                        }
                    } finally {
                        verro.unlock();
                    }
                }

                C.setBounds(j, voie2PositionPossible[laneIndex], 60, 30);
                Thread.sleep(vitess);
            }

        } catch (InterruptedException ex) {
            Logger.getLogger(carrefourManager.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            // -------------------------------------------------------------------------
            // [CONCURRENCY FIX: ReentrantLock lock() Queue Tail Safety Net]
            // finally block returns the slot under verro lock if a car exits
            // without crossing the intersection, preventing the tail from drifting negative.
            // -------------------------------------------------------------------------
            if (!slotReleased) {
                verro.lock();
                try {
                    voie2QueueTail[laneIndex].addAndGet(CAR_GAP);
                } finally {
                    verro.unlock();
                }
            }
        }
    }

    public void circuler(String laVoie, JPanel C, int p, int startPos, int vitess) {
        try {
            if (laVoie.equals("Voie 1")) {
                for (int j = startPos; j < 555; j++) {
                    // -------------------------------------------------------------------------
                    // [CONCURRENCY FIX: Semaphore]
                    // restart.acquire() sleeps the thread when STOP is pressed.
                    // restart.release() chain-wakes waiting threads when START is pressed.
                    // -------------------------------------------------------------------------
                    try {
                        if (stopButtonIsActive.get()) {
                            restart.acquire();
                            restart.release();
                        }
                    } catch (InterruptedException ex) {
                        System.out.println(ex.getMessage());
                    }
                    C.setBounds(voie1PositionPossible[p - 1], j, 30, 60);
                    Thread.sleep(vitess);
                }
            } else if (laVoie.equals("Voie 2")) {
                for (int j = startPos; j < 640; j++) {
                    // -------------------------------------------------------------------------
                    // [CONCURRENCY FIX: Semaphore]
                    // restart.acquire() sleeps the thread when STOP is pressed.
                    // restart.release() chain-wakes waiting threads when START is pressed.
                    // -------------------------------------------------------------------------
                    try {
                        if (stopButtonIsActive.get()) {
                            restart.acquire();
                            restart.release();
                        }
                    } catch (InterruptedException ex) {
                        System.out.println(ex.getMessage());
                    }
                    C.setBounds(j, voie2PositionPossible[p - 1], 60, 30);
                    Thread.sleep(vitess);
                }
            }
        } catch (InterruptedException ex) {
            Logger.getLogger(carrefourManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}