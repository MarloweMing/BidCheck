package cn.bidcheck.compat.concurrent;

/** The small subscription contract used by the bounded HTTP response readers. */
public final class Flow {
    private Flow() { }

    public interface Subscription {
        void request(long count);
        void cancel();
    }

    public interface Subscriber<T> {
        void onSubscribe(Subscription subscription);
        void onNext(T item);
        void onError(Throwable error);
        void onComplete();
    }
}
