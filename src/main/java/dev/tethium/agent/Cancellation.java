package dev.tethium.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> action = new AtomicReference<>();
    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() {
        cancelled.set(true);
        runAction();
    }
    public void onCancel(Runnable callback) {
        action.set(callback);
        if (cancelled.get()) runAction();
    }
    private void runAction() {
        var callback = action.getAndSet(null);
        if (callback != null) callback.run();
    }
}

