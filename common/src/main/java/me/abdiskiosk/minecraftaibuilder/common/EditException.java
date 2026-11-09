package me.abdiskiosk.minecraftaibuilder.common;

/** A user-facing build error (bad pattern, unknown block, limit exceeded). The message is shown to Claude. */
public class EditException extends RuntimeException {

    public EditException(String message) {
        super(message);
    }

    public EditException(String message, Throwable cause) {
        super(message, cause);
    }
}
