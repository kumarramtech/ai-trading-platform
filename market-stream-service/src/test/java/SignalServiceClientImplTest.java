import com.ram.trading.market.data.client.SignalServiceClientImpl;
import com.ram.trading.market.data.dto.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignalServiceClientImplTest {

    private SignalServiceClientImpl client;

    @BeforeEach
    void setUp() {
        client = new SignalServiceClientImpl(mock(WebClient.class));
    }

    @Test
    void sameSymbolAlwaysMapsToSameDispatchLane() {
        Tick first = tick("TCS");
        Tick second = tick("TCS");

        int firstLane = laneFor(first, 8);
        int secondLane = laneFor(second, 8);

        assertEquals(firstLane, secondLane);
    }

    @Test
    void symbolLaneIsCaseInsensitive() {
        assertEquals(laneFor(tick("TCS"), 8), laneFor(tick("tcs"), 8));
    }

    @Test
    void laneIsWithinConfiguredRange() {
        int lane = laneFor(tick("INFY"), 4);

        assertTrue(lane >= 0 && lane < 4);
    }

    private int laneFor(Tick tick, int laneCount) {
        Integer lane = ReflectionTestUtils.invokeMethod(
                client, "dispatchLane", tick, laneCount);
        if (lane == null) {
            throw new AssertionError("dispatchLane returned null");
        }
        return lane;
    }

    private Tick tick(String symbol) {
        Tick tick = mock(Tick.class);
        when(tick.getSymbol()).thenReturn(symbol);
        return tick;
    }
}
