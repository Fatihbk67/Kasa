package com.kasa.mail;

/** Hands one email to a provider. Called with no transaction open, since a provider can be slow. */
public interface EmailSender {

    /** The provider's id for the accepted message, if it gives one. */
    String send(String to, String subject, String text);

    /**
     * {@code permanent} means trying again cannot help, such as a rejected address or a wrong API key; anything
     * else, such as a timeout or a provider outage, is worth another attempt later.
     */
    final class SendFailure extends RuntimeException {

        private final boolean permanent;

        public SendFailure(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }

        public boolean permanent() {
            return permanent;
        }
    }
}
