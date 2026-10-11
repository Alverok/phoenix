package com.phoenix.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.phoenix.core.AttemptFencing;

/** What Core puts on the wire to identify an attempt. */
class AttemptWireTest {

    @Test
    void aLegacyNodeNeverSeesAnAttemptField() {
        assertEquals(
                "",
                AttemptWire.jsonFields(AttemptFencing.LEGACY, "abc", 3));
    }

    @Test
    void anEnforcedNodeGetsTheIdAsIdentityAndTheNumberAsInformation() {
        assertEquals(
                ",\"attemptId\":\"abc\",\"attemptNumber\":3",
                AttemptWire.jsonFields(AttemptFencing.ENFORCED, "abc", 3));
    }

    @Test
    void theRetiredNumericFieldIsNeverEmitted() {
        assertFalse(
                AttemptWire.jsonFields(AttemptFencing.ENFORCED, "abc", 3)
                        .contains("\"attempt\":"));
    }

    @Test
    void idsAreEscapedSoTheyCannotBreakTheJson() {
        String fields =
                AttemptWire.jsonFields(AttemptFencing.ENFORCED, "a\"b\\c", 1);

        assertTrue(fields.contains("\"attemptId\":\"a\\\"b\\\\c\""));
    }
}
