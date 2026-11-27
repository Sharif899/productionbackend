package com.sharif899.prodbackend.exception;

/** The payment amount does not equal the order total. Full payment only. */
public class PaymentAmountMismatchException extends RuntimeException {

    private final long expectedMinor;
    private final long actualMinor;

    public PaymentAmountMismatchException(long expectedMinor, long actualMinor) {
        super("Payment amount " + actualMinor + " does not equal the order total " + expectedMinor);
        this.expectedMinor = expectedMinor;
        this.actualMinor = actualMinor;
    }

    public long getExpectedMinor() {
        return expectedMinor;
    }

    public long getActualMinor() {
        return actualMinor;
    }
}
