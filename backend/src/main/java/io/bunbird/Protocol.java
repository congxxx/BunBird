package io.bunbird;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class Protocol {
    private Protocol() {}

    public record Incoming(
            @NotBlank @Size(max=128) String tenantId,
            @NotBlank @Size(max=128) String appId,
            @NotBlank @Size(max=128) String eventId,
            @NotBlank @Size(max=128) String messageId,
            @NotBlank @Size(max=128) String chatId,
            @NotBlank @Size(max=128) String senderId,
            @NotBlank @Size(max=256) String topicKey,
            @NotBlank @Size(max=12000) String text,
            @NotBlank @Pattern(regexp="p2p|group") String chatType) {}
    public record Lease(@NotBlank String leaseToken) {}
    public record Progress(@NotBlank String leaseToken,
            @Pattern(regexp="STARTED|WORKSPACE|UPDATE_[0-9]{1,3}") @NotBlank String phase,
            @NotBlank @Size(max=2000) String detail) {}
    public record Completion(@NotBlank String leaseToken,
            @Pattern(regexp="SUCCEEDED|FAILED") @NotBlank String status,
            @Size(max=12000) String result, @Size(max=64) String errorCode,
            @Size(max=128) String model, @Size(max=4000) String usageJson) {}
    public record Delivered(@NotBlank String leaseToken, boolean success,
            @Size(max=128) String messageId, @Size(max=64) String errorCode) {}
}
