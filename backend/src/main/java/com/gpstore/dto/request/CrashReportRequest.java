package com.gpstore.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a phone is allowed to say about its own crash.
 *
 * NOTE WHAT IS ABSENT: there is no customerId, no workerId and no app field
 * naming somebody else's app. Identity comes from the token in
 * CrashReportService, for the same reason AdminCreateCustomerRequest has no
 * role field - a value that cannot arrive cannot be forged.
 *
 * The @Size caps are the first of two limits. They reject an absurd body
 * outright; the service truncates whatever survives, because a validation
 * message is a worse outcome than a slightly shortened stack trace when the
 * thing being reported is that the app is already broken.
 */
public class CrashReportRequest {

    @NotBlank(message = "message is required")
    @Size(max = 2000, message = "message is too long")
    private String message;

    @Size(max = 20000, message = "stack is too long")
    private String stack;

    @Size(max = 32)
    private String appVersion;

    @Size(max = 40)
    private String buildSha;

    @Size(max = 32)
    private String platform;

    /** Defaults to fatal: an unlabelled crash is the more serious kind. */
    private Boolean fatal;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getStack() { return stack; }
    public void setStack(String stack) { this.stack = stack; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public String getBuildSha() { return buildSha; }
    public void setBuildSha(String buildSha) { this.buildSha = buildSha; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Boolean getFatal() { return fatal; }
    public void setFatal(Boolean fatal) { this.fatal = fatal; }
}
