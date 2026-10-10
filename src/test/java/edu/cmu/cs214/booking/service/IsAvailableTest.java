package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.repo.InMemoryBookingStore;
import org.junit.jupiter.api.Test;

/**
 * Tests for the agent-proposed {@code isAvailable} query.
 *
 * <p>The patched implementation only asks whether an existing booking <em>starts</em> inside the
 * requested interval. That misses every conflict where the existing booking starts earlier and
 * runs into the request, so {@code isAvailable} claims a room is free while {@code book} would
 * waitlist the caller.
 */
class IsAvailableTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");

    private BookingService newService() {
        return new BookingService(new InMemoryBookingStore());
    }

    /** Case 3 of the position table: E = [570,630) starts before Q = [600,660) but overlaps it. */
    @Test
    void notAvailableWhenAnEarlierBookingRunsIntoTheRequest() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(570, 630));
        assertFalse(svc.isAvailable(roomA, new TimeInterval(600, 660)));
    }

    /** Case 4: E = [540,720) fully contains Q = [600,660). */
    @Test
    void notAvailableWhenAnExistingBookingContainsTheRequest() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(540, 720));
        assertFalse(svc.isAvailable(roomA, new TimeInterval(600, 660)));
    }

    /** Back-to-back intervals do not overlap, so the room stays available. */
    @Test
    void availableForBackToBackIntervals() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(540, 600));
        assertTrue(svc.isAvailable(roomA, new TimeInterval(600, 660)));
        svc.book(roomA, bob, new TimeInterval(660, 720));
        assertTrue(svc.isAvailable(roomA, new TimeInterval(600, 660)));
    }

    /** isAvailable must agree with what book actually does, for every position relation. */
    @Test
    void isAvailableAgreesWithBook() {
        int[][] existing = {
            {480, 540}, {540, 600}, {570, 630}, {540, 720}, {610, 640},
            {630, 720}, {600, 660}, {660, 720}, {700, 760},
        };
        TimeInterval query = new TimeInterval(600, 660);
        for (int[] e : existing) {
            BookingService svc = newService();
            svc.book(roomA, alice, new TimeInterval(e[0], e[1]));
            boolean available = svc.isAvailable(roomA, query);
            BookingResult result = svc.book(roomA, bob, query);
            String where = "existing [" + e[0] + "," + e[1] + ")";
            if (available) {
                assertTrue(result instanceof BookingResult.Confirmed,
                        "isAvailable said free but book waitlisted: " + where);
            } else {
                assertTrue(result instanceof BookingResult.Waitlisted,
                        "isAvailable said busy but book confirmed: " + where);
            }
        }
    }
}
