package example;

import com.lmax.disruptor.EventHandler;

/**
 * @author Yelin.G at 2015/10/28 22:47
 */
public class LongEventHandler implements EventHandler<LongEvent> {
    public void onEvent(LongEvent event, long sequence, boolean endOfBatch) {
        System.out.println("Event: " + event);
    }
}
