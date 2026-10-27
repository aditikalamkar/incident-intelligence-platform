package com.opspilot.identity;

import java.util.List;
import java.util.UUID;

/** The Slack webhook URL itself is never returned: it is a credential for posting into the channel. */
public record TeamResponse(UUID id, String name, List<UUID> memberIds, boolean slackConfigured) {
}
