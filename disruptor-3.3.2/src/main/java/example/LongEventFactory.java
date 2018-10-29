package example;

import com.lmax.disruptor.EventFactory;

/**
 * @author Yelin.G at 2015/10/28 22:46
 */
public class LongEventFactory implements EventFactory<LongEvent> {
    public LongEvent newInstance() {
        return new LongEvent();
    }
}
