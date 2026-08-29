package com.gpstore.otp;

public enum OtpChannel {
    EMAIL,
    SMS;

    public static OtpChannel from(String raw) {
        if (raw != null && raw.trim().equalsIgnoreCase("SMS")) {
            return SMS;
        }
        return EMAIL;
    }
}
