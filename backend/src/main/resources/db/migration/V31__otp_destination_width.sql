-- OTP identity is now email (or still a phone when otp.channel=SMS).
-- Reuse mobile_number as the destination key; widen it so an email fits.
ALTER TABLE otp_verifications ALTER COLUMN mobile_number TYPE VARCHAR(320);
