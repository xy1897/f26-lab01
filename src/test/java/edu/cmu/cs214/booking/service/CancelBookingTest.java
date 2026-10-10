package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.repo.InMemoryBookingStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class CancelBookingTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final Room roomB = new Room("B", "Beta", 4);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");
    private final User carol = new User("u3", "Carol");
    private final User dave = new User("u4", "Dave");

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

    /** S3: cancelling promotes the waitlisted user into a confirmed booking. */
    @Test
    void cancellingPromotesTheWaitlistedUser() {
        BookingService svc = newService();
        Booking a = confirm(svc, roomA, alice, new TimeInterval(600, 660));
        assertInstanceOf(
                BookingResult.Waitlisted.class, svc.book(roomA, bob, new TimeInterval(630, 700)));

        svc.cancelBooking(a.id());

        assertEquals(1, svc.listBookings(roomA).size());
        Booking promoted = svc.listBookings(roomA).get(0);
        assertEquals(bob, promoted.user());
        assertEquals(new TimeInterval(630, 700), promoted.interval());
        // The promotion is a new booking, not the cancelled one resurrected.
        assertNotEquals(a.id(), promoted.id());
        // Bob no longer waits: he holds the booking now.
        assertEquals(0, store.waitlistForRoom(roomA).size());
    }

    /** S4: the earliest waiter wins, and only one waiter is promoted per cancellation. */
    @Test
    void promotionIsFirstComeFirstServedAndAtMostOne() {
        BookingService svc = newService();
        Booking a = confirm(svc, roomA, alice, new TimeInterval(600, 720));
        svc.book(roomA, bob, new TimeInterval(600, 660)); // waits first
        svc.book(roomA, carol, new TimeInterval(660, 700)); // waits second
        assertEquals(2, store.waitlistForRoom(roomA).size());

        svc.cancelBooking(a.id());

        // Bob has the smaller seq, so he is promoted.
        assertEquals(1, svc.listBookings(roomA).size());
        assertEquals(bob, svc.listBookings(roomA).get(0).user());
        // Carol still waits even though [660,700) no longer conflicts with anything:
        // at most one promotion per cancellation.
        assertEquals(1, store.waitlistForRoom(roomA).size());
        assertEquals(carol, store.waitlistForRoom(roomA).get(0).user());
    }

    /** S5: a waiter who still conflicts is skipped in favour of a later one that fits. */
    @Test
    void promotionSkipsWaitersThatStillConflict() {
        BookingService svc = newService();
        Booking a = confirm(svc, roomA, alice, new TimeInterval(600, 660));
        Booking daveBooking = confirm(svc, roomA, dave, new TimeInterval(700, 760));
        svc.book(roomA, bob, new TimeInterval(630, 730)); // waits first, overlaps Dave
        svc.book(roomA, carol, new TimeInterval(610, 650)); // waits second, fits

        svc.cancelBooking(a.id());

        // Bob is skipped: [630,730) still overlaps Dave's [700,760).
        assertEquals(2, svc.listBookings(roomA).size());
        assertTrue(svc.listBookings(roomA).stream().anyMatch(b -> b.user().equals(carol)));
        assertTrue(svc.listBookings(roomA).stream().anyMatch(b -> b.id().equals(daveBooking.id())));
        assertEquals(1, store.waitlistForRoom(roomA).size());
        assertEquals(bob, store.waitlistForRoom(roomA).get(0).user());
        assertNoOverlap(svc.listBookings(roomA));
    }

    /** S6: cancelling in one room never promotes a waiter from another room. */
    @Test
    void promotionDoesNotCrossRooms() {
        BookingService svc = newService();
        Booking a = confirm(svc, roomA, alice, new TimeInterval(600, 660));
        confirm(svc, roomB, bob, new TimeInterval(600, 660));
        svc.book(roomB, carol, new TimeInterval(630, 700)); // waits for room B

        svc.cancelBooking(a.id());

        assertEquals(0, svc.listBookings(roomA).size());
        // Room B is untouched: Carol still waits, Bob keeps his booking.
        assertEquals(1, svc.listBookings(roomB).size());
        assertEquals(bob, svc.listBookings(roomB).get(0).user());
        assertEquals(1, store.waitlistForRoom(roomB).size());
        assertEquals(carol, store.waitlistForRoom(roomB).get(0).user());
    }

    /** The core invariant: no two confirmed bookings of a room may overlap. */
    private void assertNoOverlap(List<Booking> bookings) {
        for (int i = 0; i < bookings.size(); i++) {
            for (int j = i + 1; j < bookings.size(); j++) {
                assertFalse(
                        bookings.get(i).interval().overlaps(bookings.get(j).interval()),
                        "overlapping bookings: " + bookings.get(i) + " and " + bookings.get(j));
            }
        }
    }
}
