package com.matjari.exception;

/** 404 Not Found. 응답: {"message": ...} */
public class NotFoundException extends MatJariException {
    public NotFoundException(String message) {
        super(message);
    }
}
