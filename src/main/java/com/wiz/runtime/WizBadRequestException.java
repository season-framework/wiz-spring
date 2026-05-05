package com.wiz.runtime;

public class WizBadRequestException extends RuntimeException {

    private final Object data;

    public WizBadRequestException(String message, Object data) {
        super(message);
        this.data = data;
    }

    public Object data() {
        return data;
    }
}