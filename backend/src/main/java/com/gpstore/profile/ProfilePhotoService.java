package com.gpstore.profile;

import com.gpstore.dto.request.SignUploadRequest;
import com.gpstore.dto.response.ConfirmedUploadResponse;
import com.gpstore.dto.response.SignedUploadResponse;
import com.gpstore.entity.Customer;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CustomerRepository;
import com.gpstore.upload.ImageKind;
import com.gpstore.upload.R2ObjectStorageService;
import com.gpstore.upload.UploadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer setting, replacing or removing their own profile photo.
 *
 * Its own service rather than another five lines on CustomerService, which
 * already takes nine collaborators - this needs object storage, which nothing
 * else in that class does.
 *
 * THE UPLOAD IS TWO REQUESTS AND THAT IS THE WHOLE SECURITY PROBLEM. The
 * customer asks for a signed URL, PUTs the bytes straight to storage, then
 * tells us the key to attach. Because attach is a separate request, the key
 * it carries is client-supplied and cannot be trusted: without a check, a
 * customer could attach another customer's freshly-uploaded key to their own
 * profile, or probe for keys that exist. {@link UploadPolicy#requireOwnedBy}
 * is what closes that, and the owner segment it checks against comes from the
 * authenticated principal - never from the request body.
 */
@Service
public class ProfilePhotoService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    private final CustomerRepository customers;
    private final R2ObjectStorageService storage;

    public ProfilePhotoService(CustomerRepository customers, R2ObjectStorageService storage) {
        this.customers = customers;
        this.storage = storage;
    }

    /**
     * A short-lived URL the app can PUT the chosen image to.
     *
     * {@code ownerId} is set here from the caller's own id, so the object key
     * this returns always sits under that customer's folder. A body-supplied
     * ownerId is not read at all - there is no field for it on this path.
     */
    public SignedUploadResponse sign(long customerId, String contentType, long contentLength) {
        SignUploadRequest request = new SignUploadRequest();
        request.setImageType(ImageKind.PROFILE);
        request.setContentType(contentType);
        request.setContentLength(contentLength);
        request.setOwnerId(customerId);
        // Type and size are validated inside sign() by UploadPolicy - a 9 MB
        // camera original is refused here, before any bytes move.
        return storage.sign(request);
    }

    /**
     * Attaches an uploaded object to the customer's profile and returns the
     * saved account.
     *
     * The previous photo is deleted afterwards rather than left behind:
     * someone who changes their picture five times should not be paying to
     * store five pictures, and an orphaned avatar is not referenced by
     * anything that could ever clean it up later.
     */
    @Transactional
    public Customer attach(long customerId, String objectKey) {
        UploadPolicy.requireOwnedBy(objectKey, ImageKind.PROFILE, customerId);

        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        String previous = customer.getProfileImageUrl();

        // Moves staging -> permanent and returns the stable stored reference.
        ConfirmedUploadResponse confirmed = storage.confirm(objectKey);
        customer.setProfileImageUrl(confirmed.getImageRef());
        Customer saved = customers.save(customer);

        deleteQuietly(previous, customerId);
        return saved;
    }

    /** Clears the photo and deletes the stored object. */
    @Transactional
    public Customer remove(long customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        String previous = customer.getProfileImageUrl();
        customer.setProfileImageUrl(null);
        Customer saved = customers.save(customer);

        deleteQuietly(previous, customerId);
        return saved;
    }

    /**
     * Storage cleanup must never fail the request that triggered it.
     *
     * The customer's intent - "this is my picture now", or "I don't want one"
     * - is already recorded in the database by the time this runs. Throwing
     * here would report failure for something that succeeded, and would leave
     * the app showing the old photo. A leaked object costs a few kilobytes;
     * a false error costs the customer's trust in the button.
     */
    private void deleteQuietly(String storedRef, long customerId) {
        if (storedRef == null || storedRef.isBlank()) {
            return;
        }
        try {
            storage.deletePublicUrl(storedRef);
        } catch (RuntimeException ex) {
            // No object key in the message: it contains the customer id.
            log.warn("Could not delete the previous profile photo for customer {}: {}",
                    customerId, ex.toString());
        }
    }
}
