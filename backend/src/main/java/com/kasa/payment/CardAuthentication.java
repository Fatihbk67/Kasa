package com.kasa.payment;

/**
 * How the cardholder proved it was them, as the card bank reported. A payment made through the API on the
 * business's own servers has none; one made on our checkout page always has one.
 */
public record CardAuthentication(Method method, String eci) {

    public enum Method { FRICTIONLESS, CHALLENGE }
}
