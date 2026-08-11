package com.itsonlybinary.miniboard;

/** No-op {@link MiniBoardListener}; override only the events you care about. */
public abstract class MiniBoardAdapter implements MiniBoardListener {

    @Override
    public void onKey(int keyIndex, int keyId) {
    }

    @Override
    public void onTypoRejected() {
    }

    @Override
    public void onLedFinish(int ledIndex) {
    }

    @Override
    public void onMessage(String text) {
    }

    @Override
    public void onSideChanged(Side side) {
    }

    @Override
    public void onDisconnected(Throwable cause) {
    }

    @Override
    public void onError(Throwable error) {
    }
}
