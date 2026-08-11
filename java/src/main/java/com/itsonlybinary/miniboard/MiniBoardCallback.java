package com.itsonlybinary.miniboard;

/**
 * Completion callback for the callback-style command overloads. Exactly one of
 * the two methods is invoked, on the board's callback thread.
 */
public interface MiniBoardCallback<T> {

    void onSuccess(T result);

    void onError(Throwable error);
}
