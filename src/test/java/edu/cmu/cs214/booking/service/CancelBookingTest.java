package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.repo.InMemoryBookingStore;
import org.junit.jupiter.api.Test;

class CancelBookingTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final Room roomB = new Room("B", "Beta", 4);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");

    private InMemoryBookingStore store;

    private BookingService newService() {
        store = new InMemoryBookingStore();
        return new BookingService(store);
    }

    private Booking confirm(BookingService svc, Room room, User user, TimeInterval interval) {
        BookingResult r = svc.book(room, user, interval);
        return assertInstanceOf(BookingResult.Confirmed.class, r).booking();
    }

    /** S1: cancelling a booking removes it and frees its slot. */
    @Test
    void cancellingRemovesTheBookingAndFreesTheSlot() {
        BookingService svc = newService();
        Booking a = confirm(svc, roomA, alice, new TimeInterval(600, 660));

        svc.cancelBooking(a.id());

        assertEquals(0, svc.listBookings(roomA).size());
        // The slot is free again: booking it now must be confirmed, not waitlisted.
        assertInstanceOf(
                BookingResult.Confirmed.class, svc.book(roomA, bob, new TimeInterval(600, 660)));
    }

    /** S2: cancelling an unknown id does nothing at all. */
    @Test
    void cancellingAnUnknownIdDoesNothing() {
        BookingService svc = newService();
        confirm(svc, roomA, alice, new TimeInterval(600, 660));
        assertInstanceOf(
                BookingResult.Waitlisted.class, svc.book(roomA, bob, new TimeInterval(630, 700)));

        assertDoesNotThrow(() -> svc.cancelBooking("no-such-id"));

        assertEquals(1, svc.listBookings(roomA).size());
        assertEquals(alice, svc.listBookings(roomA).get(0).user());
        assertEquals(1, store.waitlistForRoom(roomA).size());
    }
}
