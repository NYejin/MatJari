package com.matjari.exception;

/** 권한 없음 -> 403 {"message": ...}. 메시지는 사용자에게 보여도 되는 문구만 담는다. */
public class ForbiddenException extends MatJariException {

    public ForbiddenException(String message) {
        super(message);
    }
}
